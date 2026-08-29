package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DeepLinkResolverTest {

    private val consoles = listOf(
        "super_nintendo_entertainment_system",
        "nintendo_entertainment_system",
        "gameboy_color",
        "sony_psp"
    )

    @Test
    fun `console matches exact id in any case`() {
        assertEquals(setOf("sony_psp"), DeepLinkResolver.resolveConsoles(setOf("Sony_PSP"), consoles))
    }

    @Test
    fun `console matches short name and folder aliases`() {
        assertEquals(setOf("super_nintendo_entertainment_system"), DeepLinkResolver.resolveConsoles(setOf("snes"), consoles))
        assertEquals(setOf("gameboy_color"), DeepLinkResolver.resolveConsoles(setOf("GBC"), consoles))
        assertEquals(setOf("sony_psp"), DeepLinkResolver.resolveConsoles(setOf("psp"), consoles))
    }

    @Test
    fun `unknown consoles are dropped and several are merged`() {
        assertEquals(
            setOf("super_nintendo_entertainment_system", "nintendo_entertainment_system"),
            DeepLinkResolver.resolveConsoles(setOf("snes", "nes", "atari"), consoles)
        )
    }

    @Test
    fun `tags take the known spelling and unknown ones pass through`() {
        val known = listOf("Japan", "USA", "Europe", "En", "Game")
        assertEquals(setOf("Japan", "USA", "En", "Prototype"), DeepLinkResolver.resolveTags(setOf("japan", "usa", "EN", "Prototype"), known))
    }
}
