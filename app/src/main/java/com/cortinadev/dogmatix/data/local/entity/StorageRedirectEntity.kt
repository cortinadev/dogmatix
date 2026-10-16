package com.cortinadev.dogmatix.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A download that did not fit on its console's volume and went to the same console folder on
 * the other one (smart storage). Kept so later look-ups of the file (resume, delete, upload)
 * find it where it is.
 */
@Entity(tableName = "storage_redirects")
data class StorageRedirectEntity(
    @PrimaryKey val fileName: String,
    val consoleId: String,
    /** SAF tree URI of the root it went to. */
    val rootUri: String,
    /** Console folder below that root. */
    val subPath: String,
    val createdAt: Long = System.currentTimeMillis()
)
