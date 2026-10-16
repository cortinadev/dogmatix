package com.cortinadev.dogmatix.data.service

import android.util.LruCache
import com.cortinadev.dogmatix.data.local.dao.CoverDao
import com.cortinadev.dogmatix.data.local.entity.CoverEntity
import com.cortinadev.dogmatix.data.model.LibraryGame
import com.cortinadev.dogmatix.util.CoverPolicy
import com.cortinadev.dogmatix.util.LibretroThumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The cover of a library game, for the square thumbnails of the list and the details card:
 * the RomM server's when RomM is set up and has the game (no lookup: it comes with the
 * catalogue), else libretro-thumbnails box art (no key needed), else an image the details
 * lookup already cached.
 * Results, misses included, are kept in Room ([CoverPolicy] decides for how long) and in memory,
 * so a game costs at most one lookup and scrolling back is instant.
 */
@Singleton
class CoverRepository @Inject constructor(
    private val dao: CoverDao,
    private val thumbnails: ThumbnailService,
    private val metadata: GameMetadataService,
    private val romm: RommCatalogService
) {
    /** Key → URL, "" for a known miss. */
    private val memory = LruCache<String, String>(4_000)
    /** A fast scroll must not open dozens of connections at once. */
    private val lookups = Semaphore(4)

    /** The cover already known in memory (no waiting), so a row can draw it on its first frame. */
    fun cached(consoleId: String, fileName: String): String? =
        romm.coverUrl(consoleId, fileName) ?: memory.get(CoverPolicy.key(consoleId, fileName))?.ifEmpty { null }

    /**
     * The cover of a whole game: its preferred version's, else the first of a few other versions
     * that has one ([CoverPolicy.otherVersions]). A cover found that way is kept under the
     * preferred version too, so its row and its recommendation card show it straight away.
     */
    suspend fun gameCoverUrl(game: LibraryGame): String? {
        val preferred = game.primary.file
        coverUrl(preferred.consoleId, preferred.fileName, preferred.name)?.let { return it }
        val others = CoverPolicy.otherVersions(
            LibretroThumbnails.lookupStem(preferred.fileName, preferred.name),
            game.versions.drop(1).map { it.primary.file }
        ) { LibretroThumbnails.lookupStem(it.fileName, it.name) }
        for (other in others) {
            val url = coverUrl(other.consoleId, other.fileName, other.name) ?: continue
            val key = CoverPolicy.key(preferred.consoleId, preferred.fileName)
            memory.put(key, url)
            withContext(Dispatchers.IO) { dao.upsert(CoverEntity(key, url, SOURCE_OTHER_VERSION, System.currentTimeMillis())) }
            return url
        }
        return null
    }

    /** The cover URL for a game, or null when none is known. [romName]: the row's display name (RomM and the details cache). */
    suspend fun coverUrl(consoleId: String, fileName: String, romName: String): String? {
        romm.coverUrl(consoleId, fileName, romName)?.let { return it }
        val key = CoverPolicy.key(consoleId, fileName)
        memory.get(key)?.let { return it.ifEmpty { null } }
        return withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            dao.get(key)?.takeIf { CoverPolicy.isFresh(it.url.isNotEmpty(), it.fetchedAt, now) }?.let { row ->
                memory.put(key, row.url)
                return@withContext row.url.ifEmpty { null }
            }
            lookups.withPermit {
                // Another row may have asked for the same game while this one waited.
                memory.get(key)?.let { return@withPermit it.ifEmpty { null } }
                val boxart = try {
                    thumbnails.boxart(consoleId, LibretroThumbnails.lookupName(fileName, romName))
                } catch (_: IOException) {
                    // Offline or rate limited: try again next time instead of remembering a miss.
                    return@withPermit metadata.cached(romName, consoleId)?.imageUrl?.ifBlank { null }
                }
                val found = boxart ?: metadata.cached(romName, consoleId)?.imageUrl?.ifBlank { null }
                val source = when {
                    boxart != null -> "libretro"
                    found != null -> "metadata"
                    else -> ""
                }
                dao.upsert(CoverEntity(key, found.orEmpty(), source, now))
                memory.put(key, found.orEmpty())
                found
            }
        }
    }

    private companion object {
        /** Stored source of a cover borrowed from another version of the same game. */
        const val SOURCE_OTHER_VERSION = "version"
    }
}
