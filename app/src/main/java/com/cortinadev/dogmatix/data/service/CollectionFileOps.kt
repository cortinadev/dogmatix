package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.ActionTypes
import com.cortinadev.dogmatix.util.DiscPlaylist
import com.cortinadev.dogmatix.util.DiskFile
import com.cortinadev.dogmatix.util.DiskFolder
import com.cortinadev.dogmatix.util.MultiDisc
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CollectionFileOps"

/**
 * The only place the Collection tools delete or move anything: games, leftovers, empty folders,
 * misplaced files, the playlists of multi-disc sets and the app's caches. Every operation runs to
 * the end even if the screen goes away (a game is never left half deleted), then tells
 * [LibraryScanService] what changed and refreshes the library's "on disk" marks. Files go to the
 * trash with an entry in the history ([ActionHistoryService.trash]), so Collection → History can
 * undo them; Free up space ([deleteForGood]), empty folders and the caches are deleted for good.
 */
@OptIn(FlowPreview::class)
@Singleton
class CollectionFileOps @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val scanService: LibraryScanService,
    private val libraryIndexService: LibraryIndexService,
    private val settingsRepository: SettingsRepository,
    private val pathResolver: ConsoleDownloadPathResolver,
    private val downloadService: DownloadService,
    private val seedingManager: SeedingManager,
    private val history: ActionHistoryService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The library's "on disk" marks walk every folder: a batch of operations asks for one walk. */
    private val indexStale = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        scope.launch { indexStale.debounce(1_000).collect { runCatching { libraryIndexService.refresh() } } }
    }

    /** How an operation went: items done, items that failed, bytes freed. */
    data class Result(val done: Int, val failed: Int, val bytes: Long = 0) {
        operator fun plus(other: Result) = Result(done + other.done, failed + other.failed, bytes + other.bytes)
    }

    /**
     * Moves [files] to the trash as one history entry of [type] ([ActionTypes.DUPLICATES] or
     * [ActionTypes.REPAIR]) about [subject]; a file the trash cannot
     * take is deleted as before (the user confirmed the deletion). Then each folder of
     * [setFolders] (a game's `<game>.m3u/`) goes once a complete listing says it is empty,
     * because providers delete folders with whatever is left inside.
     */
    suspend fun delete(
        files: List<DiskFile>,
        setFolders: List<String> = emptyList(),
        type: String = ActionTypes.REPAIR,
        subject: String = files.firstOrNull()?.let { MultiDisc.stripExtension(it.name) }.orEmpty()
    ): Result = withContext(NonCancellable + Dispatchers.IO) {
        val removed = HashSet<String>()
        var bytes = 0L
        val trashed = runCatching {
            history.trash(files.map { TrashRequest(it.uri.toUri(), it.parentUri.toUri()) }, type, subject, refreshLibrary = false)
        }.onFailure { Log.w(TAG, "Trash unavailable: ${it.message}") }.getOrNull()
        val notTrashed = trashed?.failed?.mapTo(HashSet()) { it.uri.toString() } ?: files.mapTo(HashSet()) { it.uri }
        for (file in files) {
            if (file.uri !in notTrashed || deleteDocument(file.uri.toUri())) {
                removed += file.uri
                bytes += file.size
            }
        }
        val folders = setFolders.filter { deleteIfEmpty(it.toUri()) }.toSet()
        finish(removed, folders, emptySet())
        Result(removed.size, files.size - removed.size, bytes)
    }

    /**
     * Free up space: deletes [files] for good, without the trash (in the same volume the trash is
     * a rename, so nothing would be freed until it is emptied), then each empty folder of
     * [setFolders]. Nothing to undo: [recordFreed] notes it in the history.
     */
    suspend fun deleteForGood(files: List<DiskFile>, setFolders: List<String> = emptyList()): Result =
        withContext(NonCancellable + Dispatchers.IO) {
            val removed = files.filter { deleteDocument(it.uri.toUri()) }
            val folders = setFolders.filter { deleteIfEmpty(it.toUri()) }.toSet()
            finish(removed.mapTo(HashSet()) { it.uri }, folders, emptySet())
            Result(removed.size, files.size - removed.size, removed.sumOf { it.size })
        }

    /** A Free up space run in the history, as an entry that cannot be undone. */
    suspend fun recordFreed(subject: String, items: Int, bytes: Long) {
        if (items > 0) runCatching { history.record(ActionTypes.FREE_SPACE, subject, items, bytes) }
    }

    /** Deletes folders the scan saw empty, after checking again that they still are. */
    suspend fun deleteEmptyFolders(folders: List<DiskFolder>): Result = withContext(NonCancellable + Dispatchers.IO) {
        val removed = folders.filter { deleteIfEmpty(it.uri.toUri()) }.mapTo(HashSet()) { it.uri }
        finish(emptySet(), removed, emptySet())
        Result(removed.size, folders.size - removed.size)
    }

    /**
     * Moves [files] into [consoleId]'s download folder (its own directory, or its folder in the
     * download directory, created when missing). A file of the same name already there is left
     * where it was and counts as failed: nothing is ever overwritten.
     */
    suspend fun moveToConsole(files: List<DiskFile>, consoleId: String): Result = withContext(NonCancellable + Dispatchers.IO) {
        val target = consoleFolder(consoleId) ?: return@withContext Result(0, files.size)
        val moved = HashSet<String>()
        for (file in files) {
            if (file.parentUri == target.uri.toString()) continue
            if (runCatching { target.findFile(file.name) }.getOrNull() != null) continue
            if (move(file, target)) moved += file.uri
        }
        // In the history as a repair (no undo: the file is in its right folder now).
        if (moved.isNotEmpty()) {
            val first = files.first { it.uri in moved }
            runCatching { history.record(ActionTypes.REPAIR, MultiDisc.stripExtension(first.name), moved.size, files.filter { it.uri in moved }.sumOf { it.size }) }
        }
        finish(moved, emptySet(), files.mapNotNullTo(HashSet()) { it.consoleId } + consoleId)
        Result(moved.size, files.size - moved.size)
    }

    /**
     * Writes a multi-disc playlist the ES-DE way: [moves] go into `<game>.m3u/` inside
     * [parentUri] ([setFolderUri] when it already exists) and the playlist is written in it.
     */
    suspend fun createPlaylist(parentUri: String, setFolderUri: String?, playlist: DiscPlaylist, moves: List<DiskFile>, consoleId: String?): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            val folder = setFolderUri?.let { DocumentFile.fromTreeUri(context, it.toUri()) }
                ?: DocumentFile.fromTreeUri(context, parentUri.toUri())?.let { parent ->
                    runCatching { parent.findFile(playlist.name) }.getOrNull()?.takeIf { it.isDirectory } ?: parent.createDirectory(playlist.name)
                }
                ?: return@withContext false
            // A disc already in the folder under the same name stays as it is: nothing is overwritten.
            val moved = moves.filter { runCatching { folder.findFile(it.name) }.getOrNull() == null && move(it, folder) }.mapTo(HashSet()) { it.uri }
            val written = runCatching { StorageHelper.writeTextSafely(context, folder, "", playlist.name, playlist.content) }
                .onFailure { Log.w(TAG, "Could not write ${playlist.name}: ${it.message}") }
                .isSuccess
            finish(moved, emptySet(), setOfNotNull(consoleId))
            written && moved.size == moves.size
        }

    /** Bytes of the torrent and unpack caches, or null while a download, unpack or seed may be using them. */
    fun torrentCacheBytes(): Long? = if (cachesBusy()) null else cacheDirs().sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    /** Clears the torrent and unpack caches when nothing uses them; returns the bytes freed. */
    suspend fun clearTorrentCache(): Long = withContext(NonCancellable + Dispatchers.IO) {
        val bytes = torrentCacheBytes() ?: return@withContext 0L
        cacheDirs().forEach { it.deleteRecursively() }
        bytes
    }

    @OptIn(ExperimentalCoilApi::class)
    fun imageCacheBytes(): Long = runCatching { context.imageLoader.diskCache?.size ?: 0L }.getOrDefault(0L)

    /** Empties the cover cache (on disk and in memory); covers are fetched again when shown. */
    @OptIn(ExperimentalCoilApi::class)
    suspend fun clearImageCache(): Long = withContext(NonCancellable + Dispatchers.IO) {
        val bytes = imageCacheBytes()
        runCatching {
            context.imageLoader.memoryCache?.clear()
            context.imageLoader.diskCache?.clear()
        }.onFailure { Log.w(TAG, "Could not clear the image cache: ${it.message}") }
        bytes
    }

    private fun cachesBusy(): Boolean = seedingManager.isSeeding || downloadService.downloads.value.any {
        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.COPYING ||
            it.status == DownloadStatus.UNZIPPING || it.status == DownloadStatus.PAUSED
    }

    private fun cacheDirs(): List<File> = listOf("torrent_data", "extraction_temp").map { File(context.cacheDir, it) }.filter { it.exists() }

    /** The folder [consoleId] downloads to, as [DownloadFileManager] resolves it; created when missing. */
    private suspend fun consoleFolder(consoleId: String): DocumentFile? {
        settingsRepository.consoleDownloadDirectories.first()[consoleId]?.takeIf { it.isNotBlank() }?.let { custom ->
            return DocumentFile.fromTreeUri(context, custom.toUri())
        }
        val root = settingsRepository.downloadDirectory.first().takeIf { it.isNotBlank() } ?: return null
        val subPath = pathResolver.resolve(settingsRepository, consoleId).subPath
        return if (subPath.isEmpty()) StorageHelper.getDocumentFile(context, root) else StorageHelper.createDirectory(context, root, subPath)
    }

    /** Moves within the provider when it can; otherwise copies and deletes the original. */
    private fun move(file: DiskFile, target: DocumentFile): Boolean {
        val moved = runCatching {
            DocumentsContract.moveDocument(context.contentResolver, file.uri.toUri(), file.parentUri.toUri(), target.uri) != null
        }.onFailure { Log.w(TAG, "moveDocument failed for ${file.name}: ${it.message}") }.getOrDefault(false)
        if (moved) return true
        val copy = runCatching { target.createFile("application/octet-stream", file.name) }.getOrNull() ?: return false
        val copied = runCatching {
            context.contentResolver.openInputStream(file.uri.toUri())!!.use { input ->
                context.contentResolver.openOutputStream(copy.uri)!!.use { input.copyTo(it, 1 shl 20) }
            }
        }.isSuccess
        if (!copied) {
            runCatching { copy.delete() }
            return false
        }
        return deleteDocument(file.uri.toUri())
    }

    private fun deleteDocument(uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            .onFailure { Log.w(TAG, "Cannot delete $uri: ${it.message}") }
            .getOrDefault(false)

    /** Providers delete a folder with everything in it: only one a complete listing shows empty goes. */
    private fun deleteIfEmpty(folder: Uri): Boolean = isEmpty(folder) && deleteDocument(folder)

    /** True only when the provider answered, has finished loading and listed nothing. */
    private fun isEmpty(folder: Uri): Boolean = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder))
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
            cursor.extras?.getBoolean(DocumentsContract.EXTRA_LOADING) != true && cursor.count == 0
        } ?: false
    }.getOrDefault(false)

    private fun finish(removedFiles: Set<String>, removedFolders: Set<String>, touchedConsoles: Set<String>) {
        if (removedFiles.isEmpty() && removedFolders.isEmpty() && touchedConsoles.isEmpty()) return
        scanService.applyChanges(removedFiles, removedFolders, touchedConsoles)
        indexStale.tryEmit(Unit)
    }
}
