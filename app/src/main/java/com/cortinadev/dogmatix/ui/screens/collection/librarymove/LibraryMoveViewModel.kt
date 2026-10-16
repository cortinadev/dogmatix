package com.cortinadev.dogmatix.ui.screens.collection.librarymove

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.ConsoleStorage
import com.cortinadev.dogmatix.data.service.LibraryMoveService
import com.cortinadev.dogmatix.data.service.MoveProgress
import com.cortinadev.dogmatix.data.service.SmartStorageService
import com.cortinadev.dogmatix.util.ActionTypes
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.StorageHelper
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class LibraryMoveViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val mover: LibraryMoveService,
    private val smartStorage: SmartStorageService,
    settings: SettingsRepository
) : ViewModel() {

    val root: StateFlow<String> = settings.downloadDirectory.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Consoles with a folder of their own (those inside the root follow it). */
    val ownFolders: StateFlow<Int> = settings.consoleDownloadDirectories.map { it.size }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _destination = MutableStateFlow<String?>(null)
    val destination: StateFlow<String?> = _destination.asStateFlow()

    private val _destinationFree = MutableStateFlow<Long?>(null)
    val destinationFree: StateFlow<Long?> = _destinationFree.asStateFlow()

    /** True: the whole library root; false: the consoles in [selected]. */
    private val _whole = MutableStateFlow(true)
    val whole: StateFlow<Boolean> = _whole.asStateFlow()

    private val _consoles = MutableStateFlow<List<ConsoleStorage>?>(null)
    /** Consoles with a folder on disk, measured when "chosen consoles" is picked; null until then. */
    val consoles: StateFlow<List<ConsoleStorage>?> = _consoles.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    val move: StateFlow<MoveProgress?> = mover.state

    fun setDestination(uri: String) {
        _destination.value = uri
        viewModelScope.launch { _destinationFree.value = withContext(Dispatchers.IO) { StorageHelper.getFreeBytes(context, uri) } }
    }

    fun setWhole(whole: Boolean) {
        _whole.value = whole
        if (!whole && _consoles.value == null) {
            viewModelScope.launch { _consoles.value = runCatching { smartStorage.scan().consoles }.getOrDefault(emptyList()) }
        }
    }

    fun toggle(consoleId: String) {
        _selected.value = _selected.value.let { if (consoleId in it) it - consoleId else it + consoleId }
    }

    fun start() {
        val destination = _destination.value ?: return
        viewModelScope.launch {
            val job = if (_whole.value) {
                mover.planRoot(destination)
            } else {
                val chosen = _consoles.value.orEmpty().filter { it.consoleId in _selected.value }
                if (chosen.isEmpty()) null
                else mover.planConsoles(chosen.map { it.consoleId }, destination, ActionTypes.LIBRARY_MOVE,
                    FileParsingUtils.toUserReadablePath(destination) + " · " + chosen.joinToString(", ") { it.name })
            }
            when {
                job == null -> ToastUtil.showError(context, context.getString(R.string.move_nothing_to_move))
                !mover.start(job) -> ToastUtil.showError(context, context.getString(R.string.move_busy))
                else -> {
                    _destination.value = null
                    _selected.value = emptySet()
                    _consoles.value = null
                    _whole.value = true
                }
            }
        }
    }

    fun cancel() = mover.cancel()
    fun resume() { if (!mover.resume()) ToastUtil.showError(context, context.getString(R.string.move_busy)) }
    fun abandon() = mover.abandon()
    fun dismiss() = mover.dismiss()
}
