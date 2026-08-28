package com.cortinadev.dogmatix.ui.components

import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = bytes.toDouble()
    var unit = 0
    while (size >= 1024 && unit < units.size - 1) {
        size /= 1024
        unit++
    }
    return if (unit == 0) "${size.toInt()} ${units[unit]}" else String.format(Locale.US, "%.1f %s", size, units[unit])
}

/** "Chrono Trigger (USA).sfc" → "Chrono Trigger (USA)" when the tail looks like a short extension. */
fun stripExtension(name: String): String {
    val trimmed = name.trim()
    val dot = trimmed.lastIndexOf('.')
    if (dot <= 0) return trimmed
    val ext = trimmed.substring(dot + 1)
    return if (ext.length in 1..4 && ext.all { it.isLetterOrDigit() }) trimmed.substring(0, dot).trim() else trimmed
}
