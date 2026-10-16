package com.cortinadev.dogmatix.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.cortinadev.dogmatix.data.search.SearchTarget
import com.cortinadev.dogmatix.data.state.PendingNavigation
import com.cortinadev.dogmatix.di.NavigationEntryPoint
import dagger.hilt.android.EntryPointAccessors

/** The section a search result lives in. */
val SearchTarget.route: String
    get() = when (this) {
        is SearchTarget.Download -> NavRoutes.Downloads.route
        is SearchTarget.Source -> NavRoutes.Sources.route
        is SearchTarget.Setting -> NavRoutes.Settings.route
        is SearchTarget.Screen -> route
    }

@Composable
fun rememberPendingNavigation(): PendingNavigation {
    val app = LocalContext.current.applicationContext
    return remember(app) { EntryPointAccessors.fromApplication(app, NavigationEntryPoint::class.java).pendingNavigation() }
}

/**
 * Runs [onTarget] once for each search result aimed at this screen: a [T] that [accept] takes
 * (e.g. its own route for [SearchTarget.Screen]). The target is consumed before [onTarget] runs,
 * which typically waits for its data, scrolls to the element and requests focus on it.
 */
@Composable
fun <T : SearchTarget> SearchTargetEffect(type: Class<T>, accept: (T) -> Boolean = { true }, onTarget: suspend (T) -> Unit) {
    val pending = rememberPendingNavigation()
    val latestAccept by rememberUpdatedState(accept)
    val latestOnTarget by rememberUpdatedState(onTarget)
    LaunchedEffect(pending) {
        pending.target.collect { target ->
            if (target == null || !type.isInstance(target)) return@collect
            val typed: T = type.cast(target) ?: return@collect
            if (latestAccept(typed) && pending.consume(target)) latestOnTarget(typed)
        }
    }
}

@Composable
inline fun <reified T : SearchTarget> SearchTargetEffect(noinline accept: (T) -> Boolean = { true }, noinline onTarget: suspend (T) -> Unit) =
    SearchTargetEffect(T::class.java, accept, onTarget)
