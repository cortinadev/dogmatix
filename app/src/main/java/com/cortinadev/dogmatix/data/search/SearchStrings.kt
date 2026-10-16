package com.cortinadev.dogmatix.data.search

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import com.cortinadev.dogmatix.util.SearchIndex
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * String resources for the search index in the language the app shows (Settings → Language).
 * Before Android 13 the per-app language only reaches activities, not the application context,
 * so the resources are rebuilt for it here.
 */
@Singleton
class SearchStrings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private var cachedTags: String? = null
    private var cached: Resources? = null

    @Synchronized
    fun resources(): Resources {
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (tags.isEmpty()) return context.resources
        cached?.takeIf { cachedTags == tags }?.let { return it }
        val config = Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tags)) }
        return context.createConfigurationContext(config).resources.also {
            cached = it
            cachedTags = tags
        }
    }

    fun get(@StringRes id: Int): String = resources().getString(id)

    /** [StaticSearchEntry]s as searchable entries: title, hint (shown) and keywords (searched). */
    fun resolve(entries: List<StaticSearchEntry>): List<SearchEntry> {
        val res = resources()
        return entries.map { entry ->
            SearchEntry(
                id = entry.id,
                group = entry.group,
                title = res.getString(entry.title),
                subtitle = entry.hint?.let(res::getString),
                keywords = entry.keywords?.let { SearchIndex.keywordList(res.getString(it)) }.orEmpty(),
                target = entry.target
            )
        }
    }
}
