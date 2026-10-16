package com.cortinadev.dogmatix.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The cover found for a library game ([url] empty = none was found), so each game is looked up
 * at most once per [com.cortinadev.dogmatix.util.CoverPolicy] period. Keyed by
 * `consoleId|fileName`, which survives re-indexing like the favourites do.
 */
@Entity(tableName = "covers")
data class CoverEntity(
    @PrimaryKey val lookupKey: String,
    val url: String,
    /** Where it came from ("libretro", "metadata"); empty for a miss. */
    val source: String,
    val fetchedAt: Long
)
