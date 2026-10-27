package com.cortinadev.dogmatix.di

import android.content.Context
import androidx.room.Room
import com.cortinadev.dogmatix.data.local.DogmatixDatabase
import com.cortinadev.dogmatix.data.local.dao.ConsoleDao
import com.cortinadev.dogmatix.data.local.dao.CoverDao
import com.cortinadev.dogmatix.data.local.dao.DownloadHistoryDao
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.dao.DownloadTrackDao
import com.cortinadev.dogmatix.data.local.dao.FavouriteDao
import com.cortinadev.dogmatix.data.local.dao.GameMetadataDao
import com.cortinadev.dogmatix.data.local.dao.GameTraitDao
import com.cortinadev.dogmatix.data.local.dao.ManufacturerDao
import com.cortinadev.dogmatix.data.local.dao.SourceScanDao
import com.cortinadev.dogmatix.data.local.dao.RommUploadDao
import com.cortinadev.dogmatix.data.local.dao.RommDao
import com.cortinadev.dogmatix.data.local.SettingsDataStore
import com.cortinadev.dogmatix.data.repository.DownloadRepository
import com.cortinadev.dogmatix.data.repository.DownloadRepositoryImpl
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepositoryImpl
import com.cortinadev.dogmatix.data.service.ArchiveExtractorService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DogmatixDatabase =
        Room.databaseBuilder(context, DogmatixDatabase::class.java, "dogmatix_db")
            .addMigrations(*DogmatixDatabase.ALL_MIGRATIONS)
            .build()

    @Provides
    fun provideFavouriteDao(db: DogmatixDatabase): FavouriteDao = db.favouriteDao()

    @Provides
    fun provideConsoleDao(db: DogmatixDatabase): ConsoleDao = db.consoleDao()

    @Provides
    fun provideDownloadableFileDao(db: DogmatixDatabase): DownloadableFileDao = db.downloadableFileDao()

    @Provides
    fun provideManufacturerDao(db: DogmatixDatabase): ManufacturerDao = db.manufacturerDao()

    @Provides
    fun provideDownloadHistoryDao(db: DogmatixDatabase): DownloadHistoryDao = db.downloadHistoryDao()

    @Provides
    fun provideGameMetadataDao(db: DogmatixDatabase): GameMetadataDao = db.gameMetadataDao()

    @Provides
    fun provideCoverDao(db: DogmatixDatabase): CoverDao = db.coverDao()

    // escaneo
    @Provides
    fun provideSourceScanDao(db: DogmatixDatabase): SourceScanDao = db.sourceScanDao()

    // dl-motor
    @Provides
    fun provideDownloadTrackDao(db: DogmatixDatabase): DownloadTrackDao = db.downloadTrackDao()

    // romm-transporte
    @Provides
    fun provideRommUploadDao(db: DogmatixDatabase): RommUploadDao = db.rommUploadDao()

    // biblioteca
    @Provides
    fun provideGameTraitDao(db: DogmatixDatabase): GameTraitDao = db.gameTraitDao()

    // romm-catalogo
    @Provides
    fun provideRommDao(db: DogmatixDatabase): RommDao = db.rommDao()

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): SettingsDataStore = SettingsDataStore(context)

    @Provides
    @Singleton
    fun provideSettingsRepository(settingsRepositoryImpl: SettingsRepositoryImpl): SettingsRepository = settingsRepositoryImpl

    @Provides
    @Singleton
    fun provideDownloadRepository(downloadRepositoryImpl: DownloadRepositoryImpl): DownloadRepository = downloadRepositoryImpl

    @Provides
    @Singleton
    fun provideArchiveExtractorService(): ArchiveExtractorService = ArchiveExtractorService()
}
