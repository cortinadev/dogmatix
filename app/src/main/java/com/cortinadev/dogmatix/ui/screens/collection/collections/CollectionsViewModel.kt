package com.cortinadev.dogmatix.ui.screens.collection.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.repository.CollectionSummary
import com.cortinadev.dogmatix.data.repository.CollectionsRepository
import com.cortinadev.dogmatix.data.repository.DownloadableFileRepository
import com.cortinadev.dogmatix.data.repository.ExternalCollection
import com.cortinadev.dogmatix.util.CollectionRules
import com.cortinadev.dogmatix.util.ConsoleFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the rules editor offers, from what the library holds. */
data class RuleOptions(
    /** Console id → name. */
    val consoles: List<Pair<String, String>>,
    val regions: List<String>,
    val languages: List<String>,
    val tags: List<String>
)

@HiltViewModel
class CollectionsViewModel @Inject constructor(
    private val repository: CollectionsRepository,
    private val files: DownloadableFileRepository
) : ViewModel() {

    val collections: StateFlow<List<CollectionSummary>?> = repository.collections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val external: StateFlow<List<ExternalCollection>> = repository.externalCollections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun externalLabel(sourceKey: String): String = repository.externalLabel(sourceKey)

    /** The collection being opened (a smart one is evaluated first). */
    private val _opening = MutableStateFlow<Long?>(null)
    val opening: StateFlow<Long?> = _opening.asStateFlow()

    private val _options = MutableStateFlow<RuleOptions?>(null)
    val options: StateFlow<RuleOptions?> = _options.asStateFlow()

    fun open(collection: CollectionSummary) = open(collection.id)

    /** By id: also what a search result for a collection does (see CollectionScreen). */
    fun open(id: Long) {
        if (_opening.value != null) return
        viewModelScope.launch {
            _opening.value = id
            try {
                repository.showInLibrary(id)
            } finally {
                _opening.value = null
            }
        }
    }

    fun openExternal(collection: ExternalCollection) {
        viewModelScope.launch { runCatching { repository.showExternalInLibrary(collection) } }
    }

    fun create(name: String, rules: CollectionRules?) {
        viewModelScope.launch { repository.create(name, rules) }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch { repository.rename(id, name) }
    }

    fun saveRules(id: Long, name: String, rules: CollectionRules) {
        viewModelScope.launch {
            repository.rename(id, name)
            repository.updateRules(id, rules)
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    /** Consoles and tags of the library, for the rules editor (loaded once per screen). */
    fun loadOptions() {
        if (_options.value != null) return
        viewModelScope.launch {
            val consoles = runCatching { files.getConsolesWithFiles("") }.getOrDefault(emptyList())
                .map { it.id to ConsoleFormatter.getConsoleFolderName(it.id) }
                .sortedBy { it.second.lowercase() }
            val tags = runCatching { files.getCategorizedTags("") }.getOrNull()
            _options.value = RuleOptions(
                consoles = consoles,
                regions = tags?.regions?.tags.orEmpty(),
                languages = tags?.languages?.tags.orEmpty(),
                tags = tags?.contentTypes?.tags.orEmpty()
            )
        }
    }
}
