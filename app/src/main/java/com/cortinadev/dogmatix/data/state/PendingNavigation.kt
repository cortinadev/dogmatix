package com.cortinadev.dogmatix.data.state

import com.cortinadev.dogmatix.data.search.SearchTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hand-off of a search result between the library (where it is picked), the shell (which switches
 * to its section) and the screen there (which focuses the element), like [PendingLibraryFilters]
 * for deep links. The target waits here until its screen takes it with [consume].
 */
@Singleton
class PendingNavigation @Inject constructor() {
    private val _target = MutableStateFlow<SearchTarget?>(null)
    val target: StateFlow<SearchTarget?> = _target.asStateFlow()

    /** Bumped on every [submit]; the shell watches it to switch tabs. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun submit(target: SearchTarget) {
        _target.value = target
        _version.update { it + 1 }
    }

    /** Takes [target] if it is still the one waiting; false when another screen already did. */
    fun consume(target: SearchTarget): Boolean = _target.compareAndSet(target, null)
}
