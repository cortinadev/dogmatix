package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DgmtxFileTest {

    @Test
    fun `bare link is extracted`() {
        assertEquals(
            "dogmatix://library?console=snes",
            DgmtxFile.extractLink("dogmatix://library?console=snes")
        )
    }

    @Test
    fun `comments blanks and BOM are skipped`() {
        val text = "\uFEFF# Dogmatix shortcut — SNES\r\n\r\n# second comment\r\ndogmatix://library?console=snes\r\n"
        assertEquals("dogmatix://library?console=snes", DgmtxFile.extractLink(text))
    }

    @Test
    fun `only the first link counts`() {
        val text = "dogmatix://library?console=snes\ndogmatix://library?console=nes"
        assertEquals("dogmatix://library?console=snes", DgmtxFile.extractLink(text))
    }

    @Test
    fun `scheme is matched case-insensitively but other content is rejected`() {
        assertEquals("DOGMATIX://library?fav=1", DgmtxFile.extractLink("DOGMATIX://library?fav=1"))
        assertNull(DgmtxFile.extractLink(""))
        assertNull(DgmtxFile.extractLink("# only comments\n# here"))
        assertNull(DgmtxFile.extractLink("https://example.com/rom.zip"))
        assertNull(DgmtxFile.extractLink("random binary-ish content"))
    }

    @Test
    fun `library link round-trips through the deep link parser`() {
        val request = DeepLinkParser.parse(DgmtxFile.libraryLink("sony_psp"))
        assertNotNull(request)
        assertEquals(setOf("sony_psp"), request!!.consoles)
    }

    @Test
    fun `console ids with odd characters survive the round trip`() {
        val id = "weird id&co=1"
        val request = DeepLinkParser.parse(DgmtxFile.libraryLink(id))
        assertEquals(setOf(id), request!!.consoles)
    }

    @Test
    fun `generated file content parses back to its console`() {
        val content = DgmtxFile.contentForConsole("nintendo_snes", "Super Nintendo")
        val request = DeepLinkParser.parse(DgmtxFile.extractLink(content))
        assertEquals(setOf("nintendo_snes"), request!!.consoles)
    }
}
