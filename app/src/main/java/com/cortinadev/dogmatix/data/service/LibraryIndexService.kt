package com.cortinadev.dogmatix.data.service

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.FileParsingUtils
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
    private val downloadableFileDao: DownloadableFileDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _ownedKeys = MutableStateFlow<Set<String>>(emptySet())
    /** `scope|name` keys of the files found on disk; see [LibraryKeys]. */
    val ownedKeys: StateFlow<Set<String>> = _ownedKeys.asStateFlow()

    private val _freeBytes = MutableStateFlow<Long?>(null)
    val freeBytes: StateFlow<Long?> = _freeBytes.asStateFlow()

    init {
        scope.launch {
            combine(settingsRepository.downloadDirectory, settingsRepository.consoleDownloadDirectories) { root, perConsole ->
                listOf(root) + perConsole.values
            }.distinctUntilChanged().collect { refresh() }
        }
        scope.launch {
            downloadService.downloads
                .map { list -> list.filter { it.status == DownloadStatus.COMPLETED }.map { it.fileName }.toSet() }
                .distinctUntilChanged()
                // Mark finished downloads as owned right away; the full disk walk is slow over SAF.
                .onEach { completed -> _ownedKeys.update { it + completed.flatMap { keysForCompleted(it) } } }
                .debounce(500)
                .collect { refresh() }
        }
    }

    /** Keys for a download that just finished: scoped to its console (looked up by file name). */
    private suspend fun keysForCompleted(fileName: String): List<String> {
        val consoleId = downloadableFileDao.getFileByFileName(fileName)?.consoleId ?: return emptyList()
        return LibraryKeys.keysFor(LibraryKeys.consoleScope(consoleId), fileName)
    }

    fun isOwned(file: DownloadableFileEntity, keys: Set<String> = _ownedKeys.value): Boolean =
        LibraryKeys.isOwned(file.consoleId, file.fileName, keys)

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val root = settingsRepository.downloadDirectory.first()
        val custom = settingsRepository.consoleDownloadDirectories.first()
        val keys = HashSet<String>()
        if (root.isNotBlank()) {
            runCatching { StorageHelper.getDocumentFile(context, root) }.getOrNull()?.let { dir ->
                collectRoot(dir, keys)
            }
        }
        custom.forEach { (consoleId, uri) ->
            if (uri.isBlank()) return@forEach
            val dir = runCatching { StorageHelper.getDocumentFile(context, uri) }.getOrNull() ?: return@forEach
            collect(dir, LibraryKeys.customScope(consoleId), keys, depth = 0)
        }
        _ownedKeys.value = keys
        _freeBytes.value = (listOf(root) + custom.values).firstOrNull { it.isNotBlank() }
            ?.let { StorageHelper.getFreeBytes(context, it) }
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
        var deleted = false

        if (root.isNotBlank()) {
            runCatching { StorageHelper.getDocumentFile(context, root) }.getOrNull()?.let { dir ->
                val children = runCatching { dir.listFiles() }.getOrNull().orEmpty()
                for (child in children) {
                    val childName = child.name ?: continue
                    if (child.isDirectory) {
                        if (LibraryKeys.folderScope(childName) in scopes) {
                            deleted = deleteMatching(child, name, base, depth = 1) || deleted
                        }
                    } else if (matches(childName, name, base)) {
                        deleted = runCatching { child.delete() }.getOrDefault(false) || deleted
                    }
                }
            }
        }
        custom[file.consoleId]?.takeIf { it.isNotBlank() }?.let { uri ->
            runCatching { StorageHelper.getDocumentFile(context, uri) }.getOrNull()?.let { dir ->
                deleted = deleteMatching(dir, name, base, depth = 0) || deleted
            }
        }
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

    private fun deleteMatching(dir: DocumentFile, name: String, base: String, depth: Int): Boolean {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return false
        var deleted = false
        for (child in children) {
            val childName = child.name ?: continue
            if (child.isDirectory) {
                if (depth < 2) deleted = deleteMatching(child, name, base, depth + 1) || deleted
            } else if (matches(childName, name, base)) {
                deleted = runCatching { child.delete() }.getOrDefault(false) || deleted
            }
        }
        return deleted
    }

    /** Root of the download directory: loose files are root-scoped, each first-level folder is its own scope. */
    private fun collectRoot(dir: DocumentFile, into: MutableSet<String>) {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                collect(child, LibraryKeys.folderScope(name), into, depth = 1)
            } else {
                into += LibraryKeys.keysFor(LibraryKeys.ROOT_SCOPE, name)
            }
        }
    }

    private fun collect(dir: DocumentFile, scope: String, into: MutableSet<String>, depth: Int) {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                if (depth < 2) collect(child, scope, into, depth + 1)
            } else {
                into += LibraryKeys.keysFor(scope, name)
            }
        }
    }
}
