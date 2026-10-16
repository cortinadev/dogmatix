package com.cortinadev.dogmatix.util

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/** A ROM of the RomM server as the catalogue keeps it (one item of `GET /api/roms`). */
data class RommCatalogRom(
    val romId: Int,
    val platformId: Int,
    /** File name, or folder name for a multi-file ROM. */
    val fsName: String,
    val name: String,
    /** Leaf names of the ROM's files (the discs of a folder ROM); may be empty. */
    val fileNames: List<String>,
    /** Lower-case CRC / MD5 / SHA-1 of the ROM and of its files. */
    val hashes: List<String>,
    /** Cover paths as the server sends them (see [RommCovers.url]); "" when there is none. */
    val coverSmall: String,
    val coverLarge: String,
    val props: RommUserProps
)

/** What the details card shows of a game on the server (`GET /api/roms/{id}`). */
data class RommGameInfo(
    val romId: Int,
    val name: String,
    val summary: String,
    val genres: List<String>,
    /** Community rating on 0–100; null when no source rated it. */
    val ratingPercent: Int?,
    val props: RommUserProps
)

/** A RomM collection and its games. [ownerId] is null when the server does not say. */
data class RommCollectionInfo(
    val id: Int,
    val name: String,
    val romIds: Set<Int>,
    val ownerId: Int?,
    /** RomM's own favourites collection (the heart in its web interface). */
    val isFavourite: Boolean
)

/**
 * Reads RomM's JSON answers. Every field is optional: RomM 3, 4 and 5 differ in what they send
 * (`path_cover_s` vs `path_cover_small`, files always vs on request…), so a missing field never
 * fails the whole answer. Pure, so the shapes are unit-tested.
 */
object RommJson {

    /** The objects of a list answer: a bare array or a page (`{"items": […]}`). */
    fun items(json: JsonElement?): List<JsonObject> {
        val array = when {
            json == null || json.isJsonNull -> return emptyList()
            json.isJsonArray -> json.asJsonArray
            json.isJsonObject -> json.asJsonObject.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
    }

    fun catalogRoms(json: JsonElement?): List<RommCatalogRom> = items(json).mapNotNull(::catalogRom)

    fun catalogRom(o: JsonObject): RommCatalogRom? {
        val id = o.int("id") ?: return null
        val fsName = o.text("fs_name") ?: o.text("file_name") ?: return null
        val files = o.array("files")
        val hashes = LinkedHashSet<String>()
        fun addHashes(from: JsonObject) = HASH_FIELDS.forEach { f -> from.text(f)?.lowercase()?.let(hashes::add) }
        addHashes(o)
        files.forEach(::addHashes)
        return RommCatalogRom(
            romId = id,
            platformId = o.int("platform_id") ?: 0,
            fsName = fsName,
            name = o.text("name") ?: o.text("fs_name_no_tags").orEmpty(),
            fileNames = files.mapNotNull { it.text("file_name") }.distinct(),
            hashes = hashes.toList(),
            coverSmall = o.text("path_cover_small") ?: o.text("path_cover_s").orEmpty(),
            coverLarge = o.text("path_cover_large") ?: o.text("path_cover_l").orEmpty(),
            props = o.child("rom_user")?.let(::readProps) ?: RommUserProps()
        )
    }

    fun gameInfo(json: JsonElement?): RommGameInfo? {
        val o = json?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val id = o.int("id") ?: return null
        val metadatum = o.child("metadatum")
        val igdb = o.child("igdb_metadata")
        val genres = (metadatum?.strings("genres")?.takeIf { it.isNotEmpty() }
            ?: igdb?.strings("genres")?.takeIf { it.isNotEmpty() }
            ?: o.strings("genres"))
            .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        val rating = sequenceOf(metadatum?.get("average_rating"), igdb?.get("total_rating"), igdb?.get("aggregated_rating"))
            .firstNotNullOfOrNull { ratingPercent(it) }
        return RommGameInfo(
            romId = id,
            name = o.text("name") ?: o.text("fs_name_no_tags") ?: o.text("fs_name").orEmpty(),
            summary = o.text("summary") ?: igdb?.text("summary").orEmpty(),
            genres = genres,
            ratingPercent = rating,
            props = o.child("rom_user")?.let(::readProps) ?: RommUserProps()
        )
    }

    /** A `rom_user` object or the answer of `PUT …/props` (which RomM 4 wraps in `data`). */
    fun props(json: JsonElement?): RommUserProps? {
        val o = json?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val inner = o.child("rom_user") ?: o.child("data")?.takeIf { d -> PROP_FIELDS.any(d::has) } ?: o
        if (PROP_FIELDS.none(inner::has)) return null
        return readProps(inner)
    }

    fun collections(json: JsonElement?): List<RommCollectionInfo> = items(json).mapNotNull(::collectionOf)

    fun collection(json: JsonElement?): RommCollectionInfo? = json?.takeIf { it.isJsonObject }?.asJsonObject?.let(::collectionOf)

    /** `id` of an object answer (`/api/users/me`, a created collection). */
    fun id(json: JsonElement?): Int? = json?.takeIf { it.isJsonObject }?.asJsonObject?.int("id")

    /** A rating on 0–100: RomM averages and IGDB use 0–100, values of 10 or less are read as 0–10. */
    fun ratingPercent(value: JsonElement?): Int? {
        val v = (value as? JsonPrimitive)?.takeIf { it.isNumber || it.isString }?.asString?.toDoubleOrNull() ?: return null
        if (v <= 0.0) return null
        return (if (v <= 10.0) v * 10 else v).toInt().coerceIn(1, 100)
    }

    private fun collectionOf(o: JsonObject): RommCollectionInfo? {
        val id = o.int("id") ?: return null
        // `rom_ids` since RomM 3.x; very old servers listed the ROMs themselves.
        val ids = o.get("rom_ids")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { (it as? JsonPrimitive)?.asNumberOrNull() }
            ?: o.array("roms").mapNotNull { it.int("id") }
        return RommCollectionInfo(
            id = id,
            name = o.text("name").orEmpty(),
            romIds = ids.toSet(),
            ownerId = o.int("user_id"),
            isFavourite = o.bool("is_favorite") == true
        )
    }

    private fun readProps(u: JsonObject) = RommUserProps(
        status = RommPlayState.normaliseStatus(u.text("status")),
        backlogged = u.bool("backlogged") == true,
        nowPlaying = u.bool("now_playing") == true
    )

    private val HASH_FIELDS = listOf("crc_hash", "md5_hash", "sha1_hash")
    private val PROP_FIELDS = listOf("status", "backlogged", "now_playing", "rating", "hidden", "last_played")

    private fun JsonObject.child(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.array(name: String): List<JsonObject> =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.orEmpty()

    private fun JsonObject.text(name: String): String? =
        (get(name) as? JsonPrimitive)?.asString?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(name: String): Int? = (get(name) as? JsonPrimitive)?.asNumberOrNull()

    private fun JsonObject.bool(name: String): Boolean? =
        (get(name) as? JsonPrimitive)?.let { p -> if (p.isBoolean) p.asBoolean else p.asString.lowercase().toBooleanStrictOrNull() }

    private fun JsonObject.strings(name: String): List<String> =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
            when {
                e is JsonPrimitive -> e.asString
                // IGDB-shaped lists: [{"name": "RPG"}]
                e.isJsonObject -> e.asJsonObject.text("name")
                else -> null
            }
        }.orEmpty()

    private fun JsonPrimitive.asNumberOrNull(): Int? = when {
        isNumber -> asNumber.toInt()
        isString -> asString.trim().toIntOrNull()
        else -> null
    }
}
