package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueTest {

    private fun DownloadQueue.names() = waiting.map { it.name }
    private fun List<DownloadQueue.Entry>.names() = map { it.name }

    @Test
    fun startsInLineOrderUpToTheSlots() {
        val queue = DownloadQueue(slots = 2)
        listOf("a", "b", "c", "d").forEach { queue.enqueue(it, null) }
        assertEquals(listOf("a", "b"), queue.grant().names())
        assertEquals(listOf("c", "d"), queue.names())
        // Nothing more until a slot frees up.
        assertTrue(queue.grant().isEmpty())
        queue.finish("b")
        assertEquals(listOf("c"), queue.grant().names())
    }

    @Test
    fun movesSwapPlacesAndOrders() {
        val queue = DownloadQueue(slots = 1)
        val a = queue.enqueue("a", null)!!
        val b = queue.enqueue("b", null)!!
        queue.enqueue("c", null)
        val changed = queue.moveUp("b")
        assertEquals(listOf("b", "a", "c"), queue.names())
        // Both changed entries come back to be saved, with the orders swapped.
        assertEquals(setOf("b" to a.order, "a" to b.order), changed.map { it.name to it.order }.toSet())
        assertEquals(listOf("b", "c", "a"), queue.also { it.moveDown("a"); it.moveDown("a") }.names())
        // Already last / first: nothing moves, nothing to save.
        assertTrue(queue.moveDown("a").isEmpty())
        assertTrue(queue.moveUp("b").isEmpty())
        assertEquals(listOf("b"), queue.grant().names())
    }

    @Test
    fun downloadNextGoesAheadOfEverythingWaiting() {
        val queue = DownloadQueue(slots = 1)
        listOf("a", "b", "c").forEach { queue.enqueue(it, null) }
        val moved = queue.moveToFront("c").single()
        assertEquals(listOf("c", "a", "b"), queue.names())
        assertTrue(queue.waiting.drop(1).all { it.order > moved.order })
        // A second "next" goes in front of the first one.
        queue.moveToFront("b")
        assertEquals(listOf("b", "c", "a"), queue.names())
        assertEquals(listOf("b"), queue.grant().names())
    }

    @Test
    fun savedOrdersComeBackInTheSameLineWhateverTheCallOrder() {
        val first = DownloadQueue(slots = 1)
        listOf("a", "b", "c").forEach { first.enqueue(it, null) }
        first.moveToFront("c")
        first.moveDown("a")
        val saved = first.waiting.associate { it.name to it.order }   // c, b, a

        // After a restart the rows are resumed in history order, not in line order.
        val restarted = DownloadQueue(slots = 1).apply { seed(saved.values) }
        listOf("a", "b", "c").forEach { restarted.enqueue(it, null, saved.getValue(it)) }
        assertEquals(listOf("c", "b", "a"), restarted.names())
        // Something started afterwards goes to the back, "next" to the front.
        restarted.enqueue("d", null)
        restarted.enqueue("e", null)
        restarted.moveToFront("e")
        assertEquals(listOf("e", "c", "b", "a", "d"), restarted.names())
    }

    @Test
    fun heldRowsKeepTheirPlaceButAreSkipped() {
        val queue = DownloadQueue(slots = 1)
        listOf("a", "b").forEach { queue.enqueue(it, null) }
        queue.setHeld("a", true)
        assertEquals(listOf("b"), queue.grant().names())
        assertEquals(listOf("a"), queue.names())
        queue.finish("b")
        // Held and alone in line: nothing starts until it is let go.
        assertTrue(queue.grant().isEmpty())
        assertNull(queue.setHeld("a", true))
        queue.setHeld("a", false)
        assertEquals(listOf("a"), queue.grant().names())
    }

    @Test
    fun heldIsRestoredWithItsPlace() {
        val queue = DownloadQueue(slots = 2)
        queue.enqueue("a", null, order = 7, held = true)
        queue.enqueue("b", null, order = 3)
        assertEquals(listOf("b", "a"), queue.names())
        assertEquals(listOf("b"), queue.grant().names())
    }

    @Test
    fun aBusyServerLetsOtherServersThrough() {
        val queue = DownloadQueue(slots = 3, perHost = 1)
        queue.enqueue("a1", "a.example")
        queue.enqueue("a2", "A.example")
        queue.enqueue("b1", "b.example")
        queue.enqueue("t1", null)   // torrents have no host
        assertEquals(listOf("a1", "b1", "t1"), queue.grant().names())
        // a2 keeps its place at the head of the line until its server is free.
        assertEquals(listOf("a2"), queue.names())
        queue.finish("b1")
        assertTrue(queue.grant().isEmpty())
        queue.finish("a1")
        assertEquals(listOf("a2"), queue.grant().names())
    }

    @Test
    fun noPerServerLimitByDefault() {
        val queue = DownloadQueue(slots = 3)
        listOf("1", "2", "3").forEach { queue.enqueue(it, "same.example") }
        assertEquals(3, queue.grant().size)
    }

    @Test
    fun aRowStillWindingDownIsNotStartedTwice() {
        val queue = DownloadQueue(slots = 2)
        queue.enqueue("a", null)
        assertEquals(listOf("a"), queue.grant().names())
        // Retried while its previous job has not given the slot back yet.
        queue.enqueue("a", null)
        assertTrue(queue.grant().isEmpty())
        queue.finish("a")
        assertEquals(listOf("a"), queue.grant().names())
    }

    @Test
    fun aWaitingRowIsNotQueuedTwice() {
        val queue = DownloadQueue(slots = 1)
        queue.enqueue("a", null)
        assertNull(queue.enqueue("a", null))
        assertEquals(listOf("a"), queue.names())
    }

    @Test
    fun leavingTheLineFreesNothingButItsPlace() {
        val queue = DownloadQueue(slots = 1)
        listOf("a", "b", "c").forEach { queue.enqueue(it, null) }
        queue.grant()
        assertEquals("b", queue.remove("b")?.name)
        assertNull(queue.remove("b"))
        assertTrue(queue.grant().isEmpty())
        queue.finish("a")
        assertEquals(listOf("c"), queue.grant().names())
    }

    @Test
    fun moreSlotsStartMoreAtOnce() {
        val queue = DownloadQueue(slots = 1)
        listOf("a", "b", "c").forEach { queue.enqueue(it, null) }
        queue.grant()
        queue.slots = 3
        assertEquals(listOf("b", "c"), queue.grant().names())
        queue.slots = 0
        assertEquals(1, queue.slots)
    }

    @Test
    fun aFullServerDoesNotHoldUpAnotherOne() {
        // Two servers, one download each at a time, three slots.
        val queue = DownloadQueue(slots = 3, perHost = 1)
        queue.enqueue("a1", "a.example")
        queue.enqueue("a2", "a.example")
        queue.enqueue("a3", "a.example")
        queue.enqueue("b1", "b.example")
        queue.enqueue("b2", "b.example")
        assertEquals(listOf("a1", "b1"), queue.grant().names())
        // The third slot stays free rather than going to a server that is already busy.
        assertEquals(2, queue.runningCount)
        assertEquals(listOf("a2", "a3", "b2"), queue.names())
        queue.finish("b1")
        // b's turn comes although a2 and a3 are ahead of it in line.
        assertEquals(listOf("b2"), queue.grant().names())
        queue.finish("a1")
        assertEquals(listOf("a2"), queue.grant().names())
    }

    @Test
    fun cancelledWhileWaitingItNeverCountsAsRunning() {
        val queue = DownloadQueue(slots = 1)
        listOf("a", "b").forEach { queue.enqueue(it, null) }
        assertEquals(listOf("a"), queue.grant().names())
        // b is paused while it waits: it leaves the line and frees nothing.
        assertEquals("b", queue.remove("b")?.name)
        assertFalse(queue.finish("b"))
        assertEquals(1, queue.runningCount)
        queue.finish("a")
        assertTrue(queue.grant().isEmpty())
        assertEquals(0, queue.runningCount)
    }

    @Test
    fun aSlotIsGivenBackOnceAndOnlyByItsOwnStart() {
        val queue = DownloadQueue(slots = 1)
        val first = queue.enqueue("a", null)!!
        queue.grant()
        assertTrue(queue.finish("a", first.serial))
        assertFalse(queue.finish("a", first.serial))
        // Started again: the first job ending late cannot free the slot of the second one.
        val second = queue.enqueue("a", null)!!
        queue.enqueue("b", null)
        assertEquals(listOf("a"), queue.grant().names())
        assertFalse(queue.finish("a", first.serial))
        assertTrue(queue.grant().isEmpty())
        assertTrue(queue.finish("a", second.serial))
        assertEquals(listOf("b"), queue.grant().names())
        // Leaving the line works the same way: only that entry.
        val waiting = queue.enqueue("c", null)!!
        assertNull(queue.remove("c", waiting.serial + 100))
        assertEquals("c", queue.remove("c", waiting.serial)?.name)
    }

    @Test
    fun aRestartBringsBackOrderAndHoldsAndTheRestGoesAfter() {
        // Saved by the previous run: c held at the front, then a, then b.
        val saved = mapOf("a" to 4L, "b" to 9L, "c" to -2L)
        val queue = DownloadQueue(slots = 2).apply { seed(saved.values) }
        // Resumed in history order, not line order.
        listOf("b", "a", "c").forEach { queue.enqueue(it, null, saved.getValue(it), held = it == "c") }
        assertEquals(listOf("c", "a", "b"), queue.names())
        assertTrue(queue.entry("c")!!.held)
        // The held row keeps its place but does not start (nor need the service).
        assertEquals(listOf("a", "b"), queue.grant().names())
        // Started after the restart: behind everything saved.
        assertTrue(queue.enqueue("d", null)!!.order > 9L)
        queue.finish("a")
        queue.setHeld("c", false)
        assertEquals(listOf("c"), queue.grant().names())
    }

    @Test
    fun arrangeOnlyPermutesTheQueuedRows() {
        val rows = listOf("done1", "q-b", "running", "q-c", "done2", "q-a")
        val arranged = DownloadQueue.arrange(rows, listOf("q-a", "q-b", "q-c")) { it }
        assertEquals(listOf("done1", "q-a", "running", "q-b", "done2", "q-c"), arranged)
        // Nothing (or one row) queued: the list as it was.
        assertEquals(rows, DownloadQueue.arrange(rows, listOf("q-c")) { it })
        assertEquals(rows, DownloadQueue.arrange(rows, emptyList()) { it })
    }
}
