package com.cortinadev.dogmatix.data.local

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cortinadev.dogmatix.data.local.dao.GameGroupRef
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.PreferredRegion
import com.cortinadev.dogmatix.data.model.SortOption
import com.cortinadev.dogmatix.data.model.SourceFilter
import com.cortinadev.dogmatix.data.model.VersionPrefs
import com.cortinadev.dogmatix.data.repository.DownloadableFileRepository
import com.cortinadev.dogmatix.data.service.SourceStatsService
import com.cortinadev.dogmatix.testing.LibraryQueries.buckets
import com.cortinadev.dogmatix.testing.LibraryQueries.gamePage
import com.cortinadev.dogmatix.testing.TestData
import com.cortinadev.dogmatix.testing.fakeSettings
import com.cortinadev.dogmatix.util.LetterJump
import com.cortinadev.dogmatix.util.SearchNormalizer
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * The library queries on a catalogue of 20 000 rows over five consoles, in a database file like
 * the app's (`catalog_test.db` in the sandbox): paging by game never repeats nor skips a game,
 * the lenient search finds what the search box promises, the letter buckets match the list and
 * looking up more than 500 games at once (split into several queries) loses none. Timings go to
 * the log (tag `DogmatixPerf`); nothing here asserts on them, the devices differ too much.
 */
@RunWith(AndroidJUnit4::class)
class LargeCatalogTest {

    // ---- Paging ---------------------------------------------------------------------------------

    @Test
    fun pagesOfGamesFollowTheListOrderWithoutRepeatsOrGaps() = runBlocking {
        val pages = timed("page through A→Z, 250 games a page") { allPages(sort = 0) }
        assertEquals(expectedAToZ.map { it.ref }, pages)

        // The other orders: whatever the order, the pages make up the whole list once.
        listOf(1, 2, 3).forEach { sort ->
            val paged = timed("page through sort $sort") { allPages(sort) }
            val whole = dao.gamePage(limit = Int.MAX_VALUE, sort = sort)
            assertEquals("sort $sort", whole, paged)
            assertEquals("sort $sort", paged.size, paged.toSet().size)
            assertEquals("sort $sort", games.size, paged.size)
        }
        // One console only.
        val snes = dao.gamePage(limit = Int.MAX_VALUE, consoleIds = listOf("snes"))
        assertEquals(expectedAToZ.filter { it.consoleId == "snes" }.map { it.ref }, snes)
    }

    // ---- Search ---------------------------------------------------------------------------------

    @Test
    fun theSearchFindsWhatTheSearchBoxPromises() = runBlocking {
        // A word anywhere, the start of a name, upper case, accents, two words in order, a typo at the end.
        listOf("quest", "Crystal", "CRYSTAL QUEST", "elan", "Élan vital", "okami", "dragons", "1942", "de blob", "zz-no-such-game").forEach { text ->
            val found = timed("search \"$text\"") { searchAll(text) }
            val expected = expectedAToZ.filter { matches(it.title, text) }.map { it.ref }
            assertEquals("search \"$text\"", expected, found.map { GameGroupRef(it.consoleId, it.groupKey) })
            // Every version of a game found is there, best first.
            found.forEach { game -> assertEquals(games.getValue(GameGroupRef(game.consoleId, game.groupKey)).files, game.files.size) }
        }
        assertTrue("the generated catalogue has games with \"quest\"", expectedAToZ.count { matches(it.title, "quest") } > 100)
    }

    // ---- Letter jumps ---------------------------------------------------------------------------

    @Test
    fun letterBucketsMatchTheList() = runBlocking {
        val buckets = timed("letter buckets") { dao.buckets() }.associate { it.bucket to it.games }
        val expected = expectedAToZ.groupingBy { LetterJump.bucketOf(it.title) }.eachCount()
        assertEquals(expected, buckets)

        val index = timed("letter index") {
            repository.letterIndex("", emptySet(), emptySet(), false, SourceFilter.ALL, emptySet(), emptySet(), descending = false)
        }
        assertEquals(games.size, index.size)
        // Each run starts on the first game of its letter in the A→Z list.
        index.runs.forEach { run ->
            assertEquals(run.letter, LetterJump.letterOf(expectedAToZ[run.start].title))
            if (run.start > 0) assertTrue(run.letter != LetterJump.letterOf(expectedAToZ[run.start - 1].title))
        }
        // ◀ ▶ walk the runs in order.
        var position = 0
        var jumps = 0
        while (true) {
            position = index.next(position) ?: break
            jumps++
        }
        assertEquals(index.runs.size - 1, jumps)
    }

