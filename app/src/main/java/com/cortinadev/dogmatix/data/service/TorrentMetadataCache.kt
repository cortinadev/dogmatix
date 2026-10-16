package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.util.MagnetLinks
import com.cortinadev.dogmatix.util.TorrentConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.add_torrent_params
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.load_torrent_limits
import org.libtorrent4j.swig.torrent_info
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Metadata of magnets fetched before, on disk by info-hash (`<hash>.torrent`, the info dict
 * wrapped as a minimal .torrent), so neither a rescan nor a direct torrent download asks the
 * swarm again: a magnet's content never changes. Collection torrents can carry info dicts of
 * tens of MB, so the cache keeps at most [MAX_TOTAL_BYTES] and drops the least recently used.
 * A file is only trusted when its info-hash matches the magnet.
 */
@Singleton
class TorrentMetadataCache @Inject constructor(@param:ApplicationContext context: Context) {

    // Not backed up (it can weigh hundreds of MB, far over Auto Backup's quota) and not in
    // cacheDir, which the system may empty whenever it likes.
    private val dir = File(context.noBackupFilesDir, "torrent_metadata")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private fun fileFor(key: String) = File(dir, "$key.torrent")

    /** Whether [magnet]'s metadata is on disk (no parsing; for planning a scan). */
    fun contains(magnet: String): Boolean = MagnetLinks.infoHashKey(magnet)?.let { fileFor(it).isFile } ?: false

    /**
     * [magnet]'s metadata from disk, or null. The caller owns the returned object and frees it
     * (`swig().delete()`) when done: it can be large and the garbage collector only sees a handle.
     */
    fun load(magnet: String): TorrentInfo? {
        val key = MagnetLinks.infoHashKey(magnet) ?: return null
        val file = fileFor(key)
        if (!file.isFile) return null
        return try {
            val ec = error_code()
            val params = add_torrent_params.load_torrent_file(file.absolutePath, ec, limits())
            val loaded = if (ec.value() == 0) params.ti_ptr() else null
            // An owned copy: the parsed params (and their pointer) go away right after.
            val info = loaded?.takeIf { matches(it, key) }?.let { torrent_info(it) }
            params.delete()
            if (info == null) {
                Log.w(TAG, "Cached metadata for $key unreadable or for another torrent; dropped")
                file.delete()
                return null
            }
            file.setLastModified(System.currentTimeMillis())
            TorrentInfo(info)
        } catch (e: Throwable) {
            Log.w(TAG, "Cached metadata for $key failed to load: ${e.message}")
            file.delete()
            null
        }
    }

    /**
     * Saves [info] (just fetched for [magnet]) in the background. A copy is taken right away, so
     * the torrent may leave the session meanwhile. The info dict has to be copied out of the
     * native side byte by byte, which takes a few seconds for the largest ones.
     */
    fun store(magnet: String, info: TorrentInfo) {
        val key = MagnetLinks.infoHashKey(magnet) ?: return
        if (fileFor(key).isFile) return
        val copy = try { torrent_info(info.swig()) } catch (e: Throwable) { return }
        scope.launch {
            try {
                write(key, copy)
            } catch (e: Throwable) {
                Log.w(TAG, "Could not cache metadata for $key: ${e.message}")
            } finally {
                copy.delete()
            }
        }
    }

    private fun write(key: String, info: torrent_info) {
        val section = info.get_info_section()
        val size = section.size()
        if (size <= 0 || size > MAX_ENTRY_BYTES) return
        dir.mkdirs()
        val target = fileFor(key)
        val tmp = File(dir, "$key.tmp")
        BufferedOutputStream(FileOutputStream(tmp), BUFFER).use { out ->
            out.write(TORRENT_HEAD)
            val buffer = ByteArray(BUFFER)
            var offset = 0L
            while (offset < size) {
                val n = minOf(BUFFER.toLong(), size - offset).toInt()
                for (i in 0 until n) buffer[i] = section.at(offset + i)
                out.write(buffer, 0, n)
                offset += n
            }
            out.write('e'.code)
        }
        synchronized(lock) {
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
            trim()
        }
        Log.i(TAG, "Cached metadata for $key (${size / 1024} KiB)")
    }

    /** Oldest-used files go until the cache fits [MAX_TOTAL_BYTES]. */
    private fun trim() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".torrent") }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_TOTAL_BYTES) return
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= MAX_TOTAL_BYTES) break
            val length = f.length()
            if (f.delete()) total -= length
        }
    }

    private fun matches(info: torrent_info, key: String): Boolean {
        val hashes = info.info_hashes()
        return when (key.length) {
            40 -> hashes.has_v1() && hashes.getV1().to_hex().equals(key, ignoreCase = true)
            64 -> hashes.has_v2() && hashes.getV2().to_hex().equals(key, ignoreCase = true)
            else -> false
        }
    }

    /** Above libtorrent's defaults (10 MB, 3 M tokens): collection torrents list hundreds of thousands of files. */
    private fun limits() = load_torrent_limits().apply {
        setMax_buffer_size(TorrentConstants.MAX_METADATA_SIZE_BYTES + 1024)
        setMax_decode_tokens(50_000_000)
        setMax_pieces(0x800000)
    }

    companion object {
        private const val TAG = "TorrentMetadataCache"
        const val MAX_TOTAL_BYTES = 300L * 1024 * 1024
        /** Larger info dicts are not cached (copying one out of libtorrent would take too long). */
        const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
        private const val BUFFER = 64 * 1024
        private val TORRENT_HEAD = "d4:info".toByteArray(Charsets.US_ASCII)
    }
}
