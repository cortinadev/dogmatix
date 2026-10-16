package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RommCoversTest {

    @Test
    fun `builds cover urls for every RomM shape`() {
        assertEquals(
            "http://romm.local:8090/assets/romm/resources/roms/3/15/cover/small.png?ts=1",
            RommCovers.url("http://romm.local:8090/", "/assets/romm/resources/roms/3/15/cover/small.png?ts=1")
        )
        assertEquals("http://romm.local:8090/assets/romm/resources/roms/3/15/cover/small.png", RommCovers.url("http://romm.local:8090", "roms/3/15/cover/small.png"))
        assertEquals("http://romm.local/assets/romm/resources/roms/1/My%20Game/cover.png", RommCovers.url("http://romm.local", "roms/1/My Game/cover.png"))
        assertEquals("https://images.igdb.com/x.jpg", RommCovers.url("http://romm.local", "https://images.igdb.com/x.jpg"))
        assertNull(RommCovers.url("http://romm.local", " "))
        assertNull(RommCovers.url("", "roms/1/cover.png"))
    }

    @Test
    fun `reads the extension and what ES-DE can show`() {
        assertEquals("png", RommCovers.extension("/assets/romm/resources/roms/3/15/cover/big.png?ts=2026-10-01T10:00:00"))
        assertEquals("webp", RommCovers.extension("roms/3/15/cover/big.WEBP"))
        assertEquals("png", RommCovers.extension("roms/3/15/cover/big"))
        assertTrue(RommCovers.esdeReadable("jpg"))
        assertFalse(RommCovers.esdeReadable("webp"))
    }

    @Test
    fun `names media after the file, and m3u folders after their name`() {
        assertEquals("Super Mario World (USA)", RommCovers.stemOf("Super Mario World (USA).sfc", isDirectory = false))
        assertEquals("Final Fantasy VII (USA)", RommCovers.stemOf("Final Fantasy VII (USA).m3u", isDirectory = true))
        assertNull(RommCovers.stemOf("Disc images", isDirectory = true))
        assertNull(RommCovers.stemOf("${DgmtxFile.SHORTCUT_NAME}.${DgmtxFile.EXTENSION}", isDirectory = false))
        assertNull(RommCovers.stemOf(".hidden", isDirectory = false))
    }

    @Test
    fun `plans the missing covers, keeps what ES-DE has unless asked`() {
        val games = listOf(
            RommCovers.DiskGame("snes", listOf("snes"), "Super Mario World (USA)"),
            RommCovers.DiskGame("snes", listOf("snes"), "super mario world (usa)"),
            RommCovers.DiskGame("snes", listOf("snes"), "Zelda (USA)"),
            RommCovers.DiskGame("snes", listOf("snes"), "Not On Server")
        )
        val covers = mapOf("Super Mario World (USA)" to "roms/1/cover.png", "Zelda (USA)" to "roms/2/cover.png")
        val coverFor = { _: String, stem: String -> covers[stem] }
        val existing = mapOf("snes" to setOf("zelda (usa)"))

        val plan = RommCovers.planEsde(games, existing, overwrite = false, coverFor = coverFor)
        assertEquals(listOf("Super Mario World (USA)"), plan.jobs.map { it.stem })
        assertEquals(1, plan.kept)

        val replacing = RommCovers.planEsde(games, existing, overwrite = true, coverFor = coverFor)
        assertEquals(listOf("Super Mario World (USA)", "Zelda (USA)"), replacing.jobs.map { it.stem })
        assertEquals(0, replacing.kept)
    }
}
