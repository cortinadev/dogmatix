package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.ActionTypes
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.DocumentIds
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.LibraryMoveRules
import com.cortinadev.dogmatix.util.SmartStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One of the two library roots: where it is, and how full its volume is (null = unknown). */
data class StorageVolume(
    val uri: String,
    val path: String,
    /** On the device's own (internal, "primary") storage. */
    val internal: Boolean,
    val freeBytes: Long?,
    val totalBytes: Long?
)

/** A console with a folder on disk, as smart storage sees it. */
data class ConsoleStorage(
    val consoleId: String,
    val name: String,
    val place: SmartStorage.Place,
    val path: String,
    val bytes: Long,
    /** Downloads of this console that did not fit and sit in its folder on the other root. */
    val strayBytes: Long
)

data class SmartStorageScan(
    val main: StorageVolume?,
    val second: StorageVolume?,
    val consoles: List<ConsoleStorage>,
    val suggestions: List<SmartStorage.Suggestion>
)

/**
 * Smart storage: a second library root (normally on the SD card) next to the main one, and for
 * each console which of the two holds its folder (a console on the second root has that folder
 * as its own download folder). Measures the consoles, suggests moving the big ones off a tight
 * internal volume, moves them (through [LibraryMoveService], so it is paused, verified,
 * resumable and undoable) and re-runs the frontend setups, whose paths a move invalidates.
 * Downloads that do not fit are sent to the other root by [StorageRouter].
 */
