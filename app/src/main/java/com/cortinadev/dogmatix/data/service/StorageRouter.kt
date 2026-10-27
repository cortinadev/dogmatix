package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.data.local.dao.StorageRedirectDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.local.entity.StorageRedirectEntity
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.DocumentIds
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.LibraryMoveRules
import com.cortinadev.dogmatix.util.SmartStorage
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the library's files may be written right now, consulted by [DownloadFileManager] before
 * it resolves a download's folder:
 *  - while the library (or a console) is being moved, downloads wait ([awaitIdle]);
 *  - with smart storage on, a download that does not fit on its console's volume goes to the
 *    same console folder on the other root ([redirectFor]), and is remembered there so resuming,
 *    deleting or uploading it later finds it.
 * [fileOps] keeps the trash and the mover from working on the same folders at once.
 */
@Singleton
class StorageRouter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val consoleRepository: ConsoleRepository,
    private val redirectDao: StorageRedirectDao,
    private val tracker: DownloadProgressTracker,
    private val pathResolver: ConsoleDownloadPathResolver
) {
    val fileOps = Mutex()

    private val _moving = MutableStateFlow(false)
    /** A library move is running (downloads paused, new ones wait). */
    val moving: StateFlow<Boolean> = _moving.asStateFlow()

    fun setMoving(on: Boolean) { _moving.value = on }

    suspend fun awaitIdle() {
        if (_moving.value) _moving.first { !it }
    }

    data class Target(val rootUri: String, val subPath: String)

    /** Downloads already decided in this process: the decision must not flip once the file is being written. */
    private val decided = ConcurrentHashMap<String, Boolean>()

    /**
     * Where [file] goes instead of its console's own folder, or null for the usual place. A new
     * decision is only made while the download is in flight, once, and never when a partial
     * file of it already sits in the usual folder.
     */
    suspend fun redirectFor(file: DownloadableFileEntity): Target? {
        redirectDao.get(file.fileName)?.let { return Target(it.rootUri, it.subPath) }
        if (decided.containsKey(file.fileName) || !tracker.isActive(file.fileName)) return null
        val target = runCatching { decide(file) }.onFailure { Log.w(TAG, "Storage decision failed: ${it.message}") }.getOrNull()
        decided[file.fileName] = target != null
        target?.let { redirectDao.upsert(StorageRedirectEntity(file.fileName, file.consoleId, it.rootUri, it.subPath)) }
        return target
    }

    /** Called when a download's file is gone for good (deleted from Downloads). */
    suspend fun forget(fileName: String) {
        decided.remove(fileName)
        redirectDao.delete(fileName)
    }

    private suspend fun decide(file: DownloadableFileEntity): Target? {
        val second = settings.secondaryStorageRoot.first().ifBlank { return null }
        if (file.fileSize <= 0 || consoleRepository.getConsoleById(file.consoleId) == null) return null
        val main = settings.downloadDirectory.first().ifBlank { return null }
        val custom = settings.consoleDownloadDirectories.first()[file.consoleId]
        val usual = consoleFolder(file.consoleId, main, custom) ?: return null
        val secondId = SafDocs.docUri(second)?.let(SafDocs::docId) ?: return null
        val place = placeOf(usual, secondId)
        val other = if (place == SmartStorage.Place.MAIN) {
            Target(second, usual.folderName)
        } else {
            Target(main, pathResolver.resolveAll(listOf(file.consoleId), main, settings.separateByConsole.first(), emptyMap()).getValue(file.consoleId).subPath)
        }
        // Resuming: the partial is where the usual resolution puts it (`<name>.dogmatix-part` from 1.4 on).
        val name = FileParsingUtils.decodeUrlEncodedFileName(file.fileName)
        val existing = StorageHelper.getDocumentFile(context, usual.rootUri)?.let { root ->
            listOf(LibraryMoveRules.partName(name), name).firstNotNullOfOrNull { candidate ->
                StorageHelper.findFile(root, listOf(usual.subPath, candidate).filter { it.isNotEmpty() }.joinToString("/"))
            }
        }
        if (existing != null) return null
        val chosen = SmartStorage.downloadPlace(
            bytes = file.fileSize,
            own = place,
            ownFree = StorageHelper.getFreeBytes(context, usual.rootUri),
            otherFree = StorageHelper.getFreeBytes(context, other.rootUri)
        )
        return if (chosen == place) null else other
    }

    /** A console's folder as the download resolution sees it: a root (tree or folder URI) plus a sub-path. */
    data class ConsoleFolder(val rootUri: String, val subPath: String, val folderName: String)

    suspend fun consoleFolder(consoleId: String, main: String, custom: String?): ConsoleFolder? {
        if (custom != null) {
            val docId = SafDocs.docUri(custom)?.let(SafDocs::docId) ?: return null
            return ConsoleFolder(custom, "", DocumentIds.nameOf(docId))
        }
        val subPath = pathResolver.resolveAll(listOf(consoleId), main, settings.separateByConsole.first(), emptyMap()).getValue(consoleId).subPath
        return ConsoleFolder(main, subPath, subPath)
    }

    /** SECOND when [folder] lies inside the second root ([secondId], its document id). */
    fun placeOf(folder: ConsoleFolder, secondId: String): SmartStorage.Place {
        val id = SafDocs.docUri(folder.rootUri)?.let(SafDocs::docId) ?: return SmartStorage.Place.MAIN
        return if (DocumentIds.isWithin(secondId, id)) SmartStorage.Place.SECOND else SmartStorage.Place.MAIN
    }

    private companion object {
        const val TAG = "StorageRouter"
    }
}
