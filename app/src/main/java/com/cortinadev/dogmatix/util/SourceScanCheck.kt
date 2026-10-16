package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.UrlEntry
import java.security.MessageDigest

/** How a source is read: a web listing, a torrent (magnet or a picked .torrent) or a RomM platform. */
enum class SourceKind {
    WEB, TORRENT, ROMM;

    companion object {
        /** Same routing as the scan always had: magnets and `.torrent` paths, `romm://`, the rest is web. */
        fun of(url: String): SourceKind = when {
            url.startsWith("magnet:") || url.endsWith(".torrent") -> TORRENT
            RommSource.isSource(url) -> ROMM
            else -> WEB
        }
    }
}

/**
 * Whether a rescan has to read a source again, or what it gave last time still holds:
 *  - a web listing is asked with `If-None-Match` / `If-Modified-Since` when its server sent an
 *    `ETag` / `Last-Modified`; a 304 keeps the source's rows exactly as they are;
 *  - a listing (or a RomM platform) that comes back with the same files is not rewritten either:
 *    same [contentHash], no delete + insert;
 *  - a torrent's content is fixed by its info-hash (a picked `.torrent` never changes), so one
 *    indexed before with the same set-up is not asked for its metadata again.
 * The rows of a source are only trusted while there are as many as the last scan wrote. Anything
 * that changes how rows are built — its folders, its content type or [PARSER_VERSION] — makes the
 * next scan read the source in full.
 */
object SourceScanCheck {

    /** Raise when a change to how sources are indexed must re-read every source once. */
    const val PARSER_VERSION = 1

    /** What a source's rows depend on besides the listing itself (mirrors do not: rows keep the main address). */
    fun fingerprint(entry: UrlEntry): String {
        val folders = entry.folders
            .map { it.trim().replace('\\', '/').trimEnd('/').lowercase() }
            .filter { it.isNotEmpty() }
            .sorted()
        return "v$PARSER_VERSION|${entry.contentType.name}|${folders.joinToString("|")}"
    }

    /** What the last scan of a source left, plus how many rows it has in the library right now. */
    data class Previous(
        val fingerprint: String,
        val etag: String?,
        val lastModified: String?,
        /** The address that answered when it was not the source's own (a mirror). */
        val servedBy: String?,
        val contentHash: String?,
        /** Files the last successful scan wrote; negative when no scan ever succeeded. */
        val fileCount: Int,
        val rows: Int
    )

    /** The previous scan's rows still describe [entry]: not forced, same set-up, none lost since. */
    fun canReuse(entry: UrlEntry, previous: Previous?, force: Boolean): Boolean =
        !force && previous != null && previous.fileCount >= 0 &&
            previous.fingerprint == fingerprint(entry) && previous.rows == previous.fileCount

    /** Conditional request headers for [target], only when the validators came from that same address. */
    fun conditionalHeaders(entry: UrlEntry, previous: Previous?, force: Boolean, target: String): Map<String, String> {
        if (!canReuse(entry, previous, force)) return emptyMap()
        if ((previous!!.servedBy ?: entry.url) != target) return emptyMap()
        return buildMap {
            previous.etag?.takeIf { it.isNotBlank() }?.let { put("If-None-Match", it) }
            previous.lastModified?.takeIf { it.isNotBlank() }?.let { put("If-Modified-Since", it) }
        }
    }

    /** A torrent indexed before with the same set-up keeps its rows without asking the swarm again. */
    fun canSkipTorrent(entry: UrlEntry, previous: Previous?, force: Boolean): Boolean =
        SourceKind.of(entry.url) == SourceKind.TORRENT && canReuse(entry, previous, force)

    /** The source gave exactly the files it gave last time ([hash] from [contentHash]). */
    fun sameContent(entry: UrlEntry, previous: Previous?, force: Boolean, hash: String): Boolean =
        canReuse(entry, previous, force) && previous!!.contentHash == hash

    /** Hash of everything a source writes to the library: its files, in order, with their tags. */
    fun contentHash(files: List<DownloadableFileEntity>, tags: List<List<String>>): String {
        val digest = MessageDigest.getInstance("SHA-1")
        fun add(value: String?) {
            digest.update((value ?: "\u0000").toByteArray(Charsets.UTF_8))
            digest.update(0x1F)
        }
        files.forEachIndexed { i, f ->
            add(f.fileName); add(f.name); add(f.downloadUrl); add(f.fileSize.toString()); add(f.fileExtension)
            add(f.torrentFileIndex?.toString()); add(f.torrentMagnet)
            tags.getOrNull(i).orEmpty().forEach(::add)
            digest.update(0x1E)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * [downloadUrl] read from a listing served by [servedBy] (a mirror), under the source's own
     * address [primary]: rows always keep the main address, so the download mirrors still apply.
     */
    fun primaryUrl(downloadUrl: String, servedBy: String, primary: String): String =
        MirrorUrls.alternatives(downloadUrl, servedBy, listOf(primary)).firstOrNull() ?: downloadUrl
}
