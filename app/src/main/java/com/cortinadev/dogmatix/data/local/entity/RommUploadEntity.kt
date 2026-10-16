package com.cortinadev.dogmatix.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cortinadev.dogmatix.util.RommUploadResume

/**
 * An upload to RomM that has not finished yet, so it can carry on after a failure or a restart
 * from the last chunk the server acknowledged (see [RommUploadResume]). The row goes once the
 * upload completes or fails for good.
 */
@Entity(tableName = "romm_uploads")
data class RommUploadEntity(
    /** `consoleId|serverName`: one row per game going up. */
    @PrimaryKey val uploadKey: String,
    /** The download that started it (the discs of a set share one upload). */
    val downloadFileName: String,
    val consoleId: String,
    /** Base URL of the server holding the session. */
    val serverUrl: String,
    val platformId: Int,
    /** The file name on the server (`<game>.zip` for a game of several files). */
    val serverName: String,
    /** Folder of the files under the console's download folder: "" or `<game>.m3u`. */
    val folder: String,
    /** JSON list of [com.cortinadev.dogmatix.util.UploadSource], paths relative to the console folder. */
    val sources: String,
    val archive: Boolean,
    val totalSize: Long,
    val chunkSize: Int,
    val totalChunks: Int,
    /** RomM's session id; null until `/start` answered. */
    val uploadId: String?,
    /** Chunks acknowledged from the first: the index of the next to send. */
    val confirmedChunks: Int,
    /** When `/start` answered: RomM forgets the session 24 h later. */
    val sessionStartedAt: Long,
    val updatedAt: Long
) {
    fun toSession(signature: String) = RommUploadResume.Session(
        serverUrl = serverUrl,
        platformId = platformId,
        serverName = serverName,
        totalSize = totalSize,
        chunkSize = chunkSize,
        totalChunks = totalChunks,
        signature = signature,
        uploadId = uploadId,
        confirmedChunks = confirmedChunks,
        startedAt = sessionStartedAt
    )
}
