package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.cortinadev.dogmatix.data.local.entity.ActionHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActionHistoryDao {

    @Query("SELECT * FROM action_history ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActionHistoryEntity>>

    @Query("SELECT * FROM action_history WHERE id = :id")
    suspend fun get(id: Long): ActionHistoryEntity?

    @Insert
    suspend fun insert(entry: ActionHistoryEntity): Long

    @Query("UPDATE action_history SET state = :state, stateAt = :at WHERE id = :id")
    suspend fun setState(id: Long, state: String, at: Long)

    /** Entries of [type] older than [beforeId] still in [state], newest first (the undo chain of imports). */
    @Query("SELECT * FROM action_history WHERE type = :type AND id < :beforeId AND state = :state ORDER BY id DESC")
    suspend fun olderInState(type: String, beforeId: Long, state: String): List<ActionHistoryEntity>

    @Query("UPDATE action_history SET state = :state, stateAt = :at WHERE type = :type AND id < :beforeId AND state IN ('DONE', 'UNDO_FAILED')")
    suspend fun supersedeOlder(type: String, beforeId: Long, state: String, at: Long)

    /** Entries whose undo is the trash batch [batchIds]: they cannot be undone once it is emptied. */
    @Query("UPDATE action_history SET state = 'EXPIRED', stateAt = :at WHERE undoKind = :undoKind AND undoData IN (:batchIds) AND state IN ('DONE', 'UNDO_FAILED')")
    suspend fun expire(undoKind: String, batchIds: List<String>, at: Long)

    /** Everything past the newest [keep] entries. */
    @Query("SELECT * FROM action_history ORDER BY createdAt DESC, id DESC LIMIT -1 OFFSET :keep")
    suspend fun beyond(keep: Int): List<ActionHistoryEntity>

    @Query("DELETE FROM action_history WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}
