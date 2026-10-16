package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.cortinadev.dogmatix.data.local.entity.TrashItemEntity
import kotlinx.coroutines.flow.Flow

/** What is in the trash right now: items and bytes. */
data class TrashTotals(val items: Int, val bytes: Long)

@Dao
interface TrashDao {

    @Insert
    suspend fun insert(item: TrashItemEntity): Long

    @Query("SELECT * FROM trash_items WHERE batchId = :batchId")
    suspend fun batch(batchId: String): List<TrashItemEntity>

    @Query("SELECT COUNT(*) FROM trash_items WHERE batchId = :batchId AND state = 'IN_TRASH'")
    suspend fun countInTrash(batchId: String): Int

    @Query("SELECT DISTINCT batchId FROM trash_items WHERE state = 'IN_TRASH' AND trashedAt <= :before")
    suspend fun batchesTrashedBefore(before: Long): List<String>

    @Query("SELECT DISTINCT batchId FROM trash_items WHERE state = 'IN_TRASH'")
    suspend fun batchesInTrash(): List<String>

    @Query("SELECT DISTINCT treeUri FROM trash_items")
    suspend fun trees(): List<String>

    @Query("SELECT COUNT(*) AS items, COALESCE(SUM(sizeBytes), 0) AS bytes FROM trash_items WHERE state = 'IN_TRASH'")
    fun observeTotals(): Flow<TrashTotals>

    @Query("UPDATE trash_items SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: String)

    /** Rows of batches that are over (nothing left in the trash), once their history entry no longer needs them. */
    @Query("DELETE FROM trash_items WHERE batchId IN (:batchIds) AND state != 'IN_TRASH'")
    suspend fun forgetFinished(batchIds: List<String>)
}
