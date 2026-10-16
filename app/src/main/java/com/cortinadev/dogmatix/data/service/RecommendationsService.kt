package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import com.cortinadev.dogmatix.data.local.dao.DownloadHistoryDao
import com.cortinadev.dogmatix.data.local.dao.GameCandidate
import com.cortinadev.dogmatix.data.local.dao.GameRef
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.model.LibraryGame
import com.cortinadev.dogmatix.data.model.RecommendationState
import com.cortinadev.dogmatix.data.model.Shelf
import com.cortinadev.dogmatix.data.model.ShelfKind
import com.cortinadev.dogmatix.data.model.VersionPrefs
import com.cortinadev.dogmatix.data.repository.DownloadableFileRepository
import com.cortinadev.dogmatix.data.repository.FavouritesRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.LibraryGenre
import com.cortinadev.dogmatix.util.LibraryKeys
import com.cortinadev.dogmatix.util.RawgCatalog
import com.cortinadev.dogmatix.util.Recommender
import com.cortinadev.dogmatix.util.Recommender.Pick
import com.cortinadev.dogmatix.util.Recommender.Seed
import com.cortinadev.dogmatix.util.Recommender.SeedKind
import com.cortinadev.dogmatix.util.SimilarGames
import com.cortinadev.dogmatix.util.VersionGrouping
import com.cortinadev.dogmatix.util.VersionRanking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The shelves the library shows when no filter is on, worked out in the background from what
 * the user has (see [Recommender]): games on disk, finished downloads and favourites. Every
 * shelf leaves out what the user already has.
 *  - **For you**: games of the user's genres (`game_traits`) on the user's consoles, plus
 *    games related to their franchises ([SimilarGames], offline).
 *  - **Complete the series**: entries of the user's franchises they do not have.
 *  - **More for <console>**: notable games of the console they use most.
 *  - **Popular on your platforms**: RAWG's most added games of their genres, when there is a
 *    RAWG key. It comes last, after the network; nothing else waits for it.
 *  - With nothing on disk, downloaded or starred: the notable games of the biggest consoles.
 * Kept in memory and worked out again (debounced) when what the user has changes or a rescan
 * ends. Genres the user's consoles still lack are downloaded after the first, local answer.
 * Inside each shelf the games with a cover come first, and their covers are downloaded ahead.
 */
