package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.UrlEntry
import java.security.MessageDigest

/**
 * How a source is read: a web listing, a torrent (magnet or a picked .torrent), a RomM platform,
 * a web page read with CSS selectors ([UrlEntry.scrape]) or an Internet Archive item (`ia://`).
 */
enum class SourceKind {
    WEB, TORRENT, ROMM, SCRAPE, IA;

    companion object {
        /** Same routing as the scan always had: magnets and `.torrent` paths, `romm://`, `ia://`, the rest is web. */
        fun of(url: String): SourceKind = when {
            url.startsWith("magnet:") || url.endsWith(".torrent") -> TORRENT
            RommSource.isSource(url) -> ROMM
            IaSource.isSource(url) -> IA
            else -> WEB
        }

        /** As [of], but a web address with a page recipe is read with its selectors. */
        fun of(entry: UrlEntry): SourceKind {
            val kind = of(entry.url)
            return if (kind == WEB && entry.scrape != null) SCRAPE else kind
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
 * that changes how rows are built — its folders, its content type, its page recipe or
 * [PARSER_VERSION] — makes the next scan read the source in full.
 */
object SourceScanCheck {

    /** Why a scan reads a source again instead of keeping what the last scan gave (see [rereadReason]). */
    enum class Reread {
        /** "Full rescan", or a console's refresh button. */
        FORCED,
        /** Never scanned (or its result was dropped). */
        NO_RECORD,
        /** Scanned, but no scan of it ever succeeded. */
        NEVER_READ,
        /** Its folders, content type, page recipe or the parser changed ([fingerprint]). */
        SET_UP_CHANGED,
        /** The library holds another number of its rows than the last scan wrote (rows lost, or a write cut short). */
        ROWS_CHANGED,
        /** A torrent that gave no files last time, read again because its file list is at hand ([torrentReread]). */
        NO_ROWS
    }

    /** Raise when a change to how sources are indexed must re-read every source once. */
    const val PARSER_VERSION = 1

    /**
     * Raise when a change to how web listings are read must re-read every web source once (and
     * only those: torrents, RomM, page recipes and Internet Archive items keep their rows and are
     * still skipped). 2: `<pre>` listings, absolute links resolved against the server, file names
     * as the link's last part ([ListingParser]). Version 1 is the fingerprint without this part, as
     * stored before.
     */
    const val LISTING_PARSER_VERSION = 2

    /**
     * What a source's rows depend on besides the listing itself (mirrors do not: rows keep the main
     * address). A changed fingerprint makes the next scan read the source in full: no validators
     * go out (a 304 cannot keep rows built the old way) and the rows are written again. Only a web
     * listing carries [LISTING_PARSER_VERSION]; only a page read with selectors carries its recipe,
     * so every other source keeps the fingerprint it always had.
     */
    fun fingerprint(entry: UrlEntry): String {
        val folders = entry.folders
            .map { it.trim().replace('\\', '/').trimEnd('/').lowercase() }
            .filter { it.isNotEmpty() }
            .sorted()
        val base = "v$PARSER_VERSION"
        val rest = "|${entry.contentType.name}|${folders.joinToString("|")}"
        return when (SourceKind.of(entry)) {
            SourceKind.WEB -> "$base|listing$LISTING_PARSER_VERSION$rest"
            SourceKind.SCRAPE -> {
                val recipe = checkNotNull(entry.scrape)
                base + rest + "|scrape|" + listOf(recipe.item, recipe.link, recipe.name, recipe.size, recipe.pages, recipe.ext)
                    .joinToString("|") { it.trim() }
            }
            else -> base + rest
        }
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
    fun canReuse(entry: UrlEntry, previous: Previous?, force: Boolean): Boolean = rereadReason(entry, previous, force) == null

    /** Why [canReuse] says no; null when the previous scan's rows still describe [entry]. */
    fun rereadReason(entry: UrlEntry, previous: Previous?, force: Boolean): Reread? = when {
        force -> Reread.FORCED
        previous == null -> Reread.NO_RECORD
        previous.fileCount < 0 -> Reread.NEVER_READ
        previous.fingerprint != fingerprint(entry) -> Reread.SET_UP_CHANGED
        previous.rows != previous.fileCount -> Reread.ROWS_CHANGED
        else -> null
    }

    /** Conditional request headers for [target], only when the validators came from that same address. */
    fun conditionalHeaders(entry: UrlEntry, previous: Previous?, force: Boolean, target: String): Map<String, String> {
        if (!canReuse(entry, previous, force)) return emptyMap()
        if ((previous!!.servedBy ?: entry.url) != target) return emptyMap()
        return buildMap {
            previous.etag?.takeIf { it.isNotBlank() }?.let { put("If-None-Match", it) }
            previous.lastModified?.takeIf { it.isNotBlank() }?.let { put("If-Modified-Since", it) }
        }
    }

    /**
     * Why a scan has to read a torrent again; null = its rows stand and its metadata is not needed
     * at all. Decided from the last scan's result and the library's row count only.
     *
     * A torrent's content is fixed by its info-hash and the [fingerprint] already covers how its
     * rows are built, so one indexed before with the same set-up is kept as it is. One that gave no
     * files at all is read again only when [listAtHand] says its file list is there without the
     * swarm (a handle in the session, the metadata cache on disk, a local `.torrent`): reading it
     * from the swarm on every scan could never give anything else, and takes minutes for the large
     * collection torrents. [listAtHand] is only asked in that case.
     */
    fun torrentReread(entry: UrlEntry, previous: Previous?, force: Boolean, listAtHand: () -> Boolean): Reread? {
        rereadReason(entry, previous, force)?.let { return it }
        return if (previous!!.rows == 0 && listAtHand()) Reread.NO_ROWS else null
    }

    /** A torrent whose rows stand without reading its metadata ([torrentReread] has no reason). */
    fun canSkipTorrent(entry: UrlEntry, previous: Previous?, force: Boolean, listAtHand: () -> Boolean): Boolean =
        SourceKind.of(entry.url) == SourceKind.TORRENT && torrentReread(entry, previous, force, listAtHand) == null

    /**
     * The source gave exactly the files it gave last time ([hash] from [contentHash]) and the
     * library still holds them all: [files] new files against the rows there are now.
     */
    fun sameContent(entry: UrlEntry, previous: Previous?, force: Boolean, hash: String, files: Int): Boolean =
        canReuse(entry, previous, force) && previous!!.contentHash == hash && previous.rows == files

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
     * A link already under the main address stays as it is, also when the mirror's base is a
     * prefix of it (a mirror at `https://host/` of a source at `https://host/roms/`).
     */
    fun primaryUrl(downloadUrl: String, servedBy: String, primary: String): String {
        if (MirrorUrls.alternatives(downloadUrl, primary, listOf(servedBy)).isNotEmpty()) return downloadUrl
        return MirrorUrls.alternatives(downloadUrl, servedBy, listOf(primary)).firstOrNull() ?: downloadUrl
    }
}
