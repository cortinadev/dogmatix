package com.cortinadev.dogmatix.data.search

import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.repository.CollectionsRepository
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.ui.screens.collection.CollectionTool
import com.cortinadev.dogmatix.ui.screens.collection.titleRes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Key of a Collection tool in a [SearchTarget.Screen] (`smart_storage`…); CollectionScreen reads it back. */
val CollectionTool.searchKey: String get() = name.lowercase()

/** Prefix of a user collection's key in a [SearchTarget.Screen] (`collection:<id>`). */
const val COLLECTION_SEARCH_PREFIX = "collection:"

/** The tools of the Collection tab, by name and a few synonyms per language. */
class CollectionToolsSearchProvider @Inject constructor(private val strings: SearchStrings) : SearchProvider {
    override val entries: Flow<List<SearchEntry>> = flow {
        emit(strings.resolve(CollectionTool.entries.map { tool ->
            StaticSearchEntry(
                id = "tool:${tool.searchKey}", group = SearchGroup.COLLECTION, title = tool.titleRes,
                keywords = tool.keywordsRes, target = SearchTarget.Screen(NavRoutes.Collection.route, tool.searchKey)
            )
        }))
    }

    private val CollectionTool.keywordsRes: Int
        get() = when (this) {
            CollectionTool.SUMMARY -> R.string.search_kw_tool_summary
            CollectionTool.HEALTH -> R.string.search_kw_tool_health
            CollectionTool.DUPLICATES -> R.string.search_kw_tool_duplicates
            CollectionTool.REPAIR -> R.string.search_kw_tool_repair
            CollectionTool.FREE_SPACE -> R.string.search_kw_tool_free_space
            CollectionTool.SMART_STORAGE -> R.string.search_kw_tool_smart_storage
            CollectionTool.LIBRARY_MOVE -> R.string.search_kw_tool_library_move
            CollectionTool.HISTORY -> R.string.search_kw_tool_history
            CollectionTool.COLLECTIONS -> R.string.search_kw_tool_collections
        }
}

/** The user's own collections by name; the result opens the collection in the Library. */
class CollectionsSearchProvider @Inject constructor(
    private val collections: CollectionsRepository,
    private val strings: SearchStrings
) : SearchProvider {
    override val entries: Flow<List<SearchEntry>> = collections.collections.map { list ->
        val res = strings.resources()
        list.map { collection ->
            SearchEntry(
                id = "$COLLECTION_SEARCH_PREFIX${collection.id}",
                group = SearchGroup.COLLECTION,
                title = collection.name,
                subtitle = res.getQuantityString(R.plurals.collections_games, collection.games, collection.games),
                target = SearchTarget.Screen(NavRoutes.Collection.route, "$COLLECTION_SEARCH_PREFIX${collection.id}")
            )
        }
    }
}
