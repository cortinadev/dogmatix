package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryGenreTest {

    @Test
    fun libretroGenresFoldOntoTheShortList() {
        assertEquals(LibraryGenre.RPG, LibraryGenre.fromName("Role-playing (RPG)"))
        assertEquals(LibraryGenre.SHOOTER, LibraryGenre.fromName("Shoot'em Up"))
        assertEquals(LibraryGenre.SHOOTER, LibraryGenre.fromName("Lightgun Shooter"))
        assertEquals(LibraryGenre.ACTION, LibraryGenre.fromName("Beat'em Up"))
        assertEquals(LibraryGenre.SPORTS, LibraryGenre.fromName("Hunting and Fishing"))
        assertEquals(LibraryGenre.BOARD, LibraryGenre.fromName("Gambling"))
        assertEquals(LibraryGenre.BOARD, LibraryGenre.fromName("Pinball"))
        assertEquals(LibraryGenre.MUSIC, LibraryGenre.fromName("Music / Dancing"))
        assertEquals(LibraryGenre.PUZZLE, LibraryGenre.fromName("Quiz"))
        assertEquals(LibraryGenre.OTHER, LibraryGenre.fromName("Compilation"))
        assertEquals(LibraryGenre.OTHER, LibraryGenre.fromName("Casual Game"))
    }

    @Test
    fun rawgAndTheGamesDbSpellingsToo() {
        assertEquals(LibraryGenre.PLATFORM, LibraryGenre.fromName("Platformer"))
        assertEquals(LibraryGenre.PLATFORM, LibraryGenre.fromName("Platform"))
        assertEquals(LibraryGenre.RPG, LibraryGenre.fromName("RPG"))
        assertEquals(LibraryGenre.RPG, LibraryGenre.fromName("Role-Playing"))
        assertEquals(LibraryGenre.BOARD, LibraryGenre.fromName("Board Games"))
        assertEquals(LibraryGenre.RACING, LibraryGenre.fromName("Vehicle Simulation"))
        assertEquals(LibraryGenre.SIMULATION, LibraryGenre.fromName("Flight Simulator"))
        assertEquals(LibraryGenre.RPG, LibraryGenre.fromName("Massively Multiplayer"))
        assertEquals(LibraryGenre.ACTION, LibraryGenre.fromName("  ACTION "))
    }

    @Test
    fun namesThatAreNoGenreAreDropped() {
        assertNull(LibraryGenre.fromName("Indie"))
        assertNull(LibraryGenre.fromName("Demo"))
        assertNull(LibraryGenre.fromName("N/A"))
        assertNull(LibraryGenre.fromName(""))
    }

    @Test
    fun masksRoundTripAndBitsNeverMove() {
        // Stored in game_traits: these values must stay as they are.
        assertEquals(1, LibraryGenre.ACTION.mask)
        assertEquals(1 shl 3, LibraryGenre.RPG.mask)
        assertEquals(1 shl 13, LibraryGenre.OTHER.mask)
        assertEquals(LibraryGenre.entries.size, LibraryGenre.entries.map { it.bit }.distinct().size)
        val mask = LibraryGenre.maskOf(listOf("Platformer", "Action", "Indie"))
        assertEquals(listOf(LibraryGenre.ACTION, LibraryGenre.PLATFORM), LibraryGenre.inMask(mask))
        assertEquals(mask, LibraryGenre.maskOfGenres(setOf(LibraryGenre.PLATFORM, LibraryGenre.ACTION)))
        assertEquals(0, LibraryGenre.maskOf(emptyList()))
    }

    @Test
    fun deepLinkValuesResolveByIdOrName() {
        assertEquals(LibraryGenre.RPG, LibraryGenre.resolve("rpg"))
        assertEquals(LibraryGenre.PLATFORM, LibraryGenre.resolve("Platformer"))
        assertEquals(LibraryGenre.BOARD, LibraryGenre.resolve("BOARD"))
        assertNull(LibraryGenre.resolve("indie"))
    }

    @Test
    fun yearsAndDecades() {
        assertEquals(1996, LibraryDecades.parseYear("1996"))
        assertEquals(1996, LibraryDecades.parseYear("1996-06-23"))
        assertNull(LibraryDecades.parseYear("96"))
        assertNull(LibraryDecades.parseYear("19xx"))
        assertNull(LibraryDecades.parseYear("1890"))
        assertNull(LibraryDecades.parseYear(null))
        assertEquals(1990, LibraryDecades.decadeOf(1999))
        assertEquals("80", LibraryDecades.twoDigits(1980))
        assertEquals("00", LibraryDecades.twoDigits(2000))
    }

    @Test
    fun decadeSpellingsOfADeepLink() {
        assertEquals(1990, LibraryDecades.parseDecade("1990"))
        assertEquals(1990, LibraryDecades.parseDecade("1990s"))
        assertEquals(1990, LibraryDecades.parseDecade("1995"))
        assertEquals(1980, LibraryDecades.parseDecade("80s"))
        assertEquals(1980, LibraryDecades.parseDecade("'80s"))
        assertEquals(1980, LibraryDecades.parseDecade("80"))
        assertEquals(2000, LibraryDecades.parseDecade("00s"))
        assertEquals(2010, LibraryDecades.parseDecade("10"))
        assertNull(LibraryDecades.parseDecade("nineties"))
        assertNull(LibraryDecades.parseDecade("199"))
        assertNull(LibraryDecades.parseDecade(""))
    }
}
