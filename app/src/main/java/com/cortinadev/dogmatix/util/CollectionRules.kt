package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.SourceFilter
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/** "On disk" rule of a collection: any game, only games on disk, only games not on disk. */
enum class OnDiskRule { ANY, OWNED, MISSING }

/**
 * The rules of a smart collection; a game belongs when any of its files passes every rule. Values
 * of one kind are OR-ed (consoles, regions…), different kinds AND-ed, as in the library filters.
 * [extras] keeps rule fields this version does not know (added by other features, e.g. genre or
 * decade) as raw JSON, so editing a collection never drops them; [CollectionRuleExtension]s
 * turn them into SQL.
 */
data class CollectionRules(
    val consoles: Set<String> = emptySet(),
    val regions: Set<String> = emptySet(),
    val languages: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val favouritesOnly: Boolean = false,
    val onDisk: OnDiskRule = OnDiskRule.ANY,
    val source: SourceFilter = SourceFilter.ALL,
    /** Per file, in bytes; null = no bound. */
    val minBytes: Long? = null,
    val maxBytes: Long? = null,
    /** Words that must appear in the name (lenient, like the library search). */
    val nameContains: String = "",
    val extras: Map<String, String> = emptyMap()
) {
    /** A collection with no rule at all would hold the whole library. */
    val isEmpty: Boolean
        get() = consoles.isEmpty() && regions.isEmpty() && languages.isEmpty() && tags.isEmpty() &&
            !favouritesOnly && onDisk == OnDiskRule.ANY && source == SourceFilter.ALL &&
            minBytes == null && maxBytes == null && nameContains.isBlank() && extras.isEmpty()
}

/** SQL condition over `downloadable_files df`, with its bound arguments in order. */
data class SqlCondition(val sql: String, val args: List<Any> = emptyList())

/**
 * A rule field contributed by another feature: [key] is its name in the rules JSON, and
 * [condition] turns its raw JSON value into SQL over `downloadable_files df` (null = no
 * restriction). Bound with Hilt `@IntoSet`; see `CollectionsRepository`.
 */
interface CollectionRuleExtension {
    val key: String
    fun condition(rawJson: String): SqlCondition?

    /** Gets the data [condition] reads ready before a collection is worked out (consoles of its rules; empty = all). */
    suspend fun prepare(rawJson: String, consoleIds: Set<String>) {}
}

/** Stable key of a game in a collection: survives rescans like the favourites' keys. */
object CollectionKeys {
    fun gameKey(consoleId: String, groupKey: String): String = "$consoleId|$groupKey"
}

object CollectionRuleCodec {
    private val KNOWN = setOf("consoles", "regions", "languages", "tags", "favourites", "onDisk", "source", "minBytes", "maxBytes", "name")

    fun encode(rules: CollectionRules): String = JsonObject().apply {
        fun strings(key: String, values: Set<String>) {
            if (values.isNotEmpty()) add(key, JsonArray().apply { values.sorted().forEach(::add) })
        }
        strings("consoles", rules.consoles)
        strings("regions", rules.regions)
        strings("languages", rules.languages)
        strings("tags", rules.tags)
        if (rules.favouritesOnly) addProperty("favourites", true)
        if (rules.onDisk != OnDiskRule.ANY) addProperty("onDisk", rules.onDisk.name)
        if (rules.source != SourceFilter.ALL) addProperty("source", rules.source.name)
        rules.minBytes?.let { addProperty("minBytes", it) }
        rules.maxBytes?.let { addProperty("maxBytes", it) }
        if (rules.nameContains.isNotBlank()) addProperty("name", rules.nameContains.trim())
        rules.extras.forEach { (key, raw) ->
            if (key !in KNOWN) runCatching { JsonParser.parseString(raw) }.getOrNull()?.let { add(key, it) }
        }
    }.toString()

