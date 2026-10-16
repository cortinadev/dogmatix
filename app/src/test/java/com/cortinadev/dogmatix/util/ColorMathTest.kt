package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColorMathTest {

    @Test
    fun parsesHexWithOrWithoutHashAndDropsAlpha() {
        assertEquals(0xFF4E99FF.toInt(), ColorMath.parseHex("#4E99FF"))
        assertEquals(0xFF4E99FF.toInt(), ColorMath.parseHex("4e99ff"))
        assertEquals(0xFF4E99FF.toInt(), ColorMath.parseHex("#804E99FF"))
        assertEquals(0xFF000000.toInt(), ColorMath.parseHex(" #000000 "))
    }

    @Test
    fun rejectsWhatIsNotAColour() {
        assertNull(ColorMath.parseHex(null))
        assertNull(ColorMath.parseHex(""))
        assertNull(ColorMath.parseHex("#FFF"))
        assertNull(ColorMath.parseHex("#GG0000"))
        assertNull(ColorMath.parseHex("+FFFFF"))
    }

    @Test
    fun formatsAsUpperCaseRgb() {
        assertEquals("#4E99FF", ColorMath.toHex(0xFF4E99FF.toInt()))
        assertEquals("#00000A", ColorMath.toHex(0x0000000A))
    }

    @Test
    fun hsvOfPrimaries() {
        val red = ColorMath.toHsv(0xFFFF0000.toInt())
        assertEquals(0f, red.hue, 0.01f)
        assertEquals(1f, red.saturation, 0.01f)
        assertEquals(1f, red.value, 0.01f)
        assertEquals(120f, ColorMath.toHsv(0xFF00FF00.toInt()).hue, 0.01f)
        assertEquals(240f, ColorMath.toHsv(0xFF0000FF.toInt()).hue, 0.01f)
        assertEquals(0f, ColorMath.toHsv(0xFF808080.toInt()).saturation, 0.01f)
    }

    @Test
    fun hsvRoundTripsWithinOneStep() {
        listOf(0xFF4E99FF, 0xFFFF5A5F, 0xFF2ED6B8, 0xFFFFB300, 0xFF9AA0AE, 0xFF123456).forEach { argb ->
            val back = ColorMath.fromHsv(ColorMath.toHsv(argb.toInt()))
            for (shift in listOf(16, 8, 0)) {
                val diff = abs((argb.toInt() shr shift and 0xFF) - (back shr shift and 0xFF))
                assertTrue("channel off by $diff for ${ColorMath.toHex(argb.toInt())}", diff <= 1)
            }
        }
    }

    @Test
    fun hueWrapsAround() {
        assertEquals(ColorMath.fromHsv(ColorMath.Hsv(0f, 1f, 1f)), ColorMath.fromHsv(ColorMath.Hsv(360f, 1f, 1f)))
        assertEquals(ColorMath.fromHsv(ColorMath.Hsv(350f, 1f, 1f)), ColorMath.fromHsv(ColorMath.Hsv(-10f, 1f, 1f)))
    }
}
