package com.cortinadev.dogmatix.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.repository.RommCollectionsRepository
import com.cortinadev.dogmatix.data.service.GameTraitsService
import com.cortinadev.dogmatix.data.service.RommCatalogService
import com.cortinadev.dogmatix.util.RommGameInfo
import com.cortinadev.dogmatix.util.RommPlayState
import com.cortinadev.dogmatix.util.RommUserProps
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** ◀ ▶ presses in a row are sent as one write, this long after the last one. */
private const val WRITE_DELAY_MS = 700L

/** A collection of the account the game can be put in or taken out of. */
data class RommCollectionChoice(val id: Int, val name: String, val member: Boolean)

/** The game of the open details card, as the RomM server has it. */
data class RommGameUi(
    val romId: Int,
    val loading: Boolean = true,
    /** The server could not be read: the status is not shown (it would overwrite blindly). */
    val failed: Boolean = false,
    val info: RommGameInfo? = null,
    val playState: RommPlayState = RommPlayState.NONE,
    val collections: List<RommCollectionChoice> = emptyList()
)

/**
 * The RomM part of the details card: the game's data and play status on the server, and the
 * collections it is in. Nothing here is on the way of A: the card still opens on "Download".
 */
@HiltViewModel
class RommGameViewModel @Inject constructor(
    private val catalog: RommCatalogService,
    private val collections: RommCollectionsRepository,
    private val traits: GameTraitsService
) : ViewModel() {

    private val _game = MutableStateFlow<RommGameUi?>(null)
    /** Null when the game is not on the server (or RomM is not set up): the card shows nothing. */
    val game: StateFlow<RommGameUi?> = combine(_game, collections.collections) { game, all ->
        game?.copy(collections = all.filter { it.editable && !it.isFavourite }
            .map { RommCollectionChoice(it.id, it.name, game.romId in it.romIds) })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _errors = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    /** String resources of failures to show as a toast. */
    val errors: SharedFlow<Int> = _errors.asSharedFlow()

    private var loadJob: Job? = null
    private var pendingWrite: Job? = null
    /** What the server holds, so a write only sends what changed. */
    private var saved = RommUserProps()

    /** The card shows the game made of [files] (every file of its versions). */
    fun open(files: List<DownloadableFileEntity>) {
        val entry = catalog.entryForGame(files)
        if (entry?.romId == _game.value?.romId && entry != null) return
        flush()
        loadJob?.cancel()
        if (entry == null) {
            _game.value = null
            return
        }
        saved = RommUserProps()
        _game.value = RommGameUi(entry.romId, playState = entry.playState)
        collections.refreshIfStale()
        loadJob = viewModelScope.launch {
            runCatching { catalog.gameInfo(entry.romId) }
                .onSuccess { info ->
                    saved = info.props
                    _game.update { it?.takeIf { g -> g.romId == entry.romId }?.copy(loading = false, info = info, playState = RommPlayState.of(info.props)) ?: it }
                    // RomM's genres feed the library's Genre filter too (only fills gaps; RomM sends no year here).
                    files.firstOrNull()?.takeIf { info.genres.isNotEmpty() }?.let { file ->
                        runCatching { traits.remember(file.consoleId, file.groupKey, info.genres, "") }
                    }
                }
                .onFailure { _game.update { it?.takeIf { g -> g.romId == entry.romId }?.copy(loading = false, failed = true) ?: it } }
        }
    }

    fun stepPlayState(delta: Int) {
        val game = _game.value ?: return
        if (game.loading || game.failed) return
        val next = game.playState.step(delta)
        _game.value = game.copy(playState = next)
        pendingWrite?.cancel()
        pendingWrite = viewModelScope.launch {
            delay(WRITE_DELAY_MS)
            runCatching { catalog.setPlayState(game.romId, next, saved) }
                .onSuccess { saved = it }
                .onFailure {
                    _game.update { g -> g?.takeIf { it.romId == game.romId }?.copy(playState = RommPlayState.of(saved)) ?: g }
                    _errors.tryEmit(R.string.romm_status_failed)
                }
        }
    }

    fun toggleCollection(collectionId: Int) {
        val game = game.value ?: return
        val choice = game.collections.firstOrNull { it.id == collectionId } ?: return
        viewModelScope.launch {
            runCatching { collections.setMembership(collectionId, game.romId, !choice.member) }
                .onFailure { _errors.tryEmit(R.string.romm_collection_failed) }
        }
    }

    /** The card closed: a status change still waiting goes out anyway. */
    fun close() {
        flush()
        loadJob?.cancel()
        _game.value = null
    }

    private fun flush() {
        val job = pendingWrite ?: return
        pendingWrite = null
        if (!job.isActive) return
        job.cancel()
        _game.value?.let { catalog.setPlayStateLater(it.romId, it.playState, saved) }
    }

    override fun onCleared() = flush()
}
