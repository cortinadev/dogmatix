package com.cortinadev.dogmatix.di

import com.cortinadev.dogmatix.data.search.CollectionToolsSearchProvider
import com.cortinadev.dogmatix.data.search.CollectionsSearchProvider
import com.cortinadev.dogmatix.data.search.DownloadsSearchProvider
import com.cortinadev.dogmatix.data.search.SearchProvider
import com.cortinadev.dogmatix.data.search.SettingsSearchProvider
import com.cortinadev.dogmatix.data.search.SourcesSearchProvider
import com.cortinadev.dogmatix.data.state.PendingNavigation
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * What the library search box looks into besides games. Another section joins by binding its own
 * [SearchProvider] the same way (`@Binds @IntoSet`, in its own module).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SearchModule {
    @Binds @IntoSet
    abstract fun downloads(provider: DownloadsSearchProvider): SearchProvider

    @Binds @IntoSet
    abstract fun sources(provider: SourcesSearchProvider): SearchProvider

    @Binds @IntoSet
    abstract fun settings(provider: SettingsSearchProvider): SearchProvider

    @Binds @IntoSet
    abstract fun collectionTools(provider: CollectionToolsSearchProvider): SearchProvider

    @Binds @IntoSet
    abstract fun collections(provider: CollectionsSearchProvider): SearchProvider
}

/** Lets the shell and the destination screens (composables) reach [PendingNavigation]. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NavigationEntryPoint {
    fun pendingNavigation(): PendingNavigation
}
