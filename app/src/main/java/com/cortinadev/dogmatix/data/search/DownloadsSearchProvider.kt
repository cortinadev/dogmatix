package com.cortinadev.dogmatix.data.search

import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.DownloadRepository
import com.cortinadev.dogmatix.ui.components.stripExtension
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Downloads (the whole history) by name; the status is shown under it. */
class DownloadsSearchProvider @Inject constructor(
    private val downloads: DownloadRepository,
    private val strings: SearchStrings
) : SearchProvider {

    private data class Row(val fileName: String, val name: String, val status: DownloadStatus)

    override val entries: Flow<List<SearchEntry>> = downloads.downloads
        // Progress ticks every second; only rows coming, going or changing status re-index.
        .map { list -> list.map { Row(it.fileName, it.name, it.status) } }
        .distinctUntilChanged()
        .map { rows ->
            val res = strings.resources()
            val labels = DownloadStatus.entries.associateWith { res.getString(it.searchLabel) }
            rows.map { row ->
                SearchEntry(
                    id = "download:${row.fileName}",
                    group = SearchGroup.DOWNLOADS,
                    title = stripExtension(row.name),
                    subtitle = labels.getValue(row.status),
                    target = SearchTarget.Download(row.fileName)
                )
            }
        }

    private val DownloadStatus.searchLabel: Int
        get() = when (this) {
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> R.string.search_download_in_progress
            DownloadStatus.COPYING -> R.string.status_copying
            DownloadStatus.UNZIPPING -> R.string.status_extracting
            DownloadStatus.COMPLETED -> R.string.status_completed
            DownloadStatus.FAILED -> R.string.status_failed
            DownloadStatus.STOPPED -> R.string.status_stopped
            DownloadStatus.PAUSED -> R.string.status_paused
        }
}
