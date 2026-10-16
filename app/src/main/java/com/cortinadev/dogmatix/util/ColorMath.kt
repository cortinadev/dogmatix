package com.cortinadev.dogmatix.util

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Colours as opaque `0xFFRRGGBB` ints and `#RRGGBB` strings (how DataStore, Room and the
 * sources JSON keep them), plus HSV for the colour picker. Pure JVM for the tests.
 */
object ColorMath {

    private const val OPAQUE = 0xFF000000.toInt()
    private val HEX = Regex("^[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")

    /** `#RRGGBB` (also without `#`, or `#AARRGGBB` with the alpha dropped) → opaque colour; null when it is not one. */
    fun parseHex(text: String?): Int? {
        val hex = text?.trim()?.removePrefix("#") ?: return null
        if (!HEX.matches(hex)) return null
        return (hex.takeLast(6).toLong(16).toInt()) or OPAQUE
    }

    fun toHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    /** [hue] 0–360°, [saturation] and [value] 0–1. */
    data class Hsv(val hue: Float, val saturation: Float, val value: Float)

    fun toHsv(argb: Int): Hsv {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val delta = max - minOf(r, g, b)
        val hue = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return Hsv(if (hue < 0f) hue + 360f else hue, if (max == 0f) 0f else delta / max, max)
    }

    fun fromHsv(hsv: Hsv): Int {
        val hue = ((hsv.hue % 360f) + 360f) % 360f
        val s = hsv.saturation.coerceIn(0f, 1f)
        val v = hsv.value.coerceIn(0f, 1f)
        val chroma = v * s
        val x = chroma * (1f - abs((hue / 60f) % 2f - 1f))
        val (r, g, b) = when ((hue / 60f).toInt()) {
            0 -> Triple(chroma, x, 0f)
            1 -> Triple(x, chroma, 0f)
            2 -> Triple(0f, chroma, x)
            3 -> Triple(0f, x, chroma)
            4 -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        val m = v - chroma
        fun channel(c: Float) = ((c + m) * 255f).roundToInt().coerceIn(0, 255)
        return OPAQUE or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}
