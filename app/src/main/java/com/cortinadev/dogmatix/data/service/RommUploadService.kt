package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.dao.DownloadHistoryDao
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.dao.RommUploadDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.local.entity.RommUploadEntity
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.AutoRetryPolicy
import com.cortinadev.dogmatix.util.CertPin
import com.cortinadev.dogmatix.util.DownloadFailures
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.MultiDisc
import com.cortinadev.dogmatix.util.RommSource
import com.cortinadev.dogmatix.util.RommUploadResume
import com.cortinadev.dogmatix.util.RommUploadSources
import com.cortinadev.dogmatix.util.SourceChangedException
import com.cortinadev.dogmatix.util.StorageHelper
import com.cortinadev.dogmatix.util.StoredZip
import com.cortinadev.dogmatix.util.UploadSource
import com.cortinadev.dogmatix.util.ZipEntrySpec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RommUploadService"
private const val CHUNK_SIZE = RommUploadResume.CHUNK_SIZE
/** Tries per chunk before the upload counts as failed (RomM takes a chunk again). */
private const val CHUNK_TRIES = 3
/** After a restart: let the downloads list come back first (the rows that show the state). */
private const val RESUME_DELAY_MS = 5_000L
/** The Downloads row is redrawn at most this often while bytes go out. */
private const val PROGRESS_INTERVAL_MS = 300L
/** How deep a game's folder is walked (`<game>.m3u/` is flat; an extracted game may nest). */
private const val MAX_DEPTH = 4

enum class UploadStatus { UPLOADING, DONE, FAILED, PREPARING, WAITING }

data class UploadState(
    val status: UploadStatus,
    val progress: Float = 0f,
    val message: String = "",
    /** Carrying on a session cut short ("Resuming…" until its first chunk lands). */
    val resuming: Boolean = false,
    /** Epoch millis of the next automatic try of a FAILED upload; null = none planned. */
    val retryAt: Long? = null
)

/**
 * Pushes finished downloads to the RomM server (Settings → RomM). It acts once the multi-disc
 * step is done with a download ([MultiDiscService.settled]), so the files are where they stay,
 * and [DownloadService] needs no RomM knowledge; the state lives here and the Downloads screen
 * shows it next to each row.
 * - One file goes up as it is; a game of several files as one `<game>.zip` streamed straight from
 *   its files ([StoredZip]; why, in [RommUploadSources]). A disc of a multi-disc set waits for the
 *   rest and goes up with them.
 * - Every upload is recorded ([RommUploadDao]) with the chunks RomM acknowledged, so a failure or
 *   a restart carries on from there while the server keeps the session ([RommUploadResume]).
 * - Transient failures are retried on their own, on the downloads' schedule ([AutoRetryPolicy]).
 */