    /** Null for a document that is not a rules object; unknown or malformed fields are skipped or kept in extras. */
    fun decode(text: String?): CollectionRules? {
        val root = runCatching { JsonParser.parseString(text ?: return null).asJsonObject }.getOrNull() ?: return null
        fun strings(key: String): Set<String> = (root.get(key) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.asString?.trim()?.ifEmpty { null } }
            ?.toSet().orEmpty()
        fun long(key: String): Long? = (root.get(key) as? JsonPrimitive)?.takeIf { it.isNumber }?.asLong?.takeIf { it >= 0 }
        fun string(key: String): String? = (root.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.asString
        return CollectionRules(
            consoles = strings("consoles"),
            regions = strings("regions"),
            languages = strings("languages"),
            tags = strings("tags"),
            favouritesOnly = (root.get("favourites") as? JsonPrimitive)?.takeIf { it.isBoolean }?.asBoolean == true,
            onDisk = OnDiskRule.entries.firstOrNull { it.name == string("onDisk") } ?: OnDiskRule.ANY,
            source = SourceFilter.entries.firstOrNull { it.name == string("source") } ?: SourceFilter.ALL,
            minBytes = long("minBytes"),
            maxBytes = long("maxBytes"),
            nameContains = string("name").orEmpty().trim(),
            extras = root.entrySet().filter { it.key !in KNOWN }.associate { (k, v: JsonElement) -> k to v.toString() }
        )
    }
}

/** The SQL side of [CollectionRules]: one WHERE clause over `downloadable_files df`. */
object CollectionRuleSql {

    /** Mirrors the library DAO's notion of a RomM row (see RommSource). */
    private const val ROMM = "df.downloadUrl LIKE '%/api/roms/%/content/%'"

    /**
     * Every rule except [CollectionRules.onDisk] (what is on disk is only known in memory; the
     * caller filters on it) as one condition; [extensions] add the fields kept in extras.
     * Always valid SQL: no rule at all is `1`.
     */
    fun where(rules: CollectionRules, extensions: Collection<CollectionRuleExtension> = emptyList()): SqlCondition {
        val parts = ArrayList<String>()
        val args = ArrayList<Any>()
        fun inList(column: String, values: Set<String>) {
            parts += "$column IN (${values.joinToString(",") { "?" }})"
            args.addAll(values.sorted())
        }
        fun tagRule(values: Set<String>) {
            parts += "EXISTS (SELECT 1 FROM downloadable_file_tags t WHERE t.fileId = df.id AND t.tag IN (${values.joinToString(",") { "?" }}))"
            args.addAll(values.sorted())
        }
        if (rules.consoles.isNotEmpty()) inList("df.consoleId", rules.consoles)
        if (rules.regions.isNotEmpty()) tagRule(rules.regions)
        if (rules.languages.isNotEmpty()) tagRule(rules.languages)
        if (rules.tags.isNotEmpty()) tagRule(rules.tags)
        if (rules.favouritesOnly) {
            parts += "EXISTS (SELECT 1 FROM favourites f WHERE f.consoleId = df.consoleId AND f.fileName = df.fileName)"
        }
        when (rules.source) {
            SourceFilter.ALL -> Unit
            SourceFilter.TORRENT -> parts += "df.torrentFileIndex IS NOT NULL"
            SourceFilter.ROMM -> parts += ROMM
            SourceFilter.DIRECT -> parts += "(df.torrentFileIndex IS NULL AND NOT $ROMM)"
        }
        rules.minBytes?.let { parts += "df.fileSize >= ?"; args += it }
        rules.maxBytes?.let { parts += "df.fileSize <= ?"; args += it }
        SearchNormalizer.likePattern(rules.nameContains).takeIf { it.isNotEmpty() }?.let {
            parts += "df.searchKey LIKE ?"
            args += "%$it%"
        }
        for (extension in extensions) {
            val raw = rules.extras[extension.key] ?: continue
            val condition = runCatching { extension.condition(raw) }.getOrNull() ?: continue
            parts += "(${condition.sql})"
            args.addAll(condition.args)
        }
        return SqlCondition(if (parts.isEmpty()) "1" else parts.joinToString(" AND "), args)
    }

    /**
     * One page of matching rows, by id (keyset paging: OFFSET gets slower page by page on a big
     * library): `id, consoleId, groupKey, fileName`, with `?` for the last id seen and the page size last.
     */
    fun matchPage(rules: CollectionRules, extensions: Collection<CollectionRuleExtension>, afterId: Long, limit: Int): SqlCondition {
        val where = where(rules, extensions)
        return SqlCondition(
            "SELECT df.id AS id, df.consoleId AS consoleId, df.groupKey AS groupKey, df.fileName AS fileName " +
                "FROM downloadable_files df WHERE df.id > ? AND (${where.sql}) ORDER BY df.id LIMIT ?",
            listOf<Any>(afterId) + where.args + limit
        )
    }
}
