package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.cortinadev.dogmatix.data.local.dao.RommDao
import com.cortinadev.dogmatix.data.local.entity.FavouriteEntity
import com.cortinadev.dogmatix.data.local.entity.RommFavouriteBaseEntity
import com.cortinadev.dogmatix.data.repository.FavouritesRepository
import com.cortinadev.dogmatix.data.repository.RommCollectionsRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.RommCollectionInfo
import com.cortinadev.dogmatix.util.RommFavouritesMerge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RommFavouritesSync"
/** Stars changed in a row are gathered this long before one sync goes out. */
private const val DEBOUNCE_MS = 2_500L

/** How the last favourites sync went (Settings → RomM, under the switch). */
data class RommFavouritesSyncState(
    val running: Boolean = false,
    /** When the last sync finished without error; 0 = never. */
    val syncedAt: Long = 0L,
    /** Stars added / removed here, favourites added / removed on the server by the last sync. */
    val starred: Int = 0,
    val unstarred: Int = 0,
    val added: Int = 0,
    val removed: Int = 0,
    val error: String? = null
)

/**
 * Settings → RomM → "Sync favourites with RomM": the stars here and RomM's favourites
 * collection (`is_favorite`) in step. The first sync is the union of both; from then on a star
 * changed here reaches the server a moment later and a change on the server comes down with
 * the next catalogue refresh, told apart by the set agreed at the last sync
 * (`romm_favourite_base`, see [RommFavouritesMerge]). Only games paired with the catalogue
 * take part.
 */
@OptIn(FlowPreview::class)
@Singleton
class RommFavouritesSyncService @Inject constructor(
    private val settings: SettingsRepository,
    private val client: RommClient,
    private val catalog: RommCatalogService,
    private val favourites: FavouritesRepository,
    private val dao: RommDao,
    private val collections: RommCollectionsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _state = MutableStateFlow(RommFavouritesSyncState())
    val state: StateFlow<RommFavouritesSyncState> = _state.asStateFlow()

    /** The star keys the last sync left; their echo from the database is not a change. */
    @Volatile private var applied: Set<String>? = null

    init {
        scope.launch {
            settings.rommFavouritesSync.distinctUntilChanged().collectLatest { on ->
                if (!on) {
                    // Turned on again later, the first sync is a union again: nothing gets removed.
                    dao.clearFavouriteBase()
                    applied = null
                    _state.value = RommFavouritesSyncState()
                    return@collectLatest
                }
                merge(
                    favourites.keys.filter { it != applied }.map { },
                    catalog.state.map { it.refreshedAt }.distinctUntilChanged().map { }
                ).debounce(DEBOUNCE_MS).collect { syncQuietly() }
            }
        }
    }

    /** "Sync now" in Settings → RomM; nothing while the switch is off. */
    fun syncNow() {
        scope.launch { if (settings.rommFavouritesSync.first()) syncQuietly() }
    }

    private suspend fun syncQuietly() {
        try {
            sync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Already in [state]; the next star or catalogue refresh tries again.
            Log.w(TAG, "Favourites sync failed: ${e.javaClass.simpleName}")
        }
    }

    /** One merge of the stars with the server's favourites. False when there is nothing to merge with yet. */
    suspend fun sync(): Boolean = lock.withLock {
        val index = catalog.index.value
        if (!catalog.state.value.configured || index.isEmpty) return@withLock false
        _state.update { it.copy(running = true, error = null) }
        try {
            val me = runCatching { client.currentUserId() }.getOrNull()
            val all = client.collections()
            val picked = RommFavouritesMerge.pickFavourites(
                all.map { RommFavouritesMerge.CollectionRef(it.id, it.name, it.ownerId, it.isFavourite) }, me
            )
            val favourite: RommCollectionInfo? = picked?.let { p -> all.first { it.id == p.id } }

            val stars = readStars()
            val starsByRom = HashMap<Int, MutableList<FavouriteEntity>>()
            stars.forEach { s -> catalog.entryForStar(s.consoleId, s.fileName, index)?.let { starsByRom.getOrPut(it.romId) { ArrayList() } += s } }
            val local = starsByRom.keys.toSet()
            val remote = favourite?.romIds.orEmpty()
            val baseRows = dao.favouriteBase()
            val server = catalog.baseUrl
            val base = baseRows.takeIf { rows -> favourite != null && rows.isNotEmpty() && rows.all { it.collectionId == favourite.id && it.server == server } }
                ?.map { it.romId }?.toSet()
            val refs = catalog.libraryRefsFor((remote + base.orEmpty()) - local)
            val plan = RommFavouritesMerge.plan(local, remote, base, refs.keys)

            var target = favourite
            if (plan.addRemote.isNotEmpty()) {
                val collection = target ?: RommCollectionInfo(
                    id = client.createCollection(RommFavouritesMerge.NEW_COLLECTION_NAME, favourite = true),
                    name = RommFavouritesMerge.NEW_COLLECTION_NAME, romIds = emptySet(), ownerId = me, isFavourite = true
                )
                client.addToCollection(collection, plan.addRemote)
                target = collection
            }
            target?.let { if (plan.removeRemote.isNotEmpty()) client.removeFromCollection(it, plan.removeRemote) }
            if (plan.addRemote.isNotEmpty() || plan.removeRemote.isNotEmpty()) collections.refresh()

            val toStar = plan.addLocal.mapNotNull { refs[it] }.map { FavouriteEntity(it.consoleId, it.fileName) }.distinctBy { it.key }
            val toUnstar = plan.removeLocal.flatMap { starsByRom[it].orEmpty() }
            writeStars(toStar, toUnstar)
            applied = readStars().mapTo(HashSet()) { it.key }

            dao.replaceFavouriteBase(target?.let { t -> plan.base.map { RommFavouriteBaseEntity(it, t.id, server) } }.orEmpty())
            _state.value = RommFavouritesSyncState(
                syncedAt = System.currentTimeMillis(),
                starred = toStar.size,
                unstarred = toUnstar.size,
                added = plan.addRemote.size,
                removed = plan.removeRemote.size
            )
            true
        } catch (e: Exception) {
            _state.update { it.copy(running = false, error = if (e is CancellationException) null else (e.message ?: e.javaClass.simpleName).take(160)) }
            throw e
        }
    }

    // The stars here, read and written in one place, through the favourites repository's bulk calls.
    private suspend fun readStars(): List<FavouriteEntity> = favourites.all()

    private suspend fun writeStars(add: List<FavouriteEntity>, remove: List<FavouriteEntity>) {
        // Adds never replace a star that is already there (its date stays).
        favourites.addAll(add.map { it.consoleId to it.fileName })
        favourites.removeAll(remove.map { it.consoleId to it.fileName })
    }
}
