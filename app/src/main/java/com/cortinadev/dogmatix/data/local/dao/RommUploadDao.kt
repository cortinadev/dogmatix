package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cortinadev.dogmatix.data.local.entity.RommUploadEntity

@Dao
interface RommUploadDao {

    @Query("SELECT * FROM romm_uploads")
    suspend fun getAll(): List<RommUploadEntity>

    @Query("SELECT * FROM romm_uploads WHERE uploadKey = :key")
    suspend fun get(key: String): RommUploadEntity?

    @Query("SELECT * FROM romm_uploads WHERE downloadFileName = :fileName LIMIT 1")
    suspend fun forDownload(fileName: String): RommUploadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: RommUploadEntity)

    @Query("UPDATE romm_uploads SET confirmedChunks = :chunks, updatedAt = :at WHERE uploadKey = :key")
    suspend fun confirm(key: String, chunks: Int, at: Long)

    @Query("DELETE FROM romm_uploads WHERE uploadKey = :key")
    suspend fun delete(key: String)
}
