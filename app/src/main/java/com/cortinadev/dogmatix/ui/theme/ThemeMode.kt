package com.cortinadev.dogmatix.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.cortinadev.dogmatix.R

enum class ThemeMode(val labelRes: Int) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark);

    companion object {
        fun fromName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** Accent presets offered in Settings. Stored as the hex string. */
object AccentPresets {
    val all: List<Color> = listOf(
        Color(0xFFFF7F00), // orange (default)
        Color(0xFF7BE03A), // green
        Color(0xFF4E99FF), // blue
        Color(0xFFC390E8), // lilac
        Color(0xFFFF4D8D)  // pink
    )
    val default: Color = all.first()

    fun toHex(color: Color): String = String.format("#%06X", 0xFFFFFF and color.toArgb())

    fun fromHex(hex: String?): Color {
        if (hex.isNullOrBlank()) return default
        return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(default)
    }

}
