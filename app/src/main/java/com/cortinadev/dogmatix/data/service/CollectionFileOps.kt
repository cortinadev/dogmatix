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
import com.cortinadev.dogmatix.util.CollectionGames
import com.cortinadev.dogmatix.util.DiscPlaylist
import com.cortinadev.dogmatix.util.DiscSetCleanup
import com.cortinadev.dogmatix.util.DiskFile
import com.cortinadev.dogmatix.util.DiskFolder
import com.cortinadev.dogmatix.util.MultiDisc
import com.cortinadev.dogmatix.util.StorageHelper
import com.cortinadev.dogmatix.util.StorageProblem
import com.cortinadev.dogmatix.util.StorageProblems
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

    /**
     * How an operation went: items done, items that failed, bytes freed, and why some failed when
     * that can be told ([problem]: a folder lost, a card out, a full volume; [unverified]: copies
     * that did not match their original, which stayed).
     */
    data class Result(val done: Int, val failed: Int, val bytes: Long = 0, val problem: StorageProblem? = null, val unverified: Int = 0) {
        operator fun plus(other: Result) = Result(
            done + other.done, failed + other.failed, bytes + other.bytes,
            StorageProblems.worst(listOf(problem, other.problem)), unverified + other.unverified
        )
    }

    /**
     * Moves [files] to the trash as one history entry of [type] ([ActionTypes.DUPLICATES] or
     * [ActionTypes.REPAIR]) about [subject]; a file the trash cannot
     * take is deleted as before (the user confirmed the deletion). Then each folder of
     * [setFolders] (a game's `<game>.m3u/`) goes once a complete listing says it is empty,
     * because providers delete folders with whatever is left inside. [files] are deleted as given:
     * what a sheet or a set takes along ([cleanupPlan]) is worked out before the confirmation, so
     * it shows it, and its playlists to rewrite come in [rewrites]. Files whose folder cannot be
     * reached are left alone and reported ([Result.problem]).
     */
    suspend fun delete(
        files: List<DiskFile>,
        setFolders: List<String> = emptyList(),
        type: String = ActionTypes.REPAIR,
        subject: String = files.firstOrNull()?.let { MultiDisc.stripExtension(it.name) }.orEmpty(),
        rewrites: List<DiscSetCleanup.Rewrite> = emptyList()
    ): Result = withContext(NonCancellable + Dispatchers.IO) {
        val (targets, blocked) = splitReachable(files)
        val removed = HashSet<String>()
        var bytes = 0L
        val trashed = runCatching {
            history.trash(targets.map { TrashRequest(it.uri.toUri(), it.parentUri.toUri()) }, type, subject, refreshLibrary = false)
        }.onFailure { Log.w(TAG, "Trash unavailable: ${it.message}") }.getOrNull()
        val notTrashed = trashed?.failed?.mapTo(HashSet()) { it.uri.toString() } ?: targets.mapTo(HashSet()) { it.uri }
        val problems = HashMap<String, StorageProblem?>()
        for (file in targets) {
            if (file.uri !in notTrashed || deleteDocument(file.uri.toUri(), problems)) {
                removed += file.uri
                bytes += file.size
            }
        }
        val gone = vanished(targets, removed)
        val (writable, unreachable) = reachableRewrites(rewrites, blocked)
        val rewritten = rewritePlaylists(writable)
        val folders = (setFolders + setFoldersOf(targets)).distinct().filter { deleteIfEmpty(it.toUri()) }.toSet()
        finish(removed + gone, folders, rewritten, unreachable)
        Result(removed.size, targets.size - removed.size + blocked.files.size, bytes, StorageProblems.worst(problems.filterKeys { it !in gone }.values + blocked.problem))
    }

    /**
     * Free up space: deletes [files] for good, without the trash (in the same volume the trash is
     * a rename, so nothing would be freed until it is emptied), then each empty folder of
     * [setFolders]. Nothing to undo: [recordFreed] notes it in the history. As in [delete], [files]
     * already hold what they take along and [rewrites] the playlists to rewrite.
     */
    suspend fun deleteForGood(files: List<DiskFile>, setFolders: List<String> = emptyList(), rewrites: List<DiscSetCleanup.Rewrite> = emptyList()): Result =
        withContext(NonCancellable + Dispatchers.IO) {
            val (targets, blocked) = splitReachable(files)
            val problems = HashMap<String, StorageProblem?>()
            val removed = targets.filter { deleteDocument(it.uri.toUri(), problems) }
            val removedUris = removed.mapTo(HashSet()) { it.uri }
            val gone = vanished(targets, removedUris)
            val (writable, unreachable) = reachableRewrites(rewrites, blocked)
            val rewritten = rewritePlaylists(writable)
            val folders = (setFolders + setFoldersOf(targets)).distinct().filter { deleteIfEmpty(it.toUri()) }.toSet()
            finish(removedUris + gone, folders, rewritten, unreachable)
            Result(
                removed.size, targets.size - removed.size + blocked.files.size, removed.sumOf { it.size },
                StorageProblems.worst(problems.filterKeys { it !in gone }.values + blocked.problem)
            )
        }

    /**
     * What deleting [files] takes along over the current analysis ([DiscSetCleanup.plan]): asked
     * for when the confirmation opens, so it can say so, and handed back to [delete] / [deleteForGood].
     */
    fun cleanupPlan(files: List<DiskFile>): DiscSetCleanup.Plan = DiscSetCleanup.plan(files, scanService.snapshot.value?.files.orEmpty())

    /** A Free up space run in the history, as an entry that cannot be undone. */
    suspend fun recordFreed(subject: String, items: Int, bytes: Long) {
        if (items > 0) runCatching { history.record(ActionTypes.FREE_SPACE, subject, items, bytes) }
    }

    /** Deletes folders the scan saw empty, after checking again that they still are. */
    suspend fun deleteEmptyFolders(folders: List<DiskFolder>): Result = withContext(NonCancellable + Dispatchers.IO) {
        val removed = folders.filter { deleteIfEmpty(it.uri.toUri()) }.mapTo(HashSet()) { it.uri }
        val problems = folders.filter { it.uri !in removed }.map { it.rootUri }.distinct().associateWith { SafDocs.problemOf(context, it) }
        finish(emptySet(), removed, emptySet(), problems.filterValues { it != null }.keys)
        Result(removed.size, folders.size - removed.size, problem = StorageProblems.worst(problems.values))
    }

    /**
     * Moves [files] into [consoleId]'s download folder (its own directory, or its folder in the
     * download directory, created when missing). A file of the same name already there is left
     * where it was and counts as failed: nothing is ever overwritten. Copies (between volumes)
     * are checked before the original goes.
     */
    suspend fun moveToConsole(files: List<DiskFile>, consoleId: String): Result = withContext(NonCancellable + Dispatchers.IO) {
        val (reachable, blocked) = splitReachable(files)
        val target = consoleFolder(consoleId)
            ?: return@withContext Result(0, files.size, problem = consoleFolderProblem(consoleId) ?: blocked.problem).also { forget(blocked.roots) }
        SafDocs.problemOf(context, target.uri.toString())?.let { problem ->
            forget(blocked.roots)
            return@withContext Result(0, files.size, problem = problem)
        }
        val moved = HashSet<String>()
        val problems = ArrayList<StorageProblem?>()
        var unverified = 0
        for (file in reachable) {
            if (file.parentUri == target.uri.toString()) continue
            if (SafDocs.child(context, target.uri, file.name) != null) continue
            val placed = move(file, target.uri)
            when (placed.placement) {
                SafDocs.Placement.MOVED -> moved += file.uri
                SafDocs.Placement.UNVERIFIED -> unverified++
                else -> problems += placed.problem
            }
        }
        // In the history as a repair (no undo: the file is in its right folder now).
        if (moved.isNotEmpty()) {
            val first = files.first { it.uri in moved }
            runCatching { history.record(ActionTypes.REPAIR, MultiDisc.stripExtension(first.name), moved.size, files.filter { it.uri in moved }.sumOf { it.size }) }
        }
        finish(moved, emptySet(), files.mapNotNullTo(HashSet()) { it.consoleId } + consoleId, blocked.roots)
        Result(moved.size, files.size - moved.size, problem = StorageProblems.worst(problems + blocked.problem), unverified = unverified)
    }

    /**
     * Writes a multi-disc playlist the ES-DE way: [moves] go into `<game>.m3u/` inside
     * [parentUri] ([setFolderUri] when it already exists) and the playlist is written in it. A disc
     * already in the folder under the same name is never overwritten: the same file (size and
     * sampled bytes, what an interrupted run leaves) stands for the loose one, which goes; anything
     * else stays where it is and the set counts as failed. One item: the set.
     */
    suspend fun createPlaylist(parentUri: String, setFolderUri: String?, playlist: DiscPlaylist, moves: List<DiskFile>, consoleId: String?): Result =
        withContext(NonCancellable + Dispatchers.IO) {
            SafDocs.problemOf(context, parentUri)?.let { return@withContext Result(0, 1, problem = it) }
            val folder = setFolderUri?.let { DocumentFile.fromTreeUri(context, it.toUri()) }
                ?: DocumentFile.fromTreeUri(context, parentUri.toUri())?.let { parent ->
                    runCatching { parent.findFile(playlist.name) }.getOrNull()?.takeIf { it.isDirectory } ?: parent.createDirectory(playlist.name)
                }
                ?: return@withContext Result(0, 1, problem = SafDocs.problemOf(context, parentUri))
            val problems = ArrayList<StorageProblem?>()
            var unverified = 0
            val moved = HashSet<String>()
            val placed = moves.count { file ->
                val result = move(file, folder.uri)
                when (result.placement) {
                    SafDocs.Placement.MOVED -> { moved += file.uri; true }
                    SafDocs.Placement.ALREADY_THERE -> { moved += file.uri; true }
                    SafDocs.Placement.UNVERIFIED -> { unverified++; false }
                    else -> { problems += result.problem; false }
                }
            }
            // Only the discs that really are in the folder: one left outside is never a broken entry.
            val inFolder = playlist.inFolder(SafDocs.list(context, folder.uri)?.map { it.name }.orEmpty())
            val written = inFolder.entries.isNotEmpty() &&
                runCatching { StorageHelper.writeTextSafely(context, folder, "", inFolder.name, inFolder.content) }
                    .onFailure { Log.w(TAG, "Could not write ${playlist.name}: ${it.message}"); problems += StorageProblems.classify(it) }
                    .isSuccess
            finish(moved, emptySet(), setOfNotNull(consoleId))
            val ok = written && placed == moves.size
            Result(if (ok) 1 else 0, if (ok) 0 else 1, problem = StorageProblems.worst(problems), unverified = unverified)
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

    /** Within the provider when it can; otherwise copied, checked and only then is the original deleted ([SafDocs.moveFile]). */
    private suspend fun move(file: DiskFile, target: Uri): SafDocs.Placed =
        SafDocs.moveFile(context, SafDocs.Doc(file.uri.toUri(), file.name, false, file.size), file.parentUri.toUri(), target)

    /** [problems]: why a file could not be deleted, by its URI. */
    private fun deleteDocument(uri: Uri, problems: MutableMap<String, StorageProblem?>? = null): Boolean =
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            .onFailure { Log.w(TAG, "Cannot delete $uri: ${it.message}"); problems?.put(uri.toString(), StorageProblems.classify(it)) }
            .getOrDefault(false)

    /**
     * Of [targets] (in trees that can be used) the ones that could not be deleted because they are
     * no longer there (deleted or moved outside the app since the analysis): they leave the
     * analysis, and what their deletion threw (a provider's "is child of" for a missing document)
     * says nothing about the storage.
     */
    private fun vanished(targets: List<DiskFile>, removed: Set<String>): Set<String> =
        targets.filter { it.uri !in removed && SafDocs.stat(context, it.uri.toUri()) == null }.mapTo(HashSet()) { it.uri }

    /** Files left alone because their folder cannot be reached right now, and why; [roots]: the trees that could not be used. */
    private data class Blocked(val files: List<DiskFile>, val problem: StorageProblem?, val roots: Set<String>)

    /**
     * Splits [files] by whether the tree each was found through can be used now (asked once per
     * tree): an SD card that is out, or a folder whose grant is gone, is reported once instead of
     * failing file by file.
     */
    private fun splitReachable(files: List<DiskFile>): Pair<List<DiskFile>, Blocked> {
        val problems = files.map { it.rootUri }.distinct().associateWith { SafDocs.problemOf(context, it) }
        val (ok, blocked) = files.partition { problems[it.rootUri] == null }
        return ok to Blocked(blocked, StorageProblems.worst(blocked.map { problems[it.rootUri] }), problems.filterValues { it != null }.keys)
    }

    /** Why [consoleId]'s folder could not be had: its own directory or the download directory. */
    private suspend fun consoleFolderProblem(consoleId: String): StorageProblem? {
        val custom = settingsRepository.consoleDownloadDirectories.first()[consoleId]?.takeIf { it.isNotBlank() }
        return SafDocs.problemOf(context, custom ?: settingsRepository.downloadDirectory.first())
    }

    /** `<game>.m3u/` folders that held some of [files]: they go too once nothing is left in them. */
    private fun setFoldersOf(files: List<DiskFile>): List<String> =
        files.filter { it.depth >= 1 && CollectionGames.isSetFolder(it.folderPath.substringAfterLast('/')) }.map { it.parentUri }.distinct()

    /**
     * The rewrites whose playlist sits in a tree that can be reached (the others are left as they
     * are), and every tree found unusable on the way ([blocked]'s included).
     */
    private fun reachableRewrites(rewrites: List<DiscSetCleanup.Rewrite>, blocked: Blocked): Pair<List<DiscSetCleanup.Rewrite>, Set<String>> {
        val unusable = HashSet(blocked.roots)
        val checked = rewrites.map { it.playlist.rootUri }.distinct().filter { it !in unusable }
            .associateWith { SafDocs.problemOf(context, it) }
        unusable += checked.filterValues { it != null }.keys
        return rewrites.filter { it.playlist.rootUri !in unusable } to unusable
    }

    /** Writes the playlists of sets that lost a disc; returns the consoles touched (their analysis is refreshed). */
    private fun rewritePlaylists(rewrites: List<DiscSetCleanup.Rewrite>): Set<String> {
        val touched = HashSet<String>()
        for (rewrite in rewrites) {
            val file = rewrite.playlist
            val folder = DocumentFile.fromTreeUri(context, file.parentUri.toUri()) ?: continue
            runCatching { StorageHelper.writeTextSafely(context, folder, "", file.name, rewrite.content) }
                .onSuccess { file.consoleId?.let(touched::add); Log.i(TAG, "Rewrote ${file.name} with ${rewrite.entries.size} disc(s)") }
                .onFailure { Log.w(TAG, "Could not rewrite ${file.name}: ${it.message}") }
        }
        return touched
    }

    /** Providers delete a folder with everything in it: only one a complete listing shows empty goes. */
    private fun deleteIfEmpty(folder: Uri): Boolean = isEmpty(folder) && deleteDocument(folder)

    /** True only when the provider answered, has finished loading and listed nothing. */
    private fun isEmpty(folder: Uri): Boolean = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder))
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
            cursor.extras?.getBoolean(DocumentsContract.EXTRA_LOADING) != true && cursor.count == 0
        } ?: false
    }.getOrDefault(false)

    /**
     * Tells the analysis what changed. [unreachable]: trees an operation could not use; what the
     * analysis found through them stops being offered ([LibraryScanService.forgetUnreachable]).
     */
    private fun finish(removedFiles: Set<String>, removedFolders: Set<String>, touchedConsoles: Set<String>, unreachable: Set<String> = emptySet()) {
        forget(unreachable)
        if (removedFiles.isEmpty() && removedFolders.isEmpty() && touchedConsoles.isEmpty()) return
        scanService.applyChanges(removedFiles, removedFolders, touchedConsoles)
        indexStale.tryEmit(Unit)
    }

    private fun forget(unreachable: Set<String>) {
        if (unreachable.isNotEmpty()) scanService.forgetUnreachable(unreachable)
    }
}
