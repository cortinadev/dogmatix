package com.cortinadev.dogmatix.ui.screens.collection.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.repository.CollectionSummary
import com.cortinadev.dogmatix.data.repository.CollectionsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The details card's "Collection" dialog: the manual collections and which hold the game on show. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AddToCollectionViewModel @Inject constructor(
    private val repository: CollectionsRepository
) : ViewModel() {

    private val game = MutableStateFlow<Pair<String, String>?>(null)

    /** Only manual ones: a smart collection's games come from its rules. */
    val collections: StateFlow<List<CollectionSummary>?> = repository.collections
        .map { list -> list.filter { !it.isSmart } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val memberOf: StateFlow<Set<Long>> = game
        .flatMapLatest { g -> g?.let { (console, group) -> repository.collectionsOf(console, group) } ?: flowOf(emptySet()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun bind(consoleId: String, groupKey: String) {
        game.value = consoleId to groupKey
    }

    fun toggle(collectionId: Long) {
        val (console, group) = game.value ?: return
        val member = collectionId !in memberOf.value
        viewModelScope.launch { repository.setMember(collectionId, console, group, member) }
    }

    /** A new manual collection, with the game already in it. */
    fun createWithGame(name: String) {
        val (console, group) = game.value ?: return
        viewModelScope.launch {
            repository.create(name)?.let { repository.setMember(it, console, group, true) }
        }
    }
}
