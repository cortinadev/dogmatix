package com.cortinadev.dogmatix.data.repository

import android.content.Context
import com.cortinadev.dogmatix.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The RomM server's collections in Collection → Collections (read-only there; membership is
 * changed from a game's details card). A collection counts and opens the games the library has.
 */
class RommExternalCollections @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val romm: RommCollectionsRepository
) : ExternalCollectionSource {
    override val key: String = "romm"
    override val label: String get() = context.getString(R.string.collections_source_romm)

    override val collections: Flow<List<ExternalCollection>> = romm.collections.map { list ->
        list.map { collection -> ExternalCollection(key, collection.id.toString(), collection.name, collection.games.count { it.library != null }) }
    }

    override suspend fun games(id: String): List<Pair<String, String>> =
        romm.collections.value.firstOrNull { it.id.toString() == id }?.games
            ?.mapNotNull { game -> game.library?.let { it.consoleId to it.groupKey } }
            .orEmpty()
}
