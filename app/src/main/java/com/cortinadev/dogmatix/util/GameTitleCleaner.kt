package com.cortinadev.dogmatix.util

import java.text.Normalizer

/** Turns a ROM name into a title a game database can search for. */
object GameTitleCleaner {

    private val extension = Regex("\\.[A-Za-z0-9]{1,4}$")
    private val brackets = Regex("\\[[^]]*]|\\([^)]*\\)")
    private val disc = Regex("(?i)[\\s-]*\\b(disc|disk|cd|side)\\s*[0-9A-Z]\\b.*$")
    private val version = Regex("(?i)\\s+v\\d+(\\.\\d+)*$")
    private val article = Regex("^(.+),\\s*(The|A|An)$")

    fun clean(name: String): String {
        var title = name.trim()
        title = title.replace(extension, "")
        title = title.replace(brackets, " ")
        title = title.replace(disc, "")
        title = title.replace(version, "")
        title = title.replace(Regex("\\s+"), " ").trim().trimEnd('-', ' ')
        article.matchEntire(title)?.let { title = "${it.groupValues[2]} ${it.groupValues[1]}" }
        return title
    }

    private val fillers = setOf("the", "a", "an", "of", "and", "version", "edition")

    /** Roman numerals that only ever mean an entry number; I, V and X are left out (Mega Man X). */
    private val romans = mapOf(
        "ii" to "2", "iii" to "3", "iv" to "4", "vi" to "6", "vii" to "7", "viii" to "8",
        "ix" to "9", "xi" to "11", "xii" to "12", "xiii" to "13"
    )

    /**
     * Whether [candidate] (a database hit) plausibly is the game called [title]: more than half
     * of the meaningful words of [title] must appear in it, and both must carry the same entry
     * numbers ("Clash of Ninja 2" is not "Clash of Ninja"). Guards against fuzzy search results.
     */
    fun matches(title: String, candidate: String): Boolean = overlap(title, candidate)?.let { it.first * 2 > it.second } == true

    /**
     * The hit of [candidates] that [matches] [title] best: most of its words in common, then the
     * fewest extra words ("Clash of Ninja 2" before "Clash of Ninja 2 Special"). Null when none matches.
     */
    fun <T> best(title: String, candidates: List<T>, nameOf: (T) -> String): T? =
        candidates.mapNotNull { candidate ->
            val (common, wanted, extra) = overlap(title, nameOf(candidate)) ?: return@mapNotNull null
            if (common * 2 > wanted) Triple(candidate, common, extra) else null
        }.maxWithOrNull(compareBy<Triple<T, Int, Int>> { it.second }.thenByDescending { it.third })?.first

    /** (words of [title] found in [candidate], words of [title], extra words of [candidate]); null when their entry numbers differ. */
    private fun overlap(title: String, candidate: String): Triple<Int, Int, Int>? {
        val wanted = tokens(title)
        if (wanted.isEmpty()) return null
        // A database's own disambiguation, as in "DOOM (1993)", is not part of the name.
        val found = tokens(candidate.replace(brackets, " "))
        if (numbers(wanted) != numbers(found)) return null
        val common = wanted.count { it in found }
        return Triple(common, wanted.size, found.size - common)
    }

    private fun numbers(tokens: Set<String>): Set<String> =
        tokens.mapNotNull { if (it.all(Char::isDigit)) it.trimStart('0').ifEmpty { "0" } else romans[it] }.toSet()

    private fun tokens(text: String): Set<String> {
        val ascii = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        return ascii.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in fillers }.toSet()
    }
}
