package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cortinadev.dogmatix.data.local.entity.FavouriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavouriteDao {

    @Query("SELECT * FROM favourites")
    fun observeAll(): Flow<List<FavouriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE consoleId = :consoleId AND fileName = :fileName")
    suspend fun delete(consoleId: String, fileName: String)
}
