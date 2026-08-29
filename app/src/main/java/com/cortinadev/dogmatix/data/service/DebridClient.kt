package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.model.DebridProvider
import java.io.IOException

/** The API key is missing or was rejected: no point in retrying. */
class DebridAuthException(message: String) : IOException(message)
class DebridException(message: String) : IOException(message)

data class DebridFile(val id: Int, val name: String, val size: Long)

/**
 * A torrent as the debrid service sees it. [files] is empty until the service has the metadata;
 * [failure] is non-null when the service gave up on it (dead magnet, error, virus…).
 */
data class DebridTorrent(
    val id: String,
    val hash: String,
    val name: String,
    /** 0..1 */
    val progress: Float,
    val downloadFinished: Boolean,
    val files: List<DebridFile>,
    val failure: String? = null
) {
    val filesKnown: Boolean get() = files.isNotEmpty()
}

/**
 * What [DownloadService] needs from a debrid service. Ids are strings (TorBox uses ints,
 * Real-Debrid alphanumeric ids); the file to fetch is matched by name/size with `DebridMatcher`.
 */
interface DebridClient {
    val provider: DebridProvider

    /** Returns an account name (e-mail, user, plan) for a "Connected as …" toast. */
    suspend fun validateKey(key: String): String

    /** True when the service already holds [hash], so polling can be eager. */
    suspend fun isCached(hash: String): Boolean

    /** Adds [magnet] to the account and returns its torrent id. */
    suspend fun createTorrent(magnet: String): String

    suspend fun getTorrent(id: String): DebridTorrent

    /** Tells the service which file to fetch once the listing is known (no-op where not needed). */
    suspend fun selectFile(torrentId: String, fileId: Int) {}

    /** A direct (time-limited) download link for one file of a finished torrent. */
    suspend fun requestDownload(torrentId: String, fileId: Int): String

    /** Best effort: removes the torrent from the account once the file is on disk. */
    suspend fun delete(torrentId: String)
}
