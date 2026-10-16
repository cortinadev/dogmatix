package com.cortinadev.dogmatix.data.state

import com.cortinadev.dogmatix.util.DownloadFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A notification asked for the Downloads list (tapping the progress or the batch notice). The
 * shell switches to the Downloads tab while a request waits here, and the Downloads screen
 * applies its chip and clears it.
 */
@Singleton
class PendingDownloadsView @Inject constructor() {
    private val _request = MutableStateFlow<DownloadFilter?>(null)
    val request: StateFlow<DownloadFilter?> = _request.asStateFlow()

    fun submit(filter: DownloadFilter) {
        _request.value = filter
    }

    fun consume(): DownloadFilter? = _request.getAndUpdate { null }

    companion object {
        /** Intent extra with the [DownloadFilter] name to open the Downloads list on. */
        const val EXTRA_DOWNLOADS_FILTER = "com.cortinadev.dogmatix.extra.DOWNLOADS_FILTER"
    }
}
