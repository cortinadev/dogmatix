package com.cortinadev.dogmatix.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Genre and release year of one library game, for the Genre / Decade filters and the
 * recommendations. Keyed by console + `groupKey` (every version of the game), which survives
 * the wholesale re-index of a rescan like favourites do. Filled from libretro-database per
 * console and completed with the details lookups (see `GameTraitsService`).
 */
@Entity(tableName = "game_traits", primaryKeys = ["consoleId", "groupKey"])
data class GameTraitEntity(
    val consoleId: String,
    val groupKey: String,
    /** [com.cortinadev.dogmatix.util.LibraryGenre] bits; 0 when unknown. */
    val genres: Int,
    /** Year of the first release; null when unknown. */
    val year: Int?,
    /** Where it came from: [SOURCE_LIBRETRO] or [SOURCE_DETAILS]. */
    val source: String
) {
    companion object {
        const val SOURCE_LIBRETRO = "libretro"
        const val SOURCE_DETAILS = "details"
    }
}

/**
 * When a console's traits were last worked out: [fetchedAt] is when its libretro DATs were
 * downloaded (0 when libretro has none for it) and [fileCount] the console's library rows then,
 * so a rescan that changes the console matches the games again.
 */
@Entity(tableName = "game_traits_sync")
data class GameTraitSyncEntity(
    @PrimaryKey val consoleId: String,
    val fetchedAt: Long,
    val fileCount: Int
)
