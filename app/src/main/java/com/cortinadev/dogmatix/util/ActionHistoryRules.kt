package com.cortinadev.dogmatix.util

/**
 * What a history entry recorded. Stored as text, so the values never change; tools added later
 * can record their own types (the list shows their description only).
 */
object ActionTypes {
    /** Removed from Downloads together with its file. */
    const val DOWNLOAD_DELETE = "download_delete"
    /** "Delete" on a game already on disk, in the library. */
    const val LIBRARY_DELETE = "library_delete"
    const val LIBRARY_MOVE = "library_move"
    const val SMART_STORAGE = "smart_storage"
    const val SOURCES_IMPORT = "sources_import"
    const val COLLECTION_DELETE = "collection_delete"
    // The Collection tab's clean-up tools: their deletions go through the trash too.
    const val DUPLICATES = "duplicates"
    const val REPAIR = "repair"
    const val FREE_SPACE = "free_space"
}

/** Which undo handler reverts an entry (the key of its Hilt map binding). */
object UndoKinds {
    const val TRASH = "trash"
    const val MOVE = "move"
    const val SOURCES = "sources"
    const val COLLECTION = "collection"
}

enum class ActionState {
    /** Done and, if it has an undo, still undoable. */
    DONE,
    /** Reverted. */
    UNDONE,
    /** The last undo attempt failed (it may be tried again). */
    UNDO_FAILED,
    /** A move back is running; the entry ends UNDONE or UNDO_FAILED with it. */
    UNDOING,
    /** Cannot be undone any more: its trash was emptied. */
    EXPIRED,
    /** A newer entry of the same kind replaced what this one would restore. */
    SUPERSEDED;

    companion object {
        fun of(name: String?): ActionState = entries.firstOrNull { it.name == name } ?: EXPIRED
    }
}

object ActionHistoryRules {
    /** The history keeps this many entries; older ones (and their saved payloads) go. */
    const val MAX_ENTRIES = 300

    /**
     * Types where only the newest entry can be undone: undoing an older sources import would
     * throw away the newer one. Undoing the newest makes the one before it undoable again
     * (the chain is consistent: each restores what the previous one replaced).
     */
    private val CHAINED = setOf(ActionTypes.SOURCES_IMPORT)

    fun isChained(type: String): Boolean = type in CHAINED

    /** Whether an entry offers Undo at all (the handler still checks it works right now). */
    fun offersUndo(state: ActionState, undoKind: String?): Boolean =
        !undoKind.isNullOrEmpty() && (state == ActionState.DONE || state == ActionState.UNDO_FAILED)
}
