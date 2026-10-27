package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownConsolesTest {

    private val all = KnownConsoles.manufacturers.flatMap { m -> m.consoles.map { m to it } }

    @Test
    fun `ids are unique and resolve to their table key`() {
        val ids = all.map { (m, c) -> KnownConsoles.idOf(m.id, c) }
        assertEquals(ids.size, ids.toSet().size)
        all.forEach { (m, c) ->
            assertEquals(c.key, ConsoleFormatter.consoleKey(KnownConsoles.idOf(m.id, c)))
            assertTrue(c.name.isNotBlank() && c.shortName.isNotBlank() && c.aliases.isNotEmpty())
            assertEquals(m.id, SourcesJson.slug(m.name))
        }
    }

    @Test
    fun `consoles from the original sources file keep their built-in short name and aliases`() {
        listOf("nintendo" to "gameboy_advance", "nintendo" to "super_nintendo_entertainment_system", "sony" to "playstation_2", "sega" to "cd")
            .forEach { (manufacturerId, key) ->
                val console = KnownConsoles.consolesOf(manufacturerId).first { it.key == key }
                val id = KnownConsoles.idOf(manufacturerId, console)
                assertEquals(ConsoleFormatter.getDefaultShortName(id), console.shortName)
                assertEquals(ConsoleFolderAliases.defaultAliasesFor(id), console.aliases)
            }
    }

    @Test
    fun `every console with built-in aliases is in the catalogue`() {
        val keys = all.map { it.second.key }.toSet()
        listOf("gameboy", "gameboy_color", "gameboy_advance", "nintendo_entertainment_system", "super_nintendo_entertainment_system",
            "nintendo_64", "gamecube", "nintendo_ds", "nintendo_3ds", "playstation", "playstation_2", "playstation_portable",
            "master_system", "genesis", "dreamcast", "cd").forEach { assertTrue(it, it in keys) }
    }

    @Test
    fun `colours follow the family of the id`() {
        assertEquals(ConsoleFamily.NINTENDO_HANDHELD, ConsoleFamily.of("nintendo_gameboy_advance"))
        assertEquals(ConsoleFamily.SEGA, ConsoleFamily.of("sega_cd"))
        assertEquals(ConsoleFamily.COMPUTER, ConsoleFamily.of("microsoft_msx"))
        assertEquals(ConsoleFamily.XBOX, ConsoleFamily.of("microsoft_xbox"))
        assertNotEquals(ConsoleFamily.OTHER, ConsoleFamily.of("nec_pc_engine_cd"))
    }

    @Test
    fun `missing skips consoles already present by id, bare key or name`() {
        val existing = listOf(
            "nintendo_gameboy_advance" to "Game Boy Advance",
            "super_nintendo_entertainment_system" to "SNES (imported)",
            "nintendo_custom" to "nintendo 64"
        )
        val left = KnownConsoles.missing("nintendo", existing).map { it.key }
        assertFalse("gameboy_advance" in left)
        assertFalse("super_nintendo_entertainment_system" in left)
        assertFalse("nintendo_64" in left)
        assertTrue("gameboy" in left)
        assertTrue(KnownConsoles.missing("rompacks", emptyList()).isEmpty())
        assertEquals(KnownConsoles.consolesOf("sony").size, KnownConsoles.missing("sony", emptyList()).size)
    }
}
