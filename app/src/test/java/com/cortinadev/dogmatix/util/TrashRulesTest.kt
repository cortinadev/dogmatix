package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashRulesTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `retention is one of the choices`() {
        assertEquals(7, TrashRules.retentionOrDefault(7))
        assertEquals(30, TrashRules.retentionOrDefault(null))
        assertEquals(30, TrashRules.retentionOrDefault(12))
    }

    @Test
    fun `items expire once the retention has passed`() {
        val trashed = 1_000_000L
        assertFalse(TrashRules.isExpired(trashed, trashed + 6 * day, 7))
        assertTrue(TrashRules.isExpired(trashed, trashed + 7 * day, 7))
        assertFalse(TrashRules.isExpired(trashed, trashed + 29 * day, 30))
    }

    @Test
    fun `slots mirror the original folder when it is known`() {
        assertEquals(".dogmatix-trash/b1/snes", TrashRules.slotPath("b1", "snes/Game.sfc", 0))
        assertEquals(".dogmatix-trash/b1/psx/Game.m3u", TrashRules.slotPath("b1", "psx/Game.m3u/Game (Disc 1).bin", 3))
        assertEquals(".dogmatix-trash/b1", TrashRules.slotPath("b1", "Loose.nes", 1))
        assertEquals(".dogmatix-trash/b1/_2", TrashRules.slotPath("b1", "", 2))
        // Never back into the trash itself, never outside the tree.
        assertEquals(".dogmatix-trash/b1/_4", TrashRules.slotPath("b1", ".dogmatix-trash/old/x.bin", 4))
        assertEquals(".dogmatix-trash/b1/_5", TrashRules.slotPath("b1", "../x/y.bin", 5))
    }

    @Test
    fun `trashed files carry a suffix once`() {
        assertEquals("Game.sfc.dogmatix-trashed", TrashRules.trashedName("Game.sfc"))
        assertEquals("Game.sfc.dogmatix-trashed", TrashRules.trashedName("Game.sfc.dogmatix-trashed"))
        assertEquals(1_000L, TrashRules.cutoff(1_000L + 7 * day, 7))
    }

    @Test
    fun `walks skip hidden names`() {
        assertTrue(TrashRules.isHidden(TrashRules.FOLDER))
        assertTrue(TrashRules.isHidden(".nomedia"))
        assertFalse(TrashRules.isHidden("snes"))
    }
}
