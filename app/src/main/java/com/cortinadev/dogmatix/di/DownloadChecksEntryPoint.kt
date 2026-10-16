package com.cortinadev.dogmatix.di

import com.cortinadev.dogmatix.data.service.DownloadVerifier
import com.cortinadev.dogmatix.data.service.SourceStatsService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Lets list rows (a download's verdict, a source's track record) reach the download engine's
 * singletons without threading them through each screen's ViewModel.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DownloadChecksEntryPoint {
    fun downloadVerifier(): DownloadVerifier
    fun sourceStats(): SourceStatsService
}
