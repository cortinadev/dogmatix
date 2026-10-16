package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentIdsTest {

    @Test
    fun `volume and name of path ids, nothing for opaque ones`() {
        assertEquals("primary", DocumentIds.volumeOf("primary:ROMs/snes"))
        assertEquals("6A3B-BB88", DocumentIds.volumeOf("6A3B-BB88:"))
        assertNull(DocumentIds.volumeOf("1234"))
        assertEquals("Game.sfc", DocumentIds.nameOf("primary:ROMs/snes/Game.sfc"))
        assertEquals("1234", DocumentIds.nameOf("1234"))
    }

    @Test
    fun `within compares whole segments and volumes`() {
        assertTrue(DocumentIds.isWithin("primary:ROMs", "primary:ROMs"))
        assertTrue(DocumentIds.isWithin("primary:ROMs", "primary:ROMs/snes/Game.sfc"))
        assertFalse(DocumentIds.isWithin("primary:ROMs", "primary:ROMs2/snes"))
        assertFalse(DocumentIds.isWithin("primary:ROMs", "6A3B-BB88:ROMs/snes"))
        assertTrue(DocumentIds.isWithin("6A3B-BB88:", "6A3B-BB88:ROMS/psx"))
        assertFalse(DocumentIds.isWithin("opaque", "opaque/child"))
    }

    @Test
    fun `relative paths, parents and children`() {
        assertEquals("snes/Game.sfc", DocumentIds.relativePath("primary:ROMs", "primary:ROMs/snes/Game.sfc"))
        assertEquals("", DocumentIds.relativePath("primary:ROMs", "primary:ROMs"))
        assertEquals("ROMS/psx", DocumentIds.relativePath("6A3B-BB88:", "6A3B-BB88:ROMS/psx"))
        assertNull(DocumentIds.relativePath("primary:ROMs", "primary:Other/x"))
        assertEquals("primary:ROMs/snes", DocumentIds.parentOf("primary:ROMs/snes/Game.sfc"))
        assertEquals("primary:", DocumentIds.parentOf("primary:ROMs"))
        assertNull(DocumentIds.parentOf("primary:"))
        assertEquals("primary:ROMs/snes/Game.sfc", DocumentIds.child("primary:ROMs", "snes/Game.sfc"))
        assertEquals("6A3B-BB88:psx", DocumentIds.child("6A3B-BB88:", "/psx/"))
        assertEquals("primary:ROMs", DocumentIds.child("primary:ROMs", ""))
    }

    @Test
    fun `rebase carries a folder to the same place under another root`() {
        assertEquals("6A3B-BB88:ROMS/snes", DocumentIds.rebase("primary:ROMs/snes", "primary:ROMs", "6A3B-BB88:ROMS"))
        assertNull(DocumentIds.rebase("primary:Other/snes", "primary:ROMs", "6A3B-BB88:ROMS"))
    }
}
