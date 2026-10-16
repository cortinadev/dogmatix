package com.cortinadev.dogmatix.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.cortinadev.dogmatix.data.local.dao.CollectionDao
import com.cortinadev.dogmatix.data.local.entity.CollectionEntity
import com.cortinadev.dogmatix.data.local.entity.CollectionGameEntity
import com.cortinadev.dogmatix.data.service.ActionHistoryService
import com.cortinadev.dogmatix.data.service.LibraryIndexService
import com.cortinadev.dogmatix.data.service.UndoHandler
import com.cortinadev.dogmatix.data.service.UndoOutcome
import com.cortinadev.dogmatix.data.state.CollectionFilterState
import com.cortinadev.dogmatix.data.state.LibraryFilterRequest
import com.cortinadev.dogmatix.data.state.PendingLibraryFilters
import com.cortinadev.dogmatix.util.ActionTypes
import com.cortinadev.dogmatix.util.CollectionRuleCodec
import com.cortinadev.dogmatix.util.CollectionRuleExtension
import com.cortinadev.dogmatix.util.CollectionRuleSql
import com.cortinadev.dogmatix.util.CollectionRules
import com.cortinadev.dogmatix.util.LibraryKeys
import com.cortinadev.dogmatix.util.OnDiskRule
import com.cortinadev.dogmatix.util.UndoKinds
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** A collection as the lists show it; [rules] null = a manual one. */
data class CollectionSummary(val id: Long, val name: String, val rules: CollectionRules?, val games: Int) {
    val isSmart: Boolean get() = rules != null
}

/** A read-only collection that lives elsewhere (RomM), listed next to the user's own. */
data class ExternalCollection(val sourceKey: String, val id: String, val name: String, val games: Int)

/**
 * Collections from another place (the RomM server's, for one), bound with Hilt `@IntoSet`. The
 * Collections tool lists them under [label] and opens them in the library like the user's own.
 */
interface ExternalCollectionSource {
    val key: String
    val label: String
    val collections: Flow<List<ExternalCollection>>

    /** The library games of collection [id], as (consoleId, groupKey). */
    suspend fun games(id: String): List<Pair<String, String>>
}

/**
 * The user's collections: manual ones (games added from the details card) and smart ones (games
 * worked out from rules, see [CollectionRules]). Games are kept by console + version group, so
 * they survive rescans like favourites. Opening one filters the library by it
 * ([CollectionFilterState]); deleting one is recorded in the history, whose Undo recreates it.
 */
