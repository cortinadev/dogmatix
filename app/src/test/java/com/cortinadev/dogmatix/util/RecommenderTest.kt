package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.Recommender.Pick
import com.cortinadev.dogmatix.util.Recommender.Seed
import com.cortinadev.dogmatix.util.Recommender.SeedKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommenderTest {

    private fun seed(console: String, key: String, kind: SeedKind) = Seed(console, key, key, kind)

    @Test
    fun aGameIsOneSeedOfItsStrongestKind() {
        val seeds = Recommender.distinctSeeds(
            listOf(seed("snes", "zelda", SeedKind.OWNED), seed("snes", "mario", SeedKind.DOWNLOADED), seed("snes", "zelda", SeedKind.FAVOURITE))
        )
        assertEquals(listOf("snes|zelda" to SeedKind.FAVOURITE, "snes|mario" to SeedKind.DOWNLOADED), seeds.map { it.key to it.kind })
    }

    @Test
    fun consolesWeighByTheirSeeds() {
        val weights = Recommender.consoleWeights(
            listOf(seed("gba", "a", SeedKind.OWNED), seed("snes", "b", SeedKind.FAVOURITE), seed("gba", "c", SeedKind.OWNED), seed("gba", "d", SeedKind.OWNED))
        )
        assertEquals(listOf("gba" to 3, "snes" to 3), weights)
    }

    @Test
    fun topGenresLeaveOutTheFarBehindAndOther() {
        val seeds = listOf(seed("snes", "a", SeedKind.FAVOURITE), seed("snes", "b", SeedKind.OWNED), seed("snes", "c", SeedKind.OWNED))
        val genres = mapOf(
            "snes|a" to (LibraryGenre.RPG.mask or LibraryGenre.OTHER.mask),
            "snes|b" to LibraryGenre.RPG.mask,
            "snes|c" to LibraryGenre.PLATFORM.mask
        )
        val weights = Recommender.genreWeights(seeds) { genres[it.key] ?: 0 }
        assertEquals(mapOf(LibraryGenre.RPG to 4, LibraryGenre.OTHER to 3, LibraryGenre.PLATFORM to 1), weights)
        assertEquals(listOf(LibraryGenre.RPG, LibraryGenre.PLATFORM), Recommender.topGenres(weights))
        assertEquals(listOf(LibraryGenre.RPG), Recommender.topGenres(mapOf(LibraryGenre.RPG to 20, LibraryGenre.PLATFORM to 3)))
        assertEquals(emptyList<LibraryGenre>(), Recommender.topGenres(emptyMap()))
        assertEquals(1.0, Recommender.affinity(LibraryGenre.RPG.mask, weights), 1e-9)
        assertEquals(0.25, Recommender.affinity(LibraryGenre.PLATFORM.mask, weights), 1e-9)
        assertEquals(0.0, Recommender.affinity(LibraryGenre.SPORTS.mask, weights), 1e-9)
    }

    @Test
    fun forYouRanksByGenreNotabilityAndConsoleAndSkipsWhatYouHave() {
        val weights = mapOf(LibraryGenre.RPG to 4, LibraryGenre.PLATFORM to 1)
        val pool = listOf(
            Pick("snes", "mario world", "Mario World", files = 12, genres = LibraryGenre.PLATFORM.mask),
            Pick("snes", "chrono trigger", "Chrono Trigger", files = 6, genres = LibraryGenre.RPG.mask),
            Pick("snes", "zelda", "Zelda", files = 30, genres = LibraryGenre.RPG.mask),
            Pick("gba", "golden sun", "Golden Sun", files = 6, genres = LibraryGenre.RPG.mask),
            Pick("snes", "fifa", "FIFA", files = 40, genres = LibraryGenre.SPORTS.mask)
        )
        val picks = Recommender.forYou(pool, weights, mapOf("snes" to 6, "gba" to 2), excluded = setOf("snes|zelda"), limit = 10)
        assertEquals(listOf("snes|chrono trigger", "gba|golden sun", "snes|mario world"), picks.map { it.key })
        // One console cannot take the whole shelf.
        val capped = Recommender.forYou(pool, weights, mapOf("snes" to 6, "gba" to 2), excluded = emptySet(), limit = 10, perConsole = 2)
        assertEquals(listOf("snes|zelda", "snes|chrono trigger", "gba|golden sun"), capped.map { it.key })
    }

    @Test
    fun moreFromPrefersNotableGamesThatFitTheGenres() {
        val pool = listOf(
            Pick("snes", "a", "A", files = 10, genres = 0),
            Pick("snes", "b", "B", files = 8, genres = LibraryGenre.RPG.mask),
            Pick("snes", "c", "C", files = 2, genres = 0)
        )
        val picks = Recommender.moreFrom(pool, mapOf(LibraryGenre.RPG to 3), excluded = setOf("snes|c"), limit = 5)
        assertEquals(listOf("snes|b", "snes|a"), picks.map { it.key })
    }

    @Test
    fun seriesSeedsAreTheStrongestOnePerFranchise() {
        val seeds = listOf(
            Seed("snes", "zelda 3", "Zelda 3", SeedKind.OWNED),
            Seed("gba", "zelda minish", "Zelda Minish", SeedKind.DOWNLOADED),
            Seed("snes", "mario", "Mario", SeedKind.FAVOURITE),
            Seed("snes", "untitled", "", SeedKind.FAVOURITE)
        )
        val franchise = mapOf("Zelda 3" to "zelda", "Zelda Minish" to "zelda", "Mario" to "mario", "" to "")
        val picked = Recommender.seriesSeeds(seeds, { franchise.getValue(it.name) }, max = 5)
        assertEquals(listOf("snes|mario", "gba|zelda minish"), picked.map { it.key })
    }

    @Test
    fun oneSuggestionPerTitleOnTheBestConsole() {
        val picks = listOf(Pick("ps1", "mmx4", "Mega Man X4"), Pick("snes", "mmx", "Mega Man X"), Pick("saturn", "mmx4", "Mega Man X4"))
        val rank = mapOf("saturn" to 0, "snes" to 1, "ps1" to 2)
        val kept = Recommender.onePerTitle(picks, { it.name }) { rank.getValue(it) }
        assertEquals(listOf("saturn|mmx4", "snes|mmx"), kept.map { it.key })
    }

    @Test
    fun interleaveTakesInTurnsWithoutRepeats() {
        val lists = listOf(listOf("a1", "a2", "a3"), listOf("b1", "a2", "b2"), emptyList())
        assertEquals(listOf("a1", "b1", "a2", "b2", "a3"), Recommender.interleave(lists, 10) { it })
        assertEquals(listOf("a1", "b1", "a2"), Recommender.interleave(lists, 3) { it })
        assertTrue(Recommender.interleave(emptyList<List<String>>(), 3) { it }.isEmpty())
    }

    @Test
    fun capPerKeepsOrder() {
        assertEquals(listOf("a1", "b1", "a2", "b2"), Recommender.capPer(listOf("a1", "b1", "a2", "a3", "b2"), 2) { it.take(1) })
    }

    @Test
    fun coversFirstKeepsTheRankingInsideEachGroup() {
        // Upper case = has a cover.
        val ranked = listOf("a", "B", "c", "D", "E", "f")
        assertEquals(listOf("B", "D", "E", "a"), Recommender.coversFirst(ranked, 4) { it[0].isUpperCase() })
        // Short of covers, the rest fill the shelf in their order.
        assertEquals(listOf("B", "a", "c"), Recommender.coversFirst(listOf("a", "B", "c"), 5) { it[0].isUpperCase() })
        assertEquals(listOf("a", "c"), Recommender.coversFirst(listOf("a", "c", "e"), 2) { false })
    }
}
