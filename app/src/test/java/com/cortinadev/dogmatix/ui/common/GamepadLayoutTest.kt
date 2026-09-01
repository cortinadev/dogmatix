package com.cortinadev.dogmatix.ui.common

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class GamepadLayoutTest {

    @Test
    fun `each layout names the same positions its own way`() {
        // South, east, west, north: the app always calls them A, B, X, Y, and so does the legend
        // on the letter layouts — A accepts and B goes back whichever one is picked.
        assertEquals(listOf("A", "B", "X", "Y"), listOf("A", "B", "X", "Y").map(GamepadLayout.XBOX::glyphFor))
        assertEquals(listOf("A", "B", "X", "Y"), listOf("A", "B", "X", "Y").map(GamepadLayout.NINTENDO::glyphFor))
        assertEquals(listOf("✕", "○", "□", "△"), listOf("A", "B", "X", "Y").map(GamepadLayout.PLAYSTATION::glyphFor))
    }

    @Test
    fun `shoulders follow the layout and everything else is left alone`() {
        assertEquals("LT · RT", GamepadLayout.XBOX.glyphFor(GamepadLayout.TRIGGERS_KEY))
        assertEquals("L · R", GamepadLayout.NINTENDO.glyphFor(GamepadLayout.BUMPERS_KEY))
        assertEquals("L2 · R2", GamepadLayout.PLAYSTATION.glyphFor(GamepadLayout.TRIGGERS_KEY))
        for (layout in GamepadLayout.entries) {
            assertEquals("SELECT", layout.glyphFor("SELECT"))
            assertEquals("◀ ▶", layout.glyphFor("◀ ▶"))
            assertEquals("R3", layout.glyphFor("R3"))
        }
    }

    @Test
    fun `unknown or missing layout falls back to Xbox`() {
        assertEquals(GamepadLayout.XBOX, GamepadLayout.fromName(null))
        assertEquals(GamepadLayout.XBOX, GamepadLayout.fromName(""))
        assertEquals(GamepadLayout.XBOX, GamepadLayout.fromName("SEGA"))
        assertEquals(GamepadLayout.NINTENDO, GamepadLayout.fromName("NINTENDO"))
    }

    @Test
    fun `swapping face buttons keeps confirm and back working`() {
        // A and B become the keys every window already understands, so Android's own fallback
        // (BUTTON_A → DPAD_CENTER, BUTTON_B → BACK) cannot undo the swap.
        assertEquals(KeyEvent.KEYCODE_BACK, swapFaceKeyCode(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, swapFaceKeyCode(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, swapFaceKeyCode(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, swapFaceKeyCode(KeyEvent.KEYCODE_BUTTON_Y))
    }

    @Test
    fun `other keys are never swapped`() {
        val untouched = listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_SELECT
        )
        untouched.forEach { assertEquals(it, swapFaceKeyCode(it)) }
    }
}
