package com.cortinadev.dogmatix.util

import java.text.Normalizer
import kotlin.math.ln

/**
 * "Similar to this game" in the library: finds the game's franchise across every console, from
 * the title alone. Pure JVM for the tests; `SimilarGamesService` feeds it library rows and word
 * counts, and adds games of the same genre when the franchise turns up too little.
 *
 * - The **franchise words** are the title before its subtitle (`Splinter Cell - Double Agent`
 *   → `splinter cell`), without the sequel number.
 * - A word's weight comes from how many library rows contain it: `zelda` or `metroid` are rare
 *   and name a series, `super` or `world` appear everywhere and say little. The **key words** are
 *   the rare franchise words.
 * - A game with every key word is the same franchise; one with some of them is related
 *   (`Tom Clancy's Ghost Recon` for a Splinter Cell). The console never counts.
 */
object SimilarGames {

    /** One library row: its id and ROM name. */
    data class Row(val id: Long, val name: String)

    /** Row ids, best first: same franchise, then related games. */
    data class Ranked(val franchise: List<Long>, val related: List<Long>) {
        val all: List<Long> get() = franchise + related
    }

    private val fillers = setOf(
        "the", "a", "an", "of", "and", "in", "to", "for", "on", "vs", "no", "de", "la", "le", "el",
        "game", "games", "version", "disc", "edition"
    )

    /** Rare enough to name a franchise: at most this share of the library rows (or [STRONG_MIN] rows). */
    private const val STRONG_SHARE = 0.02
    private const val STRONG_MIN = 30
    /** A franchise word this common (any library) is no franchise at all ("super", "world"). */
    private const val TOO_COMMON_SHARE = 0.10

    /**
     * Title words, lower-case without accents; fillers, plain numbers and roman numerals are
     * dropped, but numbers with a leading zero stay ("007" is a franchise).
     */
    fun words(name: String): List<String> =
        fold(GameTitleCleaner.clean(name))
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 && it !in fillers && !isSequelNumber(it) }
            .distinct()

    /** The title before its subtitle, without the sequel number: the words that name the franchise. */
    fun franchiseWords(name: String): List<String> {
        val title = GameTitleCleaner.clean(name)
        val head = title.split(Regex("\\s+-\\s+|:\\s+")).first()
        return words(head)
    }

    /** One entry per game: regional versions and revisions share it. */
    fun titleKey(name: String): String = fold(GameTitleCleaner.clean(name)).replace(Regex("[^a-z0-9]+"), " ").trim()

    fun isStrong(rowsWithWord: Int, totalRows: Int): Boolean =
        rowsWithWord <= maxOf(STRONG_MIN, (totalRows * STRONG_SHARE).toInt())

    fun weight(rowsWithWord: Int, totalRows: Int): Double = ln((totalRows + 1.0) / (rowsWithWord + 1.0))

    /**
     * The franchise words that identify it: the rare ones. When none is rare, the rarest one,
     * unless even that one is everywhere (then the title names no franchise and this is empty).
     */
    fun keyWords(franchiseWords: List<String>, rowsWithWord: Map<String, Int>, totalRows: Int): List<String> {
        val counted = franchiseWords.filter { it in rowsWithWord }
        val strong = counted.filter { isStrong(rowsWithWord.getValue(it), totalRows) }
        if (strong.isNotEmpty()) return strong
        val rarest = counted.minByOrNull { rowsWithWord.getValue(it) } ?: return emptyList()
        return if (rowsWithWord.getValue(rarest) <= totalRows * TOO_COMMON_SHARE) listOf(rarest) else emptyList()
    }

    /**
     * Ranks [rows] against [targetName]. Rows of the target's own title are left out; each tier
     * is ordered by the weight of the words shared with the target (then by title), and the rows
     * of a title stay together. At most [maxTitles] titles in all.
     */
    fun rank(
        targetName: String,
        rows: List<Row>,
        rowsWithWord: Map<String, Int>,
        totalRows: Int,
        maxTitles: Int = 60
    ): Ranked {
        val keys = keyWords(franchiseWords(targetName), rowsWithWord, totalRows).toSet()
        if (keys.isEmpty()) return Ranked(emptyList(), emptyList())
        val targetTitle = titleKey(targetName)
        val targetWords = words(targetName).toSet()
        fun scoreOf(shared: Set<String>) = shared.sumOf { weight(rowsWithWord[it] ?: totalRows, totalRows) }

        data class Title(val key: String, val rows: List<Row>, val franchise: Boolean, val score: Double)
        val titles = rows.groupBy { titleKey(it.name) }
            .filterKeys { it.isNotEmpty() && it != targetTitle }
            .mapNotNull { (key, group) ->
                val titleWords = words(group.first().name).toSet()
                val sharedKeys = keys.intersect(titleWords)
                if (sharedKeys.isEmpty()) return@mapNotNull null
                Title(key, group.sortedBy { it.name.lowercase() }, sharedKeys == keys, scoreOf(targetWords.intersect(titleWords)))
            }
            .sortedWith(compareByDescending<Title> { it.franchise }.thenByDescending { it.score }.thenBy { it.key })
            .take(maxTitles)
        return Ranked(
            franchise = titles.filter { it.franchise }.flatMap { t -> t.rows.map { it.id } },
            related = titles.filterNot { it.franchise }.flatMap { t -> t.rows.map { it.id } }
        )
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

    private fun isSequelNumber(word: String): Boolean =
        (word.all(Char::isDigit) && !word.startsWith("0")) || (word.length <= 4 && word.all { it in "ivxlc" })
}
