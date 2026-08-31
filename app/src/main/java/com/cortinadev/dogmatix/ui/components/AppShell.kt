package com.cortinadev.dogmatix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import com.cortinadev.dogmatix.ui.common.Gamepad
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cortinadev.dogmatix.BuildConfig
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.navigation.NavRoutes

/** App title with the version tucked under it: tiny, faint, right-aligned to the wordmark. */
@Composable
private fun Wordmark(modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        Text(stringResource(R.string.topbar_title), style = MaterialTheme.typography.titleLarge)
        Text(
            BuildConfig.VERSION_NAME,
            fontSize = 9.sp,
            lineHeight = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

/** Landscape header: title, numbered section tabs (ZL / ZR), rescan status. */
@Composable
fun TopTabs(currentRoute: String, onSelect: (NavRoutes) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(start = 20.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Wordmark(modifier = Modifier.padding(end = 20.dp))
        NavRoutes.tabs.forEachIndexed { index, route ->
            val selected = route.route == currentRoute
            val source = rememberFocusSource()
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .focusRequester(Gamepad.tabFocus.getValue(route.route))
                    .clip(RoundedCornerShape(6.dp))
                    .focusRing(source, 6.dp)
                    .clickable(interactionSource = source, indication = null) { onSelect(route) }
                    .drawBehind {
                        if (selected) {
                            drawRect(
                                color = scheme.primary,
                                topLeft = Offset(0f, size.height - 2.dp.toPx()),
                                size = Size(size.width, 2.dp.toPx())
                            )
                        }
                    }
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "0${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) scheme.onSurface else scheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(route.labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) scheme.onSurface else scheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        RescanIndicator(modifier = Modifier.widthIn(max = 260.dp))
    }
    HorizontalDivider(color = scheme.outlineVariant, thickness = 1.dp)
}

/** Portrait header: title plus rescan status. */
@Composable
fun PortraitHeader(trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Wordmark()
        Spacer(Modifier.weight(1f))
        RescanIndicator(modifier = Modifier.widthIn(max = 200.dp))
        trailing?.let { Spacer(Modifier.padding(start = 12.dp)); it() }
    }
}

/** Portrait bottom bar with the four sections. */
@Composable
fun BottomTabs(currentRoute: String, activeDownloads: Int, onSelect: (NavRoutes) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column {
        HorizontalDivider(color = scheme.outlineVariant, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(scheme.background)
        ) {
            NavRoutes.tabs.forEach { route ->
                val selected = route.route == currentRoute
                val source = rememberFocusSource()
                val tint = if (selected) scheme.primary else scheme.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .focusRequester(Gamepad.tabFocus.getValue(route.route))
                        .clip(RoundedCornerShape(8.dp))
                        .focusRing(source)
                        .clickable(interactionSource = source, indication = null) { onSelect(route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box {
                        Icon(painterResource(route.icon), contentDescription = stringResource(route.labelRes), tint = tint, modifier = Modifier.size(22.dp))
                        if (route == NavRoutes.Downloads && activeDownloads > 0) {
                            Text(
                                activeDownloads.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onPrimary,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(start = 14.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(scheme.primary)
                                    .padding(horizontal = 5.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(route.labelRes), style = MaterialTheme.typography.labelSmall, color = tint)
                }
            }
        }
    }
}

@Composable
fun legendFor(route: String): List<LegendEntry> {
    val section = LegendEntry("ZL · ZR", stringResource(R.string.pad_section))
    val back = LegendEntry("B", stringResource(R.string.pad_back))
    return when (route) {
        NavRoutes.Home.route -> listOf(
            LegendEntry("A", stringResource(R.string.pad_download)), back,
            LegendEntry("X", stringResource(R.string.pad_filters)),
            LegendEntry("Y", stringResource(R.string.pad_search)), section
        )
        NavRoutes.Downloads.route -> listOf(
            LegendEntry("A", stringResource(R.string.pad_retry)),
            LegendEntry("X", stringResource(R.string.pad_delete)), back, section
        )
        NavRoutes.Sources.route -> listOf(LegendEntry("A", stringResource(R.string.pad_open)), back, section)
        NavRoutes.Settings.route, NavRoutes.Romm.route -> listOf(
            LegendEntry("A", stringResource(R.string.pad_change)),
            LegendEntry("◀ ▶", stringResource(R.string.pad_adjust)), back, section
        )
        else -> listOf(back)
    }
}

@Composable
fun NoGamepadHint(trailing: @Composable (() -> Unit)? = null) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.pad_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}
