package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ScanEtaTest {

    /** Feeds [fraction] at [t] (seconds from the start) every 2 s, as the scan's ticker does. */
    private fun ScanEta.at(t: Int, fraction: Double) = update(t * 1_000L, fraction)

    @Test
    fun `steady progress gives the time left once it settles`() {
        val eta = ScanEta().apply { reset(0) }
        var last: Long? = null
        // 1 % per second: at 30 s, 70 s are left.
        for (t in 2..30 step 2) last = eta.at(t, t / 100.0)
        assertNotNull(last)
        assertTrue("got $last", abs(last!! - 70_000) < 7_000)
    }

    @Test
    fun `nothing before some time and some progress`() {
        val eta = ScanEta().apply { reset(0) }
        assertNull(eta.at(2, 0.2))
        assertNull(eta.at(4, 0.4))
        // Plenty of time, too little progress.
        val slow = ScanEta().apply { reset(0) }
        for (t in 2..20 step 2) assertNull(slow.at(t, 0.001 * t))
    }

    @Test
    fun `a jumpy start is not shown until the readings agree`() {
        val eta = ScanEta().apply { reset(0) }
        assertNull(eta.at(10, 0.05))
        assertNull(eta.at(12, 0.40))
        assertNull(eta.at(14, 0.41))
        // Three readings now, but they disagree wildly (a slow torrent, then a burst of listings).
        assertNull(eta.at(16, 0.90))
    }

    @Test
    fun `a whole window without progress drops the estimate`() {
        val eta = ScanEta(windowMs = 20_000).apply { reset(0) }
        for (t in 2..30 step 2) eta.at(t, t / 100.0)
        assertNotNull(eta.at(32, 0.32))
        // Stuck at 32 % (a torrent waiting for its swarm) for longer than the window.
        var last: Long? = 0
        for (t in 34..60 step 2) last = eta.at(t, 0.32)
        assertNull(last)
    }

    @Test
    fun `done is done`() {
        val eta = ScanEta().apply { reset(0) }
        assertEquals(0L, eta.at(1, 1.0))
    }

    @Test
    fun `time left is split for the indicator`() {
        assertNull(ScanEtaFormat.hoursAndMinutes(59_000))
        assertEquals(0 to 1, ScanEtaFormat.hoursAndMinutes(60_000))
        assertEquals(0 to 3, ScanEtaFormat.hoursAndMinutes(150_000))
        assertEquals(1 to 20, ScanEtaFormat.hoursAndMinutes(80 * 60_000L))
    }
}
