package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.repository.DownloadableFileRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.ConsoleFolderAliases
import com.cortinadev.dogmatix.util.DiscRef
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.MultiDisc
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MultiDiscService"

/** What the multi-disc step made of a finished download (see [MultiDiscService.settled]). */
data class DiscSetOutcome(
    val fileName: String,
    /** `<game>.m3u` when the download's discs now sit in that folder. */
    val setFolder: String? = null,
    /** A disc of a set that will be gathered once its other discs arrive. */
    val waitingForDiscs: Boolean = false
)

/**
 * "Multi-disc games" in Settings: when the last disc of a release lands, its discs move into
 * `<console>/<game>.m3u/` next to a `<game>.m3u` playlist (ES-DE shows the folder as one game and
 * the emulator swaps discs from the playlist). Only for systems whose emulators read `.m3u`; a
 * disc that arrives later (re-download) joins the existing folder and the playlist is rewritten.
 */
@Singleton
class MultiDiscService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadService: DownloadService,
    private val downloadFileManager: DownloadFileManager,
    private val fileRepository: DownloadableFileRepository,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Two discs finishing together must not both build the folder. */
    private val mutex = Mutex()

    private val _settled = MutableSharedFlow<DiscSetOutcome>(extraBufferCapacity = 32)
    /**
     * Every finished download once this step is done with it (whether it moved anything or not),
     * so what acts on the files afterwards (the RomM upload) finds them where they will stay.
     */
    val settled: SharedFlow<DiscSetOutcome> = _settled.asSharedFlow()

    init {
        scope.launch {
            downloadService.finished.collect { fileName ->
                val outcome = runCatching { mutex.withLock { handle(fileName) } }
                    .onFailure { Log.w(TAG, "Multi-disc step failed for $fileName: ${it.message}") }
                    .getOrNull()
                _settled.emit(outcome ?: DiscSetOutcome(fileName))
            }
        }
    }

    private suspend fun handle(fileName: String): DiscSetOutcome {
        val none = DiscSetOutcome(fileName)
        if (!settingsRepository.multiDiscPlaylists.first()) return none
        val entity = downloadService.entityFor(fileName) ?: return none
        val written = downloadService.uploadCandidates(fileName)
        val disc = written.firstNotNullOfOrNull { name -> MultiDisc.discOf(name)?.takeIf { MultiDisc.isDiscImage(name) } }
            ?: MultiDisc.discOf(FileParsingUtils.decodeUrlEncodedFileName(entity.fileName))
            ?: return none

        val rootUri = downloadFileManager.getDownloadDirectoryUri(entity)
        if (rootUri == android.net.Uri.EMPTY) return none
        val subPath = downloadFileManager.getSubPath(entity)
        val systemNames = ConsoleFolderAliases.candidatesFor(entity.consoleId) +
            ConsoleFolderAliases.normalize(subPath.substringAfterLast('/'))
        if (!MultiDisc.supportsPlaylists(systemNames)) return none

        val dir = StorageHelper.createDirectory(context, rootUri.toString(), subPath) ?: return none
        val folderName = "${disc.setStem}.m3u"
        val children = dir.listFiles()
        val looseFiles = children.filter { it.isFile && it.name != null }.associateBy { it.name!! }
        val setDir = children.firstOrNull { it.isDirectory && it.name == folderName }
        val inSetFiles = setDir?.listFiles()?.filter { it.isFile && it.name != null }?.associateBy { it.name!! }.orEmpty()

        val expected = expectedDiscs(entity, disc)
        val plan = MultiDisc.plan(
            setStem = disc.setStem,
            loose = looseFiles.keys.toList(),
            inSet = inSetFiles.keys.toList(),
            expectedDiscs = expected
        ) { sheet -> sheetFiles(looseFiles[sheet] ?: inSetFiles[sheet]) }
            ?: return DiscSetOutcome(fileName, waitingForDiscs = maxOf(expected, disc.total ?: 0) >= 2)

        // An older playlist with the folder's name would make the provider rename the folder.
        plan.conflictingFile?.let { looseFiles[it]?.delete() }
        val target = setDir ?: dir.createDirectory(folderName) ?: return none
        for (name in plan.moves) {
            val file = looseFiles[name] ?: continue
            if (!move(file, dir, target)) Log.w(TAG, "Could not move $name into $folderName")
        }
        StorageHelper.writeTextSafely(context, target, "", plan.name, plan.content)
        Log.i(TAG, "Wrote $folderName with ${plan.entries.size} discs")
        return DiscSetOutcome(fileName, setFolder = folderName)
    }

    /** Discs the catalogue lists for this release (same game, same name save for the disc tag). */
    private suspend fun expectedDiscs(entity: DownloadableFileEntity, disc: DiscRef): Int =
        fileRepository.gameFileNames(entity.consoleId, entity.groupKey)
            .mapNotNull { MultiDisc.discOf(FileParsingUtils.decodeUrlEncodedFileName(it)) }
            .filter { it.setStem.equals(disc.setStem, ignoreCase = true) }
            .map { it.number }
            .distinct()
            .size

    /** What a `.cue` / `.gdi` refers to, so tracks named otherwise move along with it. */
    private fun sheetFiles(sheet: DocumentFile?): List<String> {
        if (sheet == null || sheet.length() > 256 * 1024) return emptyList()
        val text = runCatching { StorageHelper.readText(context, sheet) }.getOrNull() ?: return emptyList()
        return when (MultiDisc.extensionOf(sheet.name.orEmpty())) {
            "cue" -> MultiDisc.cueFiles(text)
            "gdi" -> MultiDisc.gdiFiles(text)
            else -> emptyList()
        }
    }

    /**
     * Moves within the volume (no copy) when the provider can; otherwise copies and deletes the
     * original. A file of the same name already in [to] is replaced.
     */
    private fun move(file: DocumentFile, from: DocumentFile, to: DocumentFile): Boolean {
        val name = file.name ?: return false
        to.findFile(name)?.takeIf { it.isFile }?.delete()
        val moved = runCatching { DocumentsContract.moveDocument(context.contentResolver, file.uri, from.uri, to.uri) != null }
            .onFailure { Log.w(TAG, "moveDocument failed for $name: ${it.message}") }
            .getOrDefault(false)
        if (moved) return true
        val copy = to.createFile("application/octet-stream", name) ?: return false
        val copied = runCatching {
            context.contentResolver.openInputStream(file.uri)!!.use { input ->
                context.contentResolver.openOutputStream(copy.uri)!!.use { input.copyTo(it, 1 shl 20) }
            }
        }.isSuccess
        if (!copied) {
            copy.delete()
            return false
        }
        return file.delete()
    }
}
