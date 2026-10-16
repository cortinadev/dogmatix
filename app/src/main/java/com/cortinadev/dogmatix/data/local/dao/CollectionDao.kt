package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import com.cortinadev.dogmatix.data.local.entity.CollectionEntity
import com.cortinadev.dogmatix.data.local.entity.CollectionGameEntity
import kotlinx.coroutines.flow.Flow

/** A collection with how many games it holds (for a smart one: at its last evaluation). */
data class CollectionWithCount(
    val id: Long,
    val name: String,
    val rules: String?,
    val createdAt: Long,
    val games: Int
)

/** A library row matching a smart collection's rules (see CollectionRuleSql.matchPage). */
data class CollectionMatchRow(val id: Long, val consoleId: String, val groupKey: String, val fileName: String)

@Dao
interface CollectionDao {

    @Query("""
        SELECT c.id, c.name, c.rules, c.createdAt,
               (SELECT COUNT(*) FROM collection_games g WHERE g.collectionId = c.id) AS games
        FROM collections c
        ORDER BY c.name COLLATE NOCASE ASC, c.id ASC
    """)
    fun observeAll(): Flow<List<CollectionWithCount>>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun get(id: Long): CollectionEntity?

    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<CollectionEntity>

    @Insert
    suspend fun insert(collection: CollectionEntity): Long

    @Update
    suspend fun update(collection: CollectionEntity)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteCollection(id: Long)

    @Query("DELETE FROM collection_games WHERE collectionId = :id")
    suspend fun clearGames(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGames(games: List<CollectionGameEntity>)

    @Query("DELETE FROM collection_games WHERE collectionId = :id AND consoleId = :consoleId AND groupKey = :groupKey")
    suspend fun removeGame(id: Long, consoleId: String, groupKey: String)

    @Query("SELECT * FROM collection_games WHERE collectionId = :id")
    suspend fun games(id: Long): List<CollectionGameEntity>

    /** Ids of the collections holding a game (the details card's "Collection" dialog). */
    @Query("SELECT collectionId FROM collection_games WHERE consoleId = :consoleId AND groupKey = :groupKey")
    fun observeCollectionsOf(consoleId: String, groupKey: String): Flow<List<Long>>

    @Transaction
    suspend fun replaceGames(id: Long, games: List<CollectionGameEntity>) {
        clearGames(id)
        games.chunked(500).forEach { insertGames(it) }
    }

    @Transaction
    suspend fun deleteWithGames(id: Long) {
        clearGames(id)
        deleteCollection(id)
    }

    @RawQuery
    suspend fun matchRows(query: SupportSQLiteQuery): List<CollectionMatchRow>
}
