package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RommMatchingTest {

    private fun rom(id: Int, fsName: String, files: List<String> = emptyList(), hashes: List<String> = emptyList()) =
        RommCatalogRom(id, 1, fsName, "", files, hashes, "roms/$id/cover/small.png", "", RommUserProps())

    private val index = RommIndex(
        listOf(
            RommMatching.entryOf("snes", rom(1, "Super Mario World (USA).zip")),
            RommMatching.entryOf("snes", rom(2, "Chrono Trigger (Europe).sfc", hashes = listOf("deadbeef"))),
            RommMatching.entryOf("psx", rom(3, "Final Fantasy VII (USA)", listOf("Final Fantasy VII (USA) (Disc 1).chd", "Final Fantasy VII (USA) (Disc 2).chd"))),
            RommMatching.entryOf("nes", rom(4, "Super Mario Bros. 3 (USA)"))
        )
    )

    @Test
    fun `file keys drop the extension, the folders and the URL escapes`() {
        assertEquals("super mario world (usa)", RommMatching.fileKey("Super Mario World (USA).sfc"))
        assertEquals("super mario world (usa)", RommMatching.fileKey("snes/Super%20Mario%20World%20(USA).zip"))
        // A folder name with a dot is not an extension.
        assertEquals("super mario bros. 3 (usa)", RommMatching.fileKey("Super Mario Bros. 3 (USA)"))
    }

    @Test
    fun `group keys are the library's`() {
        val (name, _) = FileParsingUtils.extractNameAndTags("Final Fantasy VII (USA) (Disc 1).chd")
        assertEquals(VersionGrouping.groupKey(name), RommMatching.groupKeyOf("Final Fantasy VII (USA)"))
        assertEquals("super mario world", RommMatching.groupKeyOf("Super Mario World (USA).zip"))
    }

    @Test
    fun `pairs a file by name without extension, and only in its console`() {
        assertEquals(1, index.forFile("snes", "Super Mario World (USA).sfc")?.romId)
        assertNull(index.forFile("gba", "Super Mario World (USA).sfc"))
        assertNull(index.forFile("snes", "Super Mario World (Europe).sfc"))
        // A disc of a folder ROM.
        assertEquals(3, index.forFile("psx", "Final Fantasy VII (USA) (Disc 2).chd")?.romId)
        assertEquals(4, index.forFile("nes", "Super Mario Bros. 3 (USA).nes")?.romId)
    }

    @Test
    fun `a multi-file game uploaded as one zip pairs by release`() {
        val zipped = RommIndex(listOf(RommMatching.entryOf("psx", rom(9, "Final Fantasy VII (USA).zip"))))
        assertEquals(9, zipped.forFile("psx", "Final Fantasy VII (USA) (Disc 2).cue")?.romId)
        assertEquals(9, zipped.forFile("psx", "Final Fantasy VII (USA) (Disc 1) (Track 02).bin")?.romId)
        assertNull(zipped.forFile("psx", "Final Fantasy VII (Europe) (Disc 1).cue"))
        assertEquals("final fantasy vii (usa)", RommMatching.releaseKey("Final Fantasy VII (USA) (Disc 1) (Track 2).bin"))
        // A key is never stripped twice: the dot of "Vol.2" stays.
        assertEquals("game vol.2 (usa)", RommMatching.releaseOfKey("game vol.2 (usa) (disc 1)"))
    }

    @Test
    fun `a hash wins over the name`() {
        assertEquals(2, index.forFile("snes", "Renamed.sfc", listOf("DEADBEEF"))?.romId)
        assertEquals(1, index.forFile("snes", "Super Mario World (USA).sfc", listOf("00000000"))?.romId)
    }

    @Test
    fun `a game is on the server when any version is`() {
        assertEquals(1, index.forGame("snes", "super mario world", listOf("Super Mario World (Japan).sfc"))?.romId)
        assertEquals(2, index.forGame("snes", "nothing in common", listOf("Chrono Trigger (Europe).zip"))?.romId)
        assertNull(index.forGame("snes", "zelda", listOf("Zelda (USA).sfc")))
    }

    @Test
    fun `reads the rom id of a download from the server`() {
        assertEquals(7, RommMatching.romIdFromUrl("http://romm.local:8090/", "http://romm.local:8090/api/roms/7/content/Game.iso"))
        assertNull(RommMatching.romIdFromUrl("http://other", "http://romm.local:8090/api/roms/7/content/Game.iso"))
        assertNull(RommMatching.romIdFromUrl("", "http://romm.local:8090/api/roms/7/content/Game.iso"))
    }

    @Test
    fun `lists the owned games the server lacks`() {
        val owned = listOf(
            RommOwnedGame("snes", "super mario world", "Super Mario World", listOf("Super Mario World (Europe).sfc")),
            RommOwnedGame("snes", "zelda", "Zelda", listOf("Zelda (USA).sfc"))
        )
        assertEquals(listOf("zelda"), RommMatching.missingOnServer(owned, index).map { it.groupKey })
        assertTrue(RommMatching.missingOnServer(owned, RommIndex.EMPTY).size == 2)
        assertEquals(listOf(3), index.forRomId(3).map { it.romId })
    }
}
