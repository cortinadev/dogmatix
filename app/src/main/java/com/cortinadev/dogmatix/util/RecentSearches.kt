package com.cortinadev.dogmatix.util

/**
 * The last library searches the user confirmed (closed the keyboard or sent the query while it
 * found something), newest first. The same search in another spelling ("Pokémon" after
 * "pokemon", see [SearchNormalizer]) moves to the top instead of being listed twice.
 */
object RecentSearches {
    const val MAX = 10
    private const val MIN_LENGTH = 2
    private val SPACES = Regex("\\s+")

    /** The search as it is stored and shown: trimmed, single spaces. */
    fun clean(query: String): String = query.trim().replace(SPACES, " ")

    /** One letter (or only punctuation) is not worth listing; "FF" is. */
    fun isWorthKeeping(query: String): Boolean = query.count { it.isLetterOrDigit() } >= MIN_LENGTH

    fun add(list: List<String>, query: String, max: Int = MAX): List<String> {
        val q = clean(query)
        if (!isWorthKeeping(q)) return list
        return (listOf(q) + remove(list, q)).take(max)
    }

    fun remove(list: List<String>, query: String): List<String> {
        val key = SearchNormalizer.key(query)
        return list.filterNot { SearchNormalizer.key(it) == key }
    }

    /** One search per line (the search box is single-line). */
    fun encode(list: List<String>): String = list.joinToString("\n")

    fun decode(text: String): List<String> = text.lines().map { clean(it) }.filter { it.isNotEmpty() }.take(MAX)
}
