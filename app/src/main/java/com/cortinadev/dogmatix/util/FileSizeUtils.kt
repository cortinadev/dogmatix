package com.cortinadev.dogmatix.util

object FileSizeUtils {

    /** Compiled once: every row of a directory listing goes through [parseFileSize]. */
    private val sizePattern = """(\d+(?:\.\d+)?)\s*([KMGTPE]?i?B)""".toRegex(RegexOption.IGNORE_CASE)

    /** Apache index sizes: a bare unit letter in powers of 1024 (`66M`, `1.5K`), or plain bytes (`512`). */
    private val shortSizePattern = """(\d+(?:\.\d+)?)\s*([KMGTPE])?""".toRegex(RegexOption.IGNORE_CASE)

    /** A number with commas between thousands: `1,234`, `12,345,678` (not `1,5`, a decimal comma). */
    private val thousandsNumber = Regex("""(?<![\d.,])\d{1,3}(?:,\d{3})+(?![\d,])""")

    fun parseFileSize(sizeString: String): Long {
        if (sizeString.isBlank() || sizeString == "0") return 0L

        // `1,234 KB` → 1234 KB; `1,5 MB` (a decimal comma) → 1.5 MB.
        val cleanString = sizeString.trim().replace(thousandsNumber) { it.value.replace(",", "") }.replace(',', '.')
        val match = sizePattern.find(cleanString)

        if (match == null) return parseShortSize(cleanString)

        val (numberStr, unitStr) = match.destructured
        val number = numberStr.toDoubleOrNull() ?: return 0L
        
        return when (unitStr.uppercase()) {
            "B" -> number.toLong()
            "KB" -> (number * Constants.KILOBYTE).toLong()
            "KIB" -> (number * Constants.KIBIBYTE).toLong()
            "MB" -> (number * Constants.MEGABYTE).toLong()
            "MIB" -> (number * Constants.MEBIBYTE).toLong()
            "GB" -> (number * Constants.GIGABYTE).toLong()
            "GIB" -> (number * Constants.GIBIBYTE).toLong()
            "TB" -> (number * Constants.TERABYTE).toLong()
            "TIB" -> (number * Constants.TEBIBYTE).toLong()
            "PB" -> (number * Constants.TERABYTE * Constants.KILOBYTE).toLong()
            "PIB" -> (number * Constants.TEBIBYTE * Constants.KIBIBYTE).toLong()
            "EB" -> (number * Constants.TERABYTE * Constants.MEGABYTE).toLong()
            "EIB" -> (number * Constants.TEBIBYTE * Constants.MEBIBYTE).toLong()
            else -> 0L
        }
    }

    private fun parseShortSize(text: String): Long {
        val (numberStr, unit) = shortSizePattern.matchEntire(text)?.destructured ?: return 0L
        val number = numberStr.toDoubleOrNull() ?: return 0L
        if (unit.isEmpty()) return number.toLong()
        return (number * Math.pow(1024.0, "KMGTPE".indexOf(unit.uppercase()) + 1.0)).toLong()
    }

    fun bytesToMB(bytes: Long): Float {
        return bytes / Constants.MEBIBYTE.toFloat()
    }
}
