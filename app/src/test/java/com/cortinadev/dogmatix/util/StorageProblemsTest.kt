package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class StorageProblemsTest {

    @Test
    fun `a full volume`() {
        assertEquals(StorageProblem.NO_SPACE, StorageProblems.classify(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(StorageProblem.NO_SPACE, StorageProblems.classify(IOException("write failed: EDQUOT (Disk quota exceeded)")))
        // Wrapped, as 7-Zip and the copy helpers do.
        assertEquals(StorageProblem.NO_SPACE, StorageProblems.classify(Exception("Copy failed", IOException("ENOSPC"))))
    }

    @Test
    fun `a grant that is gone`() {
        assertEquals(
            StorageProblem.PERMISSION_LOST,
            StorageProblems.classify(SecurityException("Permission Denial: reading com.android.externalstorage.ExternalStorageProvider uri … requires that you obtain access using ACTION_OPEN_DOCUMENT or related APIs"))
        )
        assertEquals(StorageProblem.PERMISSION_LOST, StorageProblems.classify(FileNotFoundException("/storage/emulated/0/ROMs/x.bin: open failed: EACCES (Permission denied)")))
        assertEquals(StorageProblem.PERMISSION_LOST, StorageProblems.classify(SecurityException("No permission")))
    }

    @Test
    fun `a card that was pulled out`() {
        assertEquals(StorageProblem.VOLUME_GONE, StorageProblems.classify(FileNotFoundException("No root for 6A3B-BB88")))
        assertEquals(
            StorageProblem.VOLUME_GONE,
            StorageProblems.classify(IllegalArgumentException("Failed to determine if 6A3B-BB88:ROMS/snes is child of 6A3B-BB88:ROMS: java.io.FileNotFoundException: Missing file for 6A3B-BB88:ROMS/snes at /storage/6A3B-BB88/ROMS/snes"))
        )
        assertEquals(StorageProblem.VOLUME_GONE, StorageProblems.classify(IOException("write failed: EIO (I/O error)")))
        assertEquals(StorageProblem.VOLUME_GONE, StorageProblems.classify(IOException("open failed: EROFS (Read-only file system)")))
        assertEquals(StorageProblem.VOLUME_GONE, StorageProblems.classify(IOException("Transport endpoint is not connected")))
    }

    @Test
    fun `what says nothing about the storage`() {
        assertNull(StorageProblems.classify(FileNotFoundException("/storage/emulated/0/ROMs/x.bin: open failed: ENOENT (No such file or directory)")))
        assertNull(StorageProblems.classify(IllegalStateException("odd")))
        // A provider that cannot rename is not a missing card.
        assertNull(StorageProblems.classify(UnsupportedOperationException("Rename not supported")))
        assertEquals(StorageProblem.VOLUME_GONE, StorageProblems.classify(StorageProblemException(StorageProblem.VOLUME_GONE, "out")))
    }

    @Test
    fun `the state of a tree`() {
        assertNull(StorageProblems.ofTree(granted = true, volumeMounted = true, reachable = true, writable = true))
        assertEquals(StorageProblem.VOLUME_GONE, StorageProblems.ofTree(granted = true, volumeMounted = false, reachable = false, writable = false))
        // The card is out even when the grant is still listed.
        assertEquals(StorageProblem.VOLUME_GONE, StorageProblems.ofTree(granted = false, volumeMounted = false, reachable = false, writable = false))
        assertEquals(StorageProblem.PERMISSION_LOST, StorageProblems.ofTree(granted = false, volumeMounted = true, reachable = true, writable = true))
        // Mounted, granted, but the folder is not there: deleted or moved away, to be picked again.
        assertEquals(StorageProblem.PERMISSION_LOST, StorageProblems.ofTree(granted = true, volumeMounted = true, reachable = false, writable = false))
        // Another provider (no volume to ask about) that stopped answering: gone.
        assertEquals(StorageProblem.VOLUME_GONE, StorageProblems.ofTree(granted = true, volumeMounted = null, reachable = false, writable = false))
        assertEquals(StorageProblem.PERMISSION_LOST, StorageProblems.ofTree(granted = true, volumeMounted = true, reachable = true, writable = false))
    }

    @Test
    fun `the worst of a batch`() {
        assertEquals(StorageProblem.VOLUME_GONE, StorageProblems.worst(listOf(null, StorageProblem.NO_SPACE, StorageProblem.VOLUME_GONE)))
        assertEquals(StorageProblem.PERMISSION_LOST, StorageProblems.worst(listOf(StorageProblem.NO_SPACE, StorageProblem.PERMISSION_LOST)))
        assertNull(StorageProblems.worst(listOf(null, null)))
    }
}
