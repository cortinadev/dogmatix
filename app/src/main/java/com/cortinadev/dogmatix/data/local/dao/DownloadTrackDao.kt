package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cortinadev.dogmatix.data.local.entity.DownloadCheckEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadResumeEntity
import com.cortinadev.dogmatix.data.local.entity.SourceStatEntity

/** Source track record, resume validators and download checks (the download engine's own tables). */
@Dao
interface DownloadTrackDao {

    @Query("SELECT * FROM source_stats")
    suspend fun allSourceStats(): List<SourceStatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSourceStat(stat: SourceStatEntity)

    @Query("SELECT * FROM download_resume WHERE fileName = :fileName LIMIT 1")
    suspend fun resumeFor(fileName: String): DownloadResumeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertResume(resume: DownloadResumeEntity)

    @Query("DELETE FROM download_resume WHERE fileName = :fileName")
    suspend fun deleteResume(fileName: String)

    @Query("DELETE FROM download_resume WHERE updatedAt < :before")
    suspend fun deleteResumesBefore(before: Long)

    @Query("SELECT * FROM download_checks")
    suspend fun allChecks(): List<DownloadCheckEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheck(check: DownloadCheckEntity)

    @Query("DELETE FROM download_checks WHERE fileName = :fileName")
    suspend fun deleteCheck(fileName: String)

    /** Checks of downloads no longer in the Downloads list. */
    @Query("DELETE FROM download_checks WHERE fileName NOT IN (SELECT fileName FROM download_history)")
    suspend fun deleteOrphanChecks()
}