    // ---- Many games at once ---------------------------------------------------------------------

    @Test
    fun moreThan500GamesAtOnceAreFetchedInParts() = runBlocking {
        val random = Random(7)
        val snes = expectedAToZ.filter { it.consoleId == "snes" }.take(1_200)
        val nes = expectedAToZ.filter { it.consoleId == "nes" }.take(300)
        val refs = (snes + nes).shuffled(random).map { it.consoleId to it.groupKey } + ("snes" to "no such game")
        val found = timed("gamesFor ${refs.size} refs") { repository.gamesFor(refs, PREFS) }
        assertEquals(refs.dropLast(1), found.map { it.consoleId to it.groupKey })
        found.forEach { game ->
            val expected = games.getValue(GameGroupRef(game.consoleId, game.groupKey))
            assertEquals("${game.groupKey}: files", expected.files, game.files.size)
            assertEquals("${game.groupKey}: versions", expected.versions, game.versions.size)
        }
    }

    // ---- Helpers --------------------------------------------------------------------------------

    private suspend fun allPages(sort: Int): List<GameGroupRef> {
        val out = ArrayList<GameGroupRef>()
        while (true) {
            val page = dao.gamePage(limit = PAGE, offset = out.size, sort = sort)
            out += page
            if (page.size < PAGE) return out
        }
    }

    private suspend fun searchAll(text: String) = buildList {
        while (true) {
            val page = repository.searchGames(text, sort = SortOption.NAME_ASC, limit = PAGE, offset = size, prefs = PREFS)
            addAll(page)
            if (page.size < PAGE) break
        }
    }

    /** What the search box promises: every word, in order, leniently (see SearchNormalizer.likePattern). */
    private fun matches(title: String, text: String): Boolean {
        val pattern = SearchNormalizer.likePattern(text)
        if (pattern.isEmpty()) return true
        return Regex(pattern.split('%').joinToString(".*") { Regex.escape(it) }).containsMatchIn(SearchNormalizer.key(title))
    }

    private inline fun <T> timed(label: String, block: () -> T): T {
        val start = System.nanoTime()
        return block().also { Log.i(TAG, "$label: ${(System.nanoTime() - start) / 1_000_000} ms") }
    }

    /** One generated game: its title (the indexed name of all its files) and how many files / versions it has. */
    private data class Game(val consoleId: String, val groupKey: String, val title: String, val files: Int, val versions: Int) {
        val ref get() = GameGroupRef(consoleId, groupKey)
    }

