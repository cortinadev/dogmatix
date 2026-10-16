package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarGamesTest {

    private val total = 50_000
    private fun row(id: Long, name: String) = SimilarGames.Row(id, name)

    @Test
    fun wordsDropTagsFillersSequelNumbersButKeepLeadingZeroNumbers() {
        assertEquals(listOf("super", "mario", "bros"), SimilarGames.words("Super Mario Bros. 3 (USA).nes"))
        assertEquals(listOf("final", "fantasy"), SimilarGames.words("Final Fantasy VII (USA) (Disc 1).chd"))
        assertEquals(listOf("legend", "zelda"), SimilarGames.words("Legend of Zelda, The (Europe).sfc"))
        assertEquals(listOf("007", "agent", "under", "fire"), SimilarGames.words("007 - Agent Under Fire (USA).iso"))
    }

    @Test
    fun franchiseWordsStopAtTheSubtitle() {
        assertEquals(listOf("tom", "clancy", "splinter", "cell"), SimilarGames.franchiseWords("Tom Clancy's Splinter Cell - Double Agent (USA).iso"))
        assertEquals(listOf("007"), SimilarGames.franchiseWords("007 - Agent Under Fire (USA).iso"))
        assertEquals(listOf("legend", "zelda"), SimilarGames.franchiseWords("Legend of Zelda, The - A Link to the Past (USA).sfc"))
        assertEquals(listOf("super", "mario", "world"), SimilarGames.franchiseWords("Super Mario World (USA).sfc"))
        assertEquals(listOf("final", "fantasy"), SimilarGames.franchiseWords("Final Fantasy VII (USA) (Disc 1).chd"))
    }

    @Test
    fun versionsShareATitleKeySequelsDoNot() {
        assertEquals(SimilarGames.titleKey("Super Mario World (USA).sfc"), SimilarGames.titleKey("Super Mario World (Europe) (Rev 1).sfc"))
        assertNotEquals(SimilarGames.titleKey("Super Mario World (USA).sfc"), SimilarGames.titleKey("Super Mario World 2 - Yoshi's Island (USA).sfc"))
    }

    @Test
    fun keyWordsAreTheRareFranchiseWords() {
        val counts = mapOf("super" to 3000, "mario" to 300, "world" to 1500)
        assertEquals(listOf("mario"), SimilarGames.keyWords(listOf("super", "mario", "world"), counts, total))
        // Nothing rare: the rarest word, unless even that one is everywhere.
        assertEquals(listOf("world"), SimilarGames.keyWords(listOf("super", "world"), counts, total))
        assertEquals(emptyList<String>(), SimilarGames.keyWords(listOf("super"), mapOf("super" to 6000), total))
        // A small library: up to 30 rows still counts as rare.
        assertEquals(listOf("mario"), SimilarGames.keyWords(listOf("mario"), mapOf("mario" to 30), 1000))
    }

    @Test
    fun theFranchiseComesFromEveryConsoleAndTheTargetIsLeftOut() {
        val counts = mapOf("super" to 3000, "mario" to 300, "world" to 1500)
        val ranked = SimilarGames.rank(
            "Super Mario World (USA).sfc",
            listOf(
                row(1, "Super Mario Bros. (World).nes"),
                row(2, "Mario Kart 64 (USA).z64"),
                row(3, "Dr. Mario (World).gb"),
                row(4, "Super Metroid (USA).sfc"),
                row(5, "Super Mario World (Europe) (Rev 1).sfc"),
                row(6, "Super Mario Bros. (Europe).nes")
            ),
            counts, total
        )
        // Super Mario Bros shares more words, so it leads; its two versions stay together.
        assertEquals(listOf(6L, 1L, 3L, 2L), ranked.franchise)
        assertTrue(ranked.related.isEmpty())
    }

    @Test
    fun aGameWithOnlySomeKeyWordsIsRelatedNotFranchise() {
        val counts = mapOf("double" to 150, "dragon" to 400)
        val ranked = SimilarGames.rank(
            "Double Dragon (USA).nes",
            listOf(
                row(1, "Double Dragon II - The Revenge (USA).nes"),
                row(2, "Dragon Quest (Japan).nes"),
                row(3, "Double Dribble (USA).nes"),
                row(4, "Contra (USA).nes")
            ),
            counts, total
        )
        assertEquals(listOf(1L), ranked.franchise)
        assertEquals(listOf(3L, 2L), ranked.related)   // "double" is the rarer word
    }

    @Test
    fun aSubSeriesRanksAboveItsUmbrella() {
        val counts = mapOf("tom" to 800, "clancy" to 40, "splinter" to 12, "cell" to 300, "double" to 150, "agent" to 200)
        val ranked = SimilarGames.rank(
            "Tom Clancy's Splinter Cell - Double Agent (USA).iso",
            listOf(
                row(1, "Tom Clancy's Splinter Cell - Chaos Theory (USA).iso"),
                row(2, "Tom Clancy's Ghost Recon (USA).iso"),
                row(3, "Double Agent Fake (USA).iso")
            ),
            counts, total
        )
        assertEquals(listOf(1L), ranked.franchise)
        assertEquals(listOf(2L), ranked.related)
    }

    @Test
    fun noFranchiseWordsNoResults() {
        assertTrue(SimilarGames.rank("(USA).sfc", listOf(row(1, "Tetris (World).gb")), emptyMap(), total).all.isEmpty())
    }

    @Test
    fun rankHonoursTheTitleLimit() {
        val rows = (1L..10L).map { row(it, "Mega Man $it Extra (USA).nes") }
        val ranked = SimilarGames.rank("Mega Man (USA).nes", rows, mapOf("mega" to 200, "man" to 900), total, maxTitles = 3)
        assertEquals(3, ranked.all.size)
    }
}
