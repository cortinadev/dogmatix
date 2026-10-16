package com.cortinadev.dogmatix.di

import com.cortinadev.dogmatix.data.service.CoverRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Lets the cover thumbnails (composables inside lists and dialogs) reach the [CoverRepository]. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CoverEntryPoint {
    fun coverRepository(): CoverRepository
}
