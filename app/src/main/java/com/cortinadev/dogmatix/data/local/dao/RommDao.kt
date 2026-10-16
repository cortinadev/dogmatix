package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.cortinadev.dogmatix.data.local.entity.RommFavouriteBaseEntity
import com.cortinadev.dogmatix.data.local.entity.RommGameEntity

/** What the RomM catalogue needs of a library row. */
data class RommLibraryFile(
    val consoleId: String,
    val fileName: String,
    val name: String,
    val groupKey: String,
    val downloadUrl: String
)

@Dao
interface RommDao {

    @Query("SELECT * FROM romm_games")
    suspend fun allGames(): List<RommGameEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGames(games: List<RommGameEntity>)

    @Query("DELETE FROM romm_games")
    suspend fun clearGames()

    @Transaction
    suspend fun replaceGames(games: List<RommGameEntity>) {
        clearGames()
        games.chunked(500).forEach { insertGames(it) }
    }

    @Query("UPDATE romm_games SET userStatus = :status WHERE romId = :romId")
    suspend fun setUserStatus(romId: Int, status: String)

    @Query("SELECT * FROM romm_favourite_base")
    suspend fun favouriteBase(): List<RommFavouriteBaseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavouriteBase(rows: List<RommFavouriteBaseEntity>)

    @Query("DELETE FROM romm_favourite_base")
    suspend fun clearFavouriteBase()

    @Transaction
    suspend fun replaceFavouriteBase(rows: List<RommFavouriteBaseEntity>) {
        clearFavouriteBase()
        if (rows.isNotEmpty()) insertFavouriteBase(rows)
    }

    /** Library rows of the consoles mapped to a RomM platform (what "upload what's missing" walks). */
    @Query("SELECT consoleId, fileName, name, groupKey, downloadUrl FROM downloadable_files WHERE consoleId IN (:consoleIds)")
    suspend fun libraryFiles(consoleIds: List<String>): List<RommLibraryFile>

    /** Library rows of some games of one console (≤ 500 keys per call: SQLite's variable limit). */
    @Query("SELECT consoleId, fileName, name, groupKey, downloadUrl FROM downloadable_files WHERE consoleId = :consoleId AND groupKey IN (:groupKeys)")
    suspend fun libraryFilesOfGames(consoleId: String, groupKeys: List<String>): List<RommLibraryFile>
}
