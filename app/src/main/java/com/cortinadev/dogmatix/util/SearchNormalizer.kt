package com.cortinadev.dogmatix.util

import java.text.Normalizer

/**
 * Turns names and user queries into a lenient, comparable form so that searching
 * "yugioh" finds "Yu-Gi-Oh!" and "virtual tenis" finds "Virtua Tennis".
 *
 * Normalization applied to both sides:
 *  - lower-case, accents stripped ("Pokémon" -> "pokemon")
 *  - anything that is not a letter or digit removed ("Yu-Gi-Oh!" -> "yugioh")
 *  - repeated letters collapsed ("tennis" -> "tenis")
 */
object SearchNormalizer {

    /** Key stored alongside each file: the whole name squashed into one token. */
    fun key(name: String): String = squash(name)

    /**
     * SQL LIKE pattern for a user query, to be wrapped as `'%' || pattern || '%'`.
     * Each word is normalized and words are joined with `%`, so they must appear in
     * order but anything may sit between them. Words of 5+ characters drop their last
     * character to forgive a trailing typo or plural ("virtual" still finds "virtua").
     */
    fun likePattern(query: String): String =
        query.split(WHITESPACE)
            .map { squash(it) }
            .filter { it.isNotEmpty() }
            .joinToString("%") { token -> if (token.length >= 5) token.dropLast(1) else token }

    private val WHITESPACE = Regex("\\s+")

    private fun squash(text: String): String {
        val folded = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        val sb = StringBuilder(folded.length)
        var last = ' '
        for (c in folded) {
            if (!c.isLetterOrDigit()) continue
            if (c != last) sb.append(c)
            last = c
        }
        return sb.toString()
    }
}
