package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.cortinadev.dogmatix.data.local.dao.TrashDao
import com.cortinadev.dogmatix.data.local.dao.TrashTotals
import com.cortinadev.dogmatix.data.local.entity.TrashItemEntity
import com.cortinadev.dogmatix.util.DocumentIds
import com.cortinadev.dogmatix.util.LibraryMoveRules
import com.cortinadev.dogmatix.util.TrashRules
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A file or folder to put in the trash; [parentUri] (its folder) lets the provider move it instead of copying. */
data class TrashRequest(val uri: Uri, val parentUri: Uri? = null)

/** What [TrashService.moveToTrash] did: [failed] were left where they were. */
data class TrashResult(val batchId: String, val moved: Int, val bytes: Long, val failed: List<TrashRequest>)

/**
 * The trash: deleting moves things to `.dogmatix-trash/<batch>/…` at the root of their own SAF
 * tree (same volume: the provider renames, no copy, no extra space), so undoing is moving them
 * back. Folders the provider cannot move between are copied, checked by size and then deleted.
 * The trash folder carries `.nomedia` and `noload.txt` so galleries and ES-DE leave it alone,
 * and the library walks skip it (dot name). Batches are emptied by age or by hand
 * ([ActionHistoryService] decides when, and keeps the history in step).
 */
