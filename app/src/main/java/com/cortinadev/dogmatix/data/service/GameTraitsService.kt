package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.BuildConfig
import com.cortinadev.dogmatix.data.local.dao.GameTraitDao
import com.cortinadev.dogmatix.data.local.entity.GameTraitEntity
import com.cortinadev.dogmatix.data.local.entity.GameTraitSyncEntity
import com.cortinadev.dogmatix.data.repository.DownloadableFileRepository
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.GameTitleCleaner
import com.cortinadev.dogmatix.util.LibraryDecades
import com.cortinadev.dogmatix.util.LibraryGenre
import com.cortinadev.dogmatix.util.LibretroMetadat
import com.cortinadev.dogmatix.util.LibretroThumbnails
import com.cortinadev.dogmatix.util.Recommender
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Genre and release year of the library's games (`game_traits`), worked out per console on
 * demand — when a Genre / Decade filter is used, and for the recommendations:
 *  1. libretro-database's genre and release-year DATs for the console's system (no API key;
 *     downloaded once a month into the cache, see [LibretroMetadat]), matched by group key;
 *  2. for what those do not know, the details lookups already cached in `game_metadata`, and
 *     every new one as the details card finds it ([remember]).
 * A console is worked out again when its number of library rows changes (a rescan).
 */
