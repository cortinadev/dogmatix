package com.cortinadev.dogmatix.util

import androidx.compose.ui.graphics.Color
import com.cortinadev.dogmatix.ui.theme.LightText
import com.cortinadev.dogmatix.ui.theme.LightningBlue
import com.cortinadev.dogmatix.ui.theme.NeonGreen

fun consoleColorFor(console: String): Color = when(console) {
    "Xbox 360" -> NeonGreen
    "Nintendo 64" -> Color.Gray
    "Sega Genesis" -> LightningBlue
    else -> LightText
}
