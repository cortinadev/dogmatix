package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.cortinadev.dogmatix.data.repository.DownloadRepository
import com.cortinadev.dogmatix.data.local.dao.StorageRedirectDao
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.ActionTypes
import com.cortinadev.dogmatix.util.DocumentIds
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.LibraryMoveRules
import com.cortinadev.dogmatix.util.MoveItem
import com.cortinadev.dogmatix.util.MoveJob
import com.cortinadev.dogmatix.util.MoveJobCodec
import com.cortinadev.dogmatix.util.MoveJobs
import com.cortinadev.dogmatix.util.MoveSettings
import com.cortinadev.dogmatix.util.MoveUndo
import com.cortinadev.dogmatix.util.StorageHelper
import com.cortinadev.dogmatix.util.TrashRules
import com.cortinadev.dogmatix.util.UndoKinds
import com.cortinadev.dogmatix.util.DownloadBulk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

enum class MovePhase { PREPARING, MOVING, FINISHING, DONE, INTERRUPTED, FAILED }

enum class MoveProblem { NO_ROOM, OVERLAP, CANNOT_OPEN, BUSY }

data class MoveProgress(
    val job: MoveJob,
    val phase: MovePhase,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val current: String = "",
    /** Same name, different size at the target: both kept where they are. */
    val conflicts: Int = 0,
    val failures: Int = 0,
    val problem: MoveProblem? = null,
    val needBytes: Long = 0,
    val freeBytes: Long = 0
) {
    val running: Boolean get() = phase == MovePhase.PREPARING || phase == MovePhase.MOVING || phase == MovePhase.FINISHING
}

/**
 * Moves library folders: the whole library root to a new folder, chosen consoles to another
 * place, or one console between the two roots of smart storage. Every file is copied under a
 * temporary name, checked by size and renamed before its original is deleted; on the same
 * volume the provider moves whole folders at once. Downloads are paused (and new ones wait)
 * while it runs, then resumed; the settings follow at the end, the library is re-indexed and
 * the move is recorded in the history, whose Undo moves everything back. The job is saved
 * first: after an interruption (cancel, a crash) it can be resumed and carries on.
 */
