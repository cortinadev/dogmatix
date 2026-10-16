package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentSearchesTest {

    @Test
    fun newestFirstWithoutDuplicates() {
        var list = emptyList<String>()
        list = RecentSearches.add(list, "zelda")
        list = RecentSearches.add(list, "mario")
        list = RecentSearches.add(list, "zelda")
        assertEquals(listOf("zelda", "mario"), list)
    }

    @Test
    fun anotherSpellingReplacesTheOldOne() {
        val list = RecentSearches.add(listOf("pokemon", "sonic"), "  Pokémon  ")
        assertEquals(listOf("Pokémon", "sonic"), list)
    }

    @Test
    fun keepsTheLastTen() {
        var list = emptyList<String>()
        (1..12).forEach { list = RecentSearches.add(list, "game $it") }
        assertEquals(RecentSearches.MAX, list.size)
        assertEquals("game 12", list.first())
        assertEquals("game 3", list.last())
    }

    @Test
    fun ignoresBlankAndOneLetterSearches() {
        val list = listOf("zelda")
        assertEquals(list, RecentSearches.add(list, "   "))
        assertEquals(list, RecentSearches.add(list, "z"))
        assertEquals(list, RecentSearches.add(list, "!!"))
        assertFalse(RecentSearches.isWorthKeeping("a"))
        assertTrue(RecentSearches.isWorthKeeping("ff"))
    }

    @Test
    fun cleansSpaces() {
        assertEquals("super mario world", RecentSearches.clean("  super   mario\tworld "))
    }

    @Test
    fun removeDropsEverySpelling() {
        assertEquals(listOf("sonic"), RecentSearches.remove(listOf("Pokémon", "sonic"), "pokemon"))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val list = listOf("zelda", "super mario", "Pokémon")
        assertEquals(list, RecentSearches.decode(RecentSearches.encode(list)))
        assertEquals(emptyList<String>(), RecentSearches.decode(""))
        assertEquals(listOf("a b"), RecentSearches.decode("\n  a   b \n\n"))
    }
}
