package com.cortinadev.dogmatix.util

/**
 * Word matching on console ids (`nintendo_gameboy_advance`, `sony_psp`…), shared by the
 * console-family colours and the libretro-thumbnails lookup. A word may span several id parts
 * (`game_boy`) but never matches inside one (`ds` does not match `dsi`). Pure JVM.
 */
object ConsoleIdMatch {

    /** Lower-case with `_` between parts and around the whole id, ready for [has]. */
    fun normalize(consoleId: String): String =
        "_" + consoleId.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_') + "_"

    fun has(normalizedId: String, word: String): Boolean = normalizedId.contains("_${word}_")

    /** Length of the longest of [words] found in [normalizedId]; 0 when none is. */
    fun longestMatch(normalizedId: String, words: List<String>): Int =
        words.filter { has(normalizedId, it) }.maxOfOrNull { it.length } ?: 0
}
