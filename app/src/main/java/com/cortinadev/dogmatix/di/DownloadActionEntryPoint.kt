package com.cortinadev.dogmatix.di

import com.cortinadev.dogmatix.data.repository.DownloadRepository
import com.cortinadev.dogmatix.data.service.DownloadNotifications
import com.cortinadev.dogmatix.data.service.DownloadQueueService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Lets the notification buttons ([com.cortinadev.dogmatix.data.service.DownloadActionReceiver]) reach the downloads. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DownloadActionEntryPoint {
    fun downloadRepository(): DownloadRepository
    fun downloadQueue(): DownloadQueueService
    fun downloadNotifications(): DownloadNotifications
}
