package com.cortinadev.dogmatix.util

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ScanScheduleAndProgressTest {

    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour

    // ---- AutoScanSchedule ----------------------------------------------------------------------

    @Test
    fun `off is never due`() {
        assertFalse(AutoScanSchedule.isDue(10 * day, 0, AutoScanFrequency.OFF))
    }

    @Test
    fun `never scanned, or the clock went back, is due`() {
        assertTrue(AutoScanSchedule.isDue(10 * day, 0, AutoScanFrequency.WEEKLY))
        assertTrue(AutoScanSchedule.isDue(10 * day, 11 * day, AutoScanFrequency.DAILY))
    }

    @Test
    fun `due one interval after the last full scan, a check early at most`() {
        val last = 100 * day
        assertFalse(AutoScanSchedule.isDue(last + 20 * hour, last, AutoScanFrequency.DAILY))
        assertTrue(AutoScanSchedule.isDue(last + 21 * hour, last, AutoScanFrequency.DAILY))
        assertTrue(AutoScanSchedule.isDue(last + day, last, AutoScanFrequency.DAILY))
        assertFalse(AutoScanSchedule.isDue(last + 6 * day, last, AutoScanFrequency.WEEKLY))
        assertTrue(AutoScanSchedule.isDue(last + 7 * day - 2 * hour, last, AutoScanFrequency.WEEKLY))
    }

    @Test
    fun `unknown names read as off`() {
        assertEquals(AutoScanFrequency.OFF, AutoScanFrequency.fromName(null))
        assertEquals(AutoScanFrequency.OFF, AutoScanFrequency.fromName("HOURLY"))
        assertEquals(AutoScanFrequency.WEEKLY, AutoScanFrequency.fromName("WEEKLY"))
    }

    // ---- ScanProgressMath ----------------------------------------------------------------------

    @Test
    fun `progress is weighted by what each source costs`() {
        val math = ScanProgressMath().apply {
            add("web", ScanProgressMath.WEIGHT_WEB)
            add("torrent", ScanProgressMath.WEIGHT_TORRENT_FETCH)
            add("skipped", 0.0)
        }
        assertEquals(0.0, math.fraction, 1e-9)
        math.finish("web")
        assertEquals(1.0 / 7, math.fraction, 1e-9)
        math.set("torrent", ScanProgressMath.PHASE_TORRENT_METADATA)
        assertEquals((1 + 6 * 0.7) / 7, math.fraction, 1e-9)
        math.finish("torrent")
        assertEquals(1.0, math.fraction, 1e-9)
    }

    @Test
    fun `a phase never goes back and unknown sources are ignored`() {
        val math = ScanProgressMath().apply { add("a", 1.0); add("b", 1.0) }
        math.set("a", 0.8)
        math.set("a", 0.2)
        math.set("ghost", 1.0)
        assertEquals(0.4, math.fraction, 1e-9)
    }

    @Test
    fun `nothing to weigh is complete`() {
        val math = ScanProgressMath().apply { add("skipped", 0.0) }
        assertTrue(math.isEmpty)
        assertEquals(1.0, math.fraction, 1e-9)
    }

    // ---- ScanHostGate --------------------------------------------------------------------------

    @Test
    fun `no more requests per server than allowed`() = runBlocking {
        val gate = ScanHostGate(perHost = 2)
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        (1..6).map {
            async {
                gate.withSlot("myrient.example") {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    delay(20)
                    inFlight.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals(2, peak.get())
    }

    @Test
    fun `a server that pushed back gets one request at a time, after the wait`() = runBlocking {
        val gate = ScanHostGate(perHost = 2)
        gate.pushBack("Myrient.example", 50)
        val started = System.currentTimeMillis()
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        (1..3).map {
            async {
                gate.withSlot("myrient.example") {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    delay(10)
                    inFlight.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals(1, peak.get())
        assertTrue(System.currentTimeMillis() - started >= 50)
        // Another server is not held up.
        val other = System.currentTimeMillis()
        gate.withSlot("mirror.example") { }
        assertTrue(System.currentTimeMillis() - other < 50)
    }

    // ---- MagnetLinks / SourceLabels ------------------------------------------------------------

    @Test
    fun `magnet info-hash in hex or base32`() {
        val hex = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"
        assertEquals(hex, MagnetLinks.infoHashKey("magnet:?xt=urn:btih:C12FE1C06BBA254A9DC9F519B335AA7C1367A88A&dn=x"))
        // The same hash in base32.
        assertEquals(hex, MagnetLinks.infoHashKey("magnet:?xt=urn:btih:YEX6DQDLXISUVHOJ6UM3GNNKPQJWPKEK"))
        val v2 = "a".repeat(64)
        assertEquals(v2, MagnetLinks.infoHashKey("magnet:?xt=urn:btmh:1220$v2"))
        assertNull(MagnetLinks.infoHashKey("https://example.com/x.torrent"))
        assertNull(MagnetLinks.infoHashKey("magnet:?dn=no-hash"))
    }

    @Test
    fun `sources shortened for the report`() {
        assertEquals(
            "myrient.example/…/Nintendo - Game Boy",
            SourceLabels.short("https://myrient.example/files/No-Intro/Nintendo%20-%20Game%20Boy/")
        )
        assertEquals("example.com", SourceLabels.short("https://example.com/"))
        assertEquals("Redump Sony PS2", SourceLabels.short("magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&dn=Redump+Sony+PS2"))
        assertEquals("btih:c12fe1c06bba", SourceLabels.short("magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a"))
        assertEquals("uploaded_1.torrent", SourceLabels.short("/data/user/0/app/files/torrents/uploaded_1.torrent"))
        assertEquals("romm://psp", SourceLabels.short("romm://psp"))
    }
}
