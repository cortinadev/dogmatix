package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.service.DebridFile
import java.util.Locale

/**
 * Pure helpers for the debrid route (TorBox, Real-Debrid): the info-hash of a magnet (to ask the
 * cache before submitting) and picking, among the files the service lists for a torrent, the one
 * the library row refers to. Services number files in their own order, so libtorrent's
 * `torrentFileIndex` is not usable; we match on base name (and size) instead.
 */
object DebridMatcher {

    private val HEX40 = Regex("^[0-9a-fA-F]{40}$")
    private val BASE32 = Regex("^[A-Za-z2-7]{32}$")

    /** Lower-case hex info-hash of [magnet], or null if it has none. Accepts hex and base32. */
    fun infoHashFromMagnet(magnet: String): String? {
        val raw = Regex("xt=urn:btih:([^&]+)", RegexOption.IGNORE_CASE).find(magnet)?.groupValues?.get(1)?.trim() ?: return null
        return when {
            HEX40.matches(raw) -> raw.lowercase(Locale.ROOT)
            BASE32.matches(raw) -> base32ToHex(raw)
            else -> null
        }
    }

    /**
     * The remote file matching [fileName] (URL-encoded or not) and [fileSize]. Preference:
     * exact base name + size › exact base name › normalised base name + size › unique size match.
     */
    fun pickFile(files: List<DebridFile>, fileName: String, fileSize: Long): DebridFile? {
        if (files.isEmpty()) return null
        val wanted = FileParsingUtils.decodeUrlEncodedFileName(fileName).substringAfterLast('/').lowercase(Locale.ROOT)
        val wantedNorm = normalise(wanted)
        fun base(f: DebridFile) = FileParsingUtils.decodeUrlEncodedFileName(f.name).substringAfterLast('/').lowercase(Locale.ROOT)

        val byName = files.filter { base(it) == wanted }
        byName.firstOrNull { it.size == fileSize }?.let { return it }
        if (byName.size == 1) return byName.single()

        val byNorm = files.filter { normalise(base(it)) == wantedNorm }
        byNorm.firstOrNull { it.size == fileSize }?.let { return it }
        if (byNorm.size == 1) return byNorm.single()

        if (fileSize > 0) files.filter { it.size == fileSize }.takeIf { it.size == 1 }?.let { return it.single() }
        return null
    }

    private fun normalise(s: String) = s.filter { it.isLetterOrDigit() }

    private fun base32ToHex(s: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0
        var value = 0
        val out = StringBuilder()
        for (c in s.uppercase(Locale.ROOT)) {
            value = (value shl 5) or alphabet.indexOf(c)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.append(String.format(Locale.ROOT, "%02x", (value shr bits) and 0xFF))
            }
        }
        return out.toString()
    }
}
