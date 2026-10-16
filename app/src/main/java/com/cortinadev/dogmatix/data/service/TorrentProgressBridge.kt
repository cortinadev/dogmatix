package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.cortinadev.dogmatix.util.DownloadFailure
import com.cortinadev.dogmatix.util.DownloadFailures
import com.cortinadev.dogmatix.data.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.libtorrent4j.AlertListener
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.FileErrorAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import org.libtorrent4j.swig.file_progress_flags_t
import org.libtorrent4j.swig.int64_vector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges libtorrent4j alert callbacks into [DownloadProgressTracker] updates.
 * Registered as an alert listener on the session in [DownloadForegroundService].
 */
@Singleton
class TorrentProgressBridge @Inject constructor(
    private val progressTracker: DownloadProgressTracker
) : AlertListener {

    private data class TrackedFile(
        val fileIndex: Int,
        val handle: TorrentHandle,
        /** Cached at track time — fileSize never changes, no need to re-call JNI every tick. */
        val expectedSize: Long,
        /** Cached too: groups files by torrent and is compared under the lock without JNI calls. */
        val infoHash: String
    )

    // Single map replaces the old separate `tracked` (fileName→index) and `handles` (fileName→handle).
    private val tracked = mutableMapOf<String, TrackedFile>()

    /**
     * Last observation per download, to derive a per-file rate from [TorrentHandle.fileProgress]
     * deltas: `status().downloadPayloadRate()` is the whole torrent's rate, so sibling files from
     * one torrent would all show the same (total) speed. Guarded by the same monitor as [tracked].
     */
    private class RateState(var bytes: Long, var nanos: Long, var emaBytesPerSec: Float = 0f)
    private val rates = mutableMapOf<String, RateState>()
    // Polling runs on IO — avoids JNI calls on the main thread.
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var pollingJob: Job? = null

    fun trackDownload(fileName: String, fileIndex: Int, handle: TorrentHandle) {
        // Read expectedSize outside the lock to avoid holding the monitor during a JNI call.
        val expectedSize = handle.torrentFile()?.files()?.fileSize(fileIndex) ?: 0L
        val infoHash = handle.infoHash().toString()
        synchronized(this) {
            tracked[fileName] = TrackedFile(fileIndex, handle, expectedSize, infoHash)
            rates.remove(fileName)
            startPolling()
        }
        Log.d(TAG, "Tracking $fileName at index $fileIndex")
    }

    fun untrackDownload(fileName: String, fileIndex: Int) {
        synchronized(this) {
            tracked.remove(fileName)
            rates.remove(fileName)
            if (tracked.isEmpty()) stopPolling()
        }
    }

    private fun startPolling() {
        if (pollingJob != null) return
        pollingJob = scope.launch {
            while (true) {
                updateProgress()
                delay(1000L)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun updateProgress() {
        // Snapshot under lock so we never hold the monitor during JNI calls. libtorrent alert
        // callbacks (onTorrentFinished etc.) also acquire this lock, so holding it while calling
        // native methods risks a deadlock.
        val snapshot: Map<String, TrackedFile>
        synchronized(this) { snapshot = tracked.toMap() }

        // One status and one progress read per torrent, not per tracked file: fileProgress()
        // copies every file of the torrent across JNI one element at a time, which for a
        // collection torrent with hundreds of thousands of files meant millions of calls per
        // tracked file every second.
        snapshot.entries.groupBy { it.value.infoHash }.values.forEach { files ->
            val handle = files.first().value.handle
            if (!handle.isValid) return@forEach
            val status = try { handle.status() } catch (e: Exception) { return@forEach }
            val indices = files.map { it.value.fileIndex }
            val received = fileBytes(handle, indices, null)
            // Only pieces that passed the hash check: a file is finished (and gets copied) once
            // these cover it, not when its last blocks arrive unverified.
            val verified = fileBytes(handle, indices, TorrentHandle.PIECE_GRANULARITY)
            files.forEach { (fileName, info) ->
                updateFile(fileName, info, status, received?.get(info.fileIndex), verified?.get(info.fileIndex))
            }
        }
    }

    /** Bytes done of each of [indices] from one native call (only those are read back); null if it failed. */
    private fun fileBytes(handle: TorrentHandle, indices: List<Int>, flags: file_progress_flags_t?): Map<Int, Long>? {
        val vector = int64_vector()
        return try {
            if (flags == null) handle.swig().file_progress(vector) else handle.swig().file_progress(vector, flags)
            val size = vector.size
            indices.filter { it < size }.associateWith { vector.get(it) }
        } catch (e: Exception) {
            null
        } finally {
            vector.delete()
        }
    }

    private fun updateFile(fileName: String, info: TrackedFile, status: TorrentStatus, receivedBytes: Long?, verifiedBytes: Long?) {
        val total = info.expectedSize
        if (total <= 0) return

        val downloaded = receivedBytes ?: (status.progress() * total).toLong()
        val progress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        val now = System.nanoTime()
        val speedMBs: Float
        synchronized(this) {
            val rate = rates.getOrPut(fileName) { RateState(downloaded, now) }
            val dtSeconds = (now - rate.nanos) / 1e9f
            if (dtSeconds > 0.2f) {
                val instant = (downloaded - rate.bytes).coerceAtLeast(0L) / dtSeconds
                // Light smoothing: per-file deltas jitter because pieces finish out of order.
                rate.emaBytesPerSec =
                    if (rate.emaBytesPerSec == 0f) instant else rate.emaBytesPerSec * 0.6f + instant * 0.4f
                rate.bytes = downloaded
                rate.nanos = now
            }
            speedMBs = rate.emaBytesPerSec / (1024f * 1024f)
        }

        val state = status.state()
        val isChecking = state == TorrentStatus.State.CHECKING_FILES ||
                         state == TorrentStatus.State.CHECKING_RESUME_DATA
        val isFinished = (verifiedBytes ?: downloaded) >= total

        val currentItem = progressTracker.downloads.value.find { it.fileName == fileName }
        val currentStatus = currentItem?.status
        // A file/torrent error alert may have failed this row while libtorrent still
        // reports full fileProgress; never resurrect terminal rows from the poll.
        if (currentStatus == DownloadStatus.FAILED || currentStatus == DownloadStatus.STOPPED ||
            currentStatus == DownloadStatus.PAUSED) return

        when {
            isFinished && currentStatus != DownloadStatus.COMPLETED &&
            currentStatus != DownloadStatus.UNZIPPING -> {
                progressTracker.updateDownloadStatus(fileName, DownloadStatus.COMPLETED)
            }
            isChecking -> {
                if (currentStatus != DownloadStatus.DOWNLOADING)
                    progressTracker.updateDownloadStatus(fileName, DownloadStatus.DOWNLOADING)
            }
            !isFinished && !isChecking && currentStatus != DownloadStatus.DOWNLOADING -> {
                progressTracker.updateDownloadStatus(fileName, DownloadStatus.DOWNLOADING)
            }
        }

        progressTracker.updateDownloadProgress(fileName, progress, speedMBs, downloaded)
    }

    fun countTrackedForHandle(handle: TorrentHandle): Int {
        val hash = handle.infoHash().toString()
        synchronized(this) {
            return tracked.values.count { it.infoHash == hash }
        }
    }

    fun getTrackedFileIndicesForHandle(handle: TorrentHandle): Set<Int> {
        val hash = handle.infoHash().toString()
        synchronized(this) {
            return tracked.values
                .filter { it.infoHash == hash }
                .map { it.fileIndex }
                .toSet()
        }
    }

    override fun types(): IntArray = intArrayOf(
        AlertType.TORRENT_FINISHED.swig(),
        AlertType.FILE_ERROR.swig(),
        AlertType.TORRENT_ERROR.swig()
    )

    override fun alert(alert: Alert<*>) {
        when (alert.type()) {
            AlertType.TORRENT_FINISHED -> onTorrentFinished(alert as TorrentFinishedAlert)
            AlertType.FILE_ERROR       -> onFileError(alert as FileErrorAlert)
            AlertType.TORRENT_ERROR    -> onTorrentError(alert as TorrentErrorAlert)
            else -> Unit
        }
    }

    private fun onTorrentFinished(alert: TorrentFinishedAlert) {
        val handle = alert.handle() ?: return
        val hash = handle.infoHash().toString()
        val snapshot: Map<String, TrackedFile>
        synchronized(this) { snapshot = tracked.toMap() }
        snapshot.entries
            .filter { (_, info) -> info.infoHash == hash }
            .forEach { (fn, _) ->
                val currentItem = progressTracker.downloads.value.find { it.fileName == fn }
                if (currentItem?.status != DownloadStatus.UNZIPPING &&
                    currentItem?.status != DownloadStatus.COMPLETED &&
                    currentItem?.status != DownloadStatus.FAILED &&
                    currentItem?.status != DownloadStatus.STOPPED &&
                    currentItem?.status != DownloadStatus.PAUSED) {
                    progressTracker.updateDownloadStatus(fn, DownloadStatus.COMPLETED)
                    Log.i(TAG, "Torrent finished: $fn — Now moving")
                }
            }
    }

    private fun onFileError(alert: FileErrorAlert) {
        val handle = alert.handle() ?: return
        Log.e(TAG, "File error: ${alert.message()}")
        // Usually the disk (full, or the cache unwritable): say so instead of a bare "failed".
        val failure = DownloadFailures.classify(Exception(alert.message())).takeIf { it != DownloadFailure.UNKNOWN } ?: DownloadFailure.TORRENT
        val hash = handle.infoHash().toString()
        val snapshot: Map<String, TrackedFile>
        synchronized(this) { snapshot = tracked.toMap() }
        snapshot.entries
            .filter { (_, info) -> info.infoHash == hash }
            .forEach { (fn, _) ->
                progressTracker.recordFailure(fn, failure)
                progressTracker.updateDownloadStatus(fn, DownloadStatus.FAILED)
            }
    }

    private fun onTorrentError(alert: TorrentErrorAlert) {
        val handle = alert.handle() ?: return
        Log.e(TAG, "Torrent error: ${alert.message()}")
        val hash = handle.infoHash().toString()
        val snapshot: Map<String, TrackedFile>
        synchronized(this) { snapshot = tracked.toMap() }
        snapshot.entries
            .filter { (_, info) -> info.infoHash == hash }
            .forEach { (fn, _) ->
                progressTracker.recordFailure(fn, DownloadFailure.TORRENT)
                progressTracker.updateDownloadStatus(fn, DownloadStatus.FAILED)
            }
    }

    companion object { private const val TAG = "TorrentProgressBridge" }
}
