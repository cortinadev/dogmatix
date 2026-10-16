package com.cortinadev.dogmatix.util

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * In-memory index for the search across the app (downloads, sources, settings…). Each item is
 * found by its title and a few keywords; both are normalized once, when the index is built, with
 * [SearchNormalizer] (case, accents, punctuation and doubled letters do not matter), so a query
 * is a quick scan.
 *
 * Best first: the title starts with the query, every query word starts a title word, the title
 * contains the query, then the same against the keywords. Equal scores keep the items' order.
 * Like the library search, a word of 5+ letters also matches without its last letter
 * ("virtual" finds "Virtua").
 */
class SearchIndex<T>(items: List<T>, title: (T) -> String, keywords: (T) -> List<String> = { emptyList() }) {

    private class Doc<T>(
        val item: T,
        val titleKey: String,
        val titleWords: List<String>,
        val keywordKeys: List<String>,
        /** Title and keyword words together: "nintendo snes" spans name and short name. */
        val allWords: List<String>
    )

    private val docs = items.map { item ->
        val titleText = title(item)
        val keywordTexts = keywords(item)
        val titleWords = words(titleText)
        Doc(
            item = item,
            titleKey = SearchNormalizer.key(titleText),
            titleWords = titleWords,
            keywordKeys = keywordTexts.map { SearchNormalizer.key(it) }.filter { it.isNotEmpty() },
            allWords = titleWords + keywordTexts.flatMap { words(it) }
        )
    }

    val size: Int get() = docs.size

    /** Matching items, best first; empty when [query] is too short to search ([isSearchable]). */
    fun search(query: String): List<T> {
        if (!isSearchable(query)) return emptyList()
        val q = SearchNormalizer.key(query)
        val qWords = words(query)
        return docs.asSequence()
            .map { it to score(q, qWords, it) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }   // stable: ties keep the items' order
            .map { it.first.item }
            .toList()
    }

    private fun score(q: String, qWords: List<String>, doc: Doc<T>): Int = when {
        doc.titleKey.startsWith(q) -> TITLE_PREFIX
        allPrefix(qWords, doc.titleWords) -> TITLE_WORDS
        q.length >= 3 && doc.titleKey.contains(q) -> TITLE_CONTAINS
        doc.keywordKeys.any { it.startsWith(q) } -> KEYWORD_PREFIX
        allPrefix(qWords, doc.allWords) -> KEYWORD_WORDS
        q.length >= 4 && doc.keywordKeys.any { it.contains(q) } -> KEYWORD_CONTAINS
        else -> 0
    }

    companion object {
        const val TITLE_PREFIX = 100
        const val TITLE_WORDS = 80
        const val TITLE_CONTAINS = 60
        const val KEYWORD_PREFIX = 40
        const val KEYWORD_WORDS = 30
        const val KEYWORD_CONTAINS = 20

        /** Shorter queries (one letter) would match half the app. Counted before doubled letters collapse: "FF" is two. */
        private const val MIN_QUERY = 2
        private val SPLIT = Regex("[^\\p{L}\\p{N}]+")
        private val LIST_SPLIT = Regex("[,;\\n]")

        fun isSearchable(query: String): Boolean =
            query.count { it.isLetterOrDigit() } >= MIN_QUERY && SearchNormalizer.key(query).isNotEmpty()

        /** Words of [text], each normalized like a library title. */
        fun words(text: String): List<String> =
            text.split(SPLIT).map { SearchNormalizer.key(it) }.filter { it.isNotEmpty() }

        /** A comma-separated keyword string resource ("folder, directory, sd card") as phrases. */
        fun keywordList(text: String): List<String> = text.split(LIST_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

        /**
         * A source address as people read it: a magnet's display name (`dn`) when it has one,
         * otherwise the URL with its %-escapes decoded ("Nintendo%20-%20SNES" → "Nintendo - SNES").
         */
        fun readableUrl(url: String): String {
            val trimmed = url.trim()
            if (trimmed.startsWith("magnet:", ignoreCase = true)) {
                val name = trimmed.substringAfter('?', "").split('&')
                    .firstOrNull { it.startsWith("dn=", ignoreCase = true) }
                    ?.substringAfter('=')
                    ?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrDefault(it) }
                    ?.trim()
                if (!name.isNullOrEmpty()) return name
                return trimmed
            }
            // A literal '+' in a path is a plus sign, not a space.
            return runCatching { URLDecoder.decode(trimmed.replace("+", "%2B"), StandardCharsets.UTF_8.name()) }.getOrDefault(trimmed)
        }

        private fun allPrefix(query: List<String>, text: List<String>): Boolean =
            query.isNotEmpty() && text.isNotEmpty() && query.all { w -> text.any { startsLenient(it, w) } }

        private fun startsLenient(word: String, prefix: String): Boolean =
            word.startsWith(prefix) || (prefix.length >= 5 && word.startsWith(prefix.dropLast(1)))
    }
}
