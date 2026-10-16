package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.UrlEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesJsonMirrorsTest {

    @Test
    fun `mirrors round-trip and are only written when present`() {
        val entries = listOf(
            UrlEntry("https://a.example/gb/", mirrors = listOf("https://b.example/gb/", "https://c.example/gb/")),
            UrlEntry("https://a.example/gbc/")
        )
        val json = SourcesJson.serializeUrlEntries(entries)
        assertEquals(entries, SourcesJson.parseUrlEntries(json))
        assertTrue(json.contains("\"mirrors\""))
        assertFalse(SourcesJson.serializeUrlEntries(listOf(UrlEntry("https://a.example/gbc/"))).contains("mirrors"))
    }

    @Test
    fun `older files without mirrors still load`() {
        val parsed = SourcesJson.parseUrlEntries("""[{"url":"https://a.example/gb/","contentType":"GAME"}]""")
        assertEquals(emptyList<String>(), parsed.single().mirrors)
    }
}
