package com.cortinadev.dogmatix.util

import kotlin.math.ln

/**
 * Ranking rules of the library's recommendations (the shelves shown instead of the list when no
 * filter is on). Everything is worked out from what the user has: games on disk, downloads and
 * favourites (the *seeds*), the consoles they are on and their genres. A game's notability is
 * how many files it has in the library (regional releases and revisions pile up on well-known
 * games): the offline stand-in for popularity. Pure JVM for the tests; `RecommendationsService`
 * feeds it.
 */
object Recommender {

    /** How strongly a seed says "I like this". */
    enum class SeedKind(val weight: Int) { FAVOURITE(3), DOWNLOADED(2), OWNED(1) }

    data class Seed(val consoleId: String, val groupKey: String, val name: String, val kind: SeedKind) {
        val key: String get() = key(consoleId, groupKey)
    }

    /** A game that may be suggested; [files] is its notability, [genres] its [LibraryGenre] bits. */
    data class Pick(val consoleId: String, val groupKey: String, val name: String, val files: Int = 1, val genres: Int = 0) {
        val key: String get() = key(consoleId, groupKey)
    }

    fun key(consoleId: String, groupKey: String): String = "$consoleId|$groupKey"

    /** One seed per game, the strongest kind winning; first-seen order is kept. */
    fun distinctSeeds(seeds: List<Seed>): List<Seed> {
        val best = LinkedHashMap<String, Seed>()
        seeds.forEach { seed -> best.merge(seed.key, seed) { old, new -> if (new.kind.weight > old.kind.weight) new else old } }
        return best.values.toList()
    }

    /** Weight of each console (the sum of its seeds'), heaviest first. */
    fun consoleWeights(seeds: List<Seed>): List<Pair<String, Int>> =
        seeds.groupBy { it.consoleId }
            .map { (console, list) -> console to list.sumOf { it.kind.weight } }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

    /** Weight of each genre: every seed adds its weight to its genres ([genresOf] gives the bits, 0 if unknown). */
    fun genreWeights(seeds: List<Seed>, genresOf: (Seed) -> Int): Map<LibraryGenre, Int> {
        val weights = HashMap<LibraryGenre, Int>()
        seeds.forEach { seed -> LibraryGenre.inMask(genresOf(seed)).forEach { weights.merge(it, seed.kind.weight, Int::plus) } }
        return weights
    }

    /**
     * The genres that describe the user: the [max] heaviest, leaving out those far behind the
     * first (under a fifth of its weight) and [LibraryGenre.OTHER] (compilations, misc).
     */
    fun topGenres(weights: Map<LibraryGenre, Int>, max: Int = 3): List<LibraryGenre> {
        val ranked = weights.filterKeys { it != LibraryGenre.OTHER }.entries
            .sortedWith(compareByDescending<Map.Entry<LibraryGenre, Int>> { it.value }.thenBy { it.key.bit })
        val top = ranked.firstOrNull()?.value ?: return emptyList()
        return ranked.filter { it.value * 5 >= top }.take(max).map { it.key }
    }

    /** How well [genres] fit the user's: the share of the heaviest genre the best of them has (0..1). */
    fun affinity(genres: Int, weights: Map<LibraryGenre, Int>): Double {
        val top = weights.values.maxOrNull()?.takeIf { it > 0 } ?: return 0.0
        return LibraryGenre.inMask(genres).maxOfOrNull { (weights[it] ?: 0).toDouble() / top } ?: 0.0
    }

    private fun notability(files: Int): Double = ln(1.0 + files.coerceAtLeast(1))

    /**
     * "For you": games of the user's genres on the user's consoles, by genre affinity ×
     * notability × how much the console is used; at most [perConsole] per console so one big
     * system does not take the whole shelf.
     */
    fun forYou(
        pool: List<Pick>,
        genreWeights: Map<LibraryGenre, Int>,
        consoleWeights: Map<String, Int>,
        excluded: Set<String>,
        limit: Int,
        perConsole: Int = limit / 2 + 1
    ): List<Pick> {
        val topConsole = consoleWeights.values.maxOrNull()?.takeIf { it > 0 } ?: 1
        val scored = pool.filter { it.key !in excluded }
            .map { it to affinity(it.genres, genreWeights) * notability(it.files) * (0.5 + 0.5 * (consoleWeights[it.consoleId] ?: 0) / topConsole) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .map { it.first }
        return capPer(scored, perConsole) { it.consoleId }.take(limit)
    }

    /** "More for <console>": its notable games, a bit ahead when they fit the user's genres. */
    fun moreFrom(pool: List<Pick>, genreWeights: Map<LibraryGenre, Int>, excluded: Set<String>, limit: Int): List<Pick> =
        pool.filter { it.key !in excluded }
            .sortedByDescending { notability(it.files) * (1.0 + affinity(it.genres, genreWeights)) }
            .take(limit)

    /** The seeds whose series to complete: strongest first (ties keep their order), one per franchise, at most [max]. */
    fun seriesSeeds(seeds: List<Seed>, franchiseOf: (Seed) -> String, max: Int): List<Seed> =
        seeds.sortedByDescending { it.kind.weight }
            .filter { franchiseOf(it).isNotEmpty() }
            .distinctBy(franchiseOf)
            .take(max)

    /**
     * One entry per title (the same game on two consoles is one suggestion): of the picks
     * sharing [titleOf], the one on the console with the lowest [consoleRank] stays, at the
     * place of the first of them.
     */
    fun onePerTitle(picks: List<Pick>, titleOf: (Pick) -> String, consoleRank: (String) -> Int): List<Pick> {
        val best = LinkedHashMap<String, Pick>()
        picks.forEach { pick ->
            best.merge(titleOf(pick), pick) { old, new -> if (consoleRank(new.consoleId) < consoleRank(old.consoleId)) new else old }
        }
        return best.values.toList()
    }

    /** Round-robin over [lists] (one per seed, say), skipping keys already taken, up to [limit]. */
    fun <T> interleave(lists: List<List<T>>, limit: Int, key: (T) -> String): List<T> {
        val out = ArrayList<T>()
        val seen = HashSet<String>()
        val iterators = lists.map { it.iterator() }
        while (out.size < limit && iterators.any { it.hasNext() }) {
            for (iterator in iterators) {
                while (iterator.hasNext()) {
                    val item = iterator.next()
                    if (seen.add(key(item))) { out += item; break }
                }
                if (out.size >= limit) break
            }
        }
        return out
    }

    /**
     * The first [limit] of [items] with the ones that have a cover ahead: a shelf of box art
     * reads better than one of console-coloured squares. Each group keeps its order (the
     * recommendation's ranking), so a better pick without a cover only yields to one with it.
     */
    fun <T> coversFirst(items: List<T>, limit: Int, hasCover: (T) -> Boolean): List<T> {
        val (withCover, without) = items.partition(hasCover)
        return (withCover + without).take(limit)
    }

    /** At most [max] items per [group], order kept. */
    fun <T> capPer(items: List<T>, max: Int, group: (T) -> String): List<T> {
        val counts = HashMap<String, Int>()
        return items.filter { item -> (counts.merge(group(item), 1, Int::plus) ?: 0) <= max }
    }
}
