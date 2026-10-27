package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.data.local.dao.DownloadTrackDao
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.entity.DownloadCheckEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.CheckResult
import com.cortinadev.dogmatix.util.CheckState
import com.cortinadev.dogmatix.util.Checksums
import com.cortinadev.dogmatix.util.DownloadCheck
import com.cortinadev.dogmatix.util.HashedFile
import com.cortinadev.dogmatix.util.IaFiles
import com.cortinadev.dogmatix.util.IaSource
import com.cortinadev.dogmatix.util.MultiDisc
import com.cortinadev.dogmatix.util.RommSource
import com.cortinadev.dogmatix.util.StorageHelper
import com.cortinadev.dogmatix.util.UnknownReason
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DownloadVerifier"

/**
 * Checks each finished download (Settings → "Verify downloads"): CRC-32 and SHA-1 (plus MD5 for
 * RomM) of every file it left on disk, extracted ones included, compared with the hashes RomM
 * keeps for its ROMs, the ones Internet Archive lists for the files of an item, or with the
 * console's No-Intro / Redump DAT. Runs one file at a time on a
 * low-priority thread once the multi-disc step is done with a finished download
 * ([MultiDiscService.settled], so the discs already sit where they will stay); neither COMPLETED
 * nor the rest of the queue waits for it, and a mismatch is only reported, nothing is ever deleted.
 */
