package com.cortinadev.dogmatix.ui.screens.settings.romm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.RommCatalogService
import com.cortinadev.dogmatix.data.service.RommCatalogState
import com.cortinadev.dogmatix.data.service.RommEsdeCoverService
import com.cortinadev.dogmatix.data.service.RommEsdeCoversProgress
import com.cortinadev.dogmatix.data.service.RommFavouritesSyncService
import com.cortinadev.dogmatix.data.service.RommFavouritesSyncState
import com.cortinadev.dogmatix.data.service.RommUploadService
import com.cortinadev.dogmatix.data.service.UploadStatus
import com.cortinadev.dogmatix.ui.common.executeWithToast
import com.cortinadev.dogmatix.util.ConsoleFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The uploads "Upload what's missing" queued, as the upload service reports them. */
data class RommUploadsProgress(val total: Int, val done: Int, val failed: Int, val uploading: Int)

data class RommCatalogUi(
    val catalog: RommCatalogState = RommCatalogState(),
    val favouritesSync: Boolean = false,
    val favourites: RommFavouritesSyncState = RommFavouritesSyncState(),
    val esdeDirectory: String = "",
    val covers: RommEsdeCoversProgress? = null,
    val uploads: RommUploadsProgress? = null
)

/** One platform's games of the "what's missing" list. */
data class RommMissingGroup(val console: String, val games: List<String>)

sealed interface RommMissingState {
    data object Checking : RommMissingState
    data class Ready(val groups: List<RommMissingGroup>, val games: Int, val fileNames: List<String>) : RommMissingState
    data class Failed(val message: String) : RommMissingState
}

/** Settings → RomM: catalogue, "Upload what's missing", favourites sync and covers to ES-DE. */
@HiltViewModel
class RommCatalogViewModel @Inject constructor(
    private val catalog: RommCatalogService,
    private val favouritesSync: RommFavouritesSyncService,
    private val esdeCovers: RommEsdeCoverService,
    private val uploads: RommUploadService,
    private val settings: SettingsRepository
) : ViewModel() {

    /** File names queued by the last "Upload what's missing". */
    private val queued = MutableStateFlow<Set<String>>(emptySet())

    private val uploadProgress = combine(queued, uploads.uploads) { names, states ->
        if (names.isEmpty()) null else RommUploadsProgress(
            total = names.size,
            done = names.count { states[it]?.status == UploadStatus.DONE },
            failed = names.count { states[it]?.status == UploadStatus.FAILED },
            // Anything else the upload service reports (uploading, preparing, waiting…) is still going.
            uploading = names.count { states[it]?.status.let { s -> s != null && s != UploadStatus.DONE && s != UploadStatus.FAILED } }
        )
    }

    val ui: StateFlow<RommCatalogUi> = combine(
        combine(catalog.state, settings.rommFavouritesSync, favouritesSync.state) { c, on, f -> Triple(c, on, f) },
        combine(settings.esdeDirectory, esdeCovers.progress, uploadProgress) { dir, covers, up -> Triple(dir, covers, up) }
    ) { (c, on, f), (dir, covers, up) ->
        RommCatalogUi(c, on, f, dir, covers, up)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RommCatalogUi())

    private val _missing = MutableStateFlow<RommMissingState?>(null)
    /** The "Upload what's missing" dialog; null when closed. */
    val missing: StateFlow<RommMissingState?> = _missing.asStateFlow()

    val coversOutcome: StateFlow<RommEsdeCoverService.Outcome?> = esdeCovers.outcome

    fun refresh() = catalog.refresh()

    fun checkMissing() {
        _missing.value = RommMissingState.Checking
        viewModelScope.launch {
            _missing.value = runCatching { catalog.missingOnServer() }.fold(
                onSuccess = { games ->
                    val groups = games.groupBy { it.consoleId }
                        .map { (consoleId, list) -> RommMissingGroup(ConsoleFormatter.getConsoleShortName(consoleId), list.map { it.name }.sorted()) }
                        .sortedBy { it.console.lowercase() }
                    RommMissingState.Ready(groups, games.size, games.flatMap { it.fileNames }.distinct())
                },
                onFailure = { RommMissingState.Failed(it.message ?: it.javaClass.simpleName) }
            ).takeIf { _missing.value == RommMissingState.Checking }
        }
    }

    /** Confirmed: every file of the missing games goes to the upload queue. */
    fun uploadMissing() {
        val ready = _missing.value as? RommMissingState.Ready ?: return
        _missing.value = null
        queued.value = ready.fileNames.toSet()
        ready.fileNames.forEach(uploads::retry)
    }

    fun dismissMissing() {
        _missing.value = null
    }

    fun setFavouritesSync(context: Context, enabled: Boolean) =
        executeWithToast(context, TAG) { settings.setRommFavouritesSync(enabled) }

    fun onEsdeDirPicked(context: Context, uri: String) = executeWithToast(context, TAG) { settings.setEsdeDirectory(uri) }

    fun writeCovers(overwrite: Boolean) = esdeCovers.start(overwrite)

    fun consumeCoversOutcome() = esdeCovers.consumeOutcome()

    private companion object { const val TAG = "RommCatalogViewModel" }
}
