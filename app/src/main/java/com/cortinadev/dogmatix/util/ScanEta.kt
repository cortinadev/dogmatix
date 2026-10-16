package com.cortinadev.dogmatix.util

/**
 * Time left of a scan, from how its progress (0..1) moves. The rate is a moving average over
 * the last [windowMs] (one quick burst of sources or one slow torrent does not swing it), and an
 * estimate is only given once it has settled: some time and some progress in, after at least
 * [minSteps] advances, with the last [stableReadings] estimates within [tolerance] of each other.
 * When nothing moves for a whole window the estimate goes away again. Not thread-safe.
 */
class ScanEta(
    private val windowMs: Long = 60_000,
    private val minElapsedMs: Long = 8_000,
    private val minFraction: Double = 0.05,
    private val minSteps: Int = 2,
    private val stableReadings: Int = 3,
    private val tolerance: Double = 0.35
) {
    private val samples = ArrayDeque<Pair<Long, Double>>()
    private val readings = ArrayDeque<Long>()
    private var startedAt = -1L
    private var lastFraction = 0.0
    private var steps = 0
    private var settled = false

    fun reset(now: Long) {
        samples.clear(); readings.clear()
        startedAt = now; lastFraction = 0.0; steps = 0; settled = false
        samples.addLast(now to 0.0)
    }

    /** Records [fraction] at [now] and returns the milliseconds left, or null while unknown. */
    fun update(now: Long, fraction: Double): Long? {
        if (startedAt < 0) reset(now)
        val f = fraction.coerceIn(0.0, 1.0)
        if (f >= 1.0) return 0L
        if (f > lastFraction) { steps++; lastFraction = f }
        samples.addLast(now to lastFraction)
        // Keep one sample older than the window, so the rate always spans the whole window.
        while (samples.size > 2 && now - samples.elementAt(1).first >= windowMs) samples.removeFirst()

        if (now - startedAt < minElapsedMs || lastFraction < minFraction || steps < minSteps) return null
        val (t0, f0) = samples.first()
        val dt = now - t0
        val df = lastFraction - f0
        if (dt <= 0 || df <= 0) {
            // Stalled for a whole window: whatever was estimated no longer holds.
            readings.clear(); settled = false
            return null
        }
        val raw = ((1.0 - lastFraction) * dt / df).toLong()
        readings.addLast(raw)
        while (readings.size > stableReadings) readings.removeFirst()
        if (!settled) {
            if (readings.size < stableReadings) return null
            val mean = readings.average()
            if (mean <= 0 || readings.any { kotlin.math.abs(it - mean) > tolerance * mean }) return null
            settled = true
        }
        return readings.average().toLong()
    }
}

/** Splits a time left into what the scan indicator shows: under a minute, minutes, or hours and minutes. */
object ScanEtaFormat {
    /** null = under a minute; otherwise whole hours and minutes, the minutes rounded up. */
    fun hoursAndMinutes(ms: Long): Pair<Int, Int>? {
        if (ms < 60_000) return null
        val minutes = ((ms + 59_999) / 60_000).toInt()
        return minutes / 60 to minutes % 60
    }
}