@Singleton
class RommUploadService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val downloadService: DownloadService,
    private val downloadableFileDao: DownloadableFileDao,
    private val historyDao: DownloadHistoryDao,
    private val downloadFileManager: DownloadFileManager,
    private val rommClient: RommClient,
    private val uploadDao: RommUploadDao,
    multiDiscService: MultiDiscService
) {
    private enum class Trigger { FINISHED, MANUAL, AUTO_RETRY, RESUME }

    /** A file of an upload, found on disk ([UploadSource.path] relative to the console folder). */
    private data class Located(val source: UploadSource, val uri: Uri)

    /** What goes up for a download: one file, or the files of one game as `<game>.zip`. */
    private data class UploadUnit(
        /** `consoleId|serverName`, the [RommUploadEntity] key. */
        val key: String,
        val consoleId: String,
        val serverName: String,
        /** "" or `<game>.m3u`: archive entries are named below it. */
        val folder: String,
        val archive: Boolean,
        val files: List<Located>,
        /** The multi-disc set it gathers, whose rows all show its state. */
        val setStem: String?
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadLock = Mutex()

    private val _uploads = MutableStateFlow<Map<String, UploadState>>(emptyMap())
    /** Upload state per download file name; absent = nothing to show. */
    val uploads: StateFlow<Map<String, UploadState>> = _uploads.asStateFlow()

    /** Automatic retries spent per download in the current chain, and the pending one. */
    private val autoRetries = ConcurrentHashMap<String, Int>()
    private val pendingRetries = ConcurrentHashMap<String, Job>()
    @Volatile private var lastShownAt = 0L

    init {
        // Once per finished download, after the multi-disc step (see MultiDiscService.settled).
        scope.launch {
            multiDiscService.settled.collect { outcome ->
                if (settingsRepository.rommAutoUpload.first()) launch { enqueue(outcome.fileName, Trigger.FINISHED, outcome.waitingForDiscs) }
            }
        }
        // Uploads cut short by a dying process carry on where the server left them.
        scope.launch {
            delay(RESUME_DELAY_MS)
            resumeInterrupted()
        }
    }

    fun hasActive(): Boolean = _uploads.value.values.any { it.status == UploadStatus.UPLOADING || it.status == UploadStatus.PREPARING }

    /** The row's button: a failed upload again now, or a disc whose set is incomplete on its own. */
    fun retry(fileName: String) {
        pendingRetries.remove(fileName)?.cancel()
        autoRetries.remove(fileName)
        scope.launch { enqueue(fileName, Trigger.MANUAL) }
    }

    private suspend fun enqueue(fileName: String, trigger: Trigger, waitingForDiscs: Boolean = false) {
        val saved = if (trigger == Trigger.RESUME) uploadDao.forDownload(fileName) else null
        val file = entityOf(fileName)
            ?: saved?.let { DownloadableFileEntity(name = it.serverName, fileName = fileName, consoleId = it.consoleId, downloadUrl = "") }
            ?: return
        val base = rommClient.configuredBaseUrl()
        if (base.isEmpty() || RommSource.isDownloadFrom(base, file.downloadUrl)) return   // it came from RomM
        val platformId = settingsRepository.rommPlatformMap.first()[file.consoleId]
        if (platformId == null) {
            Log.i(TAG, "No RomM platform mapped for ${file.consoleId}; skipping ${file.fileName}")
            return
        }
        if (waitingForDiscs) {
            // Goes up with the rest of its set, as one game, when the last disc lands.
            setStates(listOf(fileName), UploadState(UploadStatus.WAITING))
            return
        }
        setStates(listOf(fileName), UploadState(UploadStatus.UPLOADING, resuming = trigger == Trigger.RESUME))
        var rows = listOf(fileName)
        var key: String? = null
        try {
            val unit = locate(file, fileName)
            key = unit.key
            rows = rowsOf(unit, fileName)
            record(unit, fileName, base, platformId)
            uploadLock.withLock { transfer(unit, platformId, base, rows) }
            autoRetries.remove(fileName)
            setStates(rows, UploadState(UploadStatus.DONE, 1f))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(fileName, rows, key, trigger, e)
        }
    }

    /** Notes the upload before it starts, so a restart picks it up even if it never got going. */
    private suspend fun record(unit: UploadUnit, fileName: String, base: String, platformId: Int) {
        val existing = uploadDao.get(unit.key)
        if (existing != null && existing.serverUrl == base && existing.platformId == platformId) return
        if (existing != null) forgetUpload(existing.uploadKey)
        uploadDao.upsert(
            RommUploadEntity(
                uploadKey = unit.key, downloadFileName = fileName, consoleId = unit.consoleId, serverUrl = base,
                platformId = platformId, serverName = unit.serverName, folder = unit.folder,
                sources = RommUploadSources.encode(unit.files.map { it.source }), archive = unit.archive,
                totalSize = 0L, chunkSize = CHUNK_SIZE, totalChunks = 0, uploadId = null, confirmedChunks = 0,
                sessionStartedAt = 0L, updatedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun transfer(unit: UploadUnit, platformId: Int, base: String, rows: List<String>) {
        var saved = uploadDao.get(unit.key)
        val before = RommUploadSources.decode(saved?.sources)
        val sources = if (unit.archive) checksummed(unit, before, rows) else unit.files.map { it.source }
        val payload = if (unit.archive) {
            StoredZip.of(sources.map { ZipEntrySpec(RommUploadSources.entryName(unit.folder, it.path), it.size, it.crc32 ?: 0L, it.dosTime ?: 0L) })
        } else StoredZip.single(sources.single().size)
        val total = payload.size
        val chunks = RommUploadResume.chunkCount(total, CHUNK_SIZE)
        val wanted = RommUploadResume.Session(base, platformId, unit.serverName, total, CHUNK_SIZE, chunks, RommUploadSources.signature(sources))
        var start = RommUploadResume.decide(saved?.toSession(RommUploadSources.signature(before)), wanted, System.currentTimeMillis())
        var restarted = false
        while (true) {
            val resuming = start is RommUploadResume.Continue
            val (uploadId, from) = when (val s = start) {
                is RommUploadResume.Continue -> {
                    Log.i(TAG, "Resuming ${unit.serverName} at chunk ${s.fromChunk}/$chunks")
                    s.uploadId to s.fromChunk
                }
                RommUploadResume.Fresh -> {
                    // A session for other bytes (or other settings) is of no use any more.
                    saved?.let { old -> if (old.serverUrl == base) old.uploadId?.let { rommClient.uploadCancel(it) } }
                    val id = try {
                        rommClient.uploadStart(platformId, unit.serverName, total, chunks)
                    } catch (e: JsonHttp.HttpException) {
                        if (!RommUploadResume.alreadyOnServer(e.code, e.body)) throw e
                        Log.i(TAG, "${unit.serverName} is already on the RomM server")
                        uploadDao.delete(unit.key)
                        return
                    }
                    val now = System.currentTimeMillis()
                    val started = RommUploadEntity(
                        uploadKey = unit.key, downloadFileName = saved?.downloadFileName ?: rows.first(),
                        consoleId = unit.consoleId, serverUrl = base, platformId = platformId, serverName = unit.serverName,
                        folder = unit.folder, sources = RommUploadSources.encode(sources), archive = unit.archive,
                        totalSize = total, chunkSize = CHUNK_SIZE, totalChunks = chunks, uploadId = id,
                        confirmedChunks = 0, sessionStartedAt = now, updatedAt = now
                    )
                    uploadDao.upsert(started)
                    saved = started
                    id to 0
                }
            }
            try {
                send(unit, payload, uploadId, from, total, chunks, rows, resuming)
                finish(unit, payload, uploadId, total, chunks)
                uploadDao.delete(unit.key)
                Log.i(TAG, "Uploaded ${unit.serverName} to RomM platform $platformId")
                return
            } catch (e: JsonHttp.HttpException) {
                if (restarted || !RommUploadResume.sessionGone(e.code)) throw e
                // Expired, cancelled, or completed by a try whose answer got lost: /start tells.
                Log.i(TAG, "RomM no longer has the upload of ${unit.serverName}; starting over")
                restarted = true
                saved = saved?.copy(uploadId = null)
                start = RommUploadResume.Fresh
            }
        }
    }

    /** Chunks [from]… of [payload], each recorded once the server acknowledges it. */
    private suspend fun send(
        unit: UploadUnit, payload: StoredZip, uploadId: String, from: Int, total: Long, chunks: Int,
        rows: List<String>, resuming: Boolean
    ) {
        if (from >= chunks) return
        payload.openAt(RommUploadResume.offsetOf(from, CHUNK_SIZE)) { entry, offset -> openSource(unit.files[entry], offset) }.use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            for (index in from until chunks) {
                currentCoroutineContext().ensureActive()
                val length = RommUploadResume.chunkLength(index, total, CHUNK_SIZE)
                readFully(input, buffer, length)
                val offset = RommUploadResume.offsetOf(index, CHUNK_SIZE)
                val first = resuming && index == from
                putChunk(uploadId, index, buffer, length) { sent ->
                    showProgress(rows, UploadState(UploadStatus.UPLOADING, (offset + sent).toFloat() / total, resuming = first))
                }
                uploadDao.confirm(unit.key, index + 1, System.currentTimeMillis())
            }
        }
    }

    /** `/complete`; if RomM says chunks are missing (its temporary files went), those go again once. */
    private suspend fun finish(unit: UploadUnit, payload: StoredZip, uploadId: String, total: Long, chunks: Int) {
        val timeout = RommUploadResume.completeTimeoutMs(total)
        try {
            rommClient.uploadComplete(uploadId, timeout)
        } catch (e: JsonHttp.HttpException) {
            val missing = RommUploadResume.missingChunks(e.body).filter { it in 0 until chunks }
            if (e.code != 400 || missing.isEmpty()) throw e
            Log.i(TAG, "RomM lacks chunks $missing of ${unit.serverName}; sending them again")
            val buffer = ByteArray(CHUNK_SIZE)
            for (index in missing) {
                val length = RommUploadResume.chunkLength(index, total, CHUNK_SIZE)
                payload.openAt(RommUploadResume.offsetOf(index, CHUNK_SIZE)) { entry, offset -> openSource(unit.files[entry], offset) }
                    .use { readFully(it, buffer, length) }
                putChunk(uploadId, index, buffer, length) {}
            }
            rommClient.uploadComplete(uploadId, timeout)
        }
    }

    private suspend fun putChunk(uploadId: String, index: Int, buffer: ByteArray, length: Int, onSent: (Int) -> Unit) {
        var attempt = 1
        while (true) {
            try {
                rommClient.uploadChunk(uploadId, index, buffer, length, onSent)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= CHUNK_TRIES || e is RommException || !RommUploadResume.isTransient(e)) throw e
                Log.i(TAG, "Chunk $index of $uploadId failed (${e.message}); sending it again")
                delay(2_000L * attempt)
                attempt++
            }
        }
    }

    /**
     * CRC-32 and time of each file for the archive's headers: kept from an earlier try when the
     * file did not change, else read once (the row shows "preparing").
     */
    private fun checksummed(unit: UploadUnit, previous: List<UploadSource>, rows: List<String>): List<UploadSource> {
        val current = RommUploadSources.reuseChecksums(previous, unit.files.map { it.source })
        val toRead = current.indices.filter { current[it].crc32 == null || current[it].dosTime == null }.toSet()
        if (toRead.isEmpty()) return current
        val bytes = toRead.sumOf { current[it].size }.coerceAtLeast(1L)
        var read = 0L
        setStates(rows, UploadState(UploadStatus.PREPARING))
        return current.mapIndexed { i, source ->
            if (i !in toRead) return@mapIndexed source
            val crc = openSource(unit.files[i], 0L).use { input ->
                StoredZip.crc32(input) { n ->
                    read += n
                    showProgress(rows, UploadState(UploadStatus.PREPARING, read.toFloat() / bytes))
                }
            }
            source.copy(crc32 = crc, dosTime = StoredZip.dosTime(source.modified))
        }
    }

    // ---- Where the files are

    /**
     * The files of [fileName]'s game: those a cut-short upload was sending (if unchanged), the
     * download's own files, or the multi-disc folder they were moved into.
     */
    private suspend fun locate(file: DownloadableFileEntity, fileName: String): UploadUnit {
        val rootUri = downloadFileManager.getDownloadDirectoryUri(file)
        val consoleDir = (if (rootUri == Uri.EMPTY) null
            else StorageHelper.createDirectory(context, rootUri.toString(), downloadFileManager.getSubPath(file)))
            ?: throw RommException(context.getString(R.string.romm_upload_no_folder))
        val finder = Finder(consoleDir.uri)
        uploadDao.forDownload(fileName)?.let { saved -> fromRecord(saved, finder)?.let { return it } }

        val decoded = FileParsingUtils.decodeUrlEncodedFileName(fileName)
        val names = downloadService.uploadCandidates(fileName)
        val loose = names.map { finder.file(it) }
        if (loose.all { it != null }) {
            val files = loose.filterNotNull()
            val archive = RommUploadSources.needsArchive(names)
            val serverName = if (archive) RommUploadSources.archiveName(decoded) else files.single().source.path.substringAfterLast('/')
            return UploadUnit("${file.consoleId}|$serverName", file.consoleId, serverName, "", archive, files, null)
        }
        discSet(finder, file.consoleId, decoded, names)?.let { return it }
        val missing = names[loose.indexOfFirst { it == null }]
        throw RommException(context.getString(R.string.romm_upload_missing_file, missing))
    }

    private fun fromRecord(saved: RommUploadEntity, finder: Finder): UploadUnit? {
        val sources = RommUploadSources.decode(saved.sources).ifEmpty { return null }
        val files = sources.map { source ->
            val found = finder.file(source.path) ?: return null
            if (found.source.size != source.size || found.source.modified != source.modified) return null
            found.copy(source = source)
        }
        val setStem = saved.folder.takeIf { it.endsWith(".m3u") }?.removeSuffix(".m3u")
        return UploadUnit(saved.uploadKey, saved.consoleId, saved.serverName, saved.folder, saved.archive, files, setStem)
    }

    /** The `<game>.m3u/` folder [MultiDiscService] moved the download's discs into: the whole set goes as `<game>.zip`. */
    private fun discSet(finder: Finder, consoleId: String, downloadName: String, names: List<String>): UploadUnit? {
        val disc = names.firstNotNullOfOrNull { n -> MultiDisc.discOf(n)?.takeIf { MultiDisc.isDiscImage(n) } }
            ?: MultiDisc.discOf(downloadName) ?: return null
        val folder = "${disc.setStem}.m3u"
        val setDir = finder.child(folder)?.takeIf { it.isDirectory } ?: return null
        val files = walk(setDir.uri, folder, 0).sortedBy { it.source.path.lowercase() }
        val ours = names.map { it.replace('\\', '/').substringAfterLast('/') }.toSet()
        if (files.none { it.source.path.substringAfterLast('/') in ours }) return null
        val serverName = "${disc.setStem}.zip"
        return UploadUnit("$consoleId|$serverName", consoleId, serverName, folder, archive = true, files, disc.setStem)
    }

    private fun walk(dir: Uri, prefix: String, depth: Int): List<Located> =
        StorageHelper.listChildren(context, dir).flatMap { child ->
            val path = "$prefix/${child.name}"
            when {
                child.isDirectory -> if (depth < MAX_DEPTH) walk(child.uri, path, depth + 1) else emptyList()
                child.name.endsWith(".tmp") -> emptyList()   // a playlist being rewritten
                else -> listOfNotNull(located(path, child.uri))
            }
        }

    private fun located(path: String, uri: Uri): Located? {
        val doc = DocumentFile.fromSingleUri(context, uri) ?: return null
        return Located(UploadSource(path, doc.length(), doc.lastModified()), uri)
    }

    /** Finds paths below a folder with one provider query per folder ([StorageHelper.listChildren]). */
    private inner class Finder(private val root: Uri) {
        private val listings = HashMap<Uri, Map<String, StorageHelper.Child>>()

        private fun childOf(dir: Uri, name: String): StorageHelper.Child? =
            listings.getOrPut(dir) { StorageHelper.listChildren(context, dir).associateBy { it.name } }[name]

        fun child(path: String): StorageHelper.Child? {
            var dir = root
            var found: StorageHelper.Child? = null
            for (part in path.replace('\\', '/').split('/').filter { it.isNotEmpty() }) {
                found = childOf(dir, part) ?: return null
                dir = found.uri
            }
            return found
        }

        fun file(path: String): Located? = child(path)?.takeIf { !it.isDirectory }?.let { located(path, it.uri) }
    }

    /** The Downloads rows an upload's state shows on: the download, plus every disc of its set. */
    private fun rowsOf(unit: UploadUnit, fileName: String): List<String> {
        val stem = unit.setStem ?: return listOf(fileName)
        val discs = downloadService.downloads.value.map { it.fileName }.filter { row ->
            MultiDisc.discOf(FileParsingUtils.decodeUrlEncodedFileName(row))?.setStem.equals(stem, ignoreCase = true) &&
                downloadService.entityFor(row)?.consoleId == unit.consoleId
        }
        return listOf(fileName) + (discs - fileName)
    }

    private fun openSource(file: Located, offset: Long): InputStream {
        val input = context.contentResolver.openInputStream(file.uri)
            ?: throw RommException(context.getString(R.string.romm_upload_missing_file, file.source.path))
        try {
            var left = offset
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped > 0) { left -= skipped; continue }
                if (input.read() < 0) throw SourceChangedException(-1)
                left--
            }
        } catch (e: Exception) {
            input.close()
            throw e
        }
        return input
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int) {
        var filled = 0
        while (filled < length) {
            val n = input.read(buffer, filled, length - filled)
            if (n < 0) throw SourceChangedException(-1)
            filled += n
        }
    }

    // ---- Failures, restarts and state

    private suspend fun failed(fileName: String, rows: List<String>, key: String?, trigger: Trigger, error: Exception) {
        Log.w(TAG, "RomM upload failed for $fileName: ${error.message}")
        val transient = error !is RommException && RommUploadResume.isTransient(error)
        val keep = error !is RommException && RommUploadResume.keepsSession((error as? JsonHttp.HttpException)?.code)
        if (!keep && key != null) forgetUpload(key)
        val done = if (trigger == Trigger.AUTO_RETRY) autoRetries[fileName] ?: 0 else 0
        val retryAt = if (transient && AutoRetryPolicy.shouldRetry(DownloadFailures.classify(error), done, settingsRepository.autoRetryAttempts.first())) {
            val wait = AutoRetryPolicy.delayMs(done)
            autoRetries[fileName] = done + 1
            pendingRetries.remove(fileName)?.cancel()
            pendingRetries[fileName] = scope.launch {
                delay(wait)
                pendingRetries.remove(fileName)
                enqueue(fileName, Trigger.AUTO_RETRY)
            }
            System.currentTimeMillis() + wait
        } else {
            autoRetries.remove(fileName)
            null
        }
        setStates(rows, UploadState(UploadStatus.FAILED, message = messageFor(error), retryAt = retryAt))
    }

    private fun messageFor(error: Throwable): String = when {
        CertPin.isCertificateFailure(error) -> context.getString(R.string.romm_upload_cert_refused)
        error is SourceChangedException -> context.getString(R.string.romm_upload_source_changed)
        else -> error.message.orEmpty()
    }

    /** Drops the record of an upload that cannot go on, and its session on the server. */
    private suspend fun forgetUpload(key: String) {
        val saved = uploadDao.get(key) ?: return
        saved.uploadId?.let { if (saved.serverUrl == rommClient.configuredBaseUrl()) rommClient.uploadCancel(it) }
        uploadDao.delete(key)
    }

    private suspend fun resumeInterrupted() {
        val saved = runCatching { uploadDao.getAll() }.getOrDefault(emptyList())
        if (saved.isEmpty()) return
        val base = rommClient.configuredBaseUrl()
        val platforms = settingsRepository.rommPlatformMap.first()
        for (upload in saved) {
            if (upload.serverUrl != base || platforms[upload.consoleId] != upload.platformId) {
                // Another server or platform now: the session, if any, is of no use.
                forgetUpload(upload.uploadKey)
                continue
            }
            Log.i(TAG, "Resuming the RomM upload of ${upload.serverName}")
            scope.launch { enqueue(upload.downloadFileName, Trigger.RESUME) }
        }
    }

    private suspend fun entityOf(fileName: String): DownloadableFileEntity? =
        downloadService.entityFor(fileName)
            ?: downloadableFileDao.getFileByFileName(fileName)
            ?: historyDao.getByFileName(fileName)?.toEntity()

    private fun showProgress(rows: List<String>, state: UploadState) {
        val now = System.currentTimeMillis()
        if (now - lastShownAt < PROGRESS_INTERVAL_MS) return
        lastShownAt = now
        setStates(rows, state)
    }

    private fun setStates(rows: List<String>, state: UploadState) =
        _uploads.update { current -> current + rows.associateWith { state } }
}
