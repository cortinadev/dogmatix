package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RommUploadSourcesTest {

    @Test
    fun `a game of several files goes up as one zip named after it`() {
        assertTrue(RommUploadSources.needsArchive(listOf("Game (USA).cue", "Game (USA) (Track 1).bin")))
        assertFalse(RommUploadSources.needsArchive(listOf("Game (USA).chd")))
        assertEquals("Game (USA).zip", RommUploadSources.archiveName("Game (USA).7z"))
        assertEquals("Game (USA).zip", RommUploadSources.archiveName("Game (USA).zip"))
        assertEquals("Game (USA).ZIP", RommUploadSources.archiveName("Game (USA).ZIP"))
        assertEquals("Game (USA).zip", RommUploadSources.archiveName("sub/Game (USA).rar"))
        // No short extension to drop: the dot is part of the title.
        assertEquals("Super Mario Bros. 3.zip", RommUploadSources.archiveName("Super Mario Bros. 3"))
    }

    @Test
    fun `entries are named below the game folder`() {
        assertEquals("Game (Disc 1).cue", RommUploadSources.entryName("Game.m3u", "Game.m3u/Game (Disc 1).cue"))
        assertEquals("Game.m3u", RommUploadSources.entryName("Game.m3u", "Game.m3u/Game.m3u"))
        assertEquals("Disc 1/track01.bin", RommUploadSources.entryName("", "Disc 1/track01.bin"))
        assertEquals("Disc 1/track01.bin", RommUploadSources.entryName("", "/Disc 1\\track01.bin"))
    }

    @Test
    fun `checksums are kept only for files that did not change`() {
        val before = listOf(
            UploadSource("a.cue", 100, 10, crc32 = 1, dosTime = 2),
            UploadSource("a.bin", 5_000, 10, crc32 = 3, dosTime = 4)
        )
        val now = listOf(UploadSource("a.cue", 100, 10), UploadSource("a.bin", 5_000, 99), UploadSource("b.bin", 7, 1))
        val reused = RommUploadSources.reuseChecksums(before, now)
        assertEquals(1L, reused[0].crc32)
        assertEquals(2L, reused[0].dosTime)
        assertNull(reused[1].crc32)
        assertNull(reused[2].crc32)
    }

    @Test
    fun `signature follows every field`() {
        val a = listOf(UploadSource("a.bin", 5, 10, 3, 4))
        assertEquals(RommUploadSources.signature(a), RommUploadSources.signature(a.map { it.copy() }))
        assertNotEquals(RommUploadSources.signature(a), RommUploadSources.signature(listOf(a[0].copy(crc32 = 9))))
        assertNotEquals(RommUploadSources.signature(a), RommUploadSources.signature(listOf(a[0].copy(modified = 11))))
        assertNotEquals(RommUploadSources.signature(a), RommUploadSources.signature(listOf(a[0].copy(path = "b.bin"))))
    }

    @Test
    fun `sources survive the database and junk reads as nothing`() {
        val sources = listOf(UploadSource("Game.m3u/Game (Disc 1).cue", 100, 10, 0xFFFFFFFFL, 5), UploadSource("x.bin", 0, 0))
        assertEquals(sources, RommUploadSources.decode(RommUploadSources.encode(sources)))
        assertEquals(emptyList<UploadSource>(), RommUploadSources.decode("{not json"))
        assertEquals(emptyList<UploadSource>(), RommUploadSources.decode(""))
        assertEquals(emptyList<UploadSource>(), RommUploadSources.decode(null))
    }

    @Test
    fun `header name is plain ASCII`() {
        assertEquals("Game (USA) [!] 100% & more.zip", RommUploadSources.headerName("Game (USA) [!] 100% & more.zip"))
        assertEquals("Pok%C3%A9mon Gold.zip", RommUploadSources.headerName("Pokémon Gold.zip"))
        assertEquals("Pok%C3%A9mon 100%25.zip", RommUploadSources.headerName("Pokémon 100%.zip"))
        assertEquals("%F0%9F%8E%AE.zip", RommUploadSources.headerName("🎮.zip"))
        assertTrue(RommUploadSources.headerName("ドラクエ.zip").all { it.code in 0x20..0x7E })
    }
}