    companion object {
        private const val TAG = "DogmatixPerf"
        private const val NAME = "catalog_test.db"
        private const val PAGE = 250
        private const val ROWS_PER_CONSOLE = 4_000
        private val PREFS = VersionPrefs(PreferredRegion.EUROPE, setOf("EN"))

        private lateinit var db: DogmatixDatabase
        private lateinit var repository: DownloadableFileRepository
        private val dao get() = db.downloadableFileDao()
        private lateinit var games: Map<GameGroupRef, Game>
        /** The games as the A→Z list orders them: by name (SQLite byte order), console, key. */
        private lateinit var expectedAToZ: List<Game>

        private val CONSOLES = listOf("snes" to "sfc", "nes" to "nes", "psx" to "chd", "gba" to "gba", "n64" to "z64")
        private val FIRST = listOf(
            "Crystal", "Shadow", "Super", "Mega", "Dragon", "Iron", "Star", "Neon", "Silent", "Final", "Golden", "Wild",
            "Turbo", "Hyper", "Ultra", "Royal", "Lost", "Secret", "Pocket", "Space", "Ninja", "Magic", "Rocket", "Thunder",
            "Battle", "Phantom", "Mystic", "Cyber", "Brave", "Grand"
        )
        private val SECOND = listOf(
            "Quest", "Legends", "Fighter", "Racer", "Tactics", "Saga", "Warriors", "Dragons", "Kart", "Soccer", "Puzzle",
            "Hunter", "Knights", "Odyssey", "Arena", "Raiders", "Chronicles", "Rally", "Tennis", "Golf", "Pinball", "Island",
            "Empire", "Galaxy", "Dungeon", "Commando", "Blaster", "Striker", "Voyage", "Party"
        )
        /** Names at the edges of the byte order: digits, lower case, accents, punctuation. */
        private val SPECIAL = listOf(
            "1942 Strike", "007 Racing", "de Blob Island", "the Lost Vikings Saga", "Élan Vital", "Ōkami Legends",
            "'Splosion Arena", "[BIOS] System Menu", "Zzz Dream Quest", "~ Tilde Puzzle"
        )
        private val REGIONS = listOf("USA", "Europe", "Japan", "Spain", "World")

        @BeforeClass
        @JvmStatic
        fun createCatalogue() = runBlocking {
            TestData.deleteDatabase(NAME)
            db = TestData.fileDatabase(NAME)
            repository = DownloadableFileRepository(dao, SourceStatsService(db.downloadTrackDao(), fakeSettings("rommUrl" to flowOf(""))))
            db.manufacturerDao().insertManufacturer(TestData.manufacturer())
            db.consoleDao().insertConsoles(CONSOLES.map { (id, _) -> TestData.console(id, id.uppercase()) })

            val random = Random(42)
            val generated = LinkedHashMap<GameGroupRef, Game>()
            val start = System.nanoTime()
            var rows = 0
            for ((consoleId, ext) in CONSOLES) {
                // The edge cases first, so every console has them; then a shuffled mix.
                val titles = SPECIAL + FIRST.flatMap { a -> SECOND.flatMap { b -> listOf("$a $b") + (2..6).map { "$a $b $it" } } }.shuffled(random)
                val files = ArrayList<DownloadableFileEntity>()
                val tags = ArrayList<List<String>>()
                for (title in titles) {
                    if (files.size >= ROWS_PER_CONSOLE) break
                    val versions = REGIONS.shuffled(random).take(1 + random.nextInt(3))
                    val discs = if (consoleId == "psx" && random.nextInt(5) == 0) 2 else 1
                    var count = 0
                    versions.forEachIndexed { v, region ->
                        val revision = if (v == 0 && random.nextInt(6) == 0) " (Rev 1)" else ""
                        for (disc in 1..discs) {
                            val discTag = if (discs > 1) " (Disc $disc)" else ""
                            val fileName = "$title ($region)$revision$discTag.$ext"
                            files += TestData.webFile(consoleId, title, fileName, size = 100_000L + random.nextLong(50_000_000L))
                            tags += listOfNotNull(region, if (region == "Japan") "JA" else "EN", revision.takeIf { it.isNotEmpty() }?.let { "Rev 1" }, discTag.takeIf { it.isNotEmpty() }?.let { "Disc $disc" })
                            count++
                        }
                    }
                    val groupKey = files.last().groupKey
                    val previous = generated.put(GameGroupRef(consoleId, groupKey), Game(consoleId, groupKey, title, count, versions.size))
                    check(previous == null) { "\"$title\" and \"${previous?.title}\" share the key $groupKey" }
                }
                files.chunked(1_000).zip(tags.chunked(1_000)).forEach { (f, t) -> dao.insertFilesWithTags(f, t) }
                rows += files.size
            }
            Log.i(TAG, "inserted $rows rows (${generated.size} games) in ${(System.nanoTime() - start) / 1_000_000} ms")
            games = generated
            expectedAToZ = generated.values.sortedWith(compareBy<Game>({ it.title }, { it.consoleId }, { it.groupKey }))
            assertTrue("about 20 000 rows, got $rows", rows in 20_000..20_100)
        }

        @AfterClass
        @JvmStatic
        fun deleteCatalogue() {
            runCatching { db.close() }
            TestData.deleteDatabase(NAME)
        }
    }
}
