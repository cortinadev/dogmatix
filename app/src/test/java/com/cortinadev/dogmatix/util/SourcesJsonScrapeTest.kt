package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.ScrapeRecipe
import com.cortinadev.dogmatix.data.model.UrlEntry
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SourcesJsonScrapeTest {

    private val recipe = ScrapeRecipe(item = "ul.roms li", name = "a@title", size = "span.s", pages = "1-2", ext = "zip")
    private val page = UrlEntry("https://site.example/list?p={page}", scrape = recipe)

    @Test
    fun `a recipe round-trips through the urls column`() {
        val entries = listOf(page, UrlEntry("https://a.example/gbc/"))
        assertEquals(entries, SourcesJson.parseUrlEntries(SourcesJson.serializeUrlEntries(entries)))
    }

    @Test
    fun `only the fields that are set are written`() {
        val json = JsonParser.parseString(SourcesJson.serializeUrlEntries(listOf(page))).asJsonArray[0].asJsonObject
        val scrape = json.getAsJsonObject("scrape")
        assertEquals(setOf("item", "name", "size", "pages", "ext"), scrape.keySet())
        assertEquals("a@title", scrape.get("name").asString)
        val bare = JsonParser.parseString(SourcesJson.serializeUrlEntries(listOf(UrlEntry("https://x.example/", scrape = ScrapeRecipe("a.rom")))))
            .asJsonArray[0].asJsonObject.getAsJsonObject("scrape")
        assertEquals(setOf("item"), bare.keySet())
        assertFalse(SourcesJson.serializeUrlEntries(listOf(UrlEntry("https://a.example/gbc/"))).contains("scrape"))
    }

    @Test
    fun `a whole document keeps the recipe`() {
        val doc = listOf(SourceManufacturer("sega", "Sega", listOf(SourceConsole("sega_saturn", "Saturn", listOf(page)))))
        val parsed = SourcesJson.parseDocument(SourcesJson.serializeDocument(doc))
        assertEquals(recipe, parsed.single().consoles.single().urls.single().scrape)
    }

    @Test
    fun `older files without a recipe still load`() {
        val parsed = SourcesJson.parseUrlEntries("""[{"url":"https://a.example/gb/","contentType":"GAME"}]""")
        assertNull(parsed.single().scrape)
    }

    @Test
    fun `a recipe without an item selector is ignored`() {
        val parsed = SourcesJson.parseUrlEntries("""[{"url":"https://a.example/","scrape":{"link":"a@href"}},{"url":"https://b.example/","scrape":{"item":"  "}},{"url":"https://c.example/","scrape":"li"}]""")
        assertEquals(listOf(null, null, null), parsed.map { it.scrape })
    }

    @Test
    fun `blank fields read as empty and the rest is trimmed`() {
        val parsed = SourcesJson.parseUrlEntries("""[{"url":"https://a.example/","scrape":{"item":" li.game ","link":"","name":"   ","size":" td.s "}}]""")
        assertEquals(ScrapeRecipe(item = "li.game", size = "td.s"), parsed.single().scrape)
    }
}
