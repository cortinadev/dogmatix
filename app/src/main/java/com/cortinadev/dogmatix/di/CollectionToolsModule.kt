package com.cortinadev.dogmatix.di

import com.cortinadev.dogmatix.data.local.DogmatixDatabase
import com.cortinadev.dogmatix.data.local.dao.ActionHistoryDao
import com.cortinadev.dogmatix.data.local.dao.CollectionDao
import com.cortinadev.dogmatix.data.local.dao.StorageRedirectDao
import com.cortinadev.dogmatix.data.local.dao.TrashDao
import com.cortinadev.dogmatix.data.repository.CollectionsRepository
import com.cortinadev.dogmatix.data.repository.ExternalCollectionSource
import com.cortinadev.dogmatix.data.repository.RommExternalCollections
import com.cortinadev.dogmatix.data.service.DecadeRuleExtension
import com.cortinadev.dogmatix.data.service.GenreRuleExtension
import com.cortinadev.dogmatix.data.service.LibraryMoveService
import com.cortinadev.dogmatix.data.service.SourcesImportUndo
import com.cortinadev.dogmatix.data.service.TrashService
import com.cortinadev.dogmatix.data.service.UndoHandler
import com.cortinadev.dogmatix.util.CollectionRuleExtension
import com.cortinadev.dogmatix.util.UndoKinds
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import dagger.multibindings.StringKey

/**
 * History with undo, trash, smart storage, library moves and collections: their DAOs, the undo
 * handler of each kind of history entry, and the extension points other features bind into
 * (`@IntoSet` an [ExternalCollectionSource] to list another place's collections, or a
 * [CollectionRuleExtension] to add a rule field to smart collections).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CollectionToolsModule {

    @Binds @IntoMap @StringKey(UndoKinds.TRASH)
    abstract fun trashUndo(service: TrashService): UndoHandler

    @Binds @IntoMap @StringKey(UndoKinds.MOVE)
    abstract fun moveUndo(service: LibraryMoveService): UndoHandler

    @Binds @IntoMap @StringKey(UndoKinds.SOURCES)
    abstract fun sourcesUndo(service: SourcesImportUndo): UndoHandler

    @Binds @IntoMap @StringKey(UndoKinds.COLLECTION)
    abstract fun collectionUndo(repository: CollectionsRepository): UndoHandler

    @Multibinds
    abstract fun externalCollectionSources(): Set<ExternalCollectionSource>

    @Multibinds
    abstract fun collectionRuleExtensions(): Set<CollectionRuleExtension>

    // biblioteca: genre and decade of smart collections, over game_traits.
    @Binds @IntoSet
    abstract fun genreRule(extension: GenreRuleExtension): CollectionRuleExtension

    @Binds @IntoSet
    abstract fun decadeRule(extension: DecadeRuleExtension): CollectionRuleExtension

    // romm-catalogo: the RomM server's collections, listed next to the user's own.
    @Binds @IntoSet
    abstract fun rommCollections(source: RommExternalCollections): ExternalCollectionSource

    companion object {
        @Provides
        fun provideActionHistoryDao(db: DogmatixDatabase): ActionHistoryDao = db.actionHistoryDao()

        @Provides
        fun provideTrashDao(db: DogmatixDatabase): TrashDao = db.trashDao()

        @Provides
        fun provideCollectionDao(db: DogmatixDatabase): CollectionDao = db.collectionDao()

        @Provides
        fun provideStorageRedirectDao(db: DogmatixDatabase): StorageRedirectDao = db.storageRedirectDao()
    }
}
