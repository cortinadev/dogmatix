package com.cortinadev.dogmatix.data.local.entity

import androidx.room.Entity

/**
 * A game the user starred. Keyed by console + file name (not by `downloadable_files.id`)
 * so favourites survive the wholesale re-index that every rescan performs.
 */
@Entity(tableName = "favourites", primaryKeys = ["consoleId", "fileName"])
data class FavouriteEntity(
    val consoleId: String,
    val fileName: String,
    val addedAt: Long = System.currentTimeMillis()
) {
    val key: String get() = key(consoleId, fileName)

    companion object {
        fun key(consoleId: String, fileName: String): String = "$consoleId|$fileName"
    }
}
