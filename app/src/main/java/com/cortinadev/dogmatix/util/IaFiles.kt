package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** One file of an Internet Archive item, as the metadata API lists it (sizes come as text there). */
data class IaFile(
    val name: String,
    val size: Long,
    val format: String,
    val source: String,
    val md5: String? = null,
    val sha1: String? = null,
    val crc32: String? = null
)

/**
 * The file list of an Internet Archive item (`/metadata/<id>`, or `/metadata/<id>/files`): what
 * of it goes to the library, how its rows look, and what the verifier compares a download with.
 * Pure, for the tests.
 */
object IaFiles {

    /** Files Internet Archive makes for every item: not part of what was uploaded. */
    private val generatedFormats = setOf("metadata", "item tile", "archive bittorrent")
    private val itemSideFiles = listOf("_meta.xml", "_files.xml", "_reviews.xml", "_archive.torrent", "_meta.sqlite")

    /** `{"files": […]}` (the whole metadata) or `{"result": […]}` (the `files` field alone); `{}` = no such item. */
    fun parse(json: String): List<IaFile> {
        val root = objectOf(json) ?: return emptyList()
        val array = (root.get("files") ?: root.get("result"))?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = o.text("name")?.trim()?.trimStart('/')?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            IaFile(
                name = name,
                size = o.text("size")?.trim()?.toLongOrNull() ?: 0L,
                format = o.text("format").orEmpty(),
                source = o.text("source").orEmpty(),
                md5 = o.text("md5"),
                sha1 = o.text("sha1"),
                crc32 = o.text("crc32")
            )
        }
    }

    /** `item_last_updated` of the whole metadata, or the `result` of `/metadata/<id>/item_last_updated`. */
    fun lastUpdated(json: String): Long? {
        val root = objectOf(json) ?: return null
        val value = root.get("item_last_updated") ?: root.get("result") ?: return null
        return runCatching { value.asLong }.getOrNull()
    }

    /**
     * What of the item is indexed: only what was uploaded (no derivatives, thumbnails or the files
     * Internet Archive adds to every item), nothing empty, hidden or blocked, and with [folders]
     * only what sits under one of them.
     */
    fun select(files: List<IaFile>, itemId: String, folders: List<String>): List<IaFile> {
        val sideFiles = itemSideFiles.map { (itemId + it).lowercase() }.toSet() + "__ia_thumb.jpg"
        return files.filter { file ->
            val leaf = file.name.substringAfterLast('/')
            val format = file.format.trim().lowercase()
            file.source.equals("original", ignoreCase = true) &&
                format !in generatedFormats && !format.endsWith("thumb") &&
                file.size > 0 &&
                leaf.isNotBlank() && !leaf.startsWith(".") &&
                !IndexFilters.isBlocked(leaf) &&
                leaf.lowercase() !in sideFiles && file.name.lowercase() !in sideFiles &&
                IndexFilters.inFolders(file.name, folders)
        }
    }

    /** The library rows of [selected]: one per file, named and tagged like a torrent's, downloaded from the item. */
    fun rows(selected: List<IaFile>, itemId: String, consoleId: String, contentTypeTag: String): List<Pair<DownloadableFileEntity, List<String>>> =
        selected.map { file ->
            val leaf = file.name.substringAfterLast('/')
            val extension = leaf.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length < leaf.length }
            val stem = if (extension != null) leaf.dropLast(extension.length + 1) else leaf
            val (cleanName, tags) = FileParsingUtils.extractNameAndTags(stem)
            DownloadableFileEntity(
                name = cleanName.ifBlank { leaf },
                fileName = leaf,
                consoleId = consoleId,
                downloadUrl = IaSource.downloadUrl(itemId, file.name),
                fileSize = file.size,
                fileExtension = extension?.let { ".$it" }.orEmpty()
            ) to (tags + contentTypeTag).distinct()
        }

    /** The item's files as the verifier compares a download with: archives too, hashed whole by Internet Archive. */
    fun expected(files: List<IaFile>): List<ExpectedFile> = files.map { file ->
        ExpectedFile(
            name = file.name,
            size = file.size,
            crc = Checksums.normalize(file.crc32, 8),
            sha1 = Checksums.normalize(file.sha1, 40),
            md5 = Checksums.normalize(file.md5, 32)
        )
    }

    private fun objectOf(json: String): JsonObject? =
        runCatching { JsonParser.parseString(json) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let(JsonElement::getAsString)
}
