package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RommSourceTest {

    @Test
    fun `recognises romm sources and extracts the slug`() {
        assertTrue(RommSource.isSource("romm://psp"))
        assertTrue(RommSource.isSource("  ROMM://PSP/ "))
        assertFalse(RommSource.isSource("https://myrient.example/psp/"))
        assertEquals("psp", RommSource.slugOf(" ROMM://PSP/ "))
        assertEquals("genesis-slash-megadrive", RommSource.slugOf("romm://genesis-slash-megadrive"))
        assertNull(RommSource.slugOf("romm://"))
        assertNull(RommSource.slugOf("magnet:?xt=urn:btih:abc"))
        assertEquals("romm://nes", RommSource.sourceFor(" NES "))
    }

    @Test
    fun `builds encoded download urls and matches them back to the server`() {
        val url = RommSource.downloadUrl("http://romm.local:8090/", 7, "300 - March to Glory (USA).iso")
        assertEquals("http://romm.local:8090/api/roms/7/content/300%20-%20March%20to%20Glory%20%28USA%29.iso", url)
        assertTrue(RommSource.isDownloadFrom("http://romm.local:8090", url))
        assertFalse(RommSource.isDownloadFrom("http://other:8090", url))
        assertFalse(RommSource.isDownloadFrom("", url))
    }
}
