package com.cortinadev.dogmatix.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Cached result of an online metadata lookup. An empty [source] records a miss so it is not retried at once. */
@Entity(tableName = "game_metadata")
data class GameMetadataEntity(
    @PrimaryKey val lookupKey: String,
    val title: String = "",
    val description: String = "",
    val genres: String = "",
    val released: String = "",
    val developer: String = "",
    val imageUrl: String = "",
    val source: String = "",
    val fetchedAt: Long
)
