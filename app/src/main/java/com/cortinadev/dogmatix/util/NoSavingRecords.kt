package com.cortinadev.dogmatix.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * A file converted and found to save nothing (`ConversionError.NO_SAVING`), remembered across
 * restarts so Downloads and Free up space do not offer the same useless conversion again.
 *
 * It names the file by its document [uri] and the size of that main file ([mainBytes]) when it was
 * tried, plus the files it names ([companions], uri to size: the tracks of a sheet): a file
 * replaced, downloaded again or changed, or a track that changed, no longer matches. [profile] is what the
 * converter was set to (the CHD compression; empty for the others): with another profile the
 * result may differ, so the record only applies while that profile is the current one.
 */
data class NoSavingRecord(
    val uri: String,
    val mainBytes: Long,
    val inputBytes: Long,
    val format: ConversionFormat,
    val profile: String,
    val title: String,
    val consoleId: String?,
    val downloadFileName: String?,
    /** `ConversionOrigin` by name. */
    val origin: String,
    val at: Long,
    val companions: Map<String, Long> = emptyMap()
)

/** The rules of the [NoSavingRecord] list and its file (JSON written by hand: no reflection). */
object NoSavingRecords {
    const val MAX = 200

    /** The [format]'s converter profile in use: the CHD compression by name, nothing for the others. */
    fun profileOf(format: ConversionFormat, chdCompression: String): String =
        if (format == ConversionFormat.CHD) chdCompression else ""

    /** [record] added, newest first: replaces the one of the same file, format and profile; at most [max]. */
    fun add(records: List<NoSavingRecord>, record: NoSavingRecord, max: Int = MAX): List<NoSavingRecord> =
        (listOf(record) + records.filterNot { it.uri == record.uri && it.format == record.format && it.profile == record.profile })
            .sortedByDescending { it.at }
            .take(max)

    /** Without the records of the file [uri] (it was converted after all, or removed). */
    fun forgetUri(records: List<NoSavingRecord>, uri: String): List<NoSavingRecord> = records.filterNot { it.uri == uri }

    /** Without the records of the downloads [names] (downloaded again, or their row deleted). */
    fun forgetDownloads(records: List<NoSavingRecord>, names: Set<String>): List<NoSavingRecord> =
        if (names.isEmpty()) records else records.filterNot { it.downloadFileName != null && it.downloadFileName in names }

    /**
     * Whether [record] still names the same files: the main one and every companion are there
     * ([sizeOf] not null) with the size they had. A record from before companions were kept is
     * judged by its main file alone.
     */
    fun stillValid(record: NoSavingRecord, sizeOf: (String) -> Long?): Boolean =
        sizeOf(record.uri) == record.mainBytes && record.companions.all { (uri, size) -> sizeOf(uri) == size }

    /**
     * The records that apply now, one per file: those tried with the current profile of their format
     * ([profileOf]); the newest when there are several.
     */
    fun applying(records: List<NoSavingRecord>, currentProfile: (ConversionFormat) -> String): Map<String, NoSavingRecord> =
        records.filter { it.profile == currentProfile(it.format) }
            .sortedByDescending { it.at }
            .distinctBy { it.uri }
            .associateBy { it.uri }

    fun encode(records: List<NoSavingRecord>): String = JsonArray().apply {
        for (r in records) add(JsonObject().apply {
            addProperty("uri", r.uri)
            addProperty("main", r.mainBytes)
            addProperty("input", r.inputBytes)
            addProperty("format", r.format.name)
            addProperty("profile", r.profile)
            addProperty("title", r.title)
            r.consoleId?.let { addProperty("console", it) }
            r.downloadFileName?.let { addProperty("download", it) }
            addProperty("origin", r.origin)
            addProperty("at", r.at)
            if (r.companions.isNotEmpty()) add("files", JsonArray().apply {
                for ((uri, size) in r.companions) add(JsonObject().apply { addProperty("uri", uri); addProperty("size", size) })
            })
        })
    }.toString()

    /** The records in [text]; a broken file or entry is skipped, never fatal. */
    fun decode(text: String): List<NoSavingRecord> {
        val array = runCatching { JsonParser.parseString(text).asJsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching {
                val o = element.asJsonObject
                NoSavingRecord(
                    uri = o.get("uri").asString.takeIf { it.isNotEmpty() } ?: return@runCatching null,
                    mainBytes = o.get("main").asLong,
                    inputBytes = o.get("input").asLong,
                    format = ConversionFormat.entries.firstOrNull { it.name == o.get("format").asString } ?: return@runCatching null,
                    profile = o.get("profile")?.asString.orEmpty(),
                    title = o.get("title")?.asString.orEmpty(),
                    consoleId = o.get("console")?.asString?.ifEmpty { null },
                    downloadFileName = o.get("download")?.asString?.ifEmpty { null },
                    origin = o.get("origin")?.asString.orEmpty(),
                    at = o.get("at")?.asLong ?: 0L,
                    companions = o.get("files")?.asJsonArray?.associate { it.asJsonObject.let { f -> f.get("uri").asString to f.get("size").asLong } }.orEmpty()
                )
            }.getOrNull()
        }.sortedByDescending { it.at }.take(MAX)
    }
}