@Singleton
class CollectionsRepository @Inject constructor(
    private val dao: CollectionDao,
    private val filter: CollectionFilterState,
    private val pendingFilters: PendingLibraryFilters,
    private val libraryIndex: Provider<LibraryIndexService>,
    private val history: Provider<ActionHistoryService>,
    private val extensions: Set<@JvmSuppressWildcards CollectionRuleExtension>,
    private val external: Set<@JvmSuppressWildcards ExternalCollectionSource>
) : UndoHandler {

    /** Every collection, by name; also what the global search lists. */
    val collections: Flow<List<CollectionSummary>> = dao.observeAll().map { rows ->
        rows.map { CollectionSummary(it.id, it.name, it.rules?.let { r -> CollectionRuleCodec.decode(r) ?: CollectionRules() }, it.games) }
    }

    /** Collections from the bound [ExternalCollectionSource]s (none until one is bound). */
    val externalCollections: Flow<List<ExternalCollection>> =
        if (external.isEmpty()) flowOf(emptyList())
        else combine(external.map { it.collections }) { lists -> lists.flatMap { it } }

    fun externalLabel(sourceKey: String): String = external.firstOrNull { it.key == sourceKey }?.label.orEmpty()

    /** Ids of the collections holding the game. */
    fun collectionsOf(consoleId: String, groupKey: String): Flow<Set<Long>> =
        dao.observeCollectionsOf(consoleId, groupKey).map { it.toSet() }

    /** A new collection ([rules] null = manual); returns its id, null for an empty name. */
    suspend fun create(name: String, rules: CollectionRules? = null): Long? {
        val clean = cleanName(name) ?: return null
        val id = dao.insert(CollectionEntity(name = clean, rules = rules?.let(CollectionRuleCodec::encode)))
        if (rules != null) evaluate(id)
        return id
    }

    suspend fun rename(id: Long, name: String) {
        val clean = cleanName(name) ?: return
        val current = dao.get(id) ?: return
        dao.update(current.copy(name = clean))
    }

    suspend fun updateRules(id: Long, rules: CollectionRules) {
        val current = dao.get(id) ?: return
        dao.update(current.copy(rules = CollectionRuleCodec.encode(rules)))
        evaluate(id)
    }

    suspend fun get(id: Long): CollectionSummary? = dao.get(id)?.let { c ->
        CollectionSummary(c.id, c.name, c.rules?.let { CollectionRuleCodec.decode(it) ?: CollectionRules() }, dao.games(id).size)
    }

    /** Adds the game to / takes it out of the manual collection [id]. */
    suspend fun setMember(id: Long, consoleId: String, groupKey: String, member: Boolean) {
        if (member) dao.insertGames(listOf(CollectionGameEntity(id, consoleId, groupKey)))
        else dao.removeGame(id, consoleId, groupKey)
        filter.touched(id)
    }

    /** Deletes [id] and records it; Undo brings it back with its games (a smart one re-evaluates). */
    suspend fun delete(id: Long) {
        val collection = dao.get(id) ?: return
        val games = dao.games(id)
        dao.deleteWithGames(id)
        if (filter.active.value?.id == id) filter.clear()
        history.get().record(
            ActionTypes.COLLECTION_DELETE, collection.name, games.size,
            undoKind = UndoKinds.COLLECTION, undoData = encodeSnapshot(collection, if (collection.isSmart) emptyList() else games)
        )
    }

    /**
     * Works the games of the smart collection [id] out of the library again: SQL for every rule
     * but "on disk", which is checked against the disk index in memory; paged so a big library
     * never comes in one piece. Returns how many games it holds.
     */
    suspend fun evaluate(id: Long): Int = withContext(Dispatchers.IO) {
        val collection = dao.get(id) ?: return@withContext 0
        val rules = CollectionRuleCodec.decode(collection.rules ?: return@withContext dao.games(id).size) ?: CollectionRules()
        val owned = if (rules.onDisk == OnDiskRule.ANY) emptySet() else libraryIndex.get().ownedKeys.value
        // Fields of other features may need their data first (genre / decade: the consoles' traits).
        extensions.forEach { extension -> rules.extras[extension.key]?.let { raw -> runCatching { extension.prepare(raw, rules.consoles) } } }
        val all = LinkedHashSet<Pair<String, String>>()
        val ownedGames = HashSet<Pair<String, String>>()
        var after = 0L
        while (true) {
            val page = CollectionRuleSql.matchPage(rules, extensions, after, PAGE)
            val rows = dao.matchRows(SimpleSQLiteQuery(page.sql, page.args.toTypedArray()))
            for (row in rows) {
                val key = row.consoleId to row.groupKey
                all += key
                if (rules.onDisk != OnDiskRule.ANY && LibraryKeys.isOwned(row.consoleId, row.fileName, owned)) ownedGames += key
            }
            if (rows.size < PAGE) break
            after = rows.last().id
        }
        // A game counts as on disk when any of its files is.
        val games = when (rules.onDisk) {
            OnDiskRule.ANY -> all
            OnDiskRule.OWNED -> all.filter { it in ownedGames }
            OnDiskRule.MISSING -> all.filter { it !in ownedGames }
        }
        val now = System.currentTimeMillis()
        dao.replaceGames(id, games.map { (console, group) -> CollectionGameEntity(id, console, group, now) })
        filter.touched(id)
        games.size
    }

    /**
     * Shows collection [id] in the library ("Collection: X"; B there clears it). A smart one is
     * evaluated first, so it reflects the library and the disk as they are now.
     */
    suspend fun showInLibrary(id: Long) {
        val collection = dao.get(id) ?: return
        if (collection.isSmart) evaluate(id)
        filter.show(id, collection.name)
        // Same hand-off as a deep link: the shell switches to the Library, which drops the other filters.
        pendingFilters.submit(LibraryFilterRequest())
    }

    /** Shows an external collection in the library, through a scratch slot of the games table. */
    suspend fun showExternalInLibrary(collection: ExternalCollection) {
        val source = external.firstOrNull { it.key == collection.sourceKey } ?: return
        val games = source.games(collection.id).distinct()
        val now = System.currentTimeMillis()
        dao.replaceGames(EXTERNAL_SLOT, games.map { (console, group) -> CollectionGameEntity(EXTERNAL_SLOT, console, group, now) })
        filter.show(EXTERNAL_SLOT, collection.name)
        pendingFilters.submit(LibraryFilterRequest())
    }

    private fun cleanName(name: String): String? = name.trim().take(MAX_NAME).ifEmpty { null }

    // ---- UndoHandler (UndoKinds.COLLECTION): recreates a deleted collection ---------------------

    override suspend fun isAvailable(data: String): Boolean = decodeSnapshot(data) != null

    override suspend fun undo(entryId: Long, data: String): UndoOutcome {
        val (collection, games) = decodeSnapshot(data) ?: return UndoOutcome.FAILED
        val id = dao.insert(collection.copy(id = 0))
        if (collection.isSmart) evaluate(id)
        else dao.replaceGames(id, games.map { it.copy(collectionId = id) })
        return UndoOutcome.DONE
    }

    private fun encodeSnapshot(collection: CollectionEntity, games: List<CollectionGameEntity>): String = JsonObject().apply {
        addProperty("name", collection.name)
        collection.rules?.let { addProperty("rules", it) }
        addProperty("createdAt", collection.createdAt)
        add("games", JsonArray().apply {
            games.forEach { g -> add(JsonArray().apply { add(g.consoleId); add(g.groupKey); add(g.addedAt) }) }
        })
    }.toString()

    private fun decodeSnapshot(data: String): Pair<CollectionEntity, List<CollectionGameEntity>>? = runCatching {
        val o = JsonParser.parseString(data).asJsonObject
        val collection = CollectionEntity(
            name = o.get("name").asString,
            rules = o.get("rules")?.takeIf { it.isJsonPrimitive }?.asString,
            createdAt = o.get("createdAt")?.asLong ?: System.currentTimeMillis()
        )
        val games = o.getAsJsonArray("games")?.map { e ->
            val a = e.asJsonArray
            CollectionGameEntity(0, a[0].asString, a[1].asString, a[2].asLong)
        }.orEmpty()
        collection to games
    }.getOrNull()

    companion object {
        /** Games of the external collection on show in the library (no row in `collections`). */
        const val EXTERNAL_SLOT = -1L
        const val MAX_NAME = 60
        private const val PAGE = 2000
    }
}
