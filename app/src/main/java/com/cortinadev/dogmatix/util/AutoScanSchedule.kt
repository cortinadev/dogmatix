package com.cortinadev.dogmatix.util

/** "Automatic scan" in Settings: how often every source is scanned in the background. */
enum class AutoScanFrequency(val intervalMs: Long) {
    OFF(0L),
    DAILY(24 * 60 * 60 * 1000L),
    WEEKLY(7 * 24 * 60 * 60 * 1000L);

    companion object {
        fun fromName(name: String?): AutoScanFrequency = entries.firstOrNull { it.name == name } ?: OFF
    }
}

/**
 * When the background scan runs. The job wakes up every [CHECK_EVERY_MS] while its conditions
 * hold (Wi-Fi, charging) and scans once the last complete scan — manual or automatic — is one
 * interval old. Waking up a little early is allowed, so a daily scan does not drift later and
 * later every day.
 */
object AutoScanSchedule {
    const val CHECK_EVERY_MS = 3 * 60 * 60 * 1000L

    fun isDue(nowMs: Long, lastScanAtMs: Long, frequency: AutoScanFrequency): Boolean {
        if (frequency == AutoScanFrequency.OFF) return false
        // Never scanned, or the clock went back past the last scan.
        if (lastScanAtMs <= 0 || nowMs < lastScanAtMs) return true
        return nowMs - lastScanAtMs >= frequency.intervalMs - CHECK_EVERY_MS
    }
}
