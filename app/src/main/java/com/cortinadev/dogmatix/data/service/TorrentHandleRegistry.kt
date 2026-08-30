package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.TorrentConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorrentHandleRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {

    private val session = SessionManager()
    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    private val fetchMutexes = ConcurrentHashMap<String, Mutex>()

    var handleCount: Int = 0
        private set

    fun start() {
        if (!session.isRunning) {
            session.start()
            // libtorrent rejects info dicts above 3 MiB by default (peers get disconnected with
            // "metadata too large" and re-tried forever); multi-TB collection torrents need more.
            session.applySettings(
                org.libtorrent4j.SettingsPack().apply { setMaxMetadataSize(TorrentConstants.MAX_METADATA_SIZE_BYTES) }
            )
            TorrentConstants.DHT_BOOTSTRAP_NODES.forEach { (host, port) ->
                try { session.swig().add_dht_node(org.libtorrent4j.swig.string_int_pair(host, port)) }
                catch (e: Exception) { Log.w(TAG, "DHT node $host:$port failed: ${e.message}") }
            }
            Log.i(TAG, "libtorrent4j session started")
        }
    }

    fun stop() {
        if (session.isRunning) {
            handles.values.forEach { if (it.isValid) it.pause() }
            handles.clear()
            fetchMutexes.clear()
            handleCount = 0
            session.stop()
            Log.i(TAG, "libtorrent4j session stopped")
        }
    }

    val isRunning: Boolean get() = session.isRunning

    suspend fun getOrFetch(uri: String): TorrentHandle = withContext(Dispatchers.IO) {
        if (!session.isRunning) {
            start()
        }
        val optimizedUri = if (uri.startsWith("magnet:")) FileParsingUtils.optimizeMagnetUri(uri) else uri
        handles[optimizedUri]?.takeIf { it.isValid }?.let { return@withContext it }
        val mutex = fetchMutexes.getOrPut(optimizedUri) { Mutex() }
        mutex.withLock {
            handles[optimizedUri]?.takeIf { it.isValid }?.let { return@withLock it }
            Log.d(TAG, "Fetching metadata for $optimizedUri")
            val handle = fetchMetadata(optimizedUri)
            handles[optimizedUri] = handle
            handleCount = handles.size
            Log.i(TAG, "Metadata cached (${handles.size} total handles)")
            handle
        }
    }

    fun getCachedHandle(magnet: String): TorrentHandle? {
        val optimizedMagnet = if (magnet.startsWith("magnet:")) FileParsingUtils.optimizeMagnetUri(magnet) else magnet
        return handles[optimizedMagnet]?.takeIf { it.isValid }
    }

    fun getCachedInfo(magnet: String): TorrentInfo? {
        val optimizedMagnet = if (magnet.startsWith("magnet:")) FileParsingUtils.optimizeMagnetUri(magnet) else magnet
        return handles[optimizedMagnet]?.takeIf { it.isValid }?.torrentFile()
    }

    fun releaseHandle(magnet: String) {
        val optimizedMagnet = if (magnet.startsWith("magnet:")) FileParsingUtils.optimizeMagnetUri(magnet) else magnet
        handles.remove(optimizedMagnet)?.let { handle ->
            if (handle.isValid) {
                try {
                    // Nothing else tracks this torrent any more: drop whatever it left in
                    // cacheDir/torrent_data (cancelled or failed partials would pile up otherwise).
                    // The files are deleted here, synchronously, NOT via remove(DELETE_FILES):
                    // libtorrent runs that deletion asynchronously, and when the next queued
                    // download re-adds the same torrent right after this release, the pending
                    // deletion wipes the fresh download's files (FILE_ERROR → failed downloads).
                    val partials = partialFilePaths(handle)
                    // libtorrent keeps pieces of IGNOREd sibling files in ".<infohash>.parts";
                    // stale partfiles must not be reused by a later re-add of the same magnet.
                    val partfile = try { ".${handle.infoHash()}.parts" } catch (e: Exception) { null }
                    session.remove(handle)
                    deleteFromCache(partials + listOfNotNull(partfile))
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing torrent: ${e.message}")
                }
            }
        }
        fetchMutexes.remove(optimizedMagnet)
        handleCount = handles.size
        Log.i(TAG, "Released handle for $optimizedMagnet (${handles.size} remaining)")
    }

    /** Relative paths (within torrent_data) of this torrent's files that have any bytes on disk. */
    private fun partialFilePaths(handle: TorrentHandle): List<String> = try {
        val info = handle.torrentFile() ?: return emptyList()
        val progress = handle.fileProgress()
        (0 until info.numFiles())
            .filter { it < progress.size && progress[it] > 0L }
            .map { info.files().filePath(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun deleteFromCache(relativePaths: List<String>) {
        if (relativePaths.isEmpty()) return
        val root = File(context.cacheDir, "torrent_data")
        var deleted = 0
        relativePaths.forEach { path ->
            val f = File(root, path)
            if (f.exists() && f.delete()) deleted++
            // Drop now-empty parents up to (but not including) torrent_data itself.
            var dir = f.parentFile
            while (dir != null && dir != root && dir.list()?.isEmpty() == true) {
                if (!dir.delete()) break
                dir = dir.parentFile
            }
        }
        if (deleted > 0) Log.i(TAG, "Deleted $deleted partial file(s) from torrent cache")
    }

    fun session(): SessionManager = session

    private suspend fun fetchMetadata(uri: String): TorrentHandle =
        withContext(Dispatchers.IO) {
            val params = if (uri.startsWith("magnet:")) {
                org.libtorrent4j.AddTorrentParams.parseMagnetUri(uri)
            } else {
                val torrentFile = File(uri)
                val ti = org.libtorrent4j.TorrentInfo(torrentFile)
                val p = org.libtorrent4j.AddTorrentParams()
                p.torrentInfo = ti
                p
            }

            // Use cache directory for libtorrent metadata and downloads
            val torrentDataDir = File(context.cacheDir, "torrent_data").apply { mkdirs() }
            
            val swigParams = params.swig()
            swigParams.save_path = torrentDataDir.absolutePath

            val ec = org.libtorrent4j.swig.error_code()
            val swigHandle = try {
                session.swig().add_torrent(swigParams, ec)
            } catch (e: Exception) {
                null
            }
            
            val handle = if (swigHandle != null && swigHandle.is_valid) {
                TorrentHandle(swigHandle)
            } else {
                val infoHash = swigParams.info_hashes.v1
                val existingSwig = session.swig().find_torrent(infoHash)
                if (existingSwig != null && existingSwig.is_valid) {
                    TorrentHandle(existingSwig)
                } else {
                    val errorMsg = if (ec.value() != 0) ec.message() else "unknown error"
                    throw Exception("Failed to add torrent: $errorMsg [$uri]")
                }
            }

            // Immediately pause to prevent background downloading
            try {
                handle.pause()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pause handle: ${e.message}")
            }

            // Upload mode still exchanges metadata but never requests pieces: otherwise the
            // swarm starts filling the cache with random files until we get to set priorities.
            try { handle.setFlags(TorrentFlags.UPLOAD_MODE) } catch (e: Exception) { Log.w(TAG, "upload_mode: ${e.message}") }
            handle.resume() // Resume just to fetch metadata
            waitForMetadata(handle, uri)
        }

    /**
     * Waits for the torrent's metadata. The configured timeout is an *inactivity* timeout: it is
     * re-armed every time the swarm shows signs of life (bytes received — ut_metadata pieces count
     * as protocol traffic in [TorrentStatus.totalDownload] — or new peers connected), so huge
     * info dicts (e.g. whole-archive torrents with hundreds of thousands of files) can take
     * minutes as long as they keep progressing.
     */
    private suspend fun waitForMetadata(handle: TorrentHandle, uri: String): TorrentHandle {
        val timeoutS = settingsRepository.metadataTimeoutSeconds.first()
        val timeoutMs = timeoutS * 1000L
        var lastDownloaded = -1L
        var lastPeers = -1
        var deadline = System.currentTimeMillis() + timeoutMs
        val result = withTimeoutOrNull(timeoutMs * MAX_INACTIVITY_RESETS) {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    if (handle.isValid && handle.torrentFile() != null) {
                        // Once we have metadata, stop everything
                        handle.pause()
                        val info = handle.torrentFile()
                        if (info != null) {
                            val priorities = Array(info.numFiles()) { org.libtorrent4j.Priority.IGNORE }
                            handle.prioritizeFiles(priorities)
                        }
                        return@withTimeoutOrNull handle
                    }
                    if (handle.isValid) {
                        val status = handle.status()
                        val downloaded = status.totalDownload()
                        val peers = status.numPeers()
                        if (downloaded > lastDownloaded || peers > lastPeers) {
                            if (lastDownloaded >= 0 && downloaded > lastDownloaded) {
                                Log.d(TAG, "Metadata progress: $downloaded bytes, $peers peers")
                            }
                            deadline = System.currentTimeMillis() + timeoutMs
                        }
                        lastDownloaded = maxOf(lastDownloaded, downloaded)
                        lastPeers = maxOf(lastPeers, peers)
                    }
                } catch (e: Exception) {
                    return@withTimeoutOrNull null
                }
                if (System.currentTimeMillis() >= deadline) return@withTimeoutOrNull null
                delay(TorrentConstants.METADATA_POLL_INTERVAL_MS)
            }
            null
        }

        if (result == null) {
            try {
                // Upload mode wrote nothing, so a plain remove is enough (and DELETE_FILES
                // could race a concurrent re-add of the same magnet; see releaseHandle).
                if (handle.isValid) session.swig().remove_torrent(handle.swig())
            } catch (_: Exception) {}
            throw TorrentMetadataTimeoutException(
                "Metadata fetch timed out after ${timeoutS}s without progress for: $uri"
            )
        }
        handle.pause()
        return handle
    }

    companion object {
        private const val TAG = "TorrentHandleRegistry"
        /** Hard cap: a fetch can never last more than this many inactivity windows. */
        private const val MAX_INACTIVITY_RESETS = 30
    }
}

class TorrentMetadataTimeoutException(message: String) : Exception(message)
