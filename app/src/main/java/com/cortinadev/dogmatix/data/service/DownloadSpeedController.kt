package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.FileSizeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadSpeedController @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    
    fun createSpeedLimiter(onSpeedLimitChanged: (Float) -> Unit): Job {
        return CoroutineScope(Dispatchers.IO).launch {
            settingsRepository.limitSpeed.collect { newLimit ->
                onSpeedLimitChanged(newLimit)
            }
        }
    }
    
    suspend fun applySpeedThrottling(
        currentSpeed: Float,
        speedLimit: Float,
        bytesSinceLastCheck: Long,
        timeSinceLastCheck: Float
    ) {
        if (speedLimit == Float.POSITIVE_INFINITY || speedLimit <= 0) return
        
        if (currentSpeed > speedLimit) {
            val targetTime = FileSizeUtils.bytesToMB(bytesSinceLastCheck) / speedLimit // seconds
            val actualTime = timeSinceLastCheck
            val delayNeeded = (targetTime - actualTime) * 1000f // convert to milliseconds
            
            if (delayNeeded > 0) {
                delay(delayNeeded.toLong())
            }
        }
    }
    
    fun calculateSpeed(bytesDownloaded: Long, timeElapsed: Float): Float {
        return if (timeElapsed > 0) {
            FileSizeUtils.bytesToMB(bytesDownloaded) / timeElapsed
        } else {
            0f
        }
    }
}
