package com.cortinadev.dogmatix.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesJsonFavouritesTest {

    private fun gba(favourites: List<String> = emptyList()) = SourceManufacturer(
        id = "nintendo", name = "Nintendo",
        consoles = listOf(
            SourceConsole(id = "nintendo_gameboy_advance", name = "Game Boy Advance", urls = emptyList(), favourites = favourites),
            SourceConsole(id = "nintendo_snes", name = "SNES", urls = emptyList())
        )
    )

    @Test
    fun favouritesRoundTripInsideTheirConsole() {
        val json = SourcesJson.serializeDocument(listOf(gba(listOf("Advance Wars (USA).zip", "Golden Sun (Europe) (En,Fr,De).zip"))))

        // Nested in the console object: the console is the one the document already names.
        val console = JsonParser.parseString(json).asJsonObject
            .getAsJsonObject("nintendo").getAsJsonObject("gameboy_advance")
        assertEquals(2, console.getAsJsonArray("favourites").size())

        val back = SourcesJson.parseDocument(json)[0].consoles
        assertEquals("nintendo_gameboy_advance", back[0].id)
        assertEquals(listOf("Advance Wars (USA).zip", "Golden Sun (Europe) (En,Fr,De).zip"), back[0].favourites)
        assertEquals(emptyList<String>(), back[1].favourites)
    }

    @Test
    fun anIdWithoutTheManufacturerPrefixSurvivesTheRoundTrip() {
        // Older versions created "super_nintendo_entertainment_system" under "nintendo": the key
        // alone would read back as "nintendo_super_nintendo_entertainment_system".
        val legacy = SourceManufacturer(
            id = "nintendo", name = "Nintendo",
            consoles = listOf(
                SourceConsole(id = "super_nintendo_entertainment_system", name = "Super Nintendo", urls = emptyList(), favourites = listOf("Star Fox (USA).zip")),
                SourceConsole(id = "nintendo_gameboy_advance", name = "Game Boy Advance", urls = emptyList())
            )
        )
        val json = SourcesJson.serializeDocument(listOf(legacy))
        val consoles = SourcesJson.parseDocument(json)[0].consoles
        assertEquals("super_nintendo_entertainment_system", consoles[0].id)
        assertEquals(listOf("Star Fox (USA).zip"), consoles[0].favourites)
        // A prefixed id needs nothing extra.
        assertEquals("nintendo_gameboy_advance", consoles[1].id)
        val gba = JsonParser.parseString(json).asJsonObject.getAsJsonObject("nintendo").getAsJsonObject("gameboy_advance")
        assertFalse(gba.has("id"))
    }

    @Test
    fun noFavouritesMeansNoKeyAndAnEmptyList() {
        val json = SourcesJson.serializeDocument(listOf(gba()))
        assertFalse(json.contains("favourites"))

        // Documents from before this field (and the bundled asset) read as "nothing to restore".
        val old = """{ "nintendo": { "gameboy_advance": { "urls": [] } } }"""
        assertTrue(SourcesJson.parseDocument(old)[0].consoles[0].favourites.isEmpty())
    }

    @Test
    fun aMalformedListIsIgnoredAndTheRestOfTheConsoleStillParses() {
        val doc = """
            { "nintendo": {
                "gameboy_advance": { "short": "GBA", "favourites": "Advance Wars (USA).zip", "urls": [ { "url": "https://example.org/gba" } ] },
                "snes": { "favourites": [ "Chrono Trigger (USA).zip", 7, null, "Chrono Trigger (USA).zip", "" ], "urls": [] }
            } }
        """.trimIndent()
        val consoles = SourcesJson.parseDocument(doc)[0].consoles
        assertEquals(emptyList<String>(), consoles[0].favourites)
        assertEquals("GBA", consoles[0].shortName)
        assertEquals(1, consoles[0].urls.size)
        // Whatever reads as text, once each; nulls and blanks are dropped.
        assertEquals(listOf("Chrono Trigger (USA).zip", "7"), consoles[1].favourites)
    }
}