@Singleton
class SmartStorageService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val consoleRepository: ConsoleRepository,
    private val router: StorageRouter,
    private val mover: LibraryMoveService,
    private val shortcuts: FrontendShortcutService,
    private val esde: EsdeConfigService,
    private val iisu: IisuConfigService
) {
    val secondRoot: Flow<String> = settings.secondaryStorageRoot

    /** Sets the second root; false (nothing saved) when it is the library root, or inside it or around it. */
    suspend fun setSecondRoot(uri: String): Boolean {
        val main = settings.downloadDirectory.first()
        val secondId = SafDocs.docUri(uri)?.let(SafDocs::docId) ?: return false
        val mainId = SafDocs.docUri(main)?.let(SafDocs::docId)
        if (mainId != null && LibraryMoveRules.overlaps(mainId, secondId)) return false
        settings.setSecondaryStorageRoot(uri)
        return true
    }

    /** Volumes of both roots, then every console's folder measured (one SAF walk each); [onProgress] gets done / total. */
    suspend fun scan(onProgress: (Int, Int) -> Unit = { _, _ -> }): SmartStorageScan = withContext(Dispatchers.IO) {
        val mainUri = settings.downloadDirectory.first()
        val secondUri = settings.secondaryStorageRoot.first()
        val main = volume(mainUri)
        val second = volume(secondUri)
        val secondId = SafDocs.docUri(secondUri)?.let(SafDocs::docId)
        val custom = settings.consoleDownloadDirectories.first()
        val consoles = consoleRepository.getAllConsoles().first()
        val out = ArrayList<ConsoleStorage>()
        consoles.forEachIndexed { index, console ->
            onProgress(index, consoles.size)
            val folder = router.consoleFolder(console.id, mainUri, custom[console.id]) ?: return@forEachIndexed
            // Without per-console subfolders every console shares the root: nothing to tell apart.
            if (custom[console.id] == null && folder.subPath.isEmpty()) return@forEachIndexed
            val dir = folderUri(folder) ?: return@forEachIndexed
            val place = if (secondId == null) SmartStorage.Place.MAIN else router.placeOf(folder, secondId)
            val bytes = SafDocs.folderSize(context, dir)
            // Its folder of the same name on the other root holds what was redirected there.
            val otherRoot = if (place == SmartStorage.Place.MAIN) secondUri else mainUri
            val stray = if (secondId == null || folder.folderName.isEmpty()) 0L else {
                SafDocs.docUri(otherRoot)?.let { SafDocs.child(context, it, folder.folderName) }
                    ?.takeIf { it.isDirectory && it.uri.toString() != dir.toString() }
                    ?.let { SafDocs.folderSize(context, it.uri) } ?: 0L
            }
            if (bytes > 0 || stray > 0) {
                out += ConsoleStorage(
                    consoleId = console.id,
                    name = console.name.ifBlank { ConsoleFormatter.getConsoleFolderName(console.id) },
                    place = place,
                    path = FileParsingUtils.toUserReadablePath(dir.toString()),
                    bytes = bytes,
                    strayBytes = stray
                )
            }
        }
        onProgress(consoles.size, consoles.size)
        val sorted = out.sortedByDescending { it.bytes }
        SmartStorageScan(main, second, sorted, suggestionsFor(sorted, main, second))
    }

    /** Big consoles off whichever root is on internal storage, while it is tight. */
    fun suggestionsFor(consoles: List<ConsoleStorage>, main: StorageVolume?, second: StorageVolume?): List<SmartStorage.Suggestion> {
        if (main == null || second == null || main.internal == second.internal) return emptyList()
        val from = if (main.internal) SmartStorage.Place.MAIN else SmartStorage.Place.SECOND
        val (fromVolume, toVolume) = if (main.internal) main to second else second to main
        return SmartStorage.suggest(
            consoles.map { SmartStorage.Console(it.consoleId, it.place, it.bytes) },
            from, fromVolume.freeBytes, fromVolume.totalBytes, toVolume.freeBytes
        )
    }

    /** Starts moving [consoleIds] to [to]; false when there is nothing to move or a move is running. */
    suspend fun move(consoleIds: Collection<String>, to: SmartStorage.Place, subject: String): Boolean {
        val destination = (if (to == SmartStorage.Place.SECOND) settings.secondaryStorageRoot else settings.downloadDirectory).first()
        if (destination.isBlank()) return false
        val job = mover.planConsoles(consoleIds, destination, ActionTypes.SMART_STORAGE, subject) ?: return false
        return mover.start(job)
    }

    /** Result of [reconfigureFrontends]; a null result means that frontend is not set up here. */
    data class FrontendRun(val shortcutsWritten: Int, val esde: Result<EsdeConfigService.Result>?, val iisu: Result<IisuConfigService.Result>?)

    /**
     * After moving folders: the `.dgmtx` shortcuts again, and the ES-DE / iiSU setups if they
     * were configured from Settings (each reads where the console folders are now).
     */
    suspend fun reconfigureFrontends(): FrontendRun {
        val written = runCatching { shortcuts.deployShortcuts().written }.getOrDefault(0)
        val esdeDir = settings.esdeDirectory.first()
        val iisuDir = settings.iisuDirectory.first()
        return FrontendRun(
            shortcutsWritten = written,
            esde = esdeDir.takeIf { it.isNotBlank() }?.let { runCatching { esde.configure(it) } },
            iisu = iisuDir.takeIf { it.isNotBlank() }?.let { runCatching { iisu.configure(it) } }
        )
    }

    private fun folderUri(folder: StorageRouter.ConsoleFolder): android.net.Uri? {
        val root = SafDocs.docUri(folder.rootUri) ?: return null
        return if (folder.subPath.isEmpty()) root else SafDocs.child(context, root, folder.subPath)?.takeIf { it.isDirectory }?.uri
    }

    private fun volume(uri: String): StorageVolume? {
        if (uri.isBlank()) return null
        val docId = SafDocs.docUri(uri)?.let(SafDocs::docId) ?: return null
        val volumeId = DocumentIds.volumeOf(docId)
        val dir = volumeDir(volumeId)
        val stat = dir?.let { runCatching { StatFs(it.path) }.getOrNull() }
        return StorageVolume(
            uri = uri,
            path = FileParsingUtils.toUserReadablePath(uri),
            internal = volumeId == "primary",
            freeBytes = stat?.availableBytes,
            totalBytes = stat?.totalBytes
        )
    }

    private fun volumeDir(volumeId: String?): File? = when {
        volumeId == null -> null
        volumeId == "primary" -> Environment.getExternalStorageDirectory()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> runCatching {
            (context.getSystemService(Context.STORAGE_SERVICE) as StorageManager).storageVolumes
                .firstOrNull { it.uuid.equals(volumeId, ignoreCase = true) }?.directory
        }.getOrNull()
        else -> null
    }
}
