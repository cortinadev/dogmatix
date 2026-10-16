package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTraitRulesTest {

    @Test
    fun genresAndDecadesRoundTripThroughTheRulesJson() {
        val rules = GameTraitRules.withDecades(
            GameTraitRules.withGenres(CollectionRules(consoles = setOf("snes")), setOf(LibraryGenre.RPG, LibraryGenre.ACTION)),
            setOf(1990, 1980)
        )
        val decoded = CollectionRuleCodec.decode(CollectionRuleCodec.encode(rules))!!
        assertEquals(setOf(LibraryGenre.RPG, LibraryGenre.ACTION), GameTraitRules.genresOf(decoded))
        assertEquals(setOf(1980, 1990), GameTraitRules.decadesOf(decoded))
        assertEquals(setOf("snes"), decoded.consoles)
    }

    @Test
    fun anEmptySelectionDropsTheField() {
        val rules = GameTraitRules.withGenres(CollectionRules(), setOf(LibraryGenre.RPG))
        assertTrue(GameTraitRules.GENRES in rules.extras)
        val cleared = GameTraitRules.withGenres(rules, emptySet())
        assertTrue(cleared.extras.isEmpty())
        assertTrue(cleared.isEmpty)
    }

    @Test
    fun conditionsMatchTheLibraryFilters() {
        val genre = GameTraitRules.genreCondition("""["RPG","PLATFORM"]""")!!
        assertTrue(genre.sql.contains("game_traits"))
        assertEquals(listOf<Any>(LibraryGenre.RPG.mask or LibraryGenre.PLATFORM.mask), genre.args)
        val decade = GameTraitRules.decadeCondition("[2000,1990]")!!
        assertTrue(decade.sql.endsWith("IN (?,?))"))
        assertEquals(listOf<Any>(1990, 2000), decade.args)
    }

    @Test
    fun unknownOrMalformedValuesAreIgnored() {
        assertNull(GameTraitRules.genreCondition("""["NOT_A_GENRE"]"""))
        assertNull(GameTraitRules.decadeCondition("[1955]"))
        assertNull(GameTraitRules.decadeCondition("not json"))
        assertEquals(emptySet<LibraryGenre>(), GameTraitRules.decodeGenres(null))
    }

    @Test
    fun theWhereClauseCarriesTheExtensionConditions() {
        val extension = object : CollectionRuleExtension {
            override val key = GameTraitRules.GENRES
            override fun condition(rawJson: String) = GameTraitRules.genreCondition(rawJson)
        }
        val rules = GameTraitRules.withGenres(CollectionRules(), setOf(LibraryGenre.RPG))
        val where = CollectionRuleSql.where(rules, listOf(extension))
        assertTrue(where.sql.contains("g_genre.genres & ?"))
        assertEquals(listOf<Any>(LibraryGenre.RPG.mask), where.args)
    }
}
