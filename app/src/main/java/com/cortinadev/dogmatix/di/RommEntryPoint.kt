package com.cortinadev.dogmatix.di

import com.cortinadev.dogmatix.data.service.RommCatalogService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Lets list rows and images (composables, no ViewModel) reach the RomM catalogue. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RommEntryPoint {
    fun rommCatalogService(): RommCatalogService
}
