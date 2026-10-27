package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.CollectionFixtures.PSX
import com.cortinadev.dogmatix.util.CollectionFixtures.file
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscSetCleanupTest {

    private fun psx(path: String, size: Long = 1_000, refs: List<String>? = null) = file(path, size, console = PSX, top = "psx", refs = refs)

    @Test
    fun `a sheet takes its tracks along, unless another sheet still needs them`() {
        val cue = psx("Game (USA).cue", 90, refs = MultiDisc.cueFiles("FILE \".\\TRACK01.BIN\" BINARY\nFILE \"track02.bin\" BINARY\n"))
        val other = psx("Game (USA) (Rev 1).cue", 90, refs = listOf("track02.bin"))
        val all = listOf(cue, other, psx("track01.bin", 9_000), psx("track02.bin", 9_000), psx("Tekken (USA).bin", 9_000))
        val plan = DiscSetCleanup.plan(listOf(cue), all)
        assertEquals(listOf("Game (USA).cue", "track01.bin"), plan.delete.map { it.name })
        assertTrue(plan.rewrites.isEmpty())
        // What the confirmation says: one asked for, one taken along, and the bytes of both.
        assertEquals(1, plan.asked)
        assertEquals(1, plan.extra)
        assertEquals(9_090L, plan.bytes)
        // Both sheets go: so does the shared track.
        assertEquals(setOf("Game (USA).cue", "Game (USA) (Rev 1).cue", "track01.bin", "track02.bin"), DiscSetCleanup.plan(listOf(cue, other), all).delete.map { it.name }.toSet())
    }

    @Test
    fun `a set that loses a disc gets its playlist rewritten`() {
        val m3u = psx("FF7 (USA).m3u/FF7 (USA).m3u", 60, refs = listOf("FF7 (USA) (Disc 1).chd", "FF7 (USA) (Disc 2).chd", "FF7 (USA) (Disc 3).chd"))
        val discs = (1..3).map { psx("FF7 (USA).m3u/FF7 (USA) (Disc $it).chd", 9_000) }
        val all = listOf(m3u) + discs

        val one = DiscSetCleanup.plan(listOf(discs[1]), all)
        assertEquals(listOf("FF7 (USA) (Disc 2).chd"), one.delete.map { it.name })
        assertEquals(0, one.extra)
        assertEquals("FF7 (USA) (Disc 1).chd\nFF7 (USA) (Disc 3).chd\n", one.rewrites.single().content)

        // One disc left: a one-entry playlist stays, the folder still reads as the game.
        assertEquals(listOf("FF7 (USA) (Disc 3).chd"), DiscSetCleanup.plan(discs.take(2), all).rewrites.single().entries)

        // None left: the playlist goes with them.
        val none = DiscSetCleanup.plan(discs, all)
        assertTrue(none.rewrites.isEmpty())
        assertTrue(m3u in none.delete)
        // The emptied playlist counts as taken along: the confirmation shows it.
        assertEquals(3, none.asked)
        assertEquals(1, none.extra)

        // The whole set (playlist included) asked for: nothing to rewrite.
        assertTrue(DiscSetCleanup.plan(all, all).rewrites.isEmpty())
    }

    @Test
    fun `a disc deleted with its tracks rewrites the playlist next to it`() {
        val m3u = psx("Parasite Eve (USA).m3u", 60, refs = listOf("Parasite Eve (USA) (Disc 1).cue", "Parasite Eve (USA) (Disc 2).cue"))
        val cue2 = psx("Parasite Eve (USA) (Disc 2).cue", 90, refs = listOf("Parasite Eve (USA) (Disc 2).bin"))
        val all = listOf(
            m3u, psx("Parasite Eve (USA) (Disc 1).cue", 90, refs = listOf("Parasite Eve (USA) (Disc 1).bin")), psx("Parasite Eve (USA) (Disc 1).bin", 9_000),
            cue2, psx("Parasite Eve (USA) (Disc 2).bin", 9_000)
        )
        val plan = DiscSetCleanup.plan(listOf(cue2), all)
        assertEquals(listOf("Parasite Eve (USA) (Disc 2).cue", "Parasite Eve (USA) (Disc 2).bin"), plan.delete.map { it.name })
        assertEquals(listOf("Parasite Eve (USA) (Disc 1).cue"), plan.rewrites.single().entries)
    }
}
