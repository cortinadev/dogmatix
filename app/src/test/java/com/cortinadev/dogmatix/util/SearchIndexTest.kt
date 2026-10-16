package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexTest {

    private data class Item(val title: String, val keywords: List<String> = emptyList())

    private fun index(vararg items: Item) = SearchIndex(items.toList(), { it.title }, { it.keywords })

    private fun titles(index: SearchIndex<Item>, query: String) = index.search(query).map { it.title }

    @Test
    fun titlePrefixBeatsWordsBeatsContains() {
        val idx = index(
            Item("Runtime settings"),          // "time" inside a word
            Item("Time attack"),               // title starts with the query
            Item("Download timeline")          // a title word starts with it
        )
        assertEquals(listOf("Time attack", "Download timeline", "Runtime settings"), titles(idx, "time"))
    }

    @Test
    fun keywordsRankBelowTitles() {
        val idx = index(
            Item("Theme", listOf("dark mode", "light", "black")),
            Item("Dark Souls")
        )
        assertEquals(listOf("Dark Souls", "Theme"), titles(idx, "dark"))
    }

    @Test
    fun casePunctuationAndAccentsDoNotMatter() {
        val idx = index(Item("Pokémon Rojo"), Item("Yu-Gi-Oh! Duel Monsters"))
        assertEquals(listOf("Pokémon Rojo"), titles(idx, "POKEMON"))
        assertEquals(listOf("Yu-Gi-Oh! Duel Monsters"), titles(idx, "yugioh"))
        assertEquals(listOf("Yu-Gi-Oh! Duel Monsters"), titles(idx, "duel mon"))
    }

    @Test
    fun wordsCanComeFromTitleAndKeywords() {
        val idx = index(Item("Super Nintendo", listOf("snes", "sfc", "Nintendo")))
        assertEquals(listOf("Super Nintendo"), titles(idx, "nintendo snes"))
        assertEquals(listOf("Super Nintendo"), titles(idx, "sfc"))
    }

    @Test
    fun forgivesATrailingTypoOnLongWords() {
        val idx = index(Item("Virtua Tennis 2"))
        assertEquals(listOf("Virtua Tennis 2"), titles(idx, "virtual tenis"))
    }

    @Test
    fun containsNeedsAFewLetters() {
        val idx = index(Item("Speed limit"), Item("Region", listOf("country", "zone")))
        // "pe" sits inside "speed" but two letters are too few to search inside words.
        assertTrue(titles(idx, "pe").isEmpty())
        assertEquals(listOf("Speed limit"), titles(idx, "imit"))
        // Inside a keyword only from four letters on.
        assertEquals(listOf("Region"), titles(idx, "ountr"))
        assertTrue(titles(idx, "oun").isEmpty())
    }

    @Test
    fun tiesKeepTheItemOrder() {
        val idx = index(Item("Mario Kart"), Item("Mario Party"), Item("Mario Golf"))
        assertEquals(listOf("Mario Kart", "Mario Party", "Mario Golf"), titles(idx, "mario"))
    }

    @Test
    fun oneLetterIsNotSearched() {
        val idx = index(Item("Accent color"))
        assertTrue(idx.search("a").isEmpty())
        assertFalse(SearchIndex.isSearchable(" a "))
        assertTrue(SearchIndex.isSearchable("ac"))
        // Two letters even though the normalizer folds them into one.
        assertTrue(SearchIndex.isSearchable("FF"))
        assertEquals(1, index(Item("Final Fantasy VII")).search("ff").size)
    }

    @Test
    fun keywordListSplitsOnCommas() {
        assertEquals(listOf("folder", "directory", "sd card"), SearchIndex.keywordList("folder, directory,  sd card ,"))
    }

    @Test
    fun readableUrls() {
        assertEquals(
            "Nintendo - Super Nintendo Entertainment System",
            SearchIndex.readableUrl("magnet:?xt=urn:btih:abcdef&dn=Nintendo+-+Super+Nintendo+Entertainment+System&tr=udp%3A%2F%2Ftracker")
        )
        assertEquals("magnet:?xt=urn:btih:abcdef", SearchIndex.readableUrl("magnet:?xt=urn:btih:abcdef"))
        assertEquals(
            "https://archive.org/download/No-Intro/Nintendo - Game Boy (C++)/",
            SearchIndex.readableUrl("https://archive.org/download/No-Intro/Nintendo%20-%20Game%20Boy%20(C++)/")
        )
        assertEquals("romm://snes", SearchIndex.readableUrl("romm://snes"))
    }

    @Test
    fun decodedUrlsAreFoundByTheirWords() {
        val idx = index(Item(SearchIndex.readableUrl("https://myrient.example/files/Nintendo%20-%20Game%20Boy/")))
        assertEquals(1, idx.search("game boy").size)
        assertEquals(1, idx.search("myrient").size)
    }
}
