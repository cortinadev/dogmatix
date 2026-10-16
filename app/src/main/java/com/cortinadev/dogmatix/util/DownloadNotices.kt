package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus

/** Running, waiting for its turn, copying or extracting: what keeps the queue busy. Paused and held rows do not. */
fun DownloadItemModel.isBusy(held: Set<String>): Boolean =
    !isFinished && status != DownloadStatus.PAUSED && fileName !in held

/**
 * What the ongoing downloads notification shows: how many rows run and wait, the bytes of the
 * busy rows and their speed, and which of its buttons have something to act on.
 */
data class QueueProgress(
    val running: Int,
    val waiting: Int,
    val doneBytes: Long,
    val totalBytes: Long,
    /** MB/s, summed over the transfers. */
    val speed: Float,
    val canPause: Boolean,
    val canResume: Boolean,
    val canCancel: Boolean
) {
    /** 0..100, or null while no size is known. */
    val percent: Int? get() = if (totalBytes > 0L) (doneBytes * 100 / totalBytes).toInt().coerceIn(0, 100) else null

    companion object {
        fun of(rows: List<DownloadItemModel>, waiting: Set<String>, held: Set<String>): QueueProgress {
            var running = 0
            var queued = 0
            var done = 0L
            var total = 0L
            var speed = 0f
            for (row in rows) {
                if (!row.isBusy(held)) continue
                if (row.fileName in waiting) queued++ else running++
                total += row.fileSize.coerceAtLeast(0L)
                // Copying and extracting come after the transfer: their bytes are all in.
                done += if (row.status == DownloadStatus.DOWNLOADING || row.status == DownloadStatus.QUEUED)
                    row.downloadedBytes.coerceIn(0L, row.fileSize.coerceAtLeast(0L)) else row.fileSize.coerceAtLeast(0L)
                if (row.fileName !in waiting) speed += row.downloadSpeed.coerceAtLeast(0f)
            }
            return QueueProgress(
                running = running,
                waiting = queued,
                doneBytes = done,
                totalBytes = total,
                speed = speed,
                canPause = DownloadBulk.pausable(rows, held).isNotEmpty(),
                canResume = DownloadBulk.resumable(rows).isNotEmpty(),
                canCancel = DownloadBulk.stoppable(rows).isNotEmpty()
            )
        }
    }
}

/**
 * Says how a batch of downloads ended once the queue runs dry. A batch starts when a row gets
 * busy and ends when nothing is busy any more and no automatic retry is due; rows deleted in
 * between are left out. Feed it every new list (the caller waits for a quiet moment before
 * feeding an idle one: a web download sits on FAILED for a few seconds between attempts).
 */
class DownloadBatch {

    data class Summary(val completed: Int, val failed: Int)

    private val members = LinkedHashSet<String>()

    /** [retrying]: failed rows with an automatic retry scheduled, which keep the batch open. */
    fun update(rows: List<DownloadItemModel>, held: Set<String>, retrying: Set<String>): Summary? {
        var busy = false
        for (row in rows) {
            if (isBusy(row, held, retrying)) {
                busy = true
                members += row.fileName
            }
        }
        if (busy || members.isEmpty()) return null
        val byName = rows.associateBy { it.fileName }
        val ended = members.mapNotNull { byName[it] }
        members.clear()
        val summary = Summary(
            completed = ended.count { it.status == DownloadStatus.COMPLETED },
            failed = ended.count { it.status == DownloadStatus.FAILED }
        )
        // A batch the user stopped or paused entirely has nothing to report.
        return summary.takeIf { it.completed + it.failed > 0 }
    }

    companion object {
        fun isBusy(row: DownloadItemModel, held: Set<String>, retrying: Set<String>): Boolean =
            row.isBusy(held) || row.fileName in retrying

        fun anyBusy(rows: List<DownloadItemModel>, held: Set<String>, retrying: Set<String>): Boolean =
            rows.any { isBusy(it, held, retrying) }
    }
}
