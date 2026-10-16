package com.cortinadev.dogmatix.ui.screens.collection.smartstorage

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.ConsoleStorage
import com.cortinadev.dogmatix.data.service.LibraryMoveService
import com.cortinadev.dogmatix.data.service.MovePhase
import com.cortinadev.dogmatix.data.service.MoveProgress
import com.cortinadev.dogmatix.data.service.SmartStorageScan
import com.cortinadev.dogmatix.data.service.SmartStorageService
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.util.SmartStorage
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SmartStorageViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val service: SmartStorageService,
    private val mover: LibraryMoveService
) : ViewModel() {

    val secondRoot: StateFlow<String> = service.secondRoot.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _scan = MutableStateFlow<SmartStorageScan?>(null)
    val scan: StateFlow<SmartStorageScan?> = _scan.asStateFlow()

    /** Consoles measured so far / to measure, while a scan runs. */
    private val _measuring = MutableStateFlow<Pair<Int, Int>?>(null)
    val measuring: StateFlow<Pair<Int, Int>?> = _measuring.asStateFlow()

    val move: StateFlow<MoveProgress?> = mover.state

    private val _reconfiguring = MutableStateFlow(false)
    val reconfiguring: StateFlow<Boolean> = _reconfiguring.asStateFlow()

    private var scanJob: Job? = null

    init {
        refresh()
        // A move that ends changes where the consoles are: measure again.
        viewModelScope.launch {
            mover.state.map { it?.phase == MovePhase.DONE }.distinctUntilChanged().drop(1).collect { done -> if (done) refresh() }
        }
        viewModelScope.launch { service.secondRoot.distinctUntilChanged().drop(1).collect { refresh() } }
    }

    fun refresh() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _measuring.value = 0 to 0
            try {
                _scan.value = runCatching { service.scan { done, total -> _measuring.value = done to total } }.getOrNull()
            } finally {
                _measuring.value = null
            }
        }
    }

    fun setSecondRoot(uri: String) {
        viewModelScope.launch {
            if (!service.setSecondRoot(uri)) ToastUtil.showError(context, context.getString(R.string.storage_second_overlaps))
        }
    }

    /** Moves [console] to the other root (A on its row, after the confirmation). */
    fun moveToOther(console: ConsoleStorage) = startMove(listOf(console), SmartStorage.other(console.place))

    fun applySuggestions() {
        val scan = _scan.value ?: return
        val byId = scan.consoles.associateBy { it.consoleId }
        val consoles = scan.suggestions.mapNotNull { byId[it.consoleId] }
        val to = scan.suggestions.firstOrNull()?.to ?: return
        startMove(consoles, to)
    }

    private fun startMove(consoles: List<ConsoleStorage>, to: SmartStorage.Place) {
        if (consoles.isEmpty()) return
        viewModelScope.launch {
            val started = service.move(consoles.map { it.consoleId }, to, consoles.joinToString(", ") { it.name })
            if (!started) ToastUtil.showError(context, context.getString(R.string.storage_move_not_started))
        }
    }

    fun cancelMove() = mover.cancel()
    fun resumeMove() { if (!mover.resume()) ToastUtil.showError(context, context.getString(R.string.storage_move_not_started)) }
    fun abandonMove() = mover.abandon()
    fun dismissMove() = mover.dismiss()

    /** Writes the shortcuts again and re-runs the ES-DE / iiSU setups configured in Settings. */
    fun reconfigureFrontends() {
        if (_reconfiguring.value) return
        viewModelScope.launch {
            _reconfiguring.value = true
            try {
                val run = service.reconfigureFrontends()
                val parts = mutableListOf(context.getString(R.string.storage_frontends_shortcuts, run.shortcutsWritten))
                run.esde?.let { r ->
                    parts += r.fold({ context.getString(R.string.esde_configured, it.systems, it.shortcutsWritten) }, { context.getString(R.string.storage_frontends_esde_failed) })
                }
                run.iisu?.let { r ->
                    parts += r.fold({ context.getString(R.string.iisu_configured, it.consoles, it.shortcutsWritten) }, { context.getString(R.string.storage_frontends_iisu_failed) })
                }
                ToastUtil.showInfo(context, parts.joinToString("\n"))
            } finally {
                _reconfiguring.value = false
            }
        }
    }

    fun suggestionSummary(scan: SmartStorageScan): String? {
        if (scan.suggestions.isEmpty()) return null
        val names = scan.consoles.filter { c -> scan.suggestions.any { it.consoleId == c.consoleId } }.joinToString(", ") { it.name }
        return context.getString(R.string.storage_suggestion_summary, names, formatBytes(scan.suggestions.sumOf { it.bytes }))
    }
}
