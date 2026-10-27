package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.local.entity.DownloadHistoryEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.DownloadQueue
import com.cortinadev.dogmatix.util.MirrorUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The queue as the UI sees it: who waits, in order (held rows included), and who is held. */
data class QueueState(val waiting: List<String> = emptyList(), val held: Set<String> = emptySet()) {
    val waitingSet: Set<String> by lazy { waiting.toSet() }

    /** Turn of each waiting row that is not held (1 = the next to start). */
    val turns: Map<String, Int> by lazy {
        waiting.filter { it !in held }.withIndex().associate { (index, name) -> name to index + 1 }
    }
}

/**
 * A row's place in line, handed from [DownloadQueueService.enqueue] to its job. [serial] names
 * this place and no other, so the slot it is granted is given back once, and only by its job.
 */
class QueueTicket internal constructor(val name: String, val order: Long, val held: Boolean, internal val serial: Long) {
    internal val turn = CompletableDeferred<Unit>()
}

/**
 * "Concurrent downloads" and "Downloads per server" from Settings, with an explicit order: a
 * download takes its place in line when it is started ([enqueue], call order = line order) and
 * its job then waits in [withSlot] until its turn. The user reorders and holds rows; places and
 * holds are kept in `download_history` so a restart resumes the same line.
 */
@Singleton
class DownloadQueueService @Inject constructor(
    settingsRepository: SettingsRepository,
    /** Its places are saved through the history's single ordered writer (after the row's insert). */
    private val tracker: DownloadProgressTracker
) {
    private data class Place(val order: Long, val held: Boolean)

    private val lock = Any()
    private val queue = DownloadQueue()
    /** The ticket of each waiting or running row (the latest one, should a row be started twice). */
    private val tickets = HashMap<String, QueueTicket>()
    /** Last place of every row known to the queue: a resumed row goes back to it. */
    private val places = HashMap<String, Place>()
    private var batchDepth = 0

    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            settingsRepository.concurrentDownloads.collect { max -> locked { queue.slots = max } }
        }
        scope.launch {
            settingsRepository.perHostDownloads.collect { max -> locked { queue.perHost = max } }
        }
    }

    /** Places and holds saved by the previous run; call before anything is resumed. */
    fun restore(rows: List<DownloadHistoryEntity>) = synchronized(lock) {
        rows.forEach { places[it.fileName] = Place(it.queueOrder, it.queueHeld) }
        queue.seed(rows.map { it.queueOrder })
    }

    /**
     * Puts [file] in line now; its job then waits on the ticket in [withSlot]. [keepPlace]: back
     * where it stood before (a paused download resumed, a restart), held again if it was held;
     * otherwise at the back.
     */
    fun enqueue(file: DownloadableFileEntity, keepPlace: Boolean = false): QueueTicket = synchronized(lock) {
        val name = file.fileName
        // Still waiting from a job that is being cancelled right now: the new job takes its place.
        tickets.remove(name)?.takeIf { !it.turn.isCompleted }?.turn?.cancel()
        val stale = queue.remove(name)
        val previous = stale?.let { Place(it.order, it.held) } ?: if (keepPlace) places[name] else null
        val host = if (file.isTorrent) null else MirrorUrls.hostOf(file.downloadUrl)
        val entry = queue.enqueue(name, host, previous?.order, previous?.held == true)!!
        val ticket = QueueTicket(name, entry.order, entry.held, entry.serial)
        tickets[name] = ticket
        remember(entry)
        grantLocked()
        publishLocked()
        ticket
    }

    /**
     * Waits for [ticket]'s turn, then runs [block] holding its slot. Cancelled while waiting, it
     * gives its place up (and its hold: a paused or stopped row resumes un-held).
     */
    suspend fun <T> withSlot(ticket: QueueTicket, block: suspend () -> T): T {
        val name = ticket.name
        try {
            ticket.turn.await()
        } catch (e: CancellationException) {
            locked {
                val current = tickets[name] === ticket
                if (current) tickets.remove(name)
                // Granted just as it was cancelled: the slot is ours to give back.
                if (ticket.turn.isCompleted && !ticket.turn.isCancelled) queue.finish(name, ticket.serial)
                else if (current) queue.remove(name, ticket.serial)?.let { if (it.held) remember(it.copy(held = false)) }
            }
            throw e
        }
        try {
            return block()
        } finally {
            locked {
                if (tickets[name] === ticket) tickets.remove(name)
                queue.finish(name, ticket.serial)
            }
        }
    }

    /** Runs [block] (several [enqueue]s) before anything starts, so they start by order, not by call order. */
    fun <T> batch(block: () -> T): T {
        synchronized(lock) { batchDepth++ }
        try {
            return block()
        } finally {
            locked { batchDepth-- }
        }
    }

    fun moveUp(name: String) = locked { queue.moveUp(name).forEach(::remember) }

    fun moveDown(name: String) = locked { queue.moveDown(name).forEach(::remember) }

    fun moveToFront(name: String) = locked { queue.moveToFront(name).forEach(::remember) }

    fun setHeld(name: String, held: Boolean) = locked { queue.setHeld(name, held)?.let(::remember) }

    fun isHeld(name: String): Boolean = synchronized(lock) { queue.entry(name)?.held == true }

    fun isWaiting(name: String): Boolean = synchronized(lock) { queue.isWaiting(name) }

    fun heldNames(): Set<String> = state.value.held

    private fun remember(entry: DownloadQueue.Entry) {
        val place = Place(entry.order, entry.held)
        places[entry.name] = place
        // In order with every other history write: two quick moves never land the other way round,
        // and a move right after the start never lands before the row's insert (which would undo it).
        tracker.persist { setQueuePlace(entry.name, place.order, place.held) }
    }

    /** Runs [change] under the lock, then starts whoever's turn came and publishes the line. */
    private inline fun locked(change: () -> Unit) = synchronized(lock) {
        change()
        grantLocked()
        publishLocked()
    }

    private fun grantLocked() {
        if (batchDepth > 0) return
        queue.grant().forEach { tickets[it.name]?.turn?.complete(Unit) }
    }

    private fun publishLocked() {
        if (batchDepth > 0) return
        val waiting = queue.waiting
        _state.value = QueueState(waiting.map { it.name }, waiting.filter { it.held }.mapTo(HashSet()) { it.name })
    }
}
