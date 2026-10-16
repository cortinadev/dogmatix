package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchivePathsTest {

    @Test
    fun keepsFoldersInsteadOfFlattening() {
        assertEquals("Disc 1/track01.bin", ArchivePaths.safeRelativePath("Disc 1/track01.bin"))
        assertEquals("Disc 2/track01.bin", ArchivePaths.safeRelativePath("Disc 2\\track01.bin"))
        assertEquals("Game.cue", ArchivePaths.safeRelativePath("Game.cue"))
    }

    @Test
    fun rejectsPathsThatEscapeTheDestination() {
        assertNull(ArchivePaths.safeRelativePath("../evil.bin"))
        assertNull(ArchivePaths.safeRelativePath("Game/../../evil.bin"))
        assertNull(ArchivePaths.safeRelativePath("/etc/passwd"))
        assertNull(ArchivePaths.safeRelativePath("\\Windows\\evil.dll"))
        assertNull(ArchivePaths.safeRelativePath("C:/evil.bin"))
        assertNull(ArchivePaths.safeRelativePath(""))
        assertNull(ArchivePaths.safeRelativePath("./"))
    }

    @Test
    fun cleansSegmentsStorageWouldReject() {
        assertEquals("Game/a_b_c.bin", ArchivePaths.safeRelativePath("./Game//a:b?c.bin"))
        assertEquals("Game/file.bin", ArchivePaths.safeRelativePath("Game. /file.bin"))
        assertEquals("tab_name.bin", ArchivePaths.safeRelativePath("tab\tname.bin"))
        assertNull(ArchivePaths.safeRelativePath("Game/.../file.bin"))
    }

    @Test
    fun allocatorKeepsCollidingEntriesApart() {
        val allocator = ArchivePaths.Allocator()
        assertEquals("Disc 1/track01.bin", allocator.allocate("Disc 1/track01.bin"))
        assertEquals("Disc 2/track01.bin", allocator.allocate("Disc 2/track01.bin"))
        // Case-insensitive storage would merge these two.
        assertEquals("Game.bin", allocator.allocate("Game.bin"))
        assertEquals("game (2).BIN", allocator.allocate("game.BIN"))
        assertEquals("GAME (3).bin", allocator.allocate("GAME.bin"))
    }

    @Test
    fun allocatorKeepsNamesWithoutExtension() {
        val allocator = ArchivePaths.Allocator()
        allocator.allocate("Disc 1/README")
        assertEquals("Disc 1/readme (2)", allocator.allocate("Disc 1/readme"))
    }
}
