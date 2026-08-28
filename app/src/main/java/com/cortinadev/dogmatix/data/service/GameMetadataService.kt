package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.cortinadev.dogmatix.BuildConfig
import com.cortinadev.dogmatix.data.local.dao.GameMetadataDao
import com.cortinadev.dogmatix.data.local.entity.GameMetadataEntity
import com.cortinadev.dogmatix.data.model.GameDetails
import com.cortinadev.dogmatix.util.GameTitleCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Looks a game up online: RAWG first (generous quota), TheGamesDB as a fallback (1000 calls/month).
 * Every result, including misses, is cached in Room so each title costs at most one lookup.
 */
@Singleton
class GameMetadataService @Inject constructor(
    private val dao: GameMetadataDao
) {
    private val userAgent = "Dogmatix/${BuildConfig.VERSION_NAME}"

    suspend fun lookup(name: String, consoleId: String): GameDetails? = withContext(Dispatchers.IO) {
        val title = GameTitleCleaner.clean(name)
        if (title.isBlank()) return@withContext null
        val platform = consoleId.substringAfter("_", consoleId).lowercase()
        val key = "$platform|${title.lowercase()}"

        dao.get(key)?.let { cached ->
            if (cached.source.isNotEmpty()) return@withContext cached.toDetails()
            if (System.currentTimeMillis() - cached.fetchedAt < MISS_TTL_MS) return@withContext null
        }

        val rawg = runCatching { fromRawg(title, platform) }.onFailure { Log.w(TAG, "RAWG lookup failed", it) }.getOrNull()
        // RAWG keeps one entry per game with artwork shared across platforms (an Xbox shot on a
        // GameCube title). TheGamesDB has one entry per platform, so its box art is used instead.
        val tgdb = if (rawg == null || rawg.multiPlatform) {
            runCatching { fromTheGamesDb(title, platform) }.onFailure { Log.w(TAG, "TheGamesDB lookup failed", it) }.getOrNull()
        } else null
        val details = when {
            rawg == null -> tgdb
            tgdb?.imageUrl?.isNotEmpty() == true -> rawg.copy(imageUrl = tgdb.imageUrl, source = "RAWG · TheGamesDB")
            else -> rawg
        }

        dao.upsert(
            GameMetadataEntity(
                lookupKey = key,
                title = details?.title.orEmpty(),
                description = details?.description.orEmpty(),
                genres = details?.genres?.joinToString(GENRE_SEPARATOR).orEmpty(),
                released = details?.released.orEmpty(),
                developer = details?.developer.orEmpty(),
                imageUrl = details?.imageUrl.orEmpty(),
                source = details?.source.orEmpty(),
                fetchedAt = System.currentTimeMillis()
            )
        )
        details
    }

    // ---- RAWG -------------------------------------------------------------------------------

    private fun fromRawg(title: String, platform: String): GameDetails? {
        val key = BuildConfig.RAWG_API_KEY
        if (key.isEmpty()) return null
        val platformParam = RAWG_PLATFORMS[platform]?.let { "&platforms=$it" }.orEmpty()
        val search = getJson("https://api.rawg.io/api/games?key=$key&search=${encode(title)}&search_precise=true&page_size=5$platformParam")
        val game = search.getAsJsonArray("results")?.map { it.asJsonObject }
            ?.firstOrNull { GameTitleCleaner.matches(title, it.str("name")) } ?: return null
        val id = game.get("id").asLong
        // The search result has no synopsis; one more call fetches it together with the developers.
        val full = runCatching { getJson("https://api.rawg.io/api/games/$id?key=$key") }.getOrNull()
        val screenshot = game.getAsJsonArray("short_screenshots")?.map { it.asJsonObject.str("image") }?.firstOrNull { it.isNotEmpty() }
        return GameDetails(
            title = game.str("name"),
            description = full?.str("description_raw").orEmpty().unwrapLines(),
            genres = game.getAsJsonArray("genres")?.map { it.asJsonObject.str("name") }.orEmpty(),
            released = game.str("released").take(4),
            developer = full?.getAsJsonArray("developers")?.map { it.asJsonObject.str("name") }?.joinToString(", ").orEmpty(),
            imageUrl = screenshot ?: game.str("background_image"),
            source = "RAWG",
            multiPlatform = (game.getAsJsonArray("platforms")?.size() ?: 0) > 1
        )
    }

    // ---- TheGamesDB ---------------------------------------------------------------------------

    private fun fromTheGamesDb(title: String, platform: String): GameDetails? {
        val key = BuildConfig.THEGAMESDB_API_KEY
        if (key.isEmpty()) return null
        val platformParam = TGDB_PLATFORMS[platform]?.let { "&filter%5Bplatform%5D=$it" }.orEmpty()
        val json = getJson(
            "https://api.thegamesdb.net/v1.1/Games/ByGameName?apikey=$key&name=${encode(title)}" +
                "&fields=overview,genres,players,developers&include=boxart$platformParam"
        )
        val game = json.getAsJsonObject("data")?.getAsJsonArray("games")?.map { it.asJsonObject }
            ?.firstOrNull { GameTitleCleaner.matches(title, it.str("game_title")) } ?: return null
        val id = game.str("id")
        val boxart = json.getAsJsonObject("include")?.getAsJsonObject("boxart")
        val baseUrl = boxart?.getAsJsonObject("base_url")?.str("medium").orEmpty()
        val images = boxart?.getAsJsonObject("data")?.getAsJsonArray(id)?.map { it.asJsonObject }.orEmpty()
        val front = images.firstOrNull { it.str("side") == "front" } ?: images.firstOrNull()
        return GameDetails(
            title = game.str("game_title"),
            description = game.str("overview").unwrapLines(),
            genres = game.getAsJsonArray("genres")?.mapNotNull { TGDB_GENRES[it.asInt] }.orEmpty(),
            released = game.str("release_date").take(4),
            developer = "",
            imageUrl = front?.let { baseUrl + it.str("filename") }.orEmpty(),
            source = "TheGamesDB"
        )
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun getJson(url: String): JsonObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode} for ${url.substringBefore('?')}")
            return connection.inputStream.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        } finally {
            connection.disconnect()
        }
    }

    /** Databases hard-wrap their text; keep only blank lines as paragraph breaks. */
    private fun String.unwrapLines(): String = replace(Regex("(?<!\\n)\\n(?!\\n)"), " ").replace(Regex(" {2,}"), " ").trim()

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun JsonObject.str(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    private fun GameMetadataEntity.toDetails() = GameDetails(
        title = title,
        description = description,
        genres = genres.split(GENRE_SEPARATOR).filter { it.isNotEmpty() },
        released = released,
        developer = developer,
        imageUrl = imageUrl,
        source = source
    )

    companion object {
        private const val TAG = "GameMetadataService"
        private const val GENRE_SEPARATOR = "|"
        private const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000

        /** Console id (without manufacturer prefix) → RAWG platform id. */
        private val RAWG_PLATFORMS = mapOf(
            "nintendo_entertainment_system" to 49, "super_nintendo_entertainment_system" to 79,
            "gameboy" to 26, "gameboy_color" to 43, "gameboy_advance" to 24,
            "nintendo_64" to 83, "nintendo_ds" to 9, "nintendo_3ds" to 8, "gamecube" to 105,
            "playstation" to 27, "playstation_2" to 15, "playstation_portable" to 17,
            "master_system" to 74, "genesis" to 167, "dreamcast" to 106, "cd" to 119
        )

        /** Console id (without manufacturer prefix) → TheGamesDB platform id. */
        private val TGDB_PLATFORMS = mapOf(
            "nintendo_entertainment_system" to 7, "super_nintendo_entertainment_system" to 6,
            "gameboy" to 4, "gameboy_color" to 41, "gameboy_advance" to 5,
            "nintendo_64" to 3, "nintendo_ds" to 8, "nintendo_3ds" to 4912, "gamecube" to 2,
            "playstation" to 10, "playstation_2" to 11, "playstation_portable" to 13,
            "master_system" to 35, "genesis" to 18, "dreamcast" to 16, "cd" to 21
        )

        /** TheGamesDB genre ids (the /Genres endpoint would cost a request per install). */
        private val TGDB_GENRES = mapOf(
            1 to "Action", 2 to "Adventure", 3 to "Construction and Management Simulation", 4 to "Role-Playing",
            5 to "Puzzle", 6 to "Strategy", 7 to "Racing", 8 to "Shooter", 9 to "Life Simulation", 10 to "Fighting",
            11 to "Sports", 12 to "Sandbox", 13 to "Flight Simulator", 14 to "MMO", 15 to "Platform", 16 to "Stealth",
            17 to "Music", 18 to "Horror", 19 to "Vehicle Simulation", 20 to "Board", 21 to "Education", 22 to "Family",
            23 to "Party", 24 to "Productivity", 25 to "Quiz", 26 to "Utility", 27 to "Virtual Console",
            28 to "Unofficial", 29 to "GBA Video / PSP Video", 30 to "Demo"
        )
    }
}
