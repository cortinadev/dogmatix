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

    /**
     * Whether [candidate] (a database hit) plausibly is the game called [title]: more than half
     * of the meaningful words of [title] must appear in it. Guards against fuzzy search results.
     */
    fun matches(title: String, candidate: String): Boolean {
        val wanted = tokens(title)
        if (wanted.isEmpty()) return false
        val found = tokens(candidate)
        return wanted.count { it in found } * 2 > wanted.size
    }

    private fun tokens(text: String): Set<String> {
        val ascii = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        return ascii.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in fillers }.toSet()
    }
}
