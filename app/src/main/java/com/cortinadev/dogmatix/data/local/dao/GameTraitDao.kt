package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.cortinadev.dogmatix.data.local.entity.GameTraitEntity
import com.cortinadev.dogmatix.data.local.entity.GameTraitSyncEntity

@Dao
interface GameTraitDao {

    @Query("SELECT * FROM game_traits WHERE consoleId = :consoleId AND groupKey IN (:groupKeys)")
    suspend fun forGames(consoleId: String, groupKeys: List<String>): List<GameTraitEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(entry: GameTraitEntity): Long

    /** Fills what a game's row does not know yet (a libretro row without year, say). Returns the rows changed. */
    @Query("""
        UPDATE game_traits
        SET genres = CASE WHEN genres = 0 THEN :genres ELSE genres END, year = COALESCE(year, :year)
        WHERE consoleId = :consoleId AND groupKey = :groupKey
          AND ((genres = 0 AND :genres != 0) OR (year IS NULL AND :year IS NOT NULL))
    """)
    suspend fun fillMissing(consoleId: String, groupKey: String, genres: Int, year: Int?): Int

    @Query("DELETE FROM game_traits WHERE consoleId = :consoleId")
    suspend fun deleteConsole(consoleId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<GameTraitEntity>)

    /** A console's traits as just worked out, in one transaction (the filters never see half of them). */
    @Transaction
    suspend fun replaceConsole(consoleId: String, rows: List<GameTraitEntity>, sync: GameTraitSyncEntity) {
        deleteConsole(consoleId)
        rows.chunked(500).forEach { insertAll(it) }
        upsertSync(sync)
    }

    @Query("SELECT * FROM game_traits_sync WHERE consoleId = :consoleId")
    suspend fun sync(consoleId: String): GameTraitSyncEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSync(entry: GameTraitSyncEntity)

    /**
     * Details lookups already cached for one platform (`game_metadata` keys start with
     * `platform|`) that know a genre or a date. LIKE's `_` wildcard over-matches a little; the
     * caller checks the prefix.
     */
    @Query("SELECT lookupKey, title, genres, released FROM game_metadata WHERE source != '' AND (genres != '' OR released != '') AND lookupKey LIKE :prefix || '%'")
    suspend fun cachedDetails(prefix: String): List<CachedDetails>
}

data class CachedDetails(val lookupKey: String, val title: String, val genres: String, val released: String)
