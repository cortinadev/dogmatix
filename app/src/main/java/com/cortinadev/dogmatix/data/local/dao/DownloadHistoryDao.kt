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

    @Query("SELECT * FROM download_history WHERE fileName = :fileName LIMIT 1")
    suspend fun getByFileName(fileName: String): DownloadHistoryEntity?

    @Query("UPDATE download_history SET debridProvider = :provider, debridTorrentId = :torrentId, debridFileId = :fileId WHERE fileName = :fileName")
    suspend fun setDebrid(fileName: String, provider: String?, torrentId: String?, fileId: Int?)

    @Query("UPDATE download_history SET failureReason = :reason WHERE fileName = :fileName")
    suspend fun setFailure(fileName: String, reason: String?)

    @Query("DELETE FROM download_history WHERE fileName = :fileName")
    suspend fun delete(fileName: String)

    // dl-cola
    @Query("UPDATE download_history SET queueOrder = :order, queueHeld = :held WHERE fileName = :fileName")
    suspend fun setQueuePlace(fileName: String, order: Long, held: Boolean)
}