@Singleton
class TrashService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: TrashDao,
    private val router: StorageRouter
) : UndoHandler {

    val totals: Flow<TrashTotals> = dao.observeTotals()

    /** Trees whose trash folder already has its markers (this process). */
    private val prepared = HashSet<String>()

    suspend fun moveToTrash(requests: List<TrashRequest>): TrashResult = withContext(Dispatchers.IO) {
        router.fileOps.withLock {
            val batchId = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}"
            val now = System.currentTimeMillis()
            val failed = ArrayList<TrashRequest>()
            var moved = 0
            var bytes = 0L
            requests.distinctBy { it.uri }.forEachIndexed { index, request ->
                val item = runCatching { trashOne(request, batchId, index, now) }
                    .onFailure { Log.w(TAG, "Could not trash ${request.uri}: ${it.message}") }
                    .getOrNull()
                if (item == null) failed += request else {
                    moved++
                    bytes += item.sizeBytes
                }
            }
            TrashResult(batchId, moved, bytes, failed)
        }
    }

    private suspend fun trashOne(request: TrashRequest, batchId: String, index: Int, now: Long): TrashItemEntity? {
        val doc = SafDocs.stat(context, request.uri) ?: return null
        val rootId = SafDocs.treeId(request.uri)
        val docId = SafDocs.docId(request.uri)
        val relative = DocumentIds.relativePath(rootId, docId).orEmpty()
        // Never the tree itself, never what is already in the trash.
        if (docId == rootId || relative.substringBefore('/') == TrashRules.FOLDER) return null
        val treeRoot = SafDocs.treeRootOf(request.uri)
        ensureTrashFolder(treeRoot) ?: return null
        val slot = SafDocs.ensureDirs(context, treeRoot, TrashRules.slotPath(batchId, relative, index)) ?: return null
        val parent = request.parentUri
            ?: DocumentIds.parentOf(docId)?.takeIf { DocumentIds.isWithin(rootId, it) }?.let { SafDocs.sibling(request.uri, it) }
        val size = if (doc.isDirectory) SafDocs.folderSize(context, doc.uri) else doc.size.coerceAtLeast(0)
        val moved = parent?.let { SafDocs.move(context, doc.uri, it, slot) } ?: copyThenDelete(doc, slot) ?: return null
        // Files get a suffix while they wait (a provider that cannot rename leaves the name as it was).
        val trashed = if (doc.isDirectory) moved else SafDocs.rename(context, moved, TrashRules.trashedName(doc.name)) ?: moved
        val item = TrashItemEntity(
            batchId = batchId,
            treeUri = SafDocs.treeUriOf(request.uri).toString(),
            originalParentUri = parent?.toString().orEmpty(),
            relativePath = relative,
            name = doc.name,
            trashedUri = trashed.toString(),
            sizeBytes = size,
            isDirectory = doc.isDirectory,
            trashedAt = now,
            state = TrashItemEntity.IN_TRASH
        )
        return item.copy(id = dao.insert(item))
    }

    /** `.dogmatix-trash` at [treeRoot], with its markers; null when it cannot be made. */
    private fun ensureTrashFolder(treeRoot: Uri): Uri? {
        val folder = SafDocs.ensureDirs(context, treeRoot, TrashRules.FOLDER) ?: return null
        if (prepared.add(treeRoot.toString())) {
            val present = SafDocs.list(context, folder)?.map { it.name }.orEmpty()
            TrashRules.MARKERS.filter { it !in present }.forEach { SafDocs.createFile(context, folder, it) }
        }
        return folder
    }

    /**
     * Brings batch [batchId] back where it came from (recreating its folder if it is gone). An
     * item whose name is taken there again stays in the trash. [UndoOutcome.PARTIAL] when only
     * some came back.
     */
    suspend fun restore(batchId: String): UndoOutcome = withContext(Dispatchers.IO) {
        router.fileOps.withLock {
            val items = dao.batch(batchId).filter { it.state == TrashItemEntity.IN_TRASH }
            if (items.isEmpty()) return@withLock UndoOutcome.FAILED
            var restored = 0
            for (item in items) {
                if (runCatching { restoreOne(item) }.getOrDefault(false)) {
                    dao.setState(item.id, TrashItemEntity.RESTORED)
                    restored++
                }
            }
            if (dao.countInTrash(batchId) == 0) removeBatchFolders(batchId, items)
            when (restored) {
                items.size -> UndoOutcome.DONE
                0 -> UndoOutcome.FAILED
                else -> UndoOutcome.PARTIAL
            }
        }
    }

    private suspend fun restoreOne(item: TrashItemEntity): Boolean {
        val trashed = item.trashedUri.toUri()
        val doc = SafDocs.stat(context, trashed) ?: return false
        val treeRoot = SafDocs.treeRootOf(trashed)
        val target = item.originalParentUri.takeIf { it.isNotEmpty() }?.toUri()
            ?.takeIf { SafDocs.stat(context, it)?.isDirectory == true }
            ?: SafDocs.ensureDirs(context, treeRoot, item.relativePath.substringBeforeLast('/', ""))
            ?: return false
        if (SafDocs.child(context, target, item.name) != null) return false
        val slot = DocumentIds.parentOf(SafDocs.docId(trashed))?.let { SafDocs.sibling(trashed, it) }
        val back = slot?.let { SafDocs.move(context, trashed, it, target) } ?: copyThenDelete(doc, target) ?: return false
        if (doc.name != item.name && SafDocs.rename(context, back, item.name) == null) {
            Log.w(TAG, "Restored ${item.name} but could not drop the trash suffix")
        }
        return true
    }

    /** Deletes batches for good; returns the ones emptied. */
    suspend fun purge(batchIds: Collection<String>): List<String> = withContext(Dispatchers.IO) {
        router.fileOps.withLock {
            batchIds.filter { batchId ->
                val items = dao.batch(batchId).filter { it.state == TrashItemEntity.IN_TRASH }
                items.forEach { item ->
                    val uri = item.trashedUri.toUri()
                    // Already gone (deleted by hand) counts as purged.
                    if (SafDocs.stat(context, uri) == null || SafDocs.delete(context, uri)) dao.setState(item.id, TrashItemEntity.PURGED)
                }
                removeBatchFolders(batchId, items)
                dao.countInTrash(batchId) == 0
            }
        }
    }

    suspend fun purgeTrashedBefore(before: Long): List<String> = purge(dao.batchesTrashedBefore(before))

    /**
     * "Empty trash": every batch, and whatever else sits in the trash folders of the known trees
     * ([roots] plus every tree something was ever trashed in), markers excepted.
     */
    suspend fun emptyAll(roots: Collection<String>): List<String> {
        val purged = purge(dao.batchesInTrash())
        withContext(Dispatchers.IO) {
            router.fileOps.withLock {
                (roots + dao.trees()).filter { it.isNotBlank() }.mapNotNull { SafDocs.docUri(it) }
                    .map { SafDocs.treeRootOf(it) }.distinct().forEach { root ->
                        val folder = SafDocs.child(context, root, TrashRules.FOLDER)?.takeIf { it.isDirectory } ?: return@forEach
                        SafDocs.list(context, folder.uri).orEmpty()
                            .filter { it.name !in TrashRules.MARKERS }
                            .forEach { SafDocs.delete(context, it.uri) }
                    }
            }
        }
        return purged
    }

    /** History rows of finished batches are no longer needed once their entry is pruned. */
    suspend fun forget(batchIds: List<String>) {
        if (batchIds.isNotEmpty()) dao.forgetFinished(batchIds)
    }

    private fun removeBatchFolders(batchId: String, items: List<TrashItemEntity>) {
        items.map { it.trashedUri.toUri() }.map { SafDocs.treeRootOf(it) }.distinctBy { it.toString() }.forEach { root ->
            val trash = SafDocs.child(context, root, TrashRules.FOLDER) ?: return@forEach
            SafDocs.child(context, trash.uri, batchId)?.let { SafDocs.delete(context, it.uri) }
        }
    }

    /**
     * For providers that cannot move: copies [doc] (a file, or a folder with everything in it)
     * into [targetDir], checks every file's size, then deletes the original. Nothing is deleted
     * when the copy is incomplete. Returns the copy.
     */
    private suspend fun copyThenDelete(doc: SafDocs.Doc, targetDir: Uri): Uri? {
        val copy = copyTree(doc, targetDir) ?: return null
        if (!SafDocs.delete(context, doc.uri)) {
            SafDocs.delete(context, copy)
            return null
        }
        return copy
    }

    private suspend fun copyTree(doc: SafDocs.Doc, targetDir: Uri): Uri? {
        if (doc.isDirectory) {
            val dir = SafDocs.createDir(context, targetDir, doc.name) ?: return null
            val children = SafDocs.list(context, doc.uri) ?: run { SafDocs.delete(context, dir); return null }
            for (child in children) {
                if (copyTree(child, dir) == null) {
                    SafDocs.delete(context, dir)
                    return null
                }
            }
            return dir
        }
        val target = SafDocs.createFile(context, targetDir, doc.name) ?: return null
        val written = runCatching { SafDocs.copyInto(context, doc.uri, target) }.getOrNull()
        if (!LibraryMoveRules.mayDeleteSource(doc.size, written)) {
            SafDocs.delete(context, target)
            return null
        }
        return target
    }

    // ---- UndoHandler (UndoKinds.TRASH): the entry's data is the batch id -------------------------

    override suspend fun isAvailable(data: String): Boolean = dao.countInTrash(data) > 0

    override suspend fun undo(entryId: Long, data: String): UndoOutcome = restore(data)

    override suspend fun discard(data: String) = forget(listOf(data))

    private companion object {
        const val TAG = "TrashService"
    }
}
