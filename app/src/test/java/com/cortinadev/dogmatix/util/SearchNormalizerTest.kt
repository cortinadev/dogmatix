package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchNormalizerTest {

    @Test
    fun keyStripsPunctuationAccentsAndDoubleLetters() {
        assertEquals("yugiohduelmonsters", SearchNormalizer.key("Yu-Gi-Oh! Duel Monsters"))
        assertEquals("pokemonrojo", SearchNormalizer.key("Pokémon Rojo"))
        assertEquals("virtuatenis2", SearchNormalizer.key("Virtua Tennis 2"))
    }

    @Test
    fun patternJoinsWordsInOrderAndForgivesWordEndings() {
        assertEquals("yugio", SearchNormalizer.likePattern("yugioh"))
        assertEquals("mega", SearchNormalizer.likePattern("mega"))
        assertEquals("yu%gi%oh", SearchNormalizer.likePattern("yu gi oh"))
        assertEquals("virtua%teni", SearchNormalizer.likePattern("virtual tenis"))
        assertEquals("mari", SearchNormalizer.likePattern("  Mario  "))
        assertEquals("", SearchNormalizer.likePattern("!!!"))
    }

    @Test
    fun lenientQueriesMatchTheirTargets() {
        assertTrue(matches("Yu-Gi-Oh! Duel Monsters", "yugioh"))
        assertTrue(matches("Yu-Gi-Oh! Duel Monsters", "yu-gi-oh!"))
        assertTrue(matches("Yu-Gi-Oh! Duel Monsters", "yu gi oh"))
        assertTrue(matches("Virtua Tennis", "virtual tenis"))
        assertTrue(matches("Virtua Tennis", "virtua tennis"))
        assertTrue(matches("Pokémon Rojo", "pokemon"))
        assertTrue(matches("Donkey Kong Country", "donky kong"))
        assertFalse(matches("Virtua Tennis", "virtua fighter"))
    }

    /** Mirrors the SQL `searchKey LIKE '%' || pattern || '%'` used by the DAO. */
    private fun matches(name: String, query: String): Boolean {
        val regex = SearchNormalizer.likePattern(query).split("%").joinToString(".*") { Regex.escape(it) }
        return Regex(".*$regex.*").matches(SearchNormalizer.key(name))
    }
}
