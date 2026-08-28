package com.cortinadev.dogmatix.util

object VersionUtils {
    
    /**
     * Compares two version strings and returns:
     * -1 if version1 < version2
     * 0 if version1 == version2  
     * 1 if version1 > version2
     */
    fun compareVersions(version1: String, version2: String): Int {
        val v1 = parseVersion(version1)
        val v2 = parseVersion(version2)
        
        val maxLength = maxOf(v1.size, v2.size)
        
        for (i in 0 until maxLength) {
            val part1 = v1.getOrElse(i) { 0 }
            val part2 = v2.getOrElse(i) { 0 }
            
            when {
                part1 > part2 -> return 1
                part1 < part2 -> return -1
            }
        }
        
        return 0
    }
    
    private fun parseVersion(version: String): List<Int> {
        return version.replace(Regex("[^0-9.]"), "")
            .split(".")
            .mapNotNull { it.toIntOrNull() }
    }
}
