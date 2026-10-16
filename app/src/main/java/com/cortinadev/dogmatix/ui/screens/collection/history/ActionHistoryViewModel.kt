package com.cortinadev.dogmatix.ui.screens.collection.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.dao.TrashTotals
import com.cortinadev.dogmatix.data.local.entity.ActionHistoryEntity
import com.cortinadev.dogmatix.data.service.ActionHistoryService
import com.cortinadev.dogmatix.data.service.LibraryMoveService
import com.cortinadev.dogmatix.data.service.UndoOutcome
import com.cortinadev.dogmatix.util.ToastUtil
import com.cortinadev.dogmatix.util.TrashRules
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One line of the history: the entry, and whether Undo works on it right now. */
data class HistoryRow(val entry: ActionHistoryEntity, val undoable: Boolean, val running: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ActionHistoryViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val history: ActionHistoryService,
    mover: LibraryMoveService
) : ViewModel() {

    /** Newest first; Undo is checked against the present for each (files still in the trash, settings untouched…). */
    val rows: StateFlow<List<HistoryRow>> = combine(history.entries, history.undoing, mover.state.map { it?.phase }.distinctUntilChanged()) { entries, undoing, _ ->
        entries to undoing
    }.mapLatest { (entries, undoing) ->
        entries.map { HistoryRow(it, history.isUndoAvailable(it), it.id in undoing) }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val trash: StateFlow<TrashTotals> = history.trashTotals.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrashTotals(0, 0))

    val retentionDays: StateFlow<Int> = history.retentionDays.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrashRules.DEFAULT_RETENTION_DAYS)

    private val _emptying = MutableStateFlow(false)
    val emptying: StateFlow<Boolean> = _emptying.asStateFlow()

    init {
        // The trash may have aged past its limit since the app started.
        viewModelScope.launch(Dispatchers.IO) { runCatching { history.purgeExpired() } }
    }

    fun undo(id: Long) {
        viewModelScope.launch {
            val message = when (history.undo(id)) {
                UndoOutcome.DONE -> R.string.history_undo_done
                UndoOutcome.PARTIAL -> R.string.history_undo_partial
                UndoOutcome.STARTED -> R.string.history_undo_started
                UndoOutcome.FAILED -> R.string.history_undo_failed
            }
            ToastUtil.showInfo(context, context.getString(message))
        }
    }

    fun emptyTrash() {
        if (_emptying.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _emptying.value = true
            try {
                runCatching { history.emptyTrash() }
            } finally {
                _emptying.value = false
            }
        }
    }

    fun cycleRetention(delta: Int) {
        val choices = TrashRules.RETENTION_CHOICES
        val index = (choices.indexOf(retentionDays.value).coerceAtLeast(0) + delta).coerceIn(0, choices.lastIndex)
        viewModelScope.launch(Dispatchers.IO) { history.setRetentionDays(choices[index]) }
    }
}
