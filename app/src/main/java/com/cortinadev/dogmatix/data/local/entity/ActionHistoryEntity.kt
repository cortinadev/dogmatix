package com.cortinadev.dogmatix.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One entry of the action history (Collection → History). [type] is an
 * [com.cortinadev.dogmatix.util.ActionTypes] value; [subject] is what it was about (a game, a
 * collection, a file) and is shown under the type's label. [undoKind] names the handler that can
 * revert it with [undoData] (its own format); both null when it cannot be undone.
 */
@Entity(tableName = "action_history", indices = [Index("createdAt")])
data class ActionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val type: String,
    val subject: String,
    /** Files, consoles or games involved (0 when it does not apply). */
    val itemCount: Int = 0,
    val bytes: Long = 0,
    val undoKind: String? = null,
    val undoData: String? = null,
    /** An [com.cortinadev.dogmatix.util.ActionState] name. */
    val state: String,
    val stateAt: Long? = null
)
