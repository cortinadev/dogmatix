package com.cortinadev.dogmatix.util

/** How long a looked-up cover (or the lack of one) is trusted, and the key it is stored under. Pure JVM. */
object CoverPolicy {
    /** A cover that was found: libretro renames files now and then, so look again after a month. */
    const val HIT_TTL_MS = 30L * 24 * 60 * 60 * 1000
    /** A game without a cover: new box art may appear, so look again after three days. */
    const val MISS_TTL_MS = 3L * 24 * 60 * 60 * 1000

    fun key(consoleId: String, fileName: String): String = "$consoleId|$fileName"

    /** Other versions tried for a game whose preferred one has no cover. */
    const val MAX_OTHER_VERSIONS = 3

    /**
     * The versions worth trying after [preferredName] (libretro names box art after one release:
     * "Dante's Inferno (Europe)" exists, "(Spain)" does not): in their order, without repeating a
     * lookup name, at most [max].
     */
    fun <T> otherVersions(preferredName: String, versions: List<T>, max: Int = MAX_OTHER_VERSIONS, lookupName: (T) -> String): List<T> {
        val seen = hashSetOf(preferredName)
        return versions.filter { seen.add(lookupName(it)) }.take(max)
    }

    fun isFresh(found: Boolean, fetchedAt: Long, now: Long): Boolean {
        val age = now - fetchedAt
        if (age < 0) return false   // clock went back: do not trust it
        return age < if (found) HIT_TTL_MS else MISS_TTL_MS
    }
}
