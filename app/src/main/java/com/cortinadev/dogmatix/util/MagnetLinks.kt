package com.cortinadev.dogmatix.util

/**
 * The info-hash a magnet names, as lower-case hex: the v1 `btih` (40 hex digits, or 32 base32
 * characters) when present, else the SHA-256 of a v2 `btmh` (multihash `1220` + 64 hex digits).
 * Null for anything else. It keys the torrent metadata cache on disk.
 */
object MagnetLinks {
    private val btih = Regex("""xt=urn:btih:([0-9a-z]+)""", RegexOption.IGNORE_CASE)
    private val btmh = Regex("""xt=urn:btmh:1220([0-9a-f]{64})""", RegexOption.IGNORE_CASE)
    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun infoHashKey(magnet: String): String? {
        if (!magnet.startsWith("magnet:", ignoreCase = true)) return null
        btih.find(magnet)?.groupValues?.get(1)?.let { value ->
            when {
                value.length == 40 && value.all { it.isHexDigit() } -> return value.lowercase()
                value.length == 32 -> base32ToHex(value)?.let { return it }
            }
        }
        return btmh.find(magnet)?.groupValues?.get(1)?.lowercase()
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun base32ToHex(value: String): String? {
        val out = StringBuilder(40)
        var buffer = 0L
        var bits = 0
        for (c in value.uppercase()) {
            val v = BASE32.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v.toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.append("%02x".format(((buffer shr bits) and 0xFF).toInt()))
            }
        }
        return out.toString().takeIf { it.length == 40 }
    }
}
