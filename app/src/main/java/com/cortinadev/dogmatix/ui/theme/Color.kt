package com.cortinadev.dogmatix.ui.theme

import androidx.compose.ui.graphics.Color

val DeepBlack = Color(0xFF180F18)
val SoftBlack = Color(0xFF24242E)
val NeonGreen = Color(0xFF83FF2B)
val NeonRed = Color(0xFFFF1A1A)
val WarmOrange = Color(0xFFFF7F00)
val SoftOrange = Color(0xFFFFB370)
val PurpleAccent = Color(0xFFC390E8)
val LightText = Color(0xFFD7D7D7)
val LightningBlue = Color(0xFF4E99FF)
val NeonYellow = Color(0xFFFFFF00)
// Dogmatix flat palette (see the design canvas). Dark first, light mirrors it.
object DogmatixDark {
    val bg = Color(0xFF141217)
    val bg2 = Color(0xFF1A1720)
    val panel = Color(0xFF1E1B24)
    val raised = Color(0xFF2A2632)
    val line = Color(0xFF2C2834)
    val line2 = Color(0xFF4A4455)
    val text = Color(0xFFECE8F1)
    val muted = Color(0xFF8E879A)
    val muted2 = Color(0xFFB8B1C4)
    val knobOff = Color(0xFF3A3446)
    val card = raised
}

object DogmatixLight {
    val bg = Color(0xFFF6F4F8)
    val bg2 = Color(0xFFFDFCFE)
    val panel = Color(0xFFECE9F0)
    val raised = Color(0xFFE0DCE6)
    val line = Color(0xFFD9D4DF)
    val line2 = Color(0xFFA9A2B3)
    val text = Color(0xFF17131C)
    // Secondary text: ~6.5:1 on bg so small labels stay legible.
    val muted = Color(0xFF5E5769)
    val muted2 = Color(0xFF433D4D)
    val knobOff = Color(0xFFC6C0CF)
    /** Card surface: a much lighter grey than `raised` so cards read as cards, not as blocks. */
    val card = Color(0xFFFBFAFC)
}

val OnAccent = Color(0xFF141217)
val StatusSuccess = Color(0xFF7BE03A)
val StatusDanger = Color(0xFFFF4D4D)
val StatusInfo = Color(0xFF4E99FF)
