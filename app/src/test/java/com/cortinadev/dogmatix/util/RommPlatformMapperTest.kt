package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.service.RommPlatform
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RommPlatformMapperTest {

    private val platforms = listOf(
        RommPlatform(1, "psx", "psx", "PlayStation", "PlayStation"),
        RommPlatform(2, "gba", "gba", "Game Boy Advance", "Game Boy Advance"),
        RommPlatform(3, "genesis-slash-megadrive", "megadrive", "Sega Mega Drive/Genesis", "Mega Drive"),
        RommPlatform(4, "snes", "snes", "Super Nintendo Entertainment System", "SNES"),
        RommPlatform(5, "arcade", "arcade", "Arcade", "Arcade")
    )

    @After
    fun tearDown() {
        ConsoleAliasRegistry.overrides = emptyMap()
    }

    @Test
    fun `maps common consoles by slug`() {
        assertEquals(1, RommPlatformMapper.suggest("sony_playstation", platforms)?.id)
        assertEquals(2, RommPlatformMapper.suggest("nintendo_gameboy_advance", platforms)?.id)
        assertEquals(4, RommPlatformMapper.suggest("nintendo_super_nintendo_entertainment_system", platforms)?.id)
    }

    @Test
    fun `slash slugs match either half`() {
        assertEquals(3, RommPlatformMapper.suggest("sega_genesis", platforms)?.id)
    }

    @Test
    fun `falls back to the display name`() {
        val only = listOf(RommPlatform(9, "weird-slug", "weird", "Game Boy", "Game Boy"))
        assertEquals(9, RommPlatformMapper.suggest("nintendo_gameboy", only)?.id)
    }

    @Test
    fun `configured aliases are honoured`() {
        ConsoleAliasRegistry.overrides = mapOf("custom_console" to ConsoleAliasInfo(shortName = "", folderAliases = listOf("arcade")))
        assertEquals(5, RommPlatformMapper.suggest("custom_console", platforms)?.id)
    }

    @Test
    fun `no match and empty list yield null`() {
        assertNull(RommPlatformMapper.suggest("atari_jaguar", platforms))
        assertNull(RommPlatformMapper.suggest("sony_playstation", emptyList()))
    }

    @Test
    fun `chunk count rounds up and never returns zero`() {
        assertEquals(1, RommPlatformMapper.chunkCount(0, 8))
        assertEquals(1, RommPlatformMapper.chunkCount(8, 8))
        assertEquals(2, RommPlatformMapper.chunkCount(9, 8))
        assertEquals(3, RommPlatformMapper.chunkCount(24, 8))
    }
}
