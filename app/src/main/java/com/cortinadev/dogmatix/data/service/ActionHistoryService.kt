package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.local.dao.ActionHistoryDao
import com.cortinadev.dogmatix.data.local.dao.TrashTotals
import com.cortinadev.dogmatix.data.local.entity.ActionHistoryEntity
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.ActionHistoryRules
import com.cortinadev.dogmatix.util.ActionState
import com.cortinadev.dogmatix.util.TrashRules
import com.cortinadev.dogmatix.util.UndoKinds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** How an undo ended. [STARTED]: it runs on (a move back) and settles the entry when it ends. */
enum class UndoOutcome { DONE, PARTIAL, STARTED, FAILED }

/**
 * Reverts one kind of history entry ([com.cortinadev.dogmatix.util.UndoKinds]), bound into a
 * Hilt map by that key (see `CollectionToolsModule`). [data] is whatever the recorder stored.
 */
interface UndoHandler {
    /** Whether the entry can be undone right now (its files still in the trash, the settings it changed untouched since…). */
    suspend fun isAvailable(data: String): Boolean

    suspend fun undo(entryId: Long, data: String): UndoOutcome

    /** The entry left the history: drop whatever was kept only to undo it. */
    suspend fun discard(data: String) {}
}

/**
 * The action history (Collection → History) and the public way into the trash. Anything that
 * deletes, moves or replaces something the user may want back records it here:
 *
 *  - [trash] for files and folders: moves them to the trash and records one entry whose Undo
 *    brings them back (Downloads, the library's "Delete", the Collection tools);
 *  - [record] for everything else, with the [UndoHandler] key and data that revert it.
 *
 * The trash is emptied by age (Settings: 7 or 30 days) and by hand; entries whose files went for
 * good stay listed but can no longer be undone.
 */
