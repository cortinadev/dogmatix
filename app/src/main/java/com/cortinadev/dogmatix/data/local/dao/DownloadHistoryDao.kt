package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cortinadev.dogmatix.data.local.entity.DownloadHistoryEntity

@Dao
interface DownloadHistoryDao {

    @Query("SELECT * FROM download_history ORDER BY startedAt ASC")
    suspend fun getAll(): List<DownloadHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DownloadHistoryEntity)

    @Query("UPDATE download_history SET status = :status, finishedAt = :finishedAt WHERE fileName = :fileName")
    suspend fun updateStatus(fileName: String, status: String, finishedAt: Long?)

    @Query("UPDATE download_history SET status = :status, startedAt = :startedAt, finishedAt = NULL WHERE fileName = :fileName")
    suspend fun markRestarted(fileName: String, status: String, startedAt: Long)

    @Query("DELETE FROM download_history WHERE fileName = :fileName")
    suspend fun delete(fileName: String)
}
