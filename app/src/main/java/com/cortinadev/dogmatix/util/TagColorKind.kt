package com.cortinadev.dogmatix.util

/**
 * The colour group of a file tag in the library rows (Settings → Tag colours lets the user change
 * each colour). Not the filter categories of `TagKind`: this one also tells revisions and release
 * kinds apart, and leaves video standards, extensions and unknown tags neutral. Pure JVM.
 */
enum class TagColorKind(val defaultArgb: Int) {
    REGION(0xFF4E99FF.toInt()),
    LANGUAGE(0xFF2ED6B8.toInt()),
    REVISION(0xFFA57BFF.toInt()),
    RELEASE(0xFFFFB300.toInt());

    companion object {
        private val revision = Regex("^(REV(ISION)?\\s*[0-9A-Z.]+|V\\d+(\\.\\d+)*[A-Z]?|VER(SION)?\\s+\\S+)$")
        private val language = Regex("^[A-Z]{2,3}$")
        /** Three-letter tags that are not language codes. */
        private val notLanguages = setOf("ALT", "NEW", "OEM", "SET", "BOX", "PAK", "HDR", "BIOS")
        private val releaseWords = listOf(
            "DEMO", "BETA", "PROTO", "ALPHA", "SAMPLE", "PREVIEW", "HACK", "PIRATE", "KIOSK", "DEBUG",
            "UNL", "DLC", "UPDATE", "TITLE UPDATE", "PATCH", "RETROACHIEVEMENTS"
        )

        /** The group of [tag], or null when it stays neutral. */
        fun of(tag: String): TagColorKind? {
            val upper = tag.trim().uppercase()
            return when {
                upper.isEmpty() -> null
                upper in Constants.Tags.VIDEO_STANDARDS -> null
                releaseWords.any { upper.startsWith(it) } -> RELEASE   // "Proto 2", "Prototype", "Beta 1"…
                revision.matches(upper) -> REVISION
                upper in Constants.Tags.ALL_REGIONS -> REGION
                language.matches(upper) && upper !in notLanguages -> LANGUAGE
                else -> null
            }
        }
    }
}
