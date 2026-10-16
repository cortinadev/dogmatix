package com.cortinadev.dogmatix.util

/**
 * The line of downloads waiting to start. At most [slots] run at once and at most [perHost]
 * from one server (0 = no per-server limit); the rest wait in [waiting]'s order, which the
 * user changes (up, down, next) and which survives a restart through each entry's [Entry.order].
 * A held entry keeps its place but is skipped until it is let go.
 *
 * Plain state without coroutines or locking: the service turns [grant] into started jobs.
 */
class DownloadQueue(slots: Int = 3, perHost: Int = 0) {

    /** [order]: smaller starts first; unique while every order comes from this queue's counters. */
    data class Entry(val name: String, val host: String?, val order: Long, val held: Boolean = false)

    var slots: Int = slots.coerceAtLeast(1)
        set(value) { field = value.coerceAtLeast(1) }

    /** Downloads at once from one server; 0 = no limit. Torrents have no host and are never limited. */
    var perHost: Int = perHost.coerceAtLeast(0)
        set(value) { field = value.coerceAtLeast(0) }

    /** Sorted by [Entry.order]; equal orders keep their arrival order. */
    private val entries = ArrayList<Entry>()
    /** Started and not finished yet: name → host. */
    private val running = HashMap<String, String?>()
    private var lowest = 0L
    private var highest = 0L

    val waiting: List<Entry> get() = entries.toList()
    val runningCount: Int get() = running.size

    fun isWaiting(name: String): Boolean = entries.any { it.name == name }
    fun isRunning(name: String): Boolean = name in running
    fun entry(name: String): Entry? = entries.firstOrNull { it.name == name }

    /** Orders kept from an earlier run: new entries go after all of them, "next" before all of them. */
    fun seed(orders: Collection<Long>) {
        orders.minOrNull()?.let { lowest = minOf(lowest, it) }
        orders.maxOrNull()?.let { highest = maxOf(highest, it) }
    }

    /**
     * Puts [name] in line at [order] (null = at the back) and returns its entry, or null when it
     * is already waiting. A name still running (its previous job is winding down) may wait again:
     * [grant] skips it until [finish].
     */
    fun enqueue(name: String, host: String?, order: Long? = null, held: Boolean = false): Entry? {
        if (isWaiting(name)) return null
        val entry = Entry(name, host?.lowercase(), order ?: ++highest, held)
        seed(listOf(entry.order))
        val at = entries.indexOfFirst { it.order > entry.order }
        if (at < 0) entries += entry else entries.add(at, entry)
        return entry
    }

    /** Leaves the line without starting (stopped, paused or deleted while waiting). */
    fun remove(name: String): Entry? {
        val index = entries.indexOfFirst { it.name == name }
        return if (index < 0) null else entries.removeAt(index)
    }

    /** A started download ended, whatever the outcome: its slot is free again. */
    fun finish(name: String) {
        running.remove(name)
    }

    /**
     * The entries that may start now, first in line first, counted as running from here on.
     * Held entries, entries whose server is at its limit and names still running are passed
     * over without losing their place.
     */
    fun grant(): List<Entry> {
        val started = ArrayList<Entry>()
        val iterator = entries.iterator()
        while (running.size < slots && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.held || entry.name in running || hostFull(entry.host)) continue
            iterator.remove()
            running[entry.name] = entry.host
            started += entry
        }
        return started
    }

    private fun hostFull(host: String?): Boolean =
        perHost > 0 && host != null && running.values.count { it == host } >= perHost

    /** One place earlier; returns the entries whose order changed (empty when nothing moved). */
    fun moveUp(name: String): List<Entry> = swap(name, -1)

    /** One place later; returns the entries whose order changed. */
    fun moveDown(name: String): List<Entry> = swap(name, +1)

    /** "Download next": first in line, ahead of everything waiting. */
    fun moveToFront(name: String): List<Entry> {
        val index = entries.indexOfFirst { it.name == name }
        if (index <= 0) return emptyList()
        val moved = entries.removeAt(index).copy(order = minOf(lowest, entries.first().order) - 1)
        lowest = moved.order
        entries.add(0, moved)
        return listOf(moved)
    }

    /** Holds or lets go of a waiting entry; returns it when that changed anything. */
    fun setHeld(name: String, held: Boolean): Entry? {
        val index = entries.indexOfFirst { it.name == name }
        if (index < 0 || entries[index].held == held) return null
        return entries[index].copy(held = held).also { entries[index] = it }
    }

    /** Swaps places (and orders) with the neighbour [delta] away, so the list stays sorted. */
    private fun swap(name: String, delta: Int): List<Entry> {
        val i = entries.indexOfFirst { it.name == name }
        val j = i + delta
        if (i < 0 || j !in entries.indices) return emptyList()
        val a = entries[i]
        val b = entries[j]
        val movedA = a.copy(order = b.order)
        val movedB = b.copy(order = a.order)
        entries[j] = movedA
        entries[i] = movedB
        return listOf(movedA, movedB)
    }

    companion object {
        /**
         * The rows of a list with the queued ones permuted into queue order. Every other row keeps
         * its slot, so nothing jumps around when a download starts or ends: only reordering the
         * queue moves rows.
         */
        fun <T> arrange(rows: List<T>, queueOrder: List<String>, nameOf: (T) -> String): List<T> {
            if (queueOrder.size < 2) return rows
            val rank = HashMap<String, Int>(queueOrder.size * 2)
            queueOrder.forEachIndexed { index, name -> rank[name] = index }
            val queued = rows.filter { nameOf(it) in rank }.sortedBy { rank.getValue(nameOf(it)) }
            if (queued.size < 2) return rows
            var next = 0
            return rows.map { if (nameOf(it) in rank) queued[next++] else it }
        }
    }
}
