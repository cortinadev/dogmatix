package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus

/** Status chips of the Downloads list. Paused and stopped rows only show under [ALL]. */
enum class DownloadFilter { ALL, RUNNING, WAITING, HELD, FAILED, COMPLETED }

/**
 * Search and status chips of the Downloads list. A row waiting for its turn and a running one
 * are both DOWNLOADING, so the queue says which is which ([waiting] and [held] names).
 */
object DownloadListFilter {

    data class Result(val rows: List<DownloadItemModel>, val counts: Map<DownloadFilter, Int>)

    /** The chip a row counts for besides [DownloadFilter.ALL]; null = only "all". */
    fun groupOf(item: DownloadItemModel, waiting: Set<String>, held: Set<String>): DownloadFilter? = when {
        item.fileName in held -> DownloadFilter.HELD
        item.fileName in waiting && item.status == DownloadStatus.DOWNLOADING -> DownloadFilter.WAITING
        item.status == DownloadStatus.COMPLETED -> DownloadFilter.COMPLETED
        item.status == DownloadStatus.FAILED -> DownloadFilter.FAILED
        item.status == DownloadStatus.PAUSED || item.status == DownloadStatus.STOPPED -> null
        // Debrid wait, transfer, copy, extraction (and any later in-progress status).
        else -> DownloadFilter.RUNNING
    }

    /**
     * Rows matching [query] (every word, accents and punctuation ignored) and [filter], in the
     * order given; [Result.counts] counts each chip over the rows that match the query.
     */
    fun apply(
        rows: List<DownloadItemModel>,
        query: String,
        filter: DownloadFilter,
        waiting: Set<String>,
        held: Set<String>
    ): Result {
        val words = query.split(WHITESPACE).map(SearchNormalizer::key).filter { it.isNotEmpty() }
        val counts = DownloadFilter.entries.associateWithTo(LinkedHashMap()) { 0 }
        val shown = ArrayList<DownloadItemModel>()
        for (item in rows) {
            if (words.isNotEmpty()) {
                val key = SearchNormalizer.key(item.name)
                if (words.any { it !in key }) continue
            }
            val group = groupOf(item, waiting, held)
            counts[DownloadFilter.ALL] = counts.getValue(DownloadFilter.ALL) + 1
            if (group != null) counts[group] = counts.getValue(group) + 1
            if (filter == DownloadFilter.ALL || filter == group) shown += item
        }
        return Result(shown, counts)
    }

    private val WHITESPACE = Regex("\\s+")
}

/**
 * The rows each whole-list button acts on (Downloads header and notification). Pause, resume,
 * stop and clear ignore the search and the chips: "all" is the whole list; a subset is what
 * multi-selection is for. Retry failed only takes the rows the list shows, so an old failure
 * further down the history is not downloaded again behind a search or a chip.
 */
object DownloadBulk {

    /** Transfers and rows waiting for their turn; held rows stay held. */
    fun pausable(rows: List<DownloadItemModel>, held: Set<String>): List<String> =
        rows.filter { it.status == DownloadStatus.DOWNLOADING && it.fileName !in held }.map { it.fileName }

    fun resumable(rows: List<DownloadItemModel>): List<String> =
        rows.filter { it.status == DownloadStatus.PAUSED }.map { it.fileName }

    fun failed(rows: List<DownloadItemModel>): List<String> =
        rows.filter { it.status == DownloadStatus.FAILED }.map { it.fileName }

    fun completed(rows: List<DownloadItemModel>): List<String> =
        rows.filter { it.status == DownloadStatus.COMPLETED }.map { it.fileName }

    /** What "cancel all" stops: everything queued, transferring or extracting, held rows included. */
    fun stoppable(rows: List<DownloadItemModel>): List<String> =
        rows.filter {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.UNZIPPING
        }.map { it.fileName }
}
