package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.service.ScanKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRequestsTest {

    private val manual = ScanRequest.All(full = false)
    private val full = ScanRequest.All(full = true)
    private val auto = ScanRequest.All(full = false, auto = true)
    private fun consoles(vararg ids: String) = ScanRequest.Consoles(ids.toSet())
    private val snesWeb = SourceRef("snes", "https://roms.example/snes/")
    private val gbMagnet = SourceRef("gb", "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567")

    private fun merge(vararg requests: ScanRequest): ScanPlan = ScanRequests.merge(requests.toList())!!

    @Test
    fun `nothing asked, nothing to run`() {
        assertNull(ScanRequests.merge(emptyList()))
    }

    @Test
    fun `each request alone keeps its kind`() {
        assertEquals(ScanKind.MANUAL, merge(manual).kind)
        assertEquals(ScanKind.FULL, merge(full).kind)
        assertEquals(ScanKind.AUTO, merge(auto).kind)
        assertEquals(ScanKind.CONSOLE, merge(consoles("snes")).kind)
        assertEquals(ScanKind.RETRY, merge(ScanRequest.RetryFailed).kind)
        assertEquals(ScanKind.NEW_SOURCES, merge(ScanRequest.Sources(setOf(snesWeb), ScanKind.NEW_SOURCES)).kind)
        assertEquals(ScanKind.SOURCES, merge(ScanRequest.Sources(setOf(snesWeb), ScanKind.SOURCES)).kind)
    }

    @Test
    fun `a full rescan wins over everything else and reads every source in full`() {
        val plan = merge(manual, consoles("snes"), ScanRequest.RetryFailed, full, auto)
        assertEquals(ScanKind.FULL, plan.kind)
        assertTrue(plan.everything)
        assertTrue(plan.forced("snes"))
        assertTrue(plan.forced("gb"))
        assertFalse(plan.retryFailed)
        assertTrue(plan.sources.isEmpty())
    }

    @Test
    fun `an incremental pass over everything still reads the consoles asked for in full`() {
        val plan = merge(manual, consoles("snes"))
        assertEquals(ScanKind.MANUAL, plan.kind)
        assertTrue(plan.everything)
        assertTrue(plan.forced("snes"))
        assertFalse(plan.forced("gb"))
        assertTrue(plan.coversConsole("gb"))
    }

    @Test
    fun `several console refreshes become one run over those consoles`() {
        val plan = merge(consoles("snes"), consoles("gb", "snes"))
        assertEquals(ScanKind.CONSOLE, plan.kind)
        assertFalse(plan.everything)
        assertEquals(setOf("snes", "gb"), plan.consoles)
        assertTrue(plan.coversConsole("gb"))
        assertFalse(plan.coversConsole("nes"))
        assertFalse(plan.forced("nes"))
    }

    @Test
    fun `a retry joins a rescan, which already reads the failed sources`() {
        val plan = merge(ScanRequest.RetryFailed, manual)
        assertEquals(ScanKind.MANUAL, plan.kind)
        assertTrue(plan.everything)
        assertFalse(plan.retryFailed)
    }

    @Test
    fun `a retry next to a console refresh keeps both`() {
        val plan = merge(ScanRequest.RetryFailed, consoles("snes"))
        assertEquals(ScanKind.CONSOLE, plan.kind)
        assertTrue(plan.retryFailed)
        assertEquals(setOf("snes"), plan.consoles)
    }

    @Test
    fun `the background scan only names the run when it is alone`() {
        assertEquals(ScanKind.AUTO, merge(auto, auto).kind)
        assertEquals(ScanKind.MANUAL, merge(auto, manual).kind)
        assertEquals(ScanKind.MANUAL, merge(auto, consoles("snes")).kind)
    }

    @Test
    fun `single sources add up, and a pass over everything covers them`() {
        val singles = merge(
            ScanRequest.Sources(setOf(snesWeb), ScanKind.SOURCES),
            ScanRequest.Sources(setOf(gbMagnet, snesWeb), ScanKind.NEW_SOURCES)
        )
        assertEquals(ScanKind.SOURCES, singles.kind)
        assertEquals(setOf(snesWeb, gbMagnet), singles.sources)
        assertFalse(singles.forced("snes"))
        assertFalse(singles.coversConsole("snes"))

        val withAll = merge(ScanRequest.Sources(setOf(snesWeb), ScanKind.SOURCES), manual)
        assertTrue(withAll.everything)
        assertTrue(withAll.sources.isEmpty())
    }

    @Test
    fun `the same request twice is one run`() {
        assertEquals(merge(manual), merge(manual, manual))
        assertEquals(merge(consoles("snes")), merge(consoles("snes"), consoles("snes")))
    }

    // ---- Clean-up at the end of a run -----------------------------------------------------------

    @Test
    fun `legacy rows only go where every source of the console was read and answered`() {
        val legacy = ScanCleanup.legacyConsoles(
            covered = setOf("snes", "gb", "nes"),
            tasks = mapOf("snes" to 2, "gb" to 2),
            succeeded = mapOf("snes" to 2, "gb" to 1)
        )
        // gb: one source failed. nes: no source switched on, nothing claims its legacy rows.
        assertEquals(setOf("snes", "nes"), legacy)
    }

    @Test
    fun `a source that never got to the end keeps the console's legacy rows`() {
        // Three sources planned, one recorded: the other two were cut short (a run stopped half-way).
        assertTrue(ScanCleanup.legacyConsoles(setOf("snes"), mapOf("snes" to 3), mapOf("snes" to 1)).isEmpty())
        assertTrue(ScanCleanup.legacyConsoles(setOf("snes"), mapOf("snes" to 3), emptyMap()).isEmpty())
    }

    @Test
    fun `consoles the run did not read in full keep their legacy rows`() {
        assertTrue(ScanCleanup.legacyConsoles(emptySet(), mapOf("snes" to 1), mapOf("snes" to 1)).isEmpty())
    }

    private val indexed = listOf(
        SourceRef("snes", ""), SourceRef("snes", "https://a.example/snes/"), SourceRef("snes", "https://old.example/snes/"),
        SourceRef("gb", ""), SourceRef("gb", "https://a.example/gb/"),
        SourceRef("gone", ""), SourceRef("gone", "https://a.example/gone/")
    )

    @Test
    fun `a pass over everything drops sources no longer set up and consoles that are gone`() {
        val stale = ScanCleanup.staleRows(
            indexed,
            configured = mapOf("snes" to setOf("https://a.example/snes/"), "gb" to setOf("https://a.example/gb/")),
            everything = true,
            legacyConsoles = setOf("snes")
        )
        assertEquals(
            listOf(SourceRef("snes", ""), SourceRef("snes", "https://old.example/snes/"), SourceRef("gone", ""), SourceRef("gone", "https://a.example/gone/")),
            stale
        )
    }

    @Test
    fun `a run over some consoles only touches those`() {
        val stale = ScanCleanup.staleRows(
            indexed,
            configured = mapOf("snes" to setOf("https://a.example/snes/")),
            everything = false,
            legacyConsoles = emptySet()
        )
        assertEquals(listOf(SourceRef("snes", "https://old.example/snes/")), stale)
    }

    @Test
    fun `a console deleted during a run loses its rows and results`() {
        val stale = ScanCleanup.staleRows(indexed, configured = mapOf("gone" to emptySet()), everything = false, legacyConsoles = emptySet())
        assertEquals(listOf(SourceRef("gone", "https://a.example/gone/")), stale)
        val results = ScanCleanup.staleResults(
            listOf(SourceRef("gone", "https://a.example/gone/"), SourceRef("snes", "https://a.example/snes/")),
            configured = mapOf("gone" to emptySet()),
            everything = false
        )
        assertEquals(listOf(SourceRef("gone", "https://a.example/gone/")), results)
    }

    @Test
    fun `results of sources switched off or deleted go, the others stay`() {
        val results = listOf(
            SourceRef("snes", "https://a.example/snes/"), SourceRef("snes", "https://old.example/snes/"), SourceRef("gone", "https://a.example/gone/")
        )
        val configured = mapOf("snes" to setOf("https://a.example/snes/"))
        assertEquals(
            listOf(SourceRef("snes", "https://old.example/snes/"), SourceRef("gone", "https://a.example/gone/")),
            ScanCleanup.staleResults(results, configured, everything = true)
        )
        assertEquals(listOf(SourceRef("snes", "https://old.example/snes/")), ScanCleanup.staleResults(results, configured, everything = false))
    }
}
