package com.cortinadev.dogmatix

import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.common.StorageStatusViewModel
import com.cortinadev.dogmatix.ui.components.FreeSpaceText
import com.cortinadev.dogmatix.ui.components.BottomTabs
import com.cortinadev.dogmatix.ui.components.GamepadLegend
import com.cortinadev.dogmatix.ui.components.NoGamepadHint
import com.cortinadev.dogmatix.ui.components.PortraitHeader
import com.cortinadev.dogmatix.ui.components.TopTabs
import com.cortinadev.dogmatix.ui.components.legendFor
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.ui.screens.contact.ContactScreen
import com.cortinadev.dogmatix.ui.screens.download.DownloadScreen
import com.cortinadev.dogmatix.ui.screens.download.DownloadViewModel
import com.cortinadev.dogmatix.ui.screens.home.HomeScreen
import com.cortinadev.dogmatix.ui.screens.onboarding.OnboardingScreen
import com.cortinadev.dogmatix.ui.screens.settings.SettingsScreen
import com.cortinadev.dogmatix.ui.screens.settings.SettingsViewModel
import com.cortinadev.dogmatix.ui.screens.sources.SourcesScreen
import com.cortinadev.dogmatix.ui.screens.sources.SourcesViewModel
import com.cortinadev.dogmatix.ui.theme.DogmatixTheme
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        Gamepad.startWatching(this)
        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val settings by settingsViewModel.uiState.collectAsState()
            val onboardingDone by settingsViewModel.onboardingDone.collectAsState()
            DogmatixTheme(themeMode = settings.themeMode, accent = settings.accent) {
                when (onboardingDone) {
                    null -> Unit                      // DataStore not read yet: avoid flashing the wrong screen
                    false -> OnboardingHost()
                    true -> DogmatixApp()
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        Gamepad.interceptKey(event) || super.dispatchKeyEvent(event)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        Gamepad.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Full-screen: status and navigation bars stay hidden; a swipe from the edge shows them briefly. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** First-run tour on the same gradient ground as the shell; import reuses the Sources ViewModel. */
@Composable
private fun OnboardingHost() {
    val sourcesViewModel: SourcesViewModel = hiltViewModel()
    val isRescanning by sourcesViewModel.isRescanning.collectAsState()
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalDogmatixTokens.current
    val view = LocalView.current
    SideEffect {
        (view.context as? ComponentActivity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !tokens.isDark
        }
    }
    CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(tokens.gradientTop, scheme.background, scheme.background)))
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            OnboardingScreen(
                onImportSources = sourcesViewModel::importSources,
                isRescanning = isRescanning
            )
        }
    }
}

@Composable
private fun DogmatixApp() {
    val context = LocalContext.current
    val sourcesViewModel: SourcesViewModel = hiltViewModel()
    val downloadViewModel: DownloadViewModel = hiltViewModel()
    val rescanErrorMessage by sourcesViewModel.rescanErrorMessage.collectAsState()
    val downloads by downloadViewModel.downloads.collectAsState()
    val gamepadConnected by Gamepad.connected.collectAsState()
    val legendOverride by Gamepad.legendOverride.collectAsState()
    val storageViewModel: StorageStatusViewModel = hiltViewModel()
    val freeBytes by storageViewModel.freeBytes.collectAsState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(Unit) {
        sourcesViewModel.initializeSources()
    }

    if (rescanErrorMessage != null) {
        AlertDialog(
            onDismissRequest = { sourcesViewModel.clearRescanError() },
            title = { Text("Scraping Error") },
            text = { Text(rescanErrorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { sourcesViewModel.clearRescanError() }) { Text("OK") }
            }
        )
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: NavRoutes.Home.route

    Gamepad.currentRoute = currentRoute
    // After a gamepad section switch the focus ring goes away: focus is parked on an invisible
    // sink (clearing it would make Compose re-focus the first tab). The next D-pad press puts
    // it back on the active tab (see Gamepad.interceptKey).
    val focusSink = remember { FocusRequester() }
    LaunchedEffect(currentRoute) {
        if (Gamepad.pointerHidden.value) {
            withFrameNanos { }
            runCatching { focusSink.requestFocus() }
        }
    }
    LaunchedEffect(Unit) {
        Gamepad.presses.collect { button ->
            val tabs = NavRoutes.tabs
            val route = navController.currentBackStackEntry?.destination?.route
            val index = tabs.indexOfFirst { it.route == route }.coerceAtLeast(0)
            when (button) {
                GamepadButton.PREV_TAB, GamepadButton.NEXT_TAB -> {
                    val delta = if (button == GamepadButton.PREV_TAB) -1 else 1
                    Gamepad.pointerHidden.value = true
                    runCatching { focusSink.requestFocus() }
                    navController.switchTo(tabs[((index + delta) % tabs.size + tabs.size) % tabs.size])
                }
                GamepadButton.FOCUS_TAB -> runCatching { Gamepad.sectionFocus.requestFocus() }
                else -> Unit
            }
        }
    }

    val scheme = MaterialTheme.colorScheme
    val tokens = LocalDogmatixTokens.current
    val gradientTop = tokens.gradientTop
    val view = LocalView.current
    SideEffect {
        (view.context as? ComponentActivity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !tokens.isDark
        }
    }
    CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(gradientTop, scheme.background, scheme.background)))
    ) {
        Box(
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusSink)
                .focusable()
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            if (isLandscape) {
                TopTabs(currentRoute = currentRoute, onSelect = navController::switchTo)
            } else {
                PortraitHeader(trailing = { FreeSpaceText(freeBytes) })
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                NavHost(
                    navController = navController,
                    startDestination = NavRoutes.Home.route,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(NavRoutes.Home.route) { HomeScreen(navController) }
                    composable(NavRoutes.Downloads.route) { DownloadScreen(navController) }
                    composable(NavRoutes.Sources.route) { SourcesScreen() }
                    composable(NavRoutes.Settings.route) { SettingsScreen(navController) }
                    composable(NavRoutes.Contact.route) { ContactScreen(navController) }
                }
            }

            if (gamepadConnected) {
                GamepadLegend(entries = legendOverride ?: legendFor(currentRoute), trailing = if (isLandscape) ({ FreeSpaceText(freeBytes) }) else null)
            } else if (isLandscape) {
                NoGamepadHint(trailing = { FreeSpaceText(freeBytes) })
            }

            if (!isLandscape) {
                BottomTabs(
                    currentRoute = currentRoute,
                    activeDownloads = downloads.count { it.status == DownloadStatus.DOWNLOADING },
                    onSelect = navController::switchTo
                )
            }
        }
    }
    }
}

private fun NavController.switchTo(route: NavRoutes) {
    navigate(route.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
