package com.cortinadev.dogmatix.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A RomM id both sides agreed was a favourite at the last favourites sync: the base that tells
 * a star added here from a favourite removed on the server. Only valid for [collectionId] on
 * the server at [server] (its base URL): ids mean nothing on another one.
 */
@Entity(tableName = "romm_favourite_base")
data class RommFavouriteBaseEntity(
    @PrimaryKey val romId: Int,
    val collectionId: Int,
    val server: String
)
