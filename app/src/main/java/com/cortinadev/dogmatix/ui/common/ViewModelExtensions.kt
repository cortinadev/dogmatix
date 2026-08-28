package com.cortinadev.dogmatix.ui.common

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.util.ErrorHandler
import com.cortinadev.dogmatix.util.ToastUtil
import kotlinx.coroutines.launch

fun <T : ViewModel> T.executeWithToast(
    context: Context,
    tag: String,
    onError: (String) -> Unit = { ToastUtil.showError(context, it) },
    block: suspend () -> Unit
) {
    viewModelScope.launch(ErrorHandler.createExceptionHandler(tag)) {
        try {
            block()
        } catch (e: Exception) {
            val errorMsg = context.getString(R.string.error_operation_failed, e.message ?: context.getString(R.string.error_unknown))
            ErrorHandler.logError(tag, errorMsg, e)
            onError(errorMsg)
        }
    }
}
