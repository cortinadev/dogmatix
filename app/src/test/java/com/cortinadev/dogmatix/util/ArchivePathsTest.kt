package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun allocatorNeverHandsOutWhatIsAlreadyInTheFolder() {
        val allocator = ArchivePaths.Allocator(listOf("Game.bin", "Game (2).bin"))
        assertEquals("game (3).BIN", allocator.allocate("game.BIN"))
        assertEquals("Game.cue", allocator.allocate("Game.cue"))
        allocator.reserve("Disc 1/track01.bin")
        assertEquals("Disc 1/Track01 (2).bin", allocator.allocate("Disc 1/Track01.bin"))
        assertEquals("Disc 2/track01.bin", allocator.allocate("Disc 2/track01.bin"))
    }

    @Test
    fun extractionRoomAndFailures() {
        val mb = 1024L * 1024
        assertTrue(ArchiveExtraction.fits(100 * mb, null))
        assertTrue(ArchiveExtraction.fits(100 * mb, 164 * mb))
        assertFalse(ArchiveExtraction.fits(100 * mb, 163 * mb))
        assertEquals(DownloadFailure.EXTRACTION_NO_SPACE, ArchiveExtraction.writeFailure(StorageProblem.NO_SPACE, null))
        // What the error says beats what the folder looks like afterwards.
        assertEquals(DownloadFailure.EXTRACTION_NO_SPACE, ArchiveExtraction.writeFailure(StorageProblem.NO_SPACE, StorageProblem.VOLUME_GONE))
        assertEquals(DownloadFailure.STORAGE_GONE, ArchiveExtraction.writeFailure(null, StorageProblem.VOLUME_GONE))
        assertEquals(DownloadFailure.PERMISSION_LOST, ArchiveExtraction.writeFailure(StorageProblem.PERMISSION_LOST, null))
        assertEquals(DownloadFailure.STORAGE, ArchiveExtraction.writeFailure(null, null))
    }

    @Test
    fun anEntryNeverReplacesWhatIsInTheFolder() {
        assertEquals(ArchiveExtraction.Existing.NONE, ArchiveExtraction.existing(10, null, isDirectory = false, sameBytes = false))
        // The same file (an earlier unpack): taken as it is.
        assertEquals(ArchiveExtraction.Existing.SAME, ArchiveExtraction.existing(10, 10, isDirectory = false, sameBytes = true))
        // Same size, other bytes; other size; a folder of that name: the entry gets another name.
        assertEquals(ArchiveExtraction.Existing.OTHER, ArchiveExtraction.existing(10, 10, isDirectory = false, sameBytes = false))
        assertEquals(ArchiveExtraction.Existing.OTHER, ArchiveExtraction.existing(10, 9, isDirectory = false, sameBytes = true))
        assertEquals(ArchiveExtraction.Existing.OTHER, ArchiveExtraction.existing(10, null, isDirectory = true, sameBytes = false))
    }
}
