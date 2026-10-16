package com.cortinadev.dogmatix.data.repository

import android.util.Log
import com.cortinadev.dogmatix.data.service.RommCatalogService
import com.cortinadev.dogmatix.data.service.RommClient
import com.cortinadev.dogmatix.data.service.RommLibraryRef
import com.cortinadev.dogmatix.util.RommCollectionInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RommCollections"
/** Opening a details card re-reads the collections when they are older than this. */
private const val FRESH_MS = 2L * 60 * 1000

/** A game of a RomM collection; [library] is the library row it pairs with, null when there is none. */
data class RommCollectionGame(val romId: Int, val name: String, val library: RommLibraryRef?)

/** A RomM collection with its games paired with the library. */
data class RommCollection(
    val id: Int,
    val name: String,
    /** RomM's favourites collection (kept in step with the stars by the favourites sync). */
    val isFavourite: Boolean,
    /** The account owns it, so games can be added and removed (other users' public ones are read-only). */
    val editable: Boolean,
    val romIds: Set<Int>,
    val games: List<RommCollectionGame>
)

/**
 * The RomM server's collections (`/api/collections`), each with its games paired with the library
 * through the catalogue ([RommCatalogService.libraryRefsFor]). Read again after every catalogue
 * refresh and when a details card opens (if older than [FRESH_MS]); membership changes go to the
 * server at once and show here without waiting for a re-read.
 */
@Singleton
class RommCollectionsRepository @Inject constructor(
    private val client: RommClient,
    private val catalog: RommCatalogService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _collections = MutableStateFlow<List<RommCollection>>(emptyList())
    /** Every collection the account can see, favourites included; empty while RomM is not set up. */
    val collections: StateFlow<List<RommCollection>> = _collections.asStateFlow()

    private var raw: List<RommCollectionInfo> = emptyList()
    private var me: Int? = null
    private var fetchedAt = 0L

    init {
        scope.launch {
            catalog.state.map { it.configured to it.refreshedAt }.distinctUntilChanged().collectLatest { (configured, at) ->
                if (!configured) clear() else if (at > 0) refreshNow()
            }
        }
    }

    fun refreshIfStale() {
        if (System.currentTimeMillis() - fetchedAt < FRESH_MS) return
        refresh()
    }

    fun refresh() {
        scope.launch { refreshNow() }
    }

    /** Reads the collections again; false when the server could not be read (the last list stays). */
    suspend fun refreshNow(): Boolean = lock.withLock {
        try {
            raw = client.collections()
            me = runCatching { client.currentUserId() }.getOrNull() ?: me
            fetchedAt = System.currentTimeMillis()
            publish()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the RomM collections: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Adds ([member]) or removes ROM [romId] in collection [collectionId]. Throws when the server refuses. */
    suspend fun setMembership(collectionId: Int, romId: Int, member: Boolean) = lock.withLock {
        val collection = raw.firstOrNull { it.id == collectionId } ?: return@withLock
        if (member) client.addToCollection(collection, listOf(romId)) else client.removeFromCollection(collection, listOf(romId))
        raw = raw.map { c -> if (c.id != collectionId) c else c.copy(romIds = if (member) c.romIds + romId else c.romIds - romId) }
        publish()
    }

    private suspend fun publish() {
        val refs = catalog.libraryRefsFor(raw.flatMap { it.romIds })
        val index = catalog.index.value
        val owner = me
        _collections.value = raw.map { c ->
            RommCollection(
                id = c.id,
                name = c.name,
                isFavourite = c.isFavourite,
                editable = owner == null || c.ownerId == null || c.ownerId == owner,
                romIds = c.romIds,
                games = c.romIds.map { id ->
                    val ref = refs[id]
                    RommCollectionGame(id, ref?.name ?: index.forRomId(id).firstOrNull()?.name.orEmpty(), ref)
                }
            )
        }
    }

    private suspend fun clear() = lock.withLock {
        raw = emptyList()
        me = null
        fetchedAt = 0L
        _collections.value = emptyList()
    }
}
