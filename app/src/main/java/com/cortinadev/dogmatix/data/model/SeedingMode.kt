package com.cortinadev.dogmatix.data.model

/** How long a finished torrent keeps uploading ("Seed after download" in Settings). */
enum class SeedingMode {
    OFF,
    MINUTES_30,
    HOUR_1,
    RATIO_1,
    UNLIMITED;

    /**
     * True when a torrent seeded under this mode has met its goal and can be released.
     * [ratio] is bytes uploaded over bytes on disk for the torrent.
     */
    fun goalReached(elapsedMs: Long, ratio: Float): Boolean = when (this) {
        OFF -> true
        MINUTES_30 -> elapsedMs >= 30 * 60_000L
        HOUR_1 -> elapsedMs >= 60 * 60_000L
        RATIO_1 -> ratio >= 1f
        UNLIMITED -> false
    }

    companion object {
        fun fromName(name: String?): SeedingMode = entries.firstOrNull { it.name == name } ?: OFF
    }
}
