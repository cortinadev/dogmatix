package com.cortinadev.dogmatix.data.service

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.FileParsingUtils
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
 * Rescans when the folders change in Settings or a download finishes.
 */
@OptIn(FlowPreview::class)
@Singleton
class LibraryIndexService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val downloadService: DownloadService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _ownedKeys = MutableStateFlow<Set<String>>(emptySet())
    /** Lower-cased file names and base names (without extension) found on disk. */
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
                .onEach { completed -> _ownedKeys.update { it + completed.flatMap(::keysFor) } }
                .debounce(500)
                .collect { refresh() }
        }
    }

    /** The keys [isOwned] checks for a (possibly URL-encoded) file name. */
    private fun keysFor(fileName: String): List<String> {
        val name = FileParsingUtils.decodeUrlEncodedFileName(fileName).lowercase()
        return listOf(name, baseName(name))
    }

    fun isOwned(file: DownloadableFileEntity, keys: Set<String> = _ownedKeys.value): Boolean {
        if (keys.isEmpty()) return false
        val name = FileParsingUtils.decodeUrlEncodedFileName(file.fileName).lowercase()
        return name in keys || baseName(name) in keys
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val roots = (listOf(settingsRepository.downloadDirectory.first()) +
            settingsRepository.consoleDownloadDirectories.first().values)
            .filter { it.isNotBlank() }
            .distinct()
        val keys = HashSet<String>()
        roots.forEach { uri ->
            val dir = runCatching { StorageHelper.getDocumentFile(context, uri) }.getOrNull() ?: return@forEach
            collect(dir, keys, depth = 0)
        }
        _ownedKeys.value = keys
        _freeBytes.value = roots.firstOrNull()?.let { StorageHelper.getFreeBytes(context, it) }
    }

    /**
     * Delete every file on disk that [isOwned] would match for [file] (the archive itself or
     * its extracted content sharing the base name), then refresh the index.
     * Returns true if at least one file was removed.
     */
    suspend fun deleteOwned(file: DownloadableFileEntity): Boolean = withContext(Dispatchers.IO) {
        val name = FileParsingUtils.decodeUrlEncodedFileName(file.fileName).lowercase()
        val base = baseName(name)
        val roots = (listOf(settingsRepository.downloadDirectory.first()) +
            settingsRepository.consoleDownloadDirectories.first().values)
            .filter { it.isNotBlank() }
            .distinct()
        var deleted = false
        roots.forEach { uri ->
            val dir = runCatching { StorageHelper.getDocumentFile(context, uri) }.getOrNull() ?: return@forEach
            deleted = deleteMatching(dir, name, base, depth = 0) || deleted
        }
        if (deleted) {
            // Drop the keys now so the list reflects the deletion immediately; rescan in the background.
            _ownedKeys.update { it - name - base }
            scope.launch { refresh() }
        }
        deleted
    }

    private fun deleteMatching(dir: DocumentFile, name: String, base: String, depth: Int): Boolean {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return false
        var deleted = false
        for (child in children) {
            val childName = child.name?.lowercase() ?: continue
            if (child.isDirectory) {
                if (depth < 2) deleted = deleteMatching(child, name, base, depth + 1) || deleted
            } else if (childName == name || baseName(childName) == base) {
                deleted = runCatching { child.delete() }.getOrDefault(false) || deleted
            }
        }
        return deleted
    }

    private fun collect(dir: DocumentFile, into: MutableSet<String>, depth: Int) {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            val name = child.name?.lowercase() ?: continue
            if (child.isDirectory) {
                if (depth < 2) collect(child, into, depth + 1)
            } else {
                into += name
                into += baseName(name)
            }
        }
    }

    private fun baseName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }
}
