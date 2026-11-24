package com.cortinadev.dogmatix.data.local

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cortinadev.dogmatix.data.local.dao.GameGroupRef
import com.cortinadev.dogmatix.data.local.entity.CollectionEntity
import com.cortinadev.dogmatix.data.local.entity.CollectionGameEntity
import com.cortinadev.dogmatix.data.local.entity.FavouriteEntity
import com.cortinadev.dogmatix.data.local.entity.GameTraitEntity
import com.cortinadev.dogmatix.testing.LibraryQueries.buckets
import com.cortinadev.dogmatix.testing.LibraryQueries.gamePage
import com.cortinadev.dogmatix.testing.TestData
import com.cortinadev.dogmatix.testing.TestData.rows
import com.cortinadev.dogmatix.util.SearchNormalizer
import com.cortinadev.dogmatix.util.TitleClones
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regrouping translated titles into one game on a real Room database (TitleClonesService's
 * path without the download): rows change game, genre / year and collection membership follow,
 * favourites stay by file name, a second pass changes nothing and turning it off splits the game
 * back. Plus the time a 60 000-row console takes (tag `DogmatixPerf`, not asserted).
 */
@RunWith(AndroidJUnit4::class)
class TitleRegroupTest {

    private lateinit var db: DogmatixDatabase
    private val files get() = db.downloadableFileDao()
    private val sql get() = db.openHelper.writableDatabase

    private val map = mapOf(
        "pokemon - firered version" to "pokemon - firered version",
        "pokemon - edicion rojo fuego" to "pokemon - firered version",
        "pocket monsters - firered" to "pokemon - firered version"
    )

    @Before
    fun setUp() = runBlocking {
        db = TestData.inMemoryDatabase()
        db.manufacturerDao().insertManufacturer(TestData.manufacturer())
        db.consoleDao().insertConsoles(listOf(TestData.console(GBA, "Game Boy Advance")))
        files.insertAll(
            listOf(
                TestData.webFile(GBA, "Pokemon - FireRed Version", "Pokemon - FireRed Version (USA, Europe).gba"),
                TestData.webFile(GBA, "Pokemon - Edicion Rojo Fuego", "Pokemon - Edicion Rojo Fuego (Spain).gba"),
                TestData.webFile(GBA, "Pocket Monsters - FireRed", "Pocket Monsters - FireRed (Japan).gba"),
                TestData.webFile(GBA, "Pokemon - LeafGreen Version", "Pokemon - LeafGreen Version (USA, Europe).gba")
            )
        )
        Unit
    }

    @After
    fun tearDown() {
        TitleClones.enabled = true
        db.close()
    }

    private suspend fun regroup(keyOf: (String) -> String) {
        files.regroup(GBA, TitleClones.plan(files.titleRows(GBA), keyOf))
    }