@Singleton
class GameTraitsService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val files: DownloadableFileRepository,
    private val dao: GameTraitDao
) {
    private val userAgent = "Dogmatix/${BuildConfig.VERSION_NAME}"
    private val mutex = Mutex()

    private val _version = MutableStateFlow(0)
    /** Bumped whenever stored traits change, so filtered lists query again. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _loading = MutableStateFlow(false)
    /** True while consoles are being worked out (the list can say so instead of "no results"). */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Console → when its DAT download last failed (offline, rate limited): no retry for a while. */
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** Works out the consoles in [consoleIds] that need it. Returns true when anything was stored. */
    suspend fun ensure(consoleIds: Collection<String>): Boolean = withContext(Dispatchers.IO) {
        if (consoleIds.isEmpty()) return@withContext false
        mutex.withLock {
            val counts = files.consoleFileCounts()
            val now = System.currentTimeMillis()
            val stale = consoleIds.distinct().filter { id ->
                val rows = counts[id] ?: return@filter false
                val sync = dao.sync(id)
                val failed = failedAt[id]?.let { now - it < RETRY_AFTER_MS } == true
                !failed && (sync == null || sync.fileCount != rows ||
                    (sync.fetchedAt > 0 && now - sync.fetchedAt > DAT_MAX_AGE_MS))
            }
            if (stale.isEmpty()) return@withLock false
            _loading.value = true
            var changed = false
            try {
                for (consoleId in stale) {
                    try {
                        changed = workOut(consoleId, counts.getValue(consoleId)) || changed
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Traits for $consoleId failed", e)
                    }
                }
            } finally {
                _loading.value = false
                // Also when cancelled half-way: what was stored is there for the next query.
                if (changed) _version.update { it + 1 }
            }
            changed
        }
    }

    /**
     * What the details card found for a game (its genres and release date), for a game libretro
     * does not know. Never overrides what is stored, only fills gaps.
     */
    suspend fun remember(consoleId: String, groupKey: String, genres: List<String>, released: String) = withContext(Dispatchers.IO) {
        val mask = LibraryGenre.maskOf(genres)
        val year = LibraryDecades.parseYear(released)
        if (mask == 0 && year == null) return@withContext
        val inserted = dao.insertIfMissing(GameTraitEntity(consoleId, groupKey, mask, year, GameTraitEntity.SOURCE_DETAILS)) != -1L
        if (inserted || dao.fillMissing(consoleId, groupKey, mask, year) > 0) _version.update { it + 1 }
    }

    /** Stored genre bits of [games] (console + group key), keyed like [Recommender.key]; unknown games are left out. */
    suspend fun genresOf(games: Collection<Pair<String, String>>): Map<String, Int> = withContext(Dispatchers.IO) {
        games.distinct().groupBy({ it.first }, { it.second }).flatMap { (consoleId, keys) ->
            keys.chunked(500).flatMap { dao.forGames(consoleId, it) }
        }.associate { Recommender.key(it.consoleId, it.groupKey) to it.genres }
    }

    private suspend fun workOut(consoleId: String, rows: Int): Boolean {
        val system = LibretroThumbnails.systemFor(consoleId)
        val libretro = system?.let { datTraits(consoleId, it) } ?: emptyMap()
        val details = cachedDetails(consoleId)
        val traits = files.gameNames(consoleId).mapNotNull { game ->
            val fromDat = libretro[game.groupKey]
            val fromDetails = details[detailsKey(consoleId, game.name)]
            val mask = fromDat?.genres?.takeIf { it != 0 } ?: fromDetails?.genres ?: 0
            val year = fromDat?.year ?: fromDetails?.year
            if (mask == 0 && year == null) return@mapNotNull null
            val source = if (fromDat != null) GameTraitEntity.SOURCE_LIBRETRO else GameTraitEntity.SOURCE_DETAILS
            GameTraitEntity(consoleId, game.groupKey, mask, year, source)
        }
        val fetchedAt = if (system != null && libretro.isNotEmpty()) datFile(system, LibretroMetadat.Field.GENRE).lastModified().coerceAtLeast(1) else 0L
        dao.replaceConsole(consoleId, traits, GameTraitSyncEntity(consoleId, fetchedAt, rows))
        return traits.isNotEmpty()
    }

    /** The console's DATs (from the cache, else downloaded), matched by group key. */
    private fun datTraits(consoleId: String, system: LibretroThumbnails.System): Map<String, LibretroMetadat.Trait> {
        val genres = read(consoleId, system, LibretroMetadat.Field.GENRE)
        val years = read(consoleId, system, LibretroMetadat.Field.YEAR)
        return LibretroMetadat.traitsByGroup(genres, years)
    }

    private fun read(consoleId: String, system: LibretroThumbnails.System, field: LibretroMetadat.Field): List<Pair<String, String>> {
        val file = datFile(system, field)
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < DAT_MAX_AGE_MS
        if (!fresh) {
            try {
                download(LibretroMetadat.url(system, field), file)
            } catch (e: IOException) {
                // A copy that is only old still does; with none, try again later.
                if (!file.exists()) {
                    failedAt[consoleId] = System.currentTimeMillis()
                    throw e
                }
            }
        }
        if (!file.exists() || file.length() == 0L) return emptyList()
        return file.bufferedReader().useLines { LibretroMetadat.parse(it, field) }
    }

    private fun datFile(system: LibretroThumbnails.System, field: LibretroMetadat.Field): File =
        File(File(context.cacheDir, "metadat"), "${system.repo}.${field.folder}.dat")

    /** Saves [url] to [target]; a 404 (libretro has no DAT for the system) leaves an empty file. */
    private fun download(url: String, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", userAgent)
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
                HttpURLConnection.HTTP_NOT_FOUND -> temp.writeBytes(ByteArray(0))
                else -> throw IOException("HTTP $code for $url")
            }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("Could not save $target")
            }
        } finally {
            connection.disconnect()
            temp.delete()
        }
    }

    private data class Known(val genres: Int, val year: Int?)

    /** `game_metadata` rows of the console's platform, by their lookup key. */
    private suspend fun cachedDetails(consoleId: String): Map<String, Known> {
        val prefix = platformOf(consoleId) + "|"
        return dao.cachedDetails(prefix)
            // The key ends with the cleaned title that was looked up; a hit that no longer matches it is someone else's.
            .filter { it.lookupKey.startsWith(prefix) && GameTitleCleaner.matches(it.lookupKey.removePrefix(prefix), it.title) }
            .associate { it.lookupKey to Known(LibraryGenre.maskOf(it.genres.split('|')), LibraryDecades.parseYear(it.released)) }
    }

    /** The key `GameMetadataService` caches a lookup of [name] under. */
    private fun detailsKey(consoleId: String, name: String): String =
        "${platformOf(consoleId)}|${GameTitleCleaner.clean(name).lowercase()}"

    /** Same platform key as `GameMetadataService` (bare ids such as `super_nintendo_entertainment_system` stay whole). */
    private fun platformOf(consoleId: String) = ConsoleFormatter.consoleKey(consoleId).lowercase()

    private companion object {
        const val TAG = "GameTraitsService"
        const val DAT_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val RETRY_AFTER_MS = 10L * 60 * 1000
    }
}
