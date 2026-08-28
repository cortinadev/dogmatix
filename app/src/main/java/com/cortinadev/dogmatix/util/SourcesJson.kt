package com.cortinadev.dogmatix.util

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.cortinadev.dogmatix.data.model.ContentType
import com.cortinadev.dogmatix.data.model.UrlEntry

/** A manufacturer as read from (or written to) a sources JSON document. */
data class SourceManufacturer(
    val id: String,
    val name: String,
    val consoles: List<SourceConsole>
)

/** A console inside a [SourceManufacturer]; [id] is the full Room id (`manufacturer_console`). */
data class SourceConsole(
    val id: String,
    val name: String,
    val urls: List<UrlEntry>,
    /** Chip abbreviation ("GBA"); null when the document does not say. */
    val shortName: String? = null,
    /** Folder names that count as this console's download folder ("gba", "Game Boy Advance"…). */
    val folderAliases: List<String> = emptyList()
)

/**
 * Reads and writes the sources document shared by `assets/consoles.json`, the export/import
 * feature and the per-console `urls` column in Room.
 *
 * Document layout (the bundled asset only uses `urls`; export adds the display names):
 * ```
 * { "nintendo": { "_name": "Nintendo",
 *                 "gameboy_advance": { "name": "Game Boy Advance", "short": "GBA",
 *                                      "aliases": ["gba", "gameboyadvance"],
 *                                      "urls": [ { "url": "…", "contentType": "GAME", "folders": ["…"] } ] } } }
 * ```
 * `short` is the chip label in the library / downloads; `aliases` are the folder names the
 * download-path resolver accepts for the console (besides its own name and `short`).
 * Keys starting with `_` inside a manufacturer are metadata, never consoles.
 */
object SourcesJson {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** Parses a whole document. Malformed consoles are skipped; a malformed document throws. */
    fun parseDocument(json: String): List<SourceManufacturer> {
        val root = JsonParser.parseString(json).asJsonObject
        return root.entrySet().mapNotNull { (manufacturerKey, manufacturerEl) ->
            val manufacturerObj = manufacturerEl as? JsonObject ?: return@mapNotNull null
            val manufacturerId = slug(manufacturerKey)
            val manufacturerName = manufacturerObj.stringOrNull("_name") ?: formatManufacturerName(manufacturerKey)
            val consoles = manufacturerObj.entrySet()
                .filter { (key, value) -> !key.startsWith("_") && value is JsonObject }
                .map { (consoleKey, consoleEl) ->
                    val consoleObj = consoleEl.asJsonObject
                    SourceConsole(
                        id = "${manufacturerId}_${slug(consoleKey)}",
                        name = consoleObj.stringOrNull("name") ?: formatConsoleName(consoleKey),
                        urls = parseUrlEntries(consoleObj.get("urls")),
                        shortName = consoleObj.stringOrNull("short")?.trim(),
                        folderAliases = parseStrings(consoleObj.get("aliases"))
                    )
                }
            SourceManufacturer(manufacturerId, manufacturerName, consoles)
        }
    }

    /** Serialises manufacturers (with display names) into the document format. */
    fun serializeDocument(manufacturers: List<SourceManufacturer>): String {
        val root = JsonObject()
        manufacturers.forEach { manufacturer ->
            val manufacturerObj = JsonObject()
            manufacturerObj.addProperty("_name", manufacturer.name)
            manufacturer.consoles.forEach { console ->
                val consoleObj = JsonObject()
                consoleObj.addProperty("name", console.name)
                console.shortName?.takeIf { it.isNotBlank() }?.let { consoleObj.addProperty("short", it) }
                if (console.folderAliases.isNotEmpty()) consoleObj.add("aliases", stringsToJson(console.folderAliases))
                consoleObj.add("urls", urlEntriesToJson(console.urls))
                manufacturerObj.add(consoleKey(manufacturer.id, console.id), consoleObj)
            }
            root.add(manufacturer.id, manufacturerObj)
        }
        return gson.toJson(root)
    }

    /** Parses the `urls` column of a console row. Never throws: a bad column reads as empty. */
    fun parseUrlEntries(json: String): List<UrlEntry> = try {
        parseUrlEntries(JsonParser.parseString(json))
    } catch (_: Exception) {
        emptyList()
    }

    /** Serialises URL entries into the compact form stored in the `urls` column. */
    fun serializeUrlEntries(entries: List<UrlEntry>): String = urlEntriesToJson(entries).toString()

    /** Console key inside its manufacturer object: the Room id without the `manufacturer_` prefix. */
    fun consoleKey(manufacturerId: String, consoleId: String): String =
        consoleId.removePrefix("${manufacturerId}_").ifBlank { consoleId }

    /** Turns a free-form name into the id form used as JSON key and Room id. */
    fun slug(name: String): String = name.trim().lowercase().replace(Regex("\\s+"), "_")

    fun formatManufacturerName(key: String): String =
        key.split("_").filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }

    fun formatConsoleName(key: String): String =
        key.split("_").filter { it.isNotBlank() }.joinToString(" ") { word ->
            when (word.lowercase()) {
                "snes", "n64", "gb", "gbc", "gba", "psp", "ps1", "ps2", "ps3", "ps4", "ps5" -> word.uppercase()
                else -> word.replaceFirstChar { it.titlecase() }
            }
        }

    private fun parseUrlEntries(element: JsonElement?): List<UrlEntry> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val url = obj.stringOrNull("url")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val contentType = obj.stringOrNull("contentType")
                ?.let { runCatching { ContentType.valueOf(it.uppercase()) }.getOrNull() }
                ?: ContentType.GAME
            UrlEntry(url = url, contentType = contentType, folders = parseStrings(obj.get("folders")))
        }
    }

    private fun urlEntriesToJson(entries: List<UrlEntry>): JsonArray {
        val array = JsonArray()
        entries.forEach { entry ->
            val obj = JsonObject()
            obj.addProperty("url", entry.url)
            obj.addProperty("contentType", entry.contentType.name)
            if (entry.folders.isNotEmpty()) obj.add("folders", stringsToJson(entry.folders))
            array.add(obj)
        }
        return array
    }

    private fun parseStrings(element: JsonElement?): List<String> =
        (element as? JsonArray)
            ?.mapNotNull { runCatching { it.asString }.getOrNull()?.trim()?.takeIf { s -> s.isNotEmpty() } }
            ?.distinct()
            .orEmpty()

    private fun stringsToJson(values: List<String>): JsonArray = JsonArray().apply { values.forEach { add(it) } }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
}
