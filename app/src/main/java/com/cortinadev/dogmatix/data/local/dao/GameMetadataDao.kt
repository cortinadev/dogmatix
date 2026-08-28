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
}