@OptIn(FlowPreview::class)
@Singleton
class RecommendationsService @Inject constructor(
    private val files: DownloadableFileRepository,
    private val traits: GameTraitsService,
    private val history: DownloadHistoryDao,
    private val favourites: FavouritesRepository,
    private val libraryIndex: LibraryIndexService,
    private val downloadService: DownloadService,
    private val metadata: GameMetadataService,
    private val rescanState: RescanStateHolder,
    private val settings: SettingsRepository,
    private val covers: CoverRepository,
    private val romm: RommCatalogService,
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    private val _state = MutableStateFlow(RecommendationState())
    val state: StateFlow<RecommendationState> = _state.asStateFlow()

    private data class Inputs(
        val owned: Set<String>,
        val completed: Set<String>,
        val rescanTime: Long,
        val rescanning: Boolean,
        val prefs: VersionPrefs
    )

    /** Starts following the library (once per process); the first shelves come shortly after. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // Favourites are read when the shelves are worked out, not followed: starring a card
            // must not reshuffle the shelves under the cursor.
            combine(
                libraryIndex.ownedKeys,
                downloadService.downloads.map { list -> list.filter { it.status == DownloadStatus.COMPLETED }.map { it.fileName }.toSet() }.distinctUntilChanged(),
                combine(rescanState.lastRescanTime, rescanState.isRescanning) { time, running -> time to running },
                combine(settings.preferredRegion, settings.favoriteLanguages) { region, languages -> VersionPrefs(region, languages) }
            ) { owned, completed, (time, running), prefs -> Inputs(owned, completed, time, running, prefs) }
                // A rescan empties the index first: keep the last shelves until it is done.
                .filter { !it.rescanning }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MS)
                .collectLatest { inputs ->
                    try {
                        refresh(inputs)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Recommendations failed", e)
                        if (!_state.value.ready) _state.value = RecommendationState(ready = true)
                    }
                }
        }
    }

    private class Basis(val seeds: List<Seed>, val excluded: Set<String>, val ownedTitles: Set<String>)

    private class Draft(val kind: ShelfKind, val picks: List<Pick>, val consoleId: String? = null)

    private suspend fun refresh(inputs: Inputs) {
        val counts = files.consoleFileCounts()
        if (counts.isEmpty()) {
            _state.value = RecommendationState(ready = true, libraryEmpty = true)
            return
        }
        val basis = basis(inputs, counts.keys)
        if (basis.seeds.isEmpty()) {
            _state.value = RecommendationState(ready = true, shelves = materialise(highlights(counts), inputs))
            return
        }
        val consoles = Recommender.consoleWeights(basis.seeds).filter { it.first in counts }
        var (drafts, taken) = local(basis, consoles)
        _state.value = RecommendationState(ready = true, shelves = materialise(drafts, inputs))

        // Genres of the user's consoles may need a download first: the shelves come again with them.
        if (traits.ensure(consoles.map { it.first })) {
            local(basis, consoles).let { drafts = it.first; taken = it.second }
            _state.value = RecommendationState(ready = true, shelves = materialise(drafts, inputs))
        }
        popular(basis, consoles, taken)?.let { shelf ->
            val withPopular = drafts.toMutableList().apply { add(indexOfFirst { it.kind == ShelfKind.MORE_FROM } + 1, shelf) }
            _state.value = RecommendationState(ready = true, shelves = materialise(withPopular, inputs))
        }
    }

    /** The seeds, and what the user already has (by game and by title): on disk or downloaded, not merely starred. */
    private suspend fun basis(inputs: Inputs, consoles: Set<String>): Basis {
        val seeds = ArrayList<Seed>()
        favourites.keys.value.forEach { key ->
            val consoleId = key.substringBefore('|')
            if (consoleId !in consoles) return@forEach
            val name = nameOf(key.substringAfter('|'))
            seeds += Seed(consoleId, VersionGrouping.groupKey(name), name, SeedKind.FAVOURITE)
        }
        val downloads = history.getAll()
            .filter { it.status == DownloadStatus.COMPLETED.name && it.consoleId in consoles }
            .sortedByDescending { it.finishedAt ?: it.startedAt }
        downloads.take(MAX_DOWNLOAD_SEEDS).forEach { seeds += Seed(it.consoleId, VersionGrouping.groupKey(it.name), it.name, SeedKind.DOWNLOADED) }
        val owned = ownedGames(inputs.owned, consoles)
        owned.forEach { seeds += Seed(it.consoleId, it.groupKey, it.name, SeedKind.OWNED) }
        val ownedTitles = (owned.map { it.name } + downloads.map { it.name }).map(SimilarGames::titleKey).toSet()
        val excluded = owned.map { Recommender.key(it.consoleId, it.groupKey) }.toSet() +
            downloads.map { Recommender.key(it.consoleId, VersionGrouping.groupKey(it.name)) }
        return Basis(Recommender.distinctSeeds(seeds), excluded, ownedTitles)
    }

    /** Library games found on disk: the group keys of the files under each console's folders. */
    private suspend fun ownedGames(keys: Set<String>, consoles: Set<String>): List<GameRef> {
        if (keys.isEmpty()) return emptyList()
        val byScope = HashMap<String, MutableSet<String>>()
        keys.forEach { key ->
            val groupKey = VersionGrouping.groupKey(FileParsingUtils.extractNameAndTags(key.substringAfter('|')).first)
            if (groupKey.isNotEmpty()) byScope.getOrPut(key.substringBefore('|')) { HashSet() } += groupKey
        }
        return consoles.flatMap { consoleId ->
            val candidates = LibraryKeys.scopesFor(consoleId).flatMap { byScope[it].orEmpty() }.toSet()
            if (candidates.isEmpty()) emptyList() else files.gamesIn(consoleId, candidates)
        }
    }

    /** The offline shelves, and the games they took (the popular shelf must not repeat them). */
    private suspend fun local(basis: Basis, consoles: List<Pair<String, Int>>): Pair<List<Draft>, MutableSet<String>> {
        val consoleIds = consoles.map { it.first }
        val consoleWeight = consoles.toMap()
        val rank = consoleIds.withIndex().associate { it.value to it.index }
        val seedGenres = traits.genresOf(basis.seeds.map { it.consoleId to it.groupKey })
        val genreWeights = Recommender.genreWeights(basis.seeds) { seedGenres[it.key] ?: 0 }
        val topGenres = Recommender.topGenres(genreWeights)
        val taken = HashSet(basis.excluded)

        val (series, related) = series(basis, rank, taken)
        taken += series.map { it.key }

        val genrePool = if (topGenres.isEmpty()) emptyList()
        else files.notableGames(consoleIds.take(MAX_CONSOLES), LibraryGenre.maskOfGenres(topGenres), POOL_SIZE).map(::pickOf)
        val byGenre = Recommender.forYou(genrePool, genreWeights, consoleWeight, taken, SHELF_SIZE + SPARE)
        val forYou = Recommender.interleave(listOf(byGenre, related.filter { it.key !in taken }), SHELF_SIZE + SPARE) { it.key }
        taken += forYou.map { it.key }

        val top = consoleIds.first()
        val more = Recommender.moreFrom(files.notableGames(listOf(top), 0, POOL_SIZE).map(::pickOf), genreWeights, taken, SHELF_SIZE + SPARE)
        taken += more.map { it.key }

        val drafts = listOf(Draft(ShelfKind.FOR_YOU, forYou), Draft(ShelfKind.SERIES, series), Draft(ShelfKind.MORE_FROM, more, top))
        return drafts to taken
    }

    /** Entries of the seeds' franchises on the user's consoles, and related games (some key words in common). */
    private suspend fun series(basis: Basis, rank: Map<String, Int>, taken: Set<String>): Pair<List<Pick>, List<Pick>> {
        val total = files.totalFiles().coerceAtLeast(1)
        val wordCounts = HashMap<String, Int>()
        val seeds = Recommender.seriesSeeds(
            basis.seeds.filter { it.consoleId in rank },
            franchiseOf = { SimilarGames.franchiseWords(it.name).joinToString(" ") },
            max = MAX_SERIES_SEEDS
        )
        val franchise = ArrayList<List<Pick>>()
        val related = ArrayList<List<Pick>>()
        for (seed in seeds) {
            val counts = SimilarGames.words(seed.name).associateWith { word -> wordCounts.getOrPut(word) { files.rowsContaining(word) } }
            val keys = SimilarGames.keyWords(SimilarGames.franchiseWords(seed.name), counts, total)
            if (keys.isEmpty()) continue
            val rows = keys.sortedBy { counts[it] ?: Int.MAX_VALUE }.take(MAX_KEY_SEARCHES)
                .flatMap { files.gameRowsMatching(it, ROWS_PER_WORD) }
                .distinctBy { it.id }
            val ranked = SimilarGames.rank(seed.name, rows.map { SimilarGames.Row(it.id, it.name) }, counts, total)
            val byId = rows.associateBy { it.id }
            fun picks(ids: List<Long>): List<Pick> {
                val found = ids.mapNotNull(byId::get)
                    .filter { it.consoleId in rank }
                    .map { Pick(it.consoleId, it.groupKey, it.name) }
                    .distinctBy { it.key }
                    .filter { it.key !in taken && SimilarGames.titleKey(it.name) !in basis.ownedTitles }
                // The seed's own console first, then the most used ones.
                return Recommender.onePerTitle(found, { SimilarGames.titleKey(it.name) }) { console ->
                    if (console == seed.consoleId) -1 else rank[console] ?: Int.MAX_VALUE
                }
            }
            franchise += picks(ranked.franchise)
            related += picks(ranked.related)
        }
        return Recommender.interleave(franchise, SHELF_SIZE + SPARE) { it.key } to Recommender.interleave(related, MAX_RELATED) { it.key }
    }

    /** RAWG's most added games of the user's genres on their consoles, found in the library by title. */
    private suspend fun popular(basis: Basis, consoles: List<Pair<String, Int>>, taken: MutableSet<String>): Draft? {
        val rank = consoles.map { it.first }.withIndex().associate { it.value to it.index }
        val platforms = rank.keys.mapNotNull(RawgCatalog::platformFor).distinct()
        if (platforms.isEmpty()) return null
        val seedGenres = traits.genresOf(basis.seeds.map { it.consoleId to it.groupKey })
        val genres = Recommender.topGenres(Recommender.genreWeights(basis.seeds) { seedGenres[it.key] ?: 0 }, max = 2)
        val slugs = genres.mapNotNull { it.rawgSlug }.ifEmpty { listOfNotNull(LibraryGenre.ACTION.rawgSlug) }
        val titles = slugs.flatMap { metadata.popularTitles(it, platforms, POPULAR_PER_GENRE) }.distinctBy(SimilarGames::titleKey)
        if (titles.isEmpty()) return null
        val picks = ArrayList<Pick>()
        for (title in titles) {
            if (picks.size >= SHELF_SIZE + SPARE) break
            if (SimilarGames.titleKey(title) in basis.ownedTitles) continue
            // The library rows of that title: a search for its longest word, same title words.
            val words = SimilarGames.words(title).toSet()
            val probe = words.maxByOrNull { it.length } ?: continue
            val pick = files.gameRowsMatching(probe, ROWS_PER_WORD)
                .filter { it.consoleId in rank && SimilarGames.words(it.name).toSet() == words }
                .map { Pick(it.consoleId, it.groupKey, it.name) }
                .filter { it.key !in taken }
                .minByOrNull { rank[it.consoleId] ?: Int.MAX_VALUE } ?: continue
            picks += pick
            taken += pick.key
        }
        return Draft(ShelfKind.POPULAR, picks).takeIf { picks.isNotEmpty() }
    }

    /** Nothing to go by yet: the notable games of the biggest consoles. */
    private suspend fun highlights(counts: Map<String, Int>): List<Draft> =
        counts.entries.sortedByDescending { it.value }.take(HIGHLIGHT_CONSOLES).map { (consoleId, _) ->
            Draft(ShelfKind.HIGHLIGHTS, files.notableGames(listOf(consoleId), 0, SHELF_SIZE + SPARE).map(::pickOf), consoleId)
        }

    /**
     * The games behind the picks, minus unreleased builds, BIOS files and anything already on disk;
     * the ones with a cover go first ([Recommender.coversFirst]) and those covers are fetched ahead.
     */
    private suspend fun materialise(drafts: List<Draft>, inputs: Inputs): List<Shelf> {
        val games = files.gamesFor(drafts.flatMap { draft -> draft.picks.map { it.consoleId to it.groupKey } }, inputs.prefs)
            .associateBy { Recommender.key(it.consoleId, it.groupKey) }
        val candidates = drafts.map { draft -> draft.picks.mapNotNull { games[it.key] }.filter { keep(it, inputs.owned) } }
        val coverUrls = coverUrls(candidates)
        val shelves = drafts.zip(candidates).mapNotNull { (draft, list) ->
            Recommender.coversFirst(list, SHELF_SIZE) { gameKey(it) in coverUrls }
                .takeIf { it.isNotEmpty() }
                ?.let { Shelf(draft.kind, it, draft.consoleId) }
        }
        prefetch(shelves.flatMap { it.games }.mapNotNull { coverUrls[gameKey(it)] })
        return shelves
    }

    /**
     * Cover URLs of the shelves' candidates, by game key. Each shelf's first cards are looked up
     * first (the repository runs a few at a time) and the wait is capped: whatever is still
     * being looked up then lands in the cover cache for the next time the shelves are worked out.
     */
    private suspend fun coverUrls(lists: List<List<LibraryGame>>): Map<String, String> {
        val found = ConcurrentHashMap<String, String>()
        val pending = ArrayList<Deferred<Unit>>()
        Recommender.interleave(lists, Int.MAX_VALUE, ::gameKey).forEach { game ->
            val rom = game.primary.file
            val key = gameKey(game)
            covers.cached(rom.consoleId, rom.fileName)?.let { found[key] = it; return@forEach }
            // In the service's scope, not this refresh's: a lookup cut off by the wait still finishes.
            pending.add(scope.async {
                runCatching { covers.gameCoverUrl(game) }.getOrNull()?.let { found[key] = it }
                Unit
            })
        }
        if (pending.isNotEmpty()) withTimeoutOrNull(COVER_WAIT_MS) { pending.awaitAll() }
        return HashMap(found)
    }

    /** Covers already downloaded for the shelves (Coil's disk cache), so the cards show them at once. */
    private val prefetched = ConcurrentHashMap.newKeySet<String>()

    private fun prefetch(urls: List<String>) {
        val auth = romm.imageAuth.value
        urls.filter { prefetched.add(it) }.forEach { url ->
            val request = ImageRequest.Builder(context)
                .data(url)
                .apply { if (auth != null && auth.covers(url)) addHeader("Authorization", auth.header) }
                // Small and out of the memory cache: this is only about having the file on disk.
                .size(PREFETCH_PX)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .build()
            context.imageLoader.enqueue(request)
        }
    }

    private fun gameKey(game: LibraryGame) = Recommender.key(game.consoleId, game.groupKey)

    private fun keep(game: LibraryGame, owned: Set<String>): Boolean {
        val primary = game.primary
        if (VersionRanking.isNonRelease(primary.tags, primary.file.fileName)) return false
        if (BIOS.containsMatchIn(primary.file.fileName)) return false
        return game.files.none { LibraryKeys.isOwned(it.file.consoleId, it.file.fileName, owned) }
    }

    private fun pickOf(candidate: GameCandidate) =
        Pick(candidate.consoleId, candidate.groupKey, candidate.name, candidate.files, candidate.genres)

    /** The indexed name a file name gets (what the favourites keep is the file name). */
    private fun nameOf(fileName: String): String =
        FileParsingUtils.extractNameAndTags(FileParsingUtils.decodeUrlEncodedFileName(fileName).replace('\\', '/').substringAfterLast('/')).first

    private companion object {
        const val TAG = "Recommendations"
        const val DEBOUNCE_MS = 1_500L
        const val SHELF_SIZE = 20
        /** Picked beyond a shelf's size: some fall out as unreleased or already on disk, and those with a cover go first. */
        const val SPARE = 20
        /** How long a refresh waits for cover lookups before showing the shelves. */
        const val COVER_WAIT_MS = 3_000L
        const val PREFETCH_PX = 256
        const val POOL_SIZE = 300
        const val MAX_CONSOLES = 6
        const val MAX_DOWNLOAD_SEEDS = 60
        const val MAX_SERIES_SEEDS = 6
        const val MAX_KEY_SEARCHES = 2
        const val ROWS_PER_WORD = 400
        const val MAX_RELATED = 8
        const val POPULAR_PER_GENRE = 30
        const val HIGHLIGHT_CONSOLES = 4
        val BIOS = Regex("\\bbios\\b", RegexOption.IGNORE_CASE)
    }
}
