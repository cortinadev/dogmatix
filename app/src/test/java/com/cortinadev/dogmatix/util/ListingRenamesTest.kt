package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingRenamesTest {

    @Test
    fun `rows once named after their whole link pair with their last part`() {
        val old = listOf(
            "/roms/snes/Game%20(Japan).zip",                     // h5ai / IIS
            "./Zelda:%20Link.zip",                               // Apache, name with a colon
            "https://cdn.example/snes/Other%20(USA).zip?dl=1",   // another server
            "sub/Sub%20(Europe).zip",
            "Plain%20(USA).zip"                                  // a plain link never changed
        )
        val new = listOf("Game%20(Japan).zip", "Zelda:%20Link.zip", "Other%20(USA).zip", "Sub%20(Europe).zip", "Plain%20(USA).zip")
        assertEquals(
            mapOf(
                "/roms/snes/Game%20(Japan).zip" to "Game%20(Japan).zip",
                "./Zelda:%20Link.zip" to "Zelda:%20Link.zip",
                "https://cdn.example/snes/Other%20(USA).zip?dl=1" to "Other%20(USA).zip",
                "sub/Sub%20(Europe).zip" to "Sub%20(Europe).zip"
            ),
            ListingRenames.pairs(old, new)
        )
    }

    @Test
    fun `names are compared decoded, so another encoding still pairs`() {
        assertEquals(
            mapOf("/roms/Game%20%28Japan%29.zip" to "Game (Japan).zip"),
            ListingRenames.pairs(listOf("/roms/Game%20%28Japan%29.zip"), listOf("Game (Japan).zip"))
        )
    }

    @Test
    fun `a last part that two old names share, or two new ones, pairs none of them`() {
        assertTrue(ListingRenames.pairs(listOf("/a/Game.zip", "/b/Game.zip"), listOf("Game.zip")).isEmpty())
        assertTrue(ListingRenames.pairs(listOf("/a/Game.zip"), listOf("Game.zip", "Game.zip")).isEmpty())
        // The others still pair.
        assertEquals(
            mapOf("/a/Other.zip" to "Other.zip"),
            ListingRenames.pairs(listOf("/a/Game.zip", "/b/Game.zip", "/a/Other.zip"), listOf("Game.zip", "Other.zip"))
        )
    }

    @Test
    fun `a new name some old row already had is left to that row`() {
        assertTrue(ListingRenames.pairs(listOf("/a/Game.zip", "Game.zip"), listOf("Game.zip")).isEmpty())
    }

    @Test
    fun `names that did not change, torrent paths and links no longer read pair nothing`() {
        assertTrue(ListingRenames.pairs(listOf("Folder/Game.iso", "Disc 1/Track 01.bin"), listOf("Folder/Game.iso", "Disc 1/Track 01.bin")).isEmpty())
        assertTrue(ListingRenames.pairs(listOf("javascript:void(0)", "#top", "/files/"), listOf("void(0)", "top")).isEmpty())
        assertTrue(ListingRenames.pairs(listOf("/roms/Game.zip"), listOf("Other.zip")).isEmpty())
        assertTrue(ListingRenames.pairs(emptyList(), listOf("Game.zip")).isEmpty())
    }

    private val busy = setOf("DOWNLOADING", "QUEUED", "COPYING", "UNZIPPING")
    private val pairs = mapOf("/roms/A.zip" to "A.zip", "/roms/B.zip" to "B.zip", "/roms/C.zip" to "C.zip", "/roms/D.zip" to "D.zip", "/roms/E.zip" to "E.zip")

    @Test
    fun `a finished or stopped download of this console follows its file`() {
        val history = mapOf(
            "/roms/A.zip" to ListingRenames.Download("snes", "COMPLETED"),
            "/roms/B.zip" to ListingRenames.Download("snes", "PAUSED"),
            "/roms/C.zip" to ListingRenames.Download("snes", "FAILED")
        )
        assertEquals(
            mapOf("/roms/A.zip" to "A.zip", "/roms/B.zip" to "B.zip", "/roms/C.zip" to "C.zip"),
            ListingRenames.forDownloads(pairs, "snes", history, busy)
        )
    }

    @Test
    fun `running, foreign or clashing downloads, and files never downloaded, stay as they are`() {
        val history = mapOf(
            "/roms/A.zip" to ListingRenames.Download("snes", "DOWNLOADING"),  // its job writes under the old name
            "/roms/B.zip" to ListingRenames.Download("gb", "COMPLETED"),      // another console's download
            "/roms/C.zip" to ListingRenames.Download("snes", "COMPLETED"),
            "C.zip" to ListingRenames.Download("nes", "COMPLETED")            // the new name is taken
        )
        // D and E have no Downloads row: nothing to carry.
        assertTrue(ListingRenames.forDownloads(pairs, "snes", history, busy).isEmpty())
    }
}
