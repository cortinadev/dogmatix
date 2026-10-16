package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.data.model.ResolvedDownloadPath
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.MultiDisc
import com.cortinadev.dogmatix.util.RommCovers
import com.cortinadev.dogmatix.util.RommMatching
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RommEsdeCovers"
/** A cover bigger than this is not a cover. */
private const val MAX_COVER_BYTES = 16 * 1024 * 1024

data class RommEsdeCoversProgress(val done: Int, val total: Int)

data class RommEsdeCoversResult(
    val written: Int,
    /** Games whose cover ES-DE already had (left alone). */
    val kept: Int,
    val failed: Int,
    val systems: Int
)

/**
 * Settings → RomM → "RomM covers to ES-DE": for every game on disk in the console folders of
 * the mapped consoles that the server has a cover for, writes it as
 * `downloaded_media/<system>/covers/<rom without extension>.<ext>` in ES-DE's data folder
 * (`esde_directory`, picked once by SAF), leaving the covers ES-DE already has unless asked to
 * replace them. The system is the console folder's name, as in the ES-DE setup.
 */
@Singleton
class RommEsdeCoverService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val catalog: RommCatalogService,
    private val client: RommClient,
    private val pathResolver: ConsoleDownloadPathResolver
) {
    class NoEsdeFolderException : Exception()

    /** How the last batch ended, until the screen showing it calls [consumeOutcome]. */
    sealed interface Outcome {
        data class Done(val result: RommEsdeCoversResult) : Outcome
        data class Failed(val error: Throwable) : Outcome
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _progress = MutableStateFlow<RommEsdeCoversProgress?>(null)
    /** Covers written so far by the batch running now; null when none is. */
    val progress: StateFlow<RommEsdeCoversProgress?> = _progress.asStateFlow()

    private val _outcome = MutableStateFlow<Outcome?>(null)
    val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()

    /** Runs the batch in the background (leaving the screen does not stop it); false when one is running. */
    fun start(overwrite: Boolean): Boolean {
        if (job?.isActive == true) return false
        _outcome.value = null
        job = scope.launch {
            _outcome.value = try {
                Outcome.Done(export(overwrite))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Failed(e)
            }
        }
        return true
    }

    fun consumeOutcome() {
        _outcome.value = null
    }

    private suspend fun export(overwrite: Boolean): RommEsdeCoversResult = withContext(Dispatchers.IO) {
        val esdeUri = settings.esdeDirectory.first().ifBlank { throw NoEsdeFolderException() }
        val esdeDir = StorageHelper.getDocumentFile(context, esdeUri)?.takeIf { it.isDirectory && it.canWrite() }
            ?: throw FrontendSetupException.FolderNotWritable()
        if (catalog.index.value.isEmpty && !catalog.refreshNow()) throw RommException(catalog.state.value.error ?: "Could not read the RomM catalogue")
        _progress.value = RommEsdeCoversProgress(0, 0)
        try {
            val folders = consoleFolders()
            val games = folders.flatMap { (system, folder) ->
                StorageHelper.listChildren(context, folder.uri).mapNotNull { child ->
                    RommCovers.stemOf(child.name, child.isDirectory)?.let { RommCovers.DiskGame(system, folder.consoleIds, it) }
                }
            }
            val coverDirs = HashMap<String, DocumentFile>()
            val existing = HashMap<String, Map<String, List<StorageHelper.Child>>>()
            for (system in folders.keys) {
                val dir = StorageHelper.findFile(esdeDir, "downloaded_media/$system/covers")?.takeIf { it.isDirectory } ?: continue
                coverDirs[system] = dir
                existing[system] = StorageHelper.listChildren(context, dir.uri).filter { !it.isDirectory }
                    .groupBy { MultiDisc.stripExtension(it.name).lowercase() }
            }
            val index = catalog.index.value
            val plan = RommCovers.planEsde(games, existing.mapValues { it.value.keys }, overwrite) { consoleId, stem ->
                index.forGame(consoleId, RommMatching.groupKeyOf(stem), listOf(stem))
                    ?.let { it.coverLarge.ifEmpty { it.coverSmall } }?.ifEmpty { null }
            }
            _progress.value = RommEsdeCoversProgress(0, plan.jobs.size)
            var written = 0
            plan.jobs.forEachIndexed { i, cover ->
                ensureActive()
                val ok = try {
                    write(cover, esdeDir, coverDirs, existing[cover.system].orEmpty())
                } catch (e: IOException) {
                    Log.w(TAG, "Cover for ${cover.stem} not written: ${e.message}")
                    false
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Cover for ${cover.stem} not written: ${e.javaClass.simpleName}")
                    false
                }
                if (ok) written++
                _progress.value = RommEsdeCoversProgress(i + 1, plan.jobs.size)
            }
            RommEsdeCoversResult(written, plan.kept, plan.jobs.size - written, plan.jobs.map { it.system }.distinct().size)
        } finally {
            _progress.value = null
        }
    }

    private data class ConsoleFolder(val uri: Uri, val consoleIds: List<String>)

    /**
     * ES-DE system → console folder, for the mapped consoles whose games sit in a folder of their
     * own: one detected inside the download folder (named like an ES-DE system) or a custom one.
     * Several consoles can share a folder.
     */
    private suspend fun consoleFolders(): Map<String, ConsoleFolder> {
        val consoles = settings.rommPlatformMap.first().keys
        val downloadDir = settings.downloadDirectory.first()
        val custom = settings.consoleDownloadDirectories.first()
        val root = downloadDir.takeIf { it.isNotBlank() }?.let { StorageHelper.getDocumentFile(context, it) }
        val rootFolders = root?.let { StorageHelper.listChildren(context, it.uri).filter { c -> c.isDirectory } }.orEmpty()
        val out = LinkedHashMap<String, ConsoleFolder>()
        pathResolver.resolveAll(settings, consoles).forEach { (consoleId, path) ->
            val (system, uri) = when (path.source) {
                ResolvedDownloadPath.Source.DETECTED ->
                    rootFolders.firstOrNull { it.name == path.subPath }?.let { path.subPath.lowercase() to it.uri }
                ResolvedDownloadPath.Source.CUSTOM ->
                    custom[consoleId]?.let { StorageHelper.getDocumentFile(context, it) }?.let { doc -> doc.name?.lowercase()?.let { it to doc.uri } }
                else -> null
            } ?: return@forEach
            val known = out[system]
            out[system] = ConsoleFolder(known?.uri ?: uri, known?.consoleIds.orEmpty() + consoleId)
        }
        return out
    }

    private suspend fun write(job: RommCovers.Job, esdeDir: DocumentFile, coverDirs: MutableMap<String, DocumentFile>, existing: Map<String, List<StorageHelper.Child>>): Boolean {
        val url = RommCovers.url(catalog.baseUrl, job.coverPath) ?: return false
        val downloaded = client.asset(url, MAX_COVER_BYTES)
        var extension = RommCovers.extension(job.coverPath)
        var bytes = downloaded
        if (!RommCovers.esdeReadable(extension)) {
            bytes = toPng(downloaded) ?: return false
            extension = "png"
        }
        val dir = coverDirs.getOrPut(job.system) {
            StorageHelper.createDirectory(esdeDir, "downloaded_media/${job.system}/covers") ?: throw IOException("no covers folder for ${job.system}")
        }
        val name = "${job.stem}.$extension"
        val sameStem = existing[job.stem.lowercase()].orEmpty()
        // Replacing: a cover in another format would otherwise keep winning in ES-DE.
        sameStem.filter { !it.name.equals(name, ignoreCase = true) }.forEach {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, it.uri) }
        }
        // octet-stream keeps the display name as given (an image mime can gain a second extension).
        val target = sameStem.firstOrNull { it.name.equals(name, ignoreCase = true) }?.uri
            ?: dir.createFile("application/octet-stream", name)?.uri
            ?: return false
        context.contentResolver.openOutputStream(target, "wt")?.use { it.write(bytes) } ?: return false
        return true
    }

    private fun toPng(bytes: ByteArray): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
