package com.cortinadev.dogmatix.util

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler

object ErrorHandler {
    private const val TAG = "MilouErrorHandler"

    fun logError(tag: String = TAG, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }

    fun createExceptionHandler(tag: String = TAG): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            logError(tag, "Uncaught exception in coroutine", throwable)
        }
    }
}
