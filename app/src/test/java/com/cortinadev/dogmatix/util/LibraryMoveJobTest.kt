package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMoveJobTest {

    private val sd = "content://ext/tree/6A3B%3AROMS/document/6A3B%3AROMS"
    private val main = "content://ext/tree/primary%3AROMs/document/primary%3AROMs"
    private val picked = "content://ext/tree/primary%3AGames%2Fsnes"

    private val consoleMove = MoveJob(
        id = "j1",
        type = ActionTypes.SMART_STORAGE,
        subject = "SNES",
        items = listOf(
            MoveItem("$main%2Fsnes", main, "snes", sd, "snes", "nintendo_snes"),
            // A folder picked in Sources: no parent in its grant, only its contents move.
            MoveItem(picked, null, "snes", sd, "gba", "nintendo_gba")
        ),
        after = MoveSettings(),
        before = MoveSettings(consoleDirs = mapOf("nintendo_snes" to "", "nintendo_gba" to picked)),
        pausedDownloads = listOf("Game (USA).zip")
    )

    @Test
    fun `jobs and undo data survive a round trip`() {
        assertEquals(consoleMove, MoveJobCodec.decodeJob(MoveJobCodec.encodeJob(consoleMove)))
        val root = MoveJob(
            type = ActionTypes.LIBRARY_MOVE, subject = ".../Games",
            items = listOf(MoveItem("$main%2Fpsx", main, "psx", sd, "psx")),
            after = MoveSettings(sd, mapOf("x" to "y")), before = MoveSettings(main), undoOf = 42, oldRoot = main
        )
        val undo = MoveUndo(root, MoveSettings(sd, mapOf("a" to "")))
        assertEquals(undo, MoveJobCodec.decodeUndo(MoveJobCodec.encodeUndo(undo)))
    }

    @Test
    fun `broken documents read as nothing`() {
        assertNull(MoveJobCodec.decodeJob("{}"))
        assertNull(MoveJobCodec.decodeJob("not json"))
        assertNull(MoveJobCodec.decodeJob(null))
        assertNull(MoveJobCodec.decodeUndo("""{"job":{}}"""))
    }

    @Test
    fun `the move back reverses items and settings`() {
        val applied = MoveSettings(consoleDirs = mapOf("nintendo_snes" to "$sd%2Fsnes", "nintendo_gba" to "$sd%2Fgba"))
        val back = MoveJobs.reverse(consoleMove, applied) { "${it.targetParent}%2F${it.targetName}" }
        assertEquals(
            listOf(
                MoveItem("$sd%2Fsnes", sd, "snes", main, "snes", "nintendo_snes"),
                // Back into the picked folder itself.
                MoveItem("$sd%2Fgba", sd, "gba", picked, "", "nintendo_gba")
            ),
            back.items
        )
        assertEquals(consoleMove.before, back.after)
        assertEquals(applied, back.before)
        assertNull(back.undoOf)
    }

    @Test
    fun `consoles that got a folder of their own lose it on the way back`() {
        val job = consoleMove.copy(before = MoveSettings(consoleDirs = mapOf("nintendo_snes" to "")))
        val applied = MoveSettings(consoleDirs = mapOf("nintendo_snes" to "x", "nintendo_gba" to "y"))
        val back = MoveJobs.reverse(job, applied) { "dest" }
        assertEquals(mapOf("nintendo_snes" to "", "nintendo_gba" to ""), back.after.consoleDirs)
    }

    @Test
    fun `items gone from their destination are not moved back`() {
        val back = MoveJobs.reverse(consoleMove, MoveSettings()) { if (it.consoleId == "nintendo_snes") null else "here" }
        assertEquals(listOf("nintendo_gba"), back.items.map { it.consoleId })
    }

    @Test
    fun `a rerun after an interruption carries on file by file`() {
        val never = { throw AssertionError("sampled bytes asked for files of different sizes") }
        // A half-copied part in the source (an older move of the other way): deleted, never moved.
        assertEquals(LibraryMoveRules.FileStep.DROP_PART, LibraryMoveRules.fileStep("Game.iso.dogmatix-part", 10, null, never))
        // Nothing there yet (or only a stale part of it, which goes first): copied again from the start.
        assertEquals(LibraryMoveRules.FileStep.MOVE, LibraryMoveRules.fileStep("Game.iso", 10, null, never))
        assertEquals(listOf("Game.iso.dogmatix-part"), LibraryMoveRules.staleParts(listOf("Game.iso.dogmatix-part", "Other.iso.dogmatix-part", "Game.iso.txt"), "Game.iso"))
        // Renamed into place but the original not deleted yet: the same file, so the original goes.
        assertEquals(LibraryMoveRules.FileStep.ALREADY_THERE, LibraryMoveRules.fileStep("Game.iso", 10, 10) { true })
        // Same size but other bytes, another size, or a folder of that name: both stay.
        assertEquals(LibraryMoveRules.FileStep.CONFLICT, LibraryMoveRules.fileStep("Game.iso", 10, 10) { false })
        assertEquals(LibraryMoveRules.FileStep.CONFLICT, LibraryMoveRules.fileStep("Game.iso", 10, 9, never))
        assertEquals(LibraryMoveRules.FileStep.CONFLICT, LibraryMoveRules.fileStep("Game.iso", 10, -1, never))
        // A size the provider does not report is never taken for the same file.
        assertEquals(LibraryMoveRules.FileStep.CONFLICT, LibraryMoveRules.fileStep("Game.iso", -1, -1, never))
    }

    @Test
    fun `an item never lands inside itself`() {
        assertTrue(MoveJobs.landsInside("primary:ROMs/snes", "primary:ROMs", "snes"))
        assertTrue(MoveJobs.landsInside("primary:ROMs", "primary:ROMs/new", "ROMs"))
        assertFalse(MoveJobs.landsInside("primary:ROMs/snes", "6A3B-BB88:ROMS", "snes"))
        assertFalse(MoveJobs.landsInside("6A3B-BB88:ROMS/snes", "primary:Games/snes", ""))
    }
}