@Singleton
class ActionHistoryService @Inject constructor(
    private val dao: ActionHistoryDao,
    private val trashService: TrashService,
    private val settings: SettingsRepository,
    private val handlers: Map<String, @JvmSuppressWildcards Provider<UndoHandler>>,
    private val libraryIndex: dagger.Lazy<LibraryIndexService>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Newest first, at most [ActionHistoryRules.MAX_ENTRIES]. */
    val entries: Flow<List<ActionHistoryEntity>> = dao.observeRecent(ActionHistoryRules.MAX_ENTRIES)

    val trashTotals: Flow<TrashTotals> = trashService.totals

    val retentionDays: Flow<Int> = settings.trashRetentionDays

    private val _undoing = MutableStateFlow<Set<Long>>(emptySet())
    /** Entries whose undo is running in this process. */
    val undoing: StateFlow<Set<Long>> = _undoing.asStateFlow()

    init {
        scope.launch { runCatching { purgeExpired() } }
    }

    /**
     * Records an action. [undoKind] names the [UndoHandler] that reverts it with [undoData]; leave
     * both null for an entry that cannot be undone. Returns the entry id.
     */
    suspend fun record(
        type: String,
        subject: String,
        itemCount: Int = 0,
        bytes: Long = 0,
        undoKind: String? = null,
        undoData: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val id = dao.insert(
            ActionHistoryEntity(
                createdAt = now, type = type, subject = subject, itemCount = itemCount, bytes = bytes,
                undoKind = undoKind, undoData = undoData, state = ActionState.DONE.name
            )
        )
        if (undoKind != null && ActionHistoryRules.isChained(type)) dao.supersedeOlder(type, id, ActionState.SUPERSEDED.name, now)
        prune()
        return id
    }

    /**
     * Moves [requests] to the trash and records them as one [type] entry about [subject]; Undo
     * puts them back. What could not be trashed is listed in the result and left untouched: the
     * caller decides whether to delete it for good. [refreshLibrary] rescans the disk index after.
     */
    suspend fun trash(requests: List<TrashRequest>, type: String, subject: String, refreshLibrary: Boolean = true): TrashResult {
        val result = trashService.moveToTrash(requests)
        if (result.moved > 0) record(type, subject, result.moved, result.bytes, UndoKinds.TRASH, result.batchId)
        if (refreshLibrary && result.moved > 0) scope.launch { runCatching { libraryIndex.get().refresh() } }
        return result
    }

    /** Whether [entry] offers Undo right now (the pane greys out the others). */
    suspend fun isUndoAvailable(entry: ActionHistoryEntity): Boolean {
        val kind = entry.undoKind ?: return false
        if (!ActionHistoryRules.offersUndo(ActionState.of(entry.state), kind) || entry.id in _undoing.value) return false
        val handler = handlers[kind]?.get() ?: return false
        return try {
            handler.isAvailable(entry.undoData.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    suspend fun undo(id: Long): UndoOutcome {
        val entry = dao.get(id) ?: return UndoOutcome.FAILED
        if (!isUndoAvailable(entry)) return UndoOutcome.FAILED
        val handler = handlers[entry.undoKind]?.get() ?: return UndoOutcome.FAILED
        _undoing.update { it + id }
        val outcome = try {
            handler.undo(id, entry.undoData.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            UndoOutcome.FAILED
        } finally {
            _undoing.update { it - id }
        }
        when (outcome) {
            UndoOutcome.DONE -> finishUndo(id, success = true)
            UndoOutcome.STARTED -> dao.setState(id, ActionState.UNDOING.name, System.currentTimeMillis())
            // Part of it is back: the rest can be tried again.
            UndoOutcome.PARTIAL, UndoOutcome.FAILED -> dao.setState(id, ActionState.UNDO_FAILED.name, System.currentTimeMillis())
        }
        if (entry.undoKind == UndoKinds.TRASH && outcome != UndoOutcome.FAILED) scope.launch { runCatching { libraryIndex.get().refresh() } }
        return outcome
    }

    /**
     * Settles an undo: right away for most, or when a [UndoOutcome.STARTED] one ends. Undoing the
     * newest of a chained type (imports) makes the one before it undoable again.
     */
    suspend fun finishUndo(id: Long, success: Boolean) {
        val now = System.currentTimeMillis()
        dao.setState(id, if (success) ActionState.UNDONE.name else ActionState.UNDO_FAILED.name, now)
        if (!success) return
        val entry = dao.get(id) ?: return
        if (ActionHistoryRules.isChained(entry.type)) {
            dao.olderInState(entry.type, id, ActionState.SUPERSEDED.name).firstOrNull()?.let { dao.setState(it.id, ActionState.DONE.name, now) }
        }
    }

    suspend fun setRetentionDays(days: Int) {
        settings.setTrashRetentionDays(days)
        purgeExpired()
    }

    /** Empties what has been in the trash longer than the retention setting. */
    suspend fun purgeExpired() {
        val days = settings.trashRetentionDays.first()
        val purged = trashService.purgeTrashedBefore(TrashRules.cutoff(System.currentTimeMillis(), days))
        expire(purged)
    }

    /** "Empty trash": everything goes for good. Returns how many batches were emptied. */
    suspend fun emptyTrash(): Int {
        val roots = listOf(settings.downloadDirectory.first(), settings.secondaryStorageRoot.first()) +
            settings.consoleDownloadDirectories.first().values
        val purged = trashService.emptyAll(roots)
        expire(purged)
        return purged.size
    }

    private suspend fun expire(batchIds: List<String>) {
        val now = System.currentTimeMillis()
        batchIds.chunked(500).forEach { dao.expire(UndoKinds.TRASH, it, now) }
    }

    /** Keeps the newest [ActionHistoryRules.MAX_ENTRIES]; handlers drop what they kept for the rest. */
    private suspend fun prune() {
        val old = dao.beyond(ActionHistoryRules.MAX_ENTRIES)
        if (old.isEmpty()) return
        old.forEach { entry ->
            val kind = entry.undoKind ?: return@forEach
            runCatching { handlers[kind]?.get()?.discard(entry.undoData.orEmpty()) }
        }
        old.map { it.id }.chunked(500).forEach { dao.delete(it) }
    }

}
