package com.cortinadev.dogmatix.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RommJsonTest {

    private fun json(text: String) = JsonParser.parseString(text)

    @Test
    fun `reads a RomM 5 page with files, hashes, covers and play data`() {
        val page = json(
            """
            {"items": [{
              "id": 15, "platform_id": 3, "fs_name": "Final Fantasy VII (USA)", "name": "Final Fantasy VII",
              "crc_hash": null, "md5_hash": "", "sha1_hash": null,
              "path_cover_small": "/assets/romm/resources/roms/3/15/cover/small.png?ts=2026-10-01",
              "path_cover_large": "/assets/romm/resources/roms/3/15/cover/big.png?ts=2026-10-01",
              "files": [
                {"file_name": "Final Fantasy VII (USA) (Disc 1).chd", "crc_hash": "ABCD1234", "md5_hash": null},
                {"file_name": "Final Fantasy VII (USA) (Disc 2).chd", "sha1_hash": "FFEE"}
              ],
              "rom_user": {"status": "finished", "backlogged": false, "now_playing": true, "rating": 0}
            }], "total": null, "limit": 250, "offset": 0}
            """
        )
        val roms = RommJson.catalogRoms(page)
        assertEquals(1, roms.size)
        val rom = roms.single()
        assertEquals(15, rom.romId)
        assertEquals(3, rom.platformId)
        assertEquals("Final Fantasy VII (USA)", rom.fsName)
        assertEquals(listOf("Final Fantasy VII (USA) (Disc 1).chd", "Final Fantasy VII (USA) (Disc 2).chd"), rom.fileNames)
        assertEquals(listOf("abcd1234", "ffee"), rom.hashes)
        assertTrue(rom.coverSmall.startsWith("/assets/romm/resources/roms/3/15/cover/small.png"))
        assertEquals(RommUserProps(status = "finished", backlogged = false, nowPlaying = true), rom.props)
    }

    @Test
    fun `reads the older shapes - bare array, path_cover_s, file_name`() {
        val roms = RommJson.catalogRoms(json("""[{"id": "7", "file_name": "Tetris (World).gb", "path_cover_s": "roms/1/7/cover/small.png"}, {"name": "no id"}]"""))
        assertEquals(1, roms.size)
        assertEquals(7, roms[0].romId)
        assertEquals("Tetris (World).gb", roms[0].fsName)
        assertEquals("roms/1/7/cover/small.png", roms[0].coverSmall)
        assertEquals("", roms[0].coverLarge)
        assertEquals(RommUserProps(), roms[0].props)
        assertTrue(RommJson.catalogRoms(json("""{"detail": "Not found"}""")).isEmpty())
    }

    @Test
    fun `reads a game's details with genres, rating and play data`() {
        val info = RommJson.gameInfo(
            json(
                """
                {"id": 15, "name": "Final Fantasy VII", "summary": "Cloud and friends.",
                 "metadatum": {"genres": ["Role-playing (RPG)", "Adventure", "role-playing (rpg)"], "average_rating": 91.4},
                 "rom_user": {"status": "COMPLETED_100", "backlogged": true, "now_playing": false}}
                """
            )
        )!!
        assertEquals("Cloud and friends.", info.summary)
        assertEquals(listOf("Role-playing (RPG)", "Adventure"), info.genres)
        assertEquals(91, info.ratingPercent)
        assertEquals("completed_100", info.props.status)
        assertTrue(info.props.backlogged)
        assertNull(RommJson.gameInfo(json("""{"name": "no id"}""")))
    }

    @Test
    fun `falls back to IGDB data and scales ratings out of ten`() {
        val info = RommJson.gameInfo(json("""{"id": 2, "igdb_metadata": {"summary": "From IGDB", "genres": [{"name": "Shooter"}], "total_rating": 8.2}}"""))!!
        assertEquals("From IGDB", info.summary)
        assertEquals(listOf("Shooter"), info.genres)
        assertEquals(82, info.ratingPercent)
        assertNull(RommJson.ratingPercent(json("0")))
        assertEquals(100, RommJson.ratingPercent(json("150")))
    }

    @Test
    fun `reads the props answer of RomM 5 and the wrapped one of RomM 4`() {
        assertEquals(RommUserProps("retired", false, false), RommJson.props(json("""{"id": 1, "rom_id": 2, "status": "retired", "backlogged": false, "now_playing": false}""")))
        assertEquals(RommUserProps(null, true, false), RommJson.props(json("""{"data": {"status": null, "backlogged": true}}""")))
        assertEquals(RommUserProps(null, false, false), RommJson.props(json("""{"status": "made-up"}""")))
        assertNull(RommJson.props(json("""{"detail": "error"}""")))
    }

    @Test
    fun `reads collections with their games, owner and favourite flag`() {
        val list = RommJson.collections(
            json(
                """
                [{"id": 1, "name": "Favorites", "rom_ids": [3, 4, "5"], "user_id": 9, "is_favorite": true},
                 {"id": 2, "name": "Old style", "roms": [{"id": 8}, {"id": 9}]},
                 {"name": "no id"}]
                """
            )
        )
        assertEquals(2, list.size)
        assertEquals(setOf(3, 4, 5), list[0].romIds)
        assertEquals(9, list[0].ownerId)
        assertTrue(list[0].isFavourite)
        assertEquals(setOf(8, 9), list[1].romIds)
        assertNull(list[1].ownerId)
        assertFalse(list[1].isFavourite)
        assertEquals(12, RommJson.id(json("""{"id": 12, "username": "me"}""")))
    }
}
