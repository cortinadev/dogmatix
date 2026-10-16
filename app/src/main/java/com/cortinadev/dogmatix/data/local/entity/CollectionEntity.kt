package com.cortinadev.dogmatix.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A collection of the user's: manual ([rules] null, its games picked from the details card) or
 * smart ([rules] a [com.cortinadev.dogmatix.util.CollectionRuleCodec] document, its games worked
 * out from the library).
 */
@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val rules: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isSmart: Boolean get() = rules != null
}

/**
 * A game in a collection, keyed by console + version group (like favourites, not by row id) so
 * it survives the wholesale re-index of a rescan. For a smart collection these rows are the
 * last evaluation of its rules, refreshed when it is opened or edited.
 */
@Entity(
    tableName = "collection_games",
    primaryKeys = ["collectionId", "consoleId", "groupKey"],
    indices = [Index(value = ["consoleId", "groupKey"])]
)
data class CollectionGameEntity(
    val collectionId: Long,
    val consoleId: String,
    val groupKey: String,
    val addedAt: Long = System.currentTimeMillis()
)