    @Test
    fun translatedTitlesBecomeOneGameAndWhatIsKeyedByGameFollows() = runBlocking {
        // Before: four games. Traits on the Spanish title, a collection holding the Japanese one, a ★ on the Spanish file.
        assertEquals(4, files.gamePage(limit = 100).size)
        db.gameTraitDao().insertIfMissing(GameTraitEntity(GBA, "pokemon - edicion rojo fuego", 1, 2004, GameTraitEntity.SOURCE_LIBRETRO))
        val collection = db.collectionDao().insert(CollectionEntity(name = "RPG"))
        db.collectionDao().insertGames(listOf(CollectionGameEntity(collection, GBA, "pocket monsters - firered")))
        db.favouriteDao().upsert(FavouriteEntity(GBA, "Pokemon - Edicion Rojo Fuego (Spain).gba", 1L))

        regroup { TitleClones.canonicalIn(map, it) }

        val games = files.gamePage(limit = 100)
        assertEquals(
            listOf(GameGroupRef(GBA, "pokemon - firered version"), GameGroupRef(GBA, "pokemon - leafgreen version")),
            games
        )
        // Every title is a version of the one game; each row keeps its own title key.
        val rows = sql.rows("SELECT name, titleKey, groupKey FROM downloadable_files WHERE groupKey = 'pokemon - firered version' ORDER BY name")
        assertEquals(3, rows.size)
        assertEquals(listOf("pocket monsters - firered", "pokemon - edicion rojo fuego", "pokemon - firered version"), rows.map { it["titleKey"] })
        // Traits and collection moved to the game, nothing left under the old keys.
        assertEquals(listOf("pokemon - firered version"), sql.rows("SELECT groupKey FROM game_traits").map { it["groupKey"] })
        assertEquals(listOf("pokemon - firered version"), sql.rows("SELECT groupKey FROM collection_games").map { it["groupKey"] })
        // Favourites go by file name: untouched.
        assertEquals(listOf("Pokemon - Edicion Rojo Fuego (Spain).gba"), sql.rows("SELECT fileName FROM favourites").map { it["fileName"] })
        // Found by any of its titles.
        assertEquals(games.take(1), files.gamePage(limit = 10, query = SearchNormalizer.likePattern("rojo fuego")))
        assertEquals(games.take(1), files.gamePage(limit = 10, query = SearchNormalizer.likePattern("fire red")))
        // Sorted (and bucketed) by the clone list's title, the USA one here: both under P.
        assertEquals(mapOf("P" to 2), files.buckets().associate { it.bucket to it.games })

        // A second pass has nothing to do.
        assertTrue(TitleClones.plan(files.titleRows(GBA)) { TitleClones.canonicalIn(map, it) }.isEmpty)

        // Off: every title is its own game again; the traits and the membership go to each.
        regroup { it }
        assertEquals(4, files.gamePage(limit = 100).size)
        assertEquals(3, sql.rows("SELECT groupKey FROM game_traits").size)
        assertEquals(3, sql.rows("SELECT groupKey FROM collection_games").size)
    }

    @Test
    fun sixtyThousandRowsRegroupInReasonableTime() = runBlocking<Unit> {
        val console = "nintendo_gba"
        db.consoleDao().insertConsoles(listOf(TestData.console(console, "GBA big")))
        val big = HashMap<String, String>()
        val batch = ArrayList<com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity>()
        for (i in 0 until 20_000) {
            val usa = "Game $i - English Title"
            val spa = "Juego $i - Titulo Espanol"
            val jpn = "Geemu $i - Nihongo"
            listOf(usa to "USA", spa to "Spain", jpn to "Japan").forEach { (name, region) ->
                batch += TestData.webFile(console, name, "$name ($region).gba")
            }
            // Two games in three of every four are listed together.
            if (i % 4 != 0) {
                val canonical = usa.lowercase()
                big[usa.lowercase()] = canonical
                big[spa.lowercase()] = canonical
                big[jpn.lowercase()] = canonical
            }
        }
        batch.chunked(5_000).forEach { files.insertAll(it) }

        val start = SystemClock.elapsedRealtime()
        val plan = TitleClones.plan(files.titleRows(console)) { TitleClones.canonicalIn(big, it) }
        val planned = SystemClock.elapsedRealtime()
        files.regroup(console, plan)
        val done = SystemClock.elapsedRealtime()
        Log.i("DogmatixPerf", "regroup 60 000 rows: plan ${planned - start} ms, write ${done - planned} ms, ${plan.updates.values.sumOf { it.size }} rows moved")

        assertEquals(30_000, plan.updates.values.sumOf { it.size })
        assertEquals(15_000 + 5_000 * 3, files.gamePage(limit = Int.MAX_VALUE, consoleIds = listOf(console)).size)
        val second = SystemClock.elapsedRealtime()
        assertTrue(TitleClones.plan(files.titleRows(console)) { TitleClones.canonicalIn(big, it) }.isEmpty)
        Log.i("DogmatixPerf", "regroup 60 000 rows again (nothing to do): ${SystemClock.elapsedRealtime() - second} ms")
    }

    private companion object {
        const val GBA = "nintendo_game_boy_advance"
    }
}
