package com.cortinadev.dogmatix.data.service

import org.libtorrent4j.TorrentInfo
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around [TorrentHandleRegistry.getOrFetch] that returns
 * [TorrentInfo] directly — the object the scraping pipeline needs.
 */
@Singleton
class TorrentMetadataFetcher @Inject constructor(
    private val registry: TorrentHandleRegistry,
    private val metadataCache: TorrentMetadataCache
) {
    suspend fun fetch(magnet: String): TorrentInfo {
        val handle = registry.getOrFetch(magnet)
        return checkNotNull(handle.torrentFile()) {
            "TorrentHandle has no TorrentInfo after fetch for: $magnet"
        }
    }

    /**
     * Runs [block] on the torrent's file list: from a handle already in the session, else from
     * the metadata cache on disk (without adding the torrent to the session at all, so a rescan
     * holds no handle for it), else from the swarm.
     */
    suspend fun <T> withInfo(magnet: String, block: (TorrentInfo) -> T): T {
        registry.getCachedInfo(magnet)?.let { return block(it) }
        if (magnet.startsWith("magnet:")) {
            metadataCache.load(magnet)?.let { info ->
                return try { block(info) } finally { info.swig().delete() }
            }
        }
        return block(fetch(magnet))
    }

    /**
     * As [withInfo], but never from the swarm and without starting the session: a handle already
     * in it, the metadata cache on disk or a local `.torrent` read directly. Null when the file list
     * is not at hand.
     */
    fun <T : Any> withLocalInfo(magnet: String, block: (TorrentInfo) -> T): T? {
        registry.getCachedInfo(magnet)?.let { return block(it) }
        val info = if (magnet.startsWith("magnet:")) {
            metadataCache.load(magnet)
        } else {
            runCatching { TorrentInfo(File(magnet)) }.getOrNull()
        } ?: return null
        return try { block(info) } finally { info.swig().delete() }
    }

    /** The file list is at hand without the swarm: a session handle, the disk cache or a local .torrent. */
    fun hasMetadata(magnet: String): Boolean =
        !magnet.startsWith("magnet:") || registry.getCachedInfo(magnet) != null || metadataCache.contains(magnet)
}
