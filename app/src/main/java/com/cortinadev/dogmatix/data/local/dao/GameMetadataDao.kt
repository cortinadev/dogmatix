package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cortinadev.dogmatix.data.local.entity.GameMetadataEntity

@Dao
interface GameMetadataDao {

    @Query("SELECT * FROM game_metadata WHERE lookupKey = :key")
    suspend fun get(key: String): GameMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: GameMetadataEntity)

    /** Titles of looked-up games with [genre] among their genres (stored `|`-separated). */
    @Query("SELECT title FROM game_metadata WHERE source != '' AND ('|' || genres || '|') LIKE '%|' || :genre || '|%' LIMIT :limit")
    suspend fun titlesWithGenre(genre: String, limit: Int): List<String>
}
