package com.cortinadev.dogmatix.util

/**
 * Overall progress of a scan from its sources, each weighted by what it costs: a torrent that has
 * to ask the swarm for its metadata weighs much more than a web listing, and a source skipped
 * because nothing changed weighs nothing (it would make the first seconds look fast and throw the
 * time estimate off). Each source reports its own phase (0..1); it never goes back. Not thread-safe.
 */
class ScanProgressMath {
    private val weights = HashMap<String, Double>()
    private val parts = HashMap<String, Double>()

    fun add(key: String, weight: Double) {
        if (weight <= 0.0) return
        weights[key] = weight
        parts.putIfAbsent(key, 0.0)
    }

    /** Phase [fraction] of source [key]; ignored for unknown keys and for steps backwards. */
    fun set(key: String, fraction: Double) {
        val current = parts[key] ?: return
        parts[key] = maxOf(current, fraction.coerceIn(0.0, 1.0))
    }

    fun finish(key: String) = set(key, 1.0)

    val isEmpty: Boolean get() = weights.isEmpty()

    /** 0..1; 1 when nothing weighs anything. */
    val fraction: Double
        get() {
            val total = weights.values.sum()
            if (total <= 0.0) return 1.0
            return weights.entries.sumOf { (key, w) -> w * (parts[key] ?: 0.0) } / total
        }

    companion object {
        const val WEIGHT_WEB = 1.0
        const val WEIGHT_ROMM = 1.0
        /** An Internet Archive item: one metadata request (or the cheap check of its last update). */
        const val WEIGHT_IA = 1.0
        /** Each page a page-recipe source plans to read. */
        const val WEIGHT_SCRAPE_PAGE = 1.0
        /** A torrent whose metadata is at hand (cached on disk, or a handle in the session). */
        const val WEIGHT_TORRENT_KNOWN = 1.0
        /** A torrent whose metadata must come from the swarm: usually the slowest part of a scan. */
        const val WEIGHT_TORRENT_FETCH = 6.0

        /** Phases a source reports on its way (the scan marks it done at the end). */
        const val PHASE_FETCHED = 0.6
        const val PHASE_PARSED = 0.85
        const val PHASE_TORRENT_METADATA = 0.7
    }
}
