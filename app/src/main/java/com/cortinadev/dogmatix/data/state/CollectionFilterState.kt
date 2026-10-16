package com.cortinadev.dogmatix.data.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The library's "Collection: X" filter. Set from the Collection tab (or the global search) and
 * read by the library, which keeps only the games of collection [id]; [revision] changes when
 * its games do, so an open library list re-queries.
 */
data class ActiveCollection(val id: Long, val name: String, val revision: Int = 0)

@Singleton
class CollectionFilterState @Inject constructor() {
    private val _active = MutableStateFlow<ActiveCollection?>(null)
    val active: StateFlow<ActiveCollection?> = _active.asStateFlow()

    /** Set by [show] for the library request that follows it (any other request clears the filter). */
    @Volatile private var justShown = false

    fun show(id: Long, name: String) {
        _active.value = ActiveCollection(id, name, (_active.value?.revision ?: 0) + 1)
        justShown = true
    }

    /** True once after [show]: the library request being applied is the one that opens the collection. */
    fun takeJustShown(): Boolean = justShown.also { justShown = false }

    /** The games of [id] changed: an open filter on it refreshes. */
    fun touched(id: Long) {
        _active.update { current -> if (current?.id == id) current.copy(revision = current.revision + 1) else current }
    }

    fun clear() {
        _active.value = null
    }
}
