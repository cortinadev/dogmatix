package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.model.GameDetails
import com.cortinadev.dogmatix.data.repository.DownloadableFileRepository
import com.cortinadev.dogmatix.util.RawgCatalog
import com.cortinadev.dogmatix.util.SimilarGames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Similar games" for the library filter, across every console:
 *  1. the franchise and related games, from the title and how rare its words are in the
 *     library ([SimilarGames]) — offline;
 *  2. when that finds fewer than [MIN_FRANCHISE_TITLES] games, games of the same genre: titles
 *     with that genre in the details cache, then (with a RAWG key) the most added games of the
 *     genre on the library's consoles, each looked up in the library by its title words.
 */
@Singleton
class SimilarGamesService @Inject constructor(
    private val files: DownloadableFileRepository,
    private val metadata: GameMetadataService
) {
    /** Row ids, best first. [details] is what the details card found for [item] (its genres). */
    suspend fun similarTo(item: DownloadableFileWithTags, details: GameDetails?): List<Long> = withContext(Dispatchers.IO) {
        val name = item.file.name
        val total = files.totalFiles().coerceAtLeast(1)
        val counts = SimilarGames.words(name).associateWith { files.rowsContaining(it) }
        val keys = SimilarGames.keyWords(SimilarGames.franchiseWords(name), counts, total)

        // Candidates: library rows with a key word, rarest words first (they say the most).
        val pool = keys.sortedBy { counts[it] ?: Int.MAX_VALUE }.take(MAX_KEY_SEARCHES)
            .flatMap { files.idNamesMatching(it, POOL_PER_WORD) }
            .distinctBy { it.id }
        val found = SimilarGames.rank(name, pool.map { SimilarGames.Row(it.id, it.name) }, counts, total).all

        val foundIds = found.toSet()
        val titles = pool.filter { it.id in foundIds }.map { SimilarGames.titleKey(it.name) }.toSet()
        val byGenre = if (titles.size >= MIN_FRANCHISE_TITLES) emptyList()
        else sameGenre(item, details, skipTitles = titles + SimilarGames.titleKey(name))
        (found + byGenre).distinct().take(MAX_ROWS)
    }

    private suspend fun sameGenre(item: DownloadableFileWithTags, details: GameDetails?, skipTitles: Set<String>): List<Long> {
        val genres = details?.genres?.takeIf { it.isNotEmpty() }
            ?: metadata.cached(item.file.name, item.file.consoleId)?.genres.orEmpty()
        val slug = RawgCatalog.pickGenre(genres) ?: return emptyList()
        val platforms = files.getConsolesWithFiles("*").mapNotNull { RawgCatalog.platformFor(it.id) }
        val candidates = (metadata.cachedTitlesWithGenre(slug) + metadata.popularTitles(slug, platforms))
            .distinctBy(SimilarGames::titleKey)
            .filter { SimilarGames.titleKey(it) !in skipTitles }
            .take(MAX_GENRE_TITLES)
        return candidates.flatMap { title -> rowsOfTitle(title) }
    }

    /**
     * The library rows of the game called [title] (any console, any version): a search for its
     * most distinctive word, kept when the row's title words are exactly the same (so "Tetris"
     * does not bring "Tetris Attack", and "The Legend of Zelda: A Link to the Past" finds
     * "Legend of Zelda, The - A Link to the Past").
     */
    private suspend fun rowsOfTitle(title: String): List<Long> {
        val words = SimilarGames.words(title).toSet()
        val probe = words.maxByOrNull { it.length } ?: return emptyList()
        return files.idNamesMatching(probe, ROWS_PER_PROBE)
            .filter { SimilarGames.words(it.name).toSet() == words }
            .map { it.id }
    }

    private companion object {
        /** Fewer franchise games than this and same-genre games are added after them. */
        const val MIN_FRANCHISE_TITLES = 8
        const val MAX_KEY_SEARCHES = 3
        const val POOL_PER_WORD = 600
        const val MAX_GENRE_TITLES = 40
        const val ROWS_PER_PROBE = 400
        /** Rows in the filtered list (the SQL `IN` list stays well under SQLite's variable limit). */
        const val MAX_ROWS = 500
    }
}
