package com.cortinadev.dogmatix.ui.screens.download

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Android 13+ hides every notification until the user allows them. Asked once, the first time a
 * download runs (not as an onboarding step); whatever the answer, it is never asked again.
 */
@Composable
fun NotificationPermissionPrompt(viewModel: DownloadViewModel) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val active by viewModel.activeCount.collectAsState()
    val asked by viewModel.notificationPermissionAsked.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(active > 0, asked) {
        if (active == 0 || asked != false) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        // Marked first: a rotation while the system dialog is up must not ask a second time.
        viewModel.onNotificationPermissionAsked()
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
