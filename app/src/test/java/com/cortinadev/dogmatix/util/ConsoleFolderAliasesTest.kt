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
