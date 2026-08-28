package com.cortinadev.dogmatix.util

object FileSizeUtils {
    
    fun parseFileSize(sizeString: String): Long {
        if (sizeString.isBlank() || sizeString == "0") return 0L
        
        val cleanString = sizeString.trim().replace(",", "")
        val regex = """(\d+(?:\.\d+)?)\s*([KMGTPE]?i?B)""".toRegex(RegexOption.IGNORE_CASE)
        val match = regex.find(cleanString)
        
        if (match == null) return 0L
        
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
    
    fun bytesToMB(bytes: Long): Float {
        return bytes / Constants.MEBIBYTE.toFloat()
    }
}
