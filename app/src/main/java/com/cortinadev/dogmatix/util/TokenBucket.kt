package com.cortinadev.dogmatix.util

import kotlin.math.ceil

/**
 * One download budget shared by every HTTP download: each chunk read draws on the same bucket,
 * which refills at [bytesPerSecond], so three downloads together stay under the limit instead of
 * each one. A chunk is already read when it is accounted for, so the bucket may go into debt;
 * the caller then waits the time [take] returns, which spreads the waits across downloads.
 * Times are passed in (monotonic nanoseconds) so the arithmetic can be tested without a clock.
 */
class TokenBucket {
    /** 0 = no limit. */
    var bytesPerSecond: Long = 0L
        private set

    /** Bytes that may be read right now; negative while in debt. */
    private var balance = 0.0
    private var lastNanos: Long? = null

    /** A new limit starts clean: no debt or saved-up burst carried over from the old one. */
    @Synchronized
    fun setRate(bytesPerSecond: Long) {
        val rate = bytesPerSecond.coerceAtLeast(0L)
        if (rate == this.bytesPerSecond) return
        this.bytesPerSecond = rate
        balance = 0.0
        lastNanos = null
    }

    /** Accounts for [bytes] read at [nowNanos]; returns the milliseconds to wait (0 = none). */
    @Synchronized
    fun take(bytes: Int, nowNanos: Long): Long {
        val rate = bytesPerSecond
        if (rate <= 0L) return 0L
        val elapsed = (nowNanos - (lastNanos ?: nowNanos)).coerceAtLeast(0L)
        lastNanos = nowNanos
        balance = minOf(rate * BURST_SECONDS, balance + elapsed / 1e9 * rate) - bytes
        return if (balance >= 0) 0L else ceil(-balance * 1000.0 / rate).toLong()
    }

    companion object {
        /** Idle time saved up as a burst: short, so a paused download does not then exceed the limit. */
        const val BURST_SECONDS = 0.5

        /** The Settings speed limit (KB/s; 0 or infinite = none) in bytes per second, 0 = none. */
        fun bytesPerSecond(limitKbPerSecond: Float): Long =
            if (limitKbPerSecond.isInfinite() || limitKbPerSecond.isNaN() || limitKbPerSecond <= 0f) 0L
            else (limitKbPerSecond.toDouble() * 1024).toLong().coerceAtLeast(1L)
    }
}
