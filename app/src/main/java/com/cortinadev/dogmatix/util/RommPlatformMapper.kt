package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.service.RommPlatform

/**
 * Guesses which RomM platform a Dogmatix console corresponds to, using the same folder-alias
 * table that matches frontend folders (`gba`, `psx`, `snes`…) — RomM's slugs follow the same
 * naming. Also the chunk arithmetic for uploads, kept pure so both are unit-tested.
 */
object RommPlatformMapper {

    /** RomM slugs that differ from every alias we know; keyed by the console part of the id. */
    private val slugOverrides = mapOf(
        "genesis" to setOf("genesis-slash-megadrive", "genesis", "megadrive"),
        "cd" to setOf("segacd", "sega-cd"),
        "nintendo_entertainment_system" to setOf("nes", "famicom"),
        "super_nintendo_entertainment_system" to setOf("snes", "sfam"),
        "gamecube" to setOf("ngc", "gamecube"),
        "nintendo_3ds" to setOf("3ds", "n3ds"),
        "playstation" to setOf("psx", "ps"),
        "master_system" to setOf("sms", "mastersystem")
    )

    fun suggest(consoleId: String, platforms: List<RommPlatform>): RommPlatform? {
        if (platforms.isEmpty()) return null
        val key = if (consoleId.contains('_')) consoleId.substringAfter('_') else consoleId
        val candidates = ConsoleFolderAliases.candidatesFor(consoleId) +
            slugOverrides[key].orEmpty().map(ConsoleFolderAliases::normalize) +
            ConsoleFolderAliases.normalize(consoleId)
        fun n(s: String) = ConsoleFolderAliases.normalize(s.replace("-slash-", ""))
        // Slug is the most stable identifier; names come last (they are localisable / editable).
        return platforms.firstOrNull { n(it.slug) in candidates || slugParts(it.slug).any { p -> p in candidates } }
            ?: platforms.firstOrNull { n(it.fsSlug) in candidates }
            ?: platforms.firstOrNull { n(it.name) in candidates || n(it.displayName) in candidates }
    }

    /** `genesis-slash-megadrive` → [`genesis`, `megadrive`], normalised. */
    private fun slugParts(slug: String): List<String> =
        slug.split("-slash-").map(ConsoleFolderAliases::normalize).filter { it.isNotEmpty() }

    fun chunkCount(totalSize: Long, chunkSize: Int): Int =
        if (totalSize <= 0L) 1 else ((totalSize + chunkSize - 1) / chunkSize).toInt()
}
