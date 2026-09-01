package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Test

class ConsoleFolderAliasesTest {

    private val folders = listOf("Playstation", "gba", "Gameboy Advance", "GBA", "psx", "misc")

    @After
    fun clearRegistry() { ConsoleAliasRegistry.overrides = emptyMap() }

    @Test
    fun `configured short name and aliases extend the built-in ones`() {
        ConsoleAliasRegistry.overrides = mapOf("nintendo_64" to ConsoleAliasInfo("N64", listOf("Nintendo 64 ROMs")))
        assertEquals("N64", ConsoleFormatter.getConsoleShortName("nintendo_64"))
        assertEquals(listOf("nintendo-64-roms"), ConsoleFolderAliases.matchingFolders(listOf("nintendo-64-roms", "misc"), "nintendo_64"))
        assertEquals("n64", ConsoleFolderAliases.pickExistingFolder(listOf("n64"), "nintendo_64"))
        ConsoleAliasRegistry.overrides = mapOf("custom_thing" to ConsoleAliasInfo("TH", emptyList()))
        assertEquals("TH", ConsoleFormatter.getConsoleShortName("custom_thing"))
        assertEquals("th", ConsoleFolderAliases.pickExistingFolder(listOf("th"), "custom_thing"))
    }

    @Test
    fun `matchingFolders lists every match with the default name first`() {
        assertEquals(listOf("Gameboy Advance", "gba", "GBA"), ConsoleFolderAliases.matchingFolders(folders, "nintendo_gameboy_advance"))
        assertEquals(listOf("Playstation", "psx"), ConsoleFolderAliases.matchingFolders(folders, "sony_playstation"))
    }

    @Test
    fun `bare console ids keep their own entry instead of the trimmed one`() {
        // Trimming to the first underscore would turn SNES into NES's key.
        assertEquals("SNES", ConsoleFormatter.getDefaultShortName("super_nintendo_entertainment_system"))
        assertEquals("NES", ConsoleFormatter.getDefaultShortName("nintendo_entertainment_system"))
        assertEquals(false, ConsoleFolderAliases.matches("super_nintendo_entertainment_system", "nes"))
        assertEquals(true, ConsoleFolderAliases.matches("super_nintendo_entertainment_system", "snes"))
        assertEquals(true, ConsoleFolderAliases.matches("nintendo_entertainment_system", "famicom"))
        assertEquals("GBA", ConsoleFormatter.getDefaultShortName("nintendo_gameboy_advance"))
    }

    @Test
    fun `folder names keep the first word of bare console ids`() {
        assertEquals("Super Nintendo Entertainment System", ConsoleFormatter.getConsoleFolderName("super_nintendo_entertainment_system"))
        assertEquals("Playstation 2", ConsoleFormatter.getConsoleFolderName("playstation_2"))
        assertEquals("Gameboy Advance", ConsoleFormatter.getConsoleFolderName("nintendo_gameboy_advance"))
        assertEquals("Xbox", ConsoleFormatter.getConsoleFolderName("microsoft_xbox"))
        assertEquals(false, ConsoleFolderAliases.matches("super_nintendo_entertainment_system", "nintendo_entertainment_system"))
    }

    @Test
    fun `matchingFolders is empty when nothing matches`() {
        assertEquals(emptyList<String>(), ConsoleFolderAliases.matchingFolders(folders, "nintendo_64"))
        assertNull(ConsoleFolderAliases.pickExistingFolder(folders, "nintendo_64"))
    }

    @Test
    fun `pickExistingFolder returns the best match`() {
        assertEquals("Gameboy Advance", ConsoleFolderAliases.pickExistingFolder(folders, "nintendo_gameboy_advance"))
        assertEquals("psx", ConsoleFolderAliases.pickExistingFolder(listOf("psx", "misc"), "sony_playstation"))
    }
}
