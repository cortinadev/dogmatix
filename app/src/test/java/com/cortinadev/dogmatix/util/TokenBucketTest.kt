package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenBucketTest {

    private val second = 1_000_000_000L

    @Test
    fun noLimitNeverWaits() {
        val bucket = TokenBucket()
        assertEquals(0L, bucket.take(10_000_000, 0L))
        assertEquals(0L, bucket.take(10_000_000, 1L))
    }

    @Test
    fun overdrawWaitsForTheDebtAtTheRate() {
        val bucket = TokenBucket().apply { setRate(1000) }
        // 500 bytes at 1000 B/s from an empty bucket: half a second of debt.
        assertEquals(500L, bucket.take(500, 0L))
        // Waited exactly that long: the next 500 bytes cost another half second, not more.
        assertEquals(500L, bucket.take(500, second / 2))
    }

    @Test
    fun parallelDownloadsShareOneLimit() {
        val bucket = TokenBucket().apply { setRate(1000) }
        // Two downloads read 500 bytes each at the same instant: together they owe a full second.
        bucket.take(500, 0L)
        assertEquals(1000L, bucket.take(500, 0L))
    }

    @Test
    fun idleTimeBanksAtMostHalfASecond() {
        val bucket = TokenBucket().apply { setRate(1000) }
        bucket.take(0, 0L)
        // Ten idle seconds save up only BURST_SECONDS worth (500 bytes).
        assertEquals(0L, bucket.take(500, 10 * second))
        assertEquals(100L, bucket.take(100, 10 * second))
    }

    @Test
    fun steadyReadingConvergesOnTheLimit() {
        val bucket = TokenBucket().apply { setRate(250 * 1024L) }
        var now = 0L
        var read = 0L
        repeat(1000) {
            val wait = bucket.take(8192, now)
            read += 8192
            now += wait * 1_000_000L + 1_000_000L // the wait plus 1 ms of reading
        }
        val rate = read / (now / 1e9)
        assertTrue("rate $rate", rate in 240_000.0..256_000.0 * 1.01)
    }

    @Test
    fun changingTheRateDropsTheOldDebt() {
        val bucket = TokenBucket().apply { setRate(10) }
        assertTrue(bucket.take(1_000_000, 0L) > 1000L)
        bucket.setRate(1_000_000)
        assertEquals(1L, bucket.take(1000, 0L))
        bucket.setRate(0)
        assertEquals(0L, bucket.take(1_000_000, 0L))
    }

    @Test
    fun settingIsKilobytesPerSecond() {
        assertEquals(0L, TokenBucket.bytesPerSecond(Float.POSITIVE_INFINITY))
        assertEquals(0L, TokenBucket.bytesPerSecond(0f))
        assertEquals(250L * 1024, TokenBucket.bytesPerSecond(250f))
        assertEquals(5000L * 1024, TokenBucket.bytesPerSecond(5000f))
    }
}
