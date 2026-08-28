package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryKeysTest {

    private val asterix = "Asterix & Obelix (Europe) (En,Fr,De,Es).zip"

    @Test
    fun `same file name under another console's folder does not count`() {
        val keys = LibraryKeys.keysFor(LibraryKeys.folderScope("snes"), asterix).toSet()
        assertTrue(LibraryKeys.isOwned("nintendo_super_nintendo_entertainment_system", asterix, keys))
        assertFalse(LibraryKeys.isOwned("nintendo_gameboy_color", asterix, keys))
    }

    @Test
    fun `folder aliases, root, custom dir and fresh downloads all count for their console`() {
        val gbc = "nintendo_gameboy_color"
        assertTrue(LibraryKeys.isOwned(gbc, asterix, LibraryKeys.keysFor(LibraryKeys.folderScope("Gameboy Color"), asterix).toSet()))
        assertTrue(LibraryKeys.isOwned(gbc, asterix, LibraryKeys.keysFor(LibraryKeys.folderScope("GBC"), asterix).toSet()))
        assertTrue(LibraryKeys.isOwned(gbc, asterix, LibraryKeys.keysFor(LibraryKeys.ROOT_SCOPE, asterix).toSet()))
        assertTrue(LibraryKeys.isOwned(gbc, asterix, LibraryKeys.keysFor(LibraryKeys.customScope(gbc), asterix).toSet()))
        assertTrue(LibraryKeys.isOwned(gbc, asterix, LibraryKeys.keysFor(LibraryKeys.consoleScope(gbc), asterix).toSet()))
        assertFalse(LibraryKeys.isOwned(gbc, asterix, LibraryKeys.keysFor(LibraryKeys.customScope("sony_psp"), asterix).toSet()))
    }

    @Test
    fun `extracted content matches by base name and url encoding is ignored`() {
        val onDisk = LibraryKeys.keysFor(LibraryKeys.folderScope("gba"), "Metroid Fusion (USA).gba").toSet()
        assertTrue(LibraryKeys.isOwned("nintendo_gameboy_advance", "Metroid%20Fusion%20(USA).zip", onDisk))
        assertEquals(listOf("gba|metroid fusion (usa).gba", "gba|metroid fusion (usa)"), onDisk.toList().sorted().reversed())
    }
}