@Singleton
class LibraryMoveService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val router: StorageRouter,
    private val redirectDao: StorageRedirectDao,
    private val pathResolver: ConsoleDownloadPathResolver,
    private val downloadService: Provider<DownloadService>,
    private val downloads: Provider<DownloadRepository>,
    private val downloadQueue: Provider<DownloadQueueService>,
    private val libraryIndex: Provider<LibraryIndexService>,
    private val history: Provider<ActionHistoryService>
) : UndoHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobFile = File(context.filesDir, "collection_tools/move_job.json")
    private var running: Job? = null

    private val _state = MutableStateFlow<MoveProgress?>(null)
    /** The move running, the last one that ended, or an interrupted one waiting; null when idle. */
    val state: StateFlow<MoveProgress?> = _state.asStateFlow()

    init {
        scope.launch {
            readSavedJob()?.let { if (_state.value == null) _state.value = MoveProgress(it, MovePhase.INTERRUPTED) }
        }
    }

    val isBusy: Boolean get() = running?.isActive == true

    // ---- Planning ---------------------------------------------------------------------------

    /**
     * The whole library root (everything in it but the trash and hidden folders) into
     * [destination], a picked tree. Null when there is no root or [destination] overlaps it.
     */
    suspend fun planRoot(destination: String): MoveJob? = withContext(Dispatchers.IO) {
        val root = settings.downloadDirectory.first().ifBlank { return@withContext null }
        val rootDoc = SafDocs.docUri(root) ?: return@withContext null
        val destDoc = SafDocs.docUri(destination) ?: return@withContext null
        val rootId = SafDocs.docId(rootDoc)
        val destId = SafDocs.docId(destDoc)
        if (LibraryMoveRules.overlaps(rootId, destId)) return@withContext null
        val children = SafDocs.list(context, rootDoc) ?: return@withContext null
        val items = children.filter { !TrashRules.isHidden(it.name) && !LibraryMoveRules.isPart(it.name) }.map {
            MoveItem(it.uri.toString(), rootDoc.toString(), it.name, destDoc.toString(), it.name)
        }
        // Console folders picked inside the old root follow it.
        val custom = settings.consoleDownloadDirectories.first()
        val rebased = custom.mapNotNull { (id, uri) ->
            val docId = SafDocs.docUri(uri)?.let(SafDocs::docId) ?: return@mapNotNull null
            DocumentIds.rebase(docId, rootId, destId)?.let { id to SafDocs.sibling(destDoc, it).toString() }
        }.toMap()
        MoveJob(
            type = ActionTypes.LIBRARY_MOVE,
            subject = FileParsingUtils.toUserReadablePath(destination),
            items = items,
            after = MoveSettings(destination, rebased),
            before = MoveSettings(root, custom.filterKeys { it in rebased }),
            oldRoot = root
        )
    }

    /**
     * [consoleIds]' folders into [destination] (a tree, or a folder in one), each keeping its
     * folder name. Landing in the library root (where the console's folder would be found anyway)
     * drops the console's own folder setting; anywhere else it gets [destination]/<folder>.
     * Consoles without a folder on disk, or already there, are left out; null when none is left.
     */
    suspend fun planConsoles(consoleIds: Collection<String>, destination: String, type: String, subject: String): MoveJob? = withContext(Dispatchers.IO) {
        val main = settings.downloadDirectory.first().ifBlank { return@withContext null }
        val custom = settings.consoleDownloadDirectories.first()
        val destDoc = SafDocs.docUri(destination) ?: return@withContext null
        val destId = SafDocs.docId(destDoc)
        val mainDoc = SafDocs.docUri(main) ?: return@withContext null
        val toMain = SafDocs.docId(mainDoc) == destId
        val separate = settings.separateByConsole.first()
        val items = consoleIds.mapNotNull { id ->
            val folder = router.consoleFolder(id, main, custom[id]) ?: return@mapNotNull null
            // Without per-console subfolders the console has no folder of its own to move.
            if (custom[id] == null && folder.subPath.isEmpty()) return@mapNotNull null
            val source = folderDoc(folder) ?: return@mapNotNull null
            val sourceId = SafDocs.docId(source.uri)
            val sourceParent = when (custom[id]) {
                null -> mainDoc
                else -> DocumentIds.parentOf(sourceId)?.takeIf { DocumentIds.isWithin(SafDocs.treeId(source.uri), it) }
                    ?.let { SafDocs.sibling(source.uri, it) }
            }
            val name = if (toMain) {
                pathResolver.resolveAll(listOf(id), main, separate, emptyMap()).getValue(id).subPath.ifEmpty { folder.folderName }
            } else folder.folderName
            if (name.isEmpty() || MoveJobs.landsInside(sourceId, destId, name)) return@mapNotNull null
            MoveItem(source.uri.toString(), sourceParent?.toString(), source.name, destDoc.toString(), name, id, clearConsoleDir = toMain && separate)
        }
        if (items.isEmpty()) return@withContext null
        MoveJob(
            type = type, subject = subject, items = items,
            after = MoveSettings(),
            before = MoveSettings(consoleDirs = items.mapNotNull { it.consoleId }.associateWith { custom[it].orEmpty() })
        )
    }

    private fun folderDoc(folder: StorageRouter.ConsoleFolder): SafDocs.Doc? {
        val root = SafDocs.docUri(folder.rootUri) ?: return null
        val doc = if (folder.subPath.isEmpty()) SafDocs.stat(context, root) else SafDocs.child(context, root, folder.subPath)
        return doc?.takeIf { it.isDirectory }
    }

    // ---- Running ----------------------------------------------------------------------------

    /** Starts [job]; false when another move is running. */
    fun start(job: MoveJob): Boolean {
        if (isBusy) return false
        running = scope.launch { run(job) }
        return true
    }

    /** Carries on with the interrupted (or failed) move. */
    fun resume(): Boolean {
        val progress = _state.value?.takeIf { it.phase == MovePhase.INTERRUPTED || it.phase == MovePhase.FAILED } ?: return false
        return start(progress.job)
    }

    fun cancel() { running?.cancel() }

    /** Leaves an interrupted move as it is: what moved stays moved, the settings are not touched. */
    fun abandon() {
        if (isBusy) return
        val job = _state.value?.job
        _state.value = null
        scope.launch {
            jobFile.delete()
            job?.undoOf?.let { history.get().finishUndo(it, success = false) }
            resumeDownloads(job?.pausedDownloads.orEmpty())
        }
    }

    /** Clears the summary of a move that ended (or could not start). */
    fun dismiss() {
        val phase = _state.value?.phase
        if (phase == MovePhase.DONE || phase == MovePhase.FAILED && !jobFile.exists()) _state.value = null
    }

    private suspend fun run(initial: MoveJob) {
        var job = initial
        _state.value = MoveProgress(job, MovePhase.PREPARING)
        if (job.items.any { item -> ids(item)?.let { (s, t) -> MoveJobs.landsInside(s, t, item.targetName) } == true }) {
            _state.value = MoveProgress(job, MovePhase.FAILED, problem = MoveProblem.OVERLAP)
            return
        }
        if (!router.fileOps.tryLock()) {
            _state.value = MoveProgress(job, MovePhase.FAILED, problem = MoveProblem.BUSY)
            return
        }
        var keepJob = true
        try {
            job = job.copy(pausedDownloads = (job.pausedDownloads + pauseDownloads()).distinct())
            save(job)
            // Only now: a copy or extraction that was landing had to resolve its folder first.
            router.setMoving(true)

            // Count first: progress, and the room needed where files have to be copied.
            val sizes = job.items.map { measure(it.source.toUri()) }
            val copyBytes = job.items.indices.filter { !sameVolume(job.items[it].source, job.items[it].targetParent) }.sumOf { sizes[it].second }
            if (copyBytes > 0) {
                val free = job.items.firstOrNull { !sameVolume(it.source, it.targetParent) }?.let { StorageHelper.getFreeBytes(context, it.targetParent) }
                if (!LibraryMoveRules.fits(copyBytes, free)) {
                    keepJob = false
                    _state.value = MoveProgress(job, MovePhase.FAILED, problem = MoveProblem.NO_ROOM, needBytes = copyBytes, freeBytes = free ?: 0)
                    job.undoOf?.let { history.get().finishUndo(it, success = false) }
                    return
                }
            }
            val files = sizes.sumOf { it.first }
            val bytes = sizes.sumOf { it.second }
            _state.value = MoveProgress(job, MovePhase.MOVING, filesTotal = files, bytesTotal = bytes)

            job.items.forEachIndexed { index, item -> moveItem(item, sizes[index]) }

            _state.update { it?.copy(phase = MovePhase.FINISHING, current = "") }
            withContext(NonCancellable) {
                val applied = applySettings(job)
                keepJob = false
                val left = _state.value?.let { it.failures + it.conflicts } ?: 0
                val undoOf = job.undoOf
                if (undoOf != null) {
                    history.get().finishUndo(undoOf, success = left == 0)
                } else {
                    val back = MoveJobs.reverse(job, applied) { destinationOf(it)?.toString() }
                    history.get().record(job.type, job.subject, files, bytes, UndoKinds.MOVE, MoveJobCodec.encodeUndo(MoveUndo(back, applied)))
                }
                runCatching { libraryIndex.get().refresh() }
                _state.update { it?.copy(phase = MovePhase.DONE) }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { _state.update { it?.copy(phase = MovePhase.INTERRUPTED) } }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Move failed", e)
            _state.update { it?.copy(phase = MovePhase.FAILED, problem = it.problem ?: MoveProblem.CANNOT_OPEN) }
        } finally {
            withContext(NonCancellable) {
                if (!keepJob) jobFile.delete()
                router.setMoving(false)
                router.fileOps.unlock()
                resumeDownloads(job.pausedDownloads)
            }
        }
    }

    private fun ids(item: MoveItem): Pair<String, String>? {
        val s = SafDocs.docUri(item.source)?.let { runCatching { SafDocs.docId(it) }.getOrNull() } ?: return null
        val t = SafDocs.docUri(item.targetParent)?.let { runCatching { SafDocs.docId(it) }.getOrNull() } ?: return null
        return s to t
    }

    private fun sameVolume(a: String, b: String): Boolean {
        val ua = SafDocs.docUri(a) ?: return false
        val ub = SafDocs.docUri(b) ?: return false
        val volume = DocumentIds.volumeOf(SafDocs.docId(ua)) ?: return false
        return ua.authority == ub.authority && volume == DocumentIds.volumeOf(SafDocs.docId(ub))
    }

    /** Files and bytes below [source] (or of the file itself). */
    private fun measure(source: Uri): Pair<Int, Long> {
        val doc = SafDocs.stat(context, source) ?: return 0 to 0L
        if (!doc.isDirectory) return 1 to doc.size.coerceAtLeast(0)
        var files = 0
        var bytes = 0L
        SafDocs.list(context, source).orEmpty().forEach { child ->
            val (f, b) = measure(child.uri)
            files += f
            bytes += b
        }
        return files to bytes
    }

    private suspend fun moveItem(item: MoveItem, size: Pair<Int, Long>) {
        val doc = SafDocs.stat(context, item.source.toUri()) ?: return   // moved already (a rerun)
        val sourceParent = item.sourceParent?.toUri()
        val targetParent = item.targetParent.toUri()
        if (!doc.isDirectory) {
            moveFile(doc, sourceParent, targetParent)
            return
        }
        if (item.targetName.isEmpty()) {
            mergeInto(doc, sourceParent, targetParent)
            return
        }
        val existing = SafDocs.child(context, targetParent, item.targetName)
        // A folder not there yet moves whole, renamed if its name changes (same volume: instant).
        if (existing == null && sourceParent != null) {
            val moved = SafDocs.move(context, doc.uri, sourceParent, targetParent)
            if (moved != null) {
                if (doc.name != item.targetName) SafDocs.rename(context, moved, item.targetName)
                advance(size.first, size.second, doc.name)
                return
            }
        }
        val target = existing?.takeIf { it.isDirectory }?.uri ?: SafDocs.createDir(context, targetParent, item.targetName)
            ?: throw IllegalStateException("Cannot create ${item.targetName}")
        mergeInto(doc, sourceParent, target)
    }

    /** Moves everything in the folder [source] into the folder [target], then removes [source] if it was emptied. */
    private suspend fun mergeInto(source: SafDocs.Doc, sourceParent: Uri?, target: Uri) {
        for (child in SafDocs.list(context, source.uri).orEmpty()) {
            if (child.isDirectory) {
                if (TrashRules.isHidden(child.name)) continue
                val existing = SafDocs.child(context, target, child.name)
                if (existing == null && SafDocs.move(context, child.uri, source.uri, target) != null) {
                    val (f, b) = measure(SafDocs.child(context, target, child.name)?.uri ?: continue)
                    advance(f, b, child.name)
                    continue
                }
                val into = existing?.takeIf { it.isDirectory }?.uri ?: SafDocs.createDir(context, target, child.name)
                    ?: throw IllegalStateException("Cannot create ${child.name}")
                mergeInto(child, source.uri, into)
            } else {
                moveFile(child, source.uri, target)
            }
        }
        // The emptied folder goes, unless it is the root of a grant (a console folder picked in Sources).
        if (sourceParent != null && SafDocs.list(context, source.uri)?.isEmpty() == true) SafDocs.delete(context, source.uri)
    }

    private suspend fun moveFile(doc: SafDocs.Doc, sourceParent: Uri?, target: Uri) {
        if (LibraryMoveRules.isPart(doc.name)) {
            SafDocs.delete(context, doc.uri)
            return
        }
        _state.update { it?.copy(current = doc.name) }
        val existing = SafDocs.child(context, target, doc.name)
        if (existing != null) {
            // Never onto itself (a folder moved into its own place).
            if (SafDocs.docId(existing.uri) == SafDocs.docId(doc.uri)) return
            when (LibraryMoveRules.existing(doc.size, existing.size.takeIf { !existing.isDirectory })) {
                // Copied by an earlier run (or the same file already there): the original can go.
                LibraryMoveRules.Existing.SAME -> SafDocs.delete(context, doc.uri)
                LibraryMoveRules.Existing.CONFLICT -> _state.update { it?.copy(conflicts = it.conflicts + 1) }
            }
            advance(1, doc.size.coerceAtLeast(0), doc.name)
            return
        }
        if (sourceParent != null && SafDocs.move(context, doc.uri, sourceParent, target) != null) {
            advance(1, doc.size.coerceAtLeast(0), doc.name)
            return
        }
        // Copy under a temporary name, check the size, rename, and only then delete the original.
        SafDocs.child(context, target, LibraryMoveRules.partName(doc.name))?.let { SafDocs.delete(context, it.uri) }
        val part = SafDocs.createFile(context, target, LibraryMoveRules.partName(doc.name)) ?: throw IllegalStateException("Cannot write to target")
        var copied = 0L
        try {
            SafDocs.copyInto(context, doc.uri, part) { n ->
                copied += n
                _state.update { it?.copy(bytesDone = it.bytesDone + n) }
            }
        } catch (e: Exception) {
            withContext(NonCancellable) { SafDocs.delete(context, part) }
            _state.update { it?.copy(bytesDone = it.bytesDone - copied) }
            if (e is CancellationException) throw e
            _state.update { it?.copy(failures = it.failures + 1) }
            return
        }
        val size = SafDocs.stat(context, part)?.size
        val renamed = if (LibraryMoveRules.mayDeleteSource(doc.size, size)) SafDocs.rename(context, part, doc.name) else null
        if (renamed == null) {
            SafDocs.delete(context, part)
            _state.update { it?.copy(failures = it.failures + 1) }
            return
        }
        SafDocs.delete(context, doc.uri)
        _state.update { it?.copy(filesDone = it.filesDone + 1) }
    }

    private fun advance(files: Int, bytes: Long, name: String) {
        _state.update { it?.copy(filesDone = it.filesDone + files, bytesDone = it.bytesDone + bytes, current = name) }
    }

    /** Switches the settings to where things are now; returns what was applied (the move back expects it). */
    private suspend fun applySettings(job: MoveJob): MoveSettings {
        val consoleDirs = LinkedHashMap(job.after.consoleDirs)
        for (item in job.items) {
            val id = item.consoleId ?: continue
            if (id in consoleDirs) continue
            consoleDirs[id] = if (item.clearConsoleDir) "" else destinationOf(item)?.toString().orEmpty()
        }
        job.after.downloadDirectory?.let { settings.updateDownloadDirectory(it) }
        consoleDirs.forEach { (id, uri) -> settings.updateConsoleDownloadDirectory(id, uri) }
        // Downloads that had gone to the other volume: inside a moved console folder they are home now.
        val movedConsoles = job.items.mapNotNull { it.consoleId }
        if (movedConsoles.isNotEmpty()) redirectDao.deleteForConsoles(movedConsoles)
        val oldRoot = job.oldRoot
        val newRoot = job.after.downloadDirectory
        if (oldRoot != null && newRoot != null) redirectDao.rebaseRoot(oldRoot, newRoot)
        return MoveSettings(job.after.downloadDirectory, consoleDirs)
    }

    private fun destinationOf(item: MoveItem): Uri? {
        val parent = item.targetParent.toUri()
        if (item.targetName.isEmpty()) return parent
        return SafDocs.child(context, parent, item.targetName)?.uri
    }

    // ---- Downloads ----------------------------------------------------------------------------

    /** Pauses what is downloading and waits (two minutes at most) for copies and extractions to land. */
    /**
     * "Pause all" of the download queue (rows waiting for their turn included; held rows keep
     * their hold and do not start). Returns what it paused, so only that is resumed afterwards.
     */
    private suspend fun pauseDownloads(): List<String> {
        val service = downloadService.get()
        val repository = downloads.get()
        val active = DownloadBulk.pausable(repository.getDownloads(), repository.queue.value.held)
        repository.pauseAll()
        withTimeoutOrNull(120_000) {
            while (service.downloads.value.any { it.status == DownloadStatus.COPYING || it.status == DownloadStatus.UNZIPPING }) delay(500)
        }
        return active
    }

    /**
     * Like "Resume all", but only for what [pauseDownloads] paused (a download the user had
     * paused stays paused); they go back to their places in line and start by that order.
     */
    private fun resumeDownloads(fileNames: List<String>) {
        if (fileNames.isEmpty()) return
        val service = downloadService.get()
        val parked = service.downloads.value
            .filter { it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.STOPPED }
            .map { it.fileName }.toSet()
        downloadQueue.get().batch { fileNames.filter { it in parked }.forEach { service.retryDownload(it) } }
    }

    // ---- Persistence ----------------------------------------------------------------------------

    private fun save(job: MoveJob) {
        jobFile.parentFile?.mkdirs()
        jobFile.writeText(MoveJobCodec.encodeJob(job))
    }

    private fun readSavedJob(): MoveJob? = runCatching { if (jobFile.exists()) MoveJobCodec.decodeJob(jobFile.readText()) else null }.getOrNull()

    // ---- UndoHandler (UndoKinds.MOVE) -----------------------------------------------------------

    override suspend fun isAvailable(data: String): Boolean {
        if (isBusy || _state.value?.phase == MovePhase.INTERRUPTED) return false
        val undo = MoveJobCodec.decodeUndo(data) ?: return false
        undo.expect.downloadDirectory?.let { if (settings.downloadDirectory.first() != it) return false }
        val custom = settings.consoleDownloadDirectories.first()
        return undo.expect.consoleDirs.all { (id, uri) -> custom[id].orEmpty() == uri }
    }

    override suspend fun undo(entryId: Long, data: String): UndoOutcome {
        val undo = MoveJobCodec.decodeUndo(data) ?: return UndoOutcome.FAILED
        return if (start(undo.job.copy(id = UUID.randomUUID().toString(), undoOf = entryId))) UndoOutcome.STARTED else UndoOutcome.FAILED
    }

    private companion object {
        const val TAG = "LibraryMoveService"
    }
}
