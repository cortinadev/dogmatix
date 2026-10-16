package com.cortinadev.dogmatix.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.util.ConsoleFamily
import com.cortinadev.dogmatix.util.TagColorKind

/** Background and text colour of one chip. */
@Immutable
data class ChipColors(val background: Color, val content: Color)

/**
 * Colours of the library chips: the console chip (and the cover placeholder) in its console's
 * colour — picked in Sources, else the family default — and each tag group in the colour picked
 * in Settings. Colours are mixed into the theme's surface, so they stay subtle and readable in
 * light, dark and true black.
 */
@Stable
class TagPalette(
    private val consoleColors: Map<String, Int>,
    private val kindColors: Map<TagColorKind, Int>,
    private val ground: Color,
    private val dark: Boolean,
    /** Extension, video standard and unrecognised tags. */
    val neutral: ChipColors
) {
    fun consoleColor(consoleId: String): Color = Color(consoleColors[consoleId] ?: ConsoleFamily.of(consoleId).defaultArgb)

    fun kindColor(kind: TagColorKind): Color = Color(kindColors[kind] ?: kind.defaultArgb)

    fun console(consoleId: String): ChipColors = tint(consoleColor(consoleId), strong = true)

    fun tag(tag: String): ChipColors = TagColorKind.of(tag)?.let { tint(kindColor(it), strong = false) } ?: neutral

    /** [strong]: the console chip, a little more saturated than the tags next to it. */
    fun tint(color: Color, strong: Boolean): ChipColors = ChipColors(
        background = lerp(ground, color, if (strong) (if (dark) 0.30f else 0.24f) else (if (dark) 0.16f else 0.14f)),
        content = if (dark) lerp(color, Color.White, if (strong) 0.30f else 0.40f)
        else lerp(color, Color.Black, if (strong) 0.45f else 0.50f)
    )
}

/** Provided by `MainActivity` with the colours from Room and DataStore; null falls back to the defaults. */
val LocalTagPalette = compositionLocalOf<TagPalette?> { null }

@Composable
fun rememberTagPalette(consoleColors: Map<String, Int>, kindColors: Map<TagColorKind, Int>): TagPalette {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalDogmatixTokens.current.isDark
    val ground = scheme.surfaceContainerHigh
    val neutralText = scheme.secondary
    return remember(consoleColors, kindColors, ground, neutralText, dark) {
        TagPalette(consoleColors, kindColors, ground, dark, ChipColors(ground, neutralText))
    }
}

/** The palette in effect: the provided one, or the built-in defaults (previews, onboarding). */
@Composable
fun tagPalette(): TagPalette = LocalTagPalette.current ?: rememberTagPalette(emptyMap(), emptyMap())
