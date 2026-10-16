package com.cortinadev.dogmatix.data.service

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.ActionTypes
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.LibraryMoveRules
import com.cortinadev.dogmatix.util.TrashRules
import com.cortinadev.dogmatix.util.LibraryKeys
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Knows what is already on disk: the file names under the download folders (two levels deep)
 * so the library can mark owned games, plus the free space of the download volume.
 * Keys are scoped by the folder they were found in (see [LibraryKeys]) so a game owned for one
 * console is not marked for another console whose ROM happens to share the file name.
 * Rescans when the folders change in Settings or a download finishes.
 */
@OptIn(FlowPreview::class)
@Singleton
class LibraryIndexService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val downloadService: DownloadService,
    private val downloadableFileDao: DownloadableFileDao,
    // coleccion-gestion: "Delete" goes to the trash.
    private val history: ActionHistoryService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _ownedKeys = MutableStateFlow<Set<String>>(emptySet())
    /** `scope|name` keys of the files found on disk; see [LibraryKeys]. */
    val ownedKeys: StateFlow<Set<String>> = _ownedKeys.asStateFlow()

    private val _freeBytes = MutableStateFlow<Long?>(null)
    val freeBytes: StateFlow<Long?> = _freeBytes.asStateFlow()

    /** Finished downloads already marked as owned (only touched by the collector in init). */
    private val markedCompleted = HashSet<String>()

    init {
        scope.launch {
            combine(settingsRepository.downloadDirectory, settingsRepository.consoleDownloadDirectories, settingsRepository.secondaryStorageRoot) { root, perConsole, second ->
                listOf(root, second) + perConsole.values
            }.distinctUntilChanged().collect { refresh() }
        }
        scope.launch {
            downloadService.downloads
                .map { list -> list.filter { it.status == DownloadStatus.COMPLETED }.map { it.fileName }.toSet() }
                .distinctUntilChanged()
                // Mark finished downloads as owned right away; the full disk walk is slow over SAF.
                // Only the ones not marked yet: resolving every finished row again each time one
                // more finished was quadratic, enough for an ANR with a big queue or after a restart.
                .onEach { completed ->
                    markedCompleted.retainAll(completed)
                    val keys = completed.filter { markedCompleted.add(it) }.flatMap { keysForCompleted(it) }
                    if (keys.isNotEmpty()) _ownedKeys.update { it + keys }
                }
                .debounce(500)
                .collect { refresh() }
        }
    }

    /** Keys for a download that just finished: scoped to its console (from the download itself when known). */
    private suspend fun keysForCompleted(fileName: String): List<String> {
        val consoleId = (downloadService.entityFor(fileName) ?: downloadableFileDao.getFileByFileName(fileName))?.consoleId
            ?: return emptyList()
        return LibraryKeys.keysFor(LibraryKeys.consoleScope(consoleId), fileName)
    }

    fun isOwned(file: DownloadableFileEntity, keys: Set<String> = _ownedKeys.value): Boolean =
        LibraryKeys.isOwned(file.consoleId, file.fileName, keys)

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val root = settingsRepository.downloadDirectory.first()
        val custom = settingsRepository.consoleDownloadDirectories.first()
        // Free space first: it is one call, the walk below can take a while on a big library.
        _freeBytes.value = (listOf(root) + custom.values).firstOrNull { it.isNotBlank() }
            ?.let { StorageHelper.getFreeBytes(context, it) }
        val keys = HashSet<String>()
        // The second root of smart storage holds console folders laid out like the main one.
        for (rootUri in listOf(root, settingsRepository.secondaryStorageRoot.first())) {
            if (rootUri.isBlank()) continue
            runCatching { StorageHelper.getDocumentFile(context, rootUri) }.getOrNull()?.let { dir ->
                collectRoot(dir.uri, keys)
            }
        }
        custom.forEach { (consoleId, uri) ->
            if (uri.isBlank()) return@forEach
            val dir = runCatching { StorageHelper.getDocumentFile(context, uri) }.getOrNull() ?: return@forEach
            collect(dir.uri, LibraryKeys.customScope(consoleId), keys, depth = 0)
        }
        _ownedKeys.value = keys
    }

    /**
     * Delete every file on disk that [isOwned] would match for [file] — only inside the folders
     * that belong to its console — then refresh the index. Returns true if something was removed.
     */
    suspend fun deleteOwned(file: DownloadableFileEntity): Boolean = withContext(Dispatchers.IO) {
        val name = FileParsingUtils.decodeUrlEncodedFileName(file.fileName).lowercase()
        val base = LibraryKeys.baseName(name)
        val scopes = LibraryKeys.scopesFor(file.consoleId)
        val root = settingsRepository.downloadDirectory.first()
        val custom = settingsRepository.consoleDownloadDirectories.first()
        val found = ArrayList<DocumentFile>()

        for (rootUri in listOf(root, settingsRepository.secondaryStorageRoot.first())) {
            if (rootUri.isBlank()) continue
            runCatching { StorageHelper.getDocumentFile(context, rootUri) }.getOrNull()?.let { dir ->
                val children = runCatching { dir.listFiles() }.getOrNull().orEmpty()
                for (child in children) {
                    val childName = child.name ?: continue
                    if (child.isDirectory) {
                        if (LibraryKeys.folderScope(childName) in scopes) {
                            findMatching(child, name, base, depth = 1, found)
                        }
                    } else if (matches(childName, name, base)) {
                        found += child
                    }
                }
            }
        }
        custom[file.consoleId]?.takeIf { it.isNotBlank() }?.let { uri ->
            runCatching { StorageHelper.getDocumentFile(context, uri) }.getOrNull()?.let { dir ->
                findMatching(dir, name, base, depth = 0, found)
            }
        }
        // coleccion-gestion: to the trash as one entry; what it cannot take is deleted as before.
        val unique = found.distinctBy { it.uri }
        val result = if (unique.isEmpty()) null else runCatching {
            history.trash(unique.map { TrashRequest(it.uri, it.parentFile?.uri) }, ActionTypes.LIBRARY_DELETE, file.name, refreshLibrary = false)
        }.getOrNull()
        val leftovers = result?.failed?.map { it.uri }?.toSet() ?: unique.map { it.uri }.toSet()
        val removed = unique.filter { it.uri in leftovers }.count { runCatching { it.delete() }.getOrDefault(false) }
        val deleted = (result?.moved ?: 0) + removed > 0
        if (deleted) {
            // Drop the keys now so the list reflects the deletion immediately; rescan in the background.
            _ownedKeys.update { keys -> keys.filterNot { k -> scopes.any { s -> k == "$s|$name" || k == "$s|$base" } }.toSet() }
            scope.launch { refresh() }
        }
        deleted
    }

    private fun matches(childName: String, name: String, base: String): Boolean {
        val lower = childName.lowercase()
        return lower == name || LibraryKeys.baseName(lower) == base
    }

    private fun findMatching(dir: DocumentFile, name: String, base: String, depth: Int, into: MutableList<DocumentFile>) {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            val childName = child.name ?: continue
            if (TrashRules.isHidden(childName)) continue
            if (child.isDirectory) {
                if (depth < 2) findMatching(child, name, base, depth + 1, into)
            } else if (matches(childName, name, base)) {
                into += child
            }
        }
    }

    /**
     * Root of the download directory: loose files are root-scoped, each first-level folder is its own scope.
     * Folders are listed with [StorageHelper.listChildren] (one provider query per folder).
     */
    private fun collectRoot(dir: android.net.Uri, into: MutableSet<String>) {
        for (child in StorageHelper.listChildren(context, dir)) {
            // The trash, copies half-way through a library move and other hidden entries.
            if (TrashRules.isHidden(child.name) || LibraryMoveRules.isPart(child.name)) continue
            if (child.isDirectory) {
                collect(child.uri, LibraryKeys.folderScope(child.name), into, depth = 1)
            } else {
                into += LibraryKeys.keysFor(LibraryKeys.ROOT_SCOPE, child.name)
            }
        }
    }

    private fun collect(dir: android.net.Uri, scope: String, into: MutableSet<String>, depth: Int) {
        for (child in StorageHelper.listChildren(context, dir)) {
            if (TrashRules.isHidden(child.name) || LibraryMoveRules.isPart(child.name)) continue
            if (child.isDirectory) {
                if (depth < 2) collect(child.uri, scope, into, depth + 1)
            } else {
                into += LibraryKeys.keysFor(scope, child.name)
            }
        }
    }
}