@Singleton
class DownloadVerifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val downloadService: DownloadService,
    private val multiDiscService: MultiDiscService,
    private val downloadableFileDao: DownloadableFileDao,
    private val downloadFileManager: DownloadFileManager,
    private val rommClient: RommClient,
    private val datStore: DatStore,
    private val dao: DownloadTrackDao,
    private val iaAuth: IaAuth
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "dogmatix-verify")
    }.asCoroutineDispatcher()
    private val queue = Channel<String>(Channel.UNLIMITED)
    /** Bumped by every new check of a file, so an older one still running does not overwrite it. */
    private val generations = ConcurrentHashMap<String, Int>()

    private val _results = MutableStateFlow<Map<String, CheckResult>>(emptyMap())
    /** The latest verdict per download file name (absent = never checked). */
    val results: StateFlow<Map<String, CheckResult>> = _results.asStateFlow()

    init {
        scope.launch { restore() }
        scope.launch { multiDiscService.settled.collect { onFinished(it.fileName) } }
        scope.launch(worker) { for (fileName in queue) runCheck(fileName) }
    }

    private suspend fun restore() {
        runCatching { dao.deleteOrphanChecks() }
        val restored = runCatching { dao.allChecks() }.getOrDefault(emptyList())
            .mapNotNull { row -> DownloadCheck.decode(row.result)?.let { row.fileName to it } }
            .toMap()
        _results.update { restored + it }
        // Checks the process died in the middle of start again: they kept their list of files.
        restored.filterValues { it.state == CheckState.CHECKING }.keys.forEach { queue.trySend(it) }
    }

    private suspend fun onFinished(fileName: String) {
        if (!settingsRepository.verifyDownloads.first()) {
            clear(fileName)
            return
        }
        generations.merge(fileName, 1, Int::plus)
        save(fileName, CheckResult(CheckState.CHECKING, files = downloadService.uploadCandidates(fileName)))
        queue.trySend(fileName)
    }

    private suspend fun runCheck(fileName: String) {
        val pending = _results.value[fileName]?.takeIf { it.state == CheckState.CHECKING } ?: return
        if (!settingsRepository.verifyDownloads.first()) {
            clear(fileName)
            return
        }
        val generation = generations[fileName]
        val result = try {
            check(fileName, pending.files)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not check $fileName: ${e.message}")
            CheckResult(CheckState.UNKNOWN, files = pending.files, reason = UnknownReason.ERROR)
        }
        if (generations[fileName] != generation) return
        Log.i(TAG, "$fileName: ${result.state} (${result.against ?: result.reason})")
        save(fileName, result)
    }

    private suspend fun check(fileName: String, paths: List<String>): CheckResult {
        fun unknown(reason: UnknownReason, against: String? = null) = CheckResult(CheckState.UNKNOWN, against, paths, reason = reason)
        val entity = downloadService.entityFor(fileName) ?: downloadableFileDao.getFileByFileName(fileName)
            ?: return unknown(UnknownReason.NOT_FOUND)
        val dirUri = downloadFileManager.getDownloadDirectoryUri(entity)
        if (dirUri == android.net.Uri.EMPTY) return unknown(UnknownReason.NOT_FOUND)
        val directory = StorageHelper.createDirectory(context, dirUri.toString(), downloadFileManager.getSubPath(entity))
            ?: return unknown(UnknownReason.NOT_FOUND)
        val fromRomm = RommSource.isDownloadFrom(rommClient.configuredBaseUrl(), entity.downloadUrl)
        // A file of an Internet Archive item (not one listed inside a ZIP of it, which IA has no hashes for).
        val fromIa = IaSource.itemAndPathOf(entity.downloadUrl)?.let { !IaSource.isInsideArchive(it.second) } == true

        val job = currentCoroutineContext()[Job]
        val checkpoint = { job?.ensureActive(); Unit }
        fun hashAll(wholeFile: Boolean) = paths.flatMap { path ->
            val doc = locate(directory, path) ?: return@flatMap emptyList()
            hash(doc, path, withMd5 = fromRomm, wholeFile = wholeFile, checkpoint)
        }
        // Internet Archive hashes a ZIP as it is; the DATs below describe what is inside it.
        val whole = if (fromIa) hashAll(wholeFile = true) else null
        if (whole != null) {
            if (whole.isEmpty()) return unknown(UnknownReason.NOT_FOUND)
            iaVerdict(entity, whole)?.let { return it.copy(files = paths) }
        }
        val hashed = whole?.takeIf { hashedFiles -> hashedFiles.none { it.path.endsWith(".zip", ignoreCase = true) } } ?: hashAll(wholeFile = false)
        if (hashed.isEmpty()) return unknown(UnknownReason.NOT_FOUND)

        if (fromRomm) rommVerdict(entity, hashed)?.let { return it.copy(files = paths) }

        // DATs say nothing about cue sheets, playlists or converted images (CHD, RVZ…).
        val described = hashed.filter { DownloadCheck.datDescribes(it.path) }
        if (described.isEmpty()) return unknown(UnknownReason.UNSUPPORTED)
        datStore.ensureAuto(entity.consoleId)
        val perDat = datStore.check(entity.consoleId, described)
        if (perDat.isEmpty()) return unknown(UnknownReason.NO_REFERENCE)
        val verdict = DownloadCheck.best(perDat.map { (against, verdicts) -> DownloadCheck.combine(against, described, verdicts) })
        return verdict?.copy(files = paths) ?: unknown(UnknownReason.NOT_LISTED, perDat.first().first)
    }

    /** Where the download left [path], or the `<game>.m3u/` folder a disc has since moved into. */
    private fun locate(directory: DocumentFile, path: String): DocumentFile? =
        StorageHelper.findFile(directory, path)?.takeIf { it.isFile }
            ?: MultiDisc.discOf(DownloadCheck.leafOf(path))?.let { disc ->
                directory.findFile("${disc.setStem}.m3u")?.takeIf { it.isDirectory }?.findFile(DownloadCheck.leafOf(path))?.takeIf { it.isFile }
            }

    /** A ZIP kept as it is is checked by what is inside it (unless [wholeFile]); anything else by its own bytes. */
    private fun hash(doc: DocumentFile, path: String, withMd5: Boolean, wholeFile: Boolean, checkpoint: () -> Unit): List<HashedFile> {
        fun open() = context.contentResolver.openInputStream(doc.uri) ?: throw java.io.IOException("Could not read $path")
        if (!wholeFile && path.endsWith(".zip", ignoreCase = true)) {
            val entries = runCatching { open().use { Checksums.zipEntries(it, withMd5, checkpoint) } }.getOrNull()
            if (!entries.isNullOrEmpty()) return entries.map { (name, hashes) -> HashedFile("$path/$name", hashes) }
        }
        return listOf(HashedFile(path, open().use { Checksums.hash(it, withMd5, checkpoint) }))
    }

    /** What RomM's own hashes say; null when it has none for this ROM (hashing off, a platform it does not hash). */
    private suspend fun rommVerdict(entity: DownloadableFileEntity, hashed: List<HashedFile>): CheckResult? {
        val id = DownloadCheck.rommRomIdOf(entity.downloadUrl) ?: return null
        val base = rommClient.configuredBaseUrl().ifEmpty { return null }
        val body = runCatching { JsonHttp.requireOk(JsonHttp.request("GET", "$base/api/roms/$id", rommClient.downloadHeaders(), configure = rommClient::secure)).body }
            .onFailure { Log.w(TAG, "RomM did not give the hashes of ROM $id: ${it.message}") }
            .getOrNull() ?: return null
        val expected = DownloadCheck.rommExpectedFiles(body)
        if (expected.isEmpty()) return null
        return DownloadCheck.combine(DownloadCheck.ROMM, hashed, hashed.map { DownloadCheck.check(it, expected) })
    }

    /** What Internet Archive's hashes of the item's files say; null when it cannot be asked or does not list the file. */
    private suspend fun iaVerdict(entity: DownloadableFileEntity, hashed: List<HashedFile>): CheckResult? {
        val (id, _) = IaSource.itemAndPathOf(entity.downloadUrl) ?: return null
        val url = IaSource.metadataUrl(id) + "/files"
        val headers = iaAuth.headers(url)
        val body = runCatching { JsonHttp.requireOk(JsonHttp.request("GET", url, headers)).body }
            .onFailure { Log.w(TAG, "Internet Archive did not give the hashes of $id: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return null
        val expected = IaFiles.expected(IaFiles.parse(body))
        if (expected.isEmpty()) return null
        return DownloadCheck.combine(DownloadCheck.IA, hashed, hashed.map { DownloadCheck.check(it, expected) })
    }

    private suspend fun save(fileName: String, result: CheckResult) {
        _results.update { it + (fileName to result) }
        runCatching { dao.upsertCheck(DownloadCheckEntity(fileName, DownloadCheck.encode(result), System.currentTimeMillis())) }
            .onFailure { Log.w(TAG, "Could not save the check of $fileName: ${it.message}") }
    }

    private suspend fun clear(fileName: String) {
        if (fileName !in _results.value) return
        _results.update { it - fileName }
        runCatching { dao.deleteCheck(fileName) }
    }
}
