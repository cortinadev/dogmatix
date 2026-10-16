package com.cortinadev.dogmatix.data.model

/** The kinds of shelf the library shows when no filter is on, in screen order. */
enum class ShelfKind {
    /** Games of the user's genres on the user's consoles. */
    FOR_YOU,
    /** Entries of the user's franchises they do not have. */
    SERIES,
    /** Notable games of the console the user plays most ([Shelf.consoleId]). */
    MORE_FROM,
    /** RAWG's most added games of the user's genres on the user's consoles (needs a RAWG key). */
    POPULAR,
    /** Nothing on disk, downloaded or starred yet: the notable games of one console ([Shelf.consoleId]). */
    HIGHLIGHTS
}

data class Shelf(val kind: ShelfKind, val games: List<LibraryGame>, val consoleId: String? = null) {
    val key: String get() = "$kind:${consoleId.orEmpty()}"
}

/**
 * What the recommendations show: nothing yet ([ready] false), an empty library, or shelves.
 * [games] finds the game behind a card by its primary file id (A downloads its preferred version).
 */
data class RecommendationState(
    val ready: Boolean = false,
    val libraryEmpty: Boolean = false,
    val shelves: List<Shelf> = emptyList()
) {
    val games: Map<Long, LibraryGame> = shelves.flatMap { it.games }.associateBy { it.primary.file.id }
}
