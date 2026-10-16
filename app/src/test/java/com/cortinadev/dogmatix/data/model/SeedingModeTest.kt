package com.cortinadev.dogmatix.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedingModeTest {

    @Test
    fun `unknown or missing name falls back to OFF`() {
        assertEquals(SeedingMode.OFF, SeedingMode.fromName(null))
        assertEquals(SeedingMode.OFF, SeedingMode.fromName("whatever"))
        assertEquals(SeedingMode.RATIO_1, SeedingMode.fromName("RATIO_1"))
    }

    @Test
    fun `timed modes finish exactly at their limit`() {
        assertFalse(SeedingMode.MINUTES_30.goalReached(30 * 60_000L - 1, ratio = 99f))
        assertTrue(SeedingMode.MINUTES_30.goalReached(30 * 60_000L, ratio = 0f))
        assertFalse(SeedingMode.HOUR_1.goalReached(60 * 60_000L - 1, ratio = 99f))
        assertTrue(SeedingMode.HOUR_1.goalReached(60 * 60_000L, ratio = 0f))
    }

    @Test
    fun `ratio mode ignores elapsed time`() {
        assertFalse(SeedingMode.RATIO_1.goalReached(elapsedMs = Long.MAX_VALUE, ratio = 0.99f))
        assertTrue(SeedingMode.RATIO_1.goalReached(elapsedMs = 0L, ratio = 1f))
    }

    @Test
    fun `unlimited never finishes and off finishes immediately`() {
        assertFalse(SeedingMode.UNLIMITED.goalReached(Long.MAX_VALUE, ratio = 99f))
        assertTrue(SeedingMode.OFF.goalReached(0L, ratio = 0f))
    }
}
