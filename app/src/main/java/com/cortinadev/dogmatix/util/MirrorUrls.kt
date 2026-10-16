package com.cortinadev.dogmatix.util

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Reserve addresses of a web source: the same listing served from another host or path
 * (a mirror). A file indexed under the source's address can be fetched from each mirror by
 * swapping the base and keeping the rest of its path.
 */
object MirrorUrls {

    /**
     * The same file under each of [mirrors], in order, when [downloadUrl] lies under [sourceUrl];
     * empty otherwise. Bases are compared decoded, so `Game%20Boy` and `Game Boy` match.
     */
    fun alternatives(downloadUrl: String, sourceUrl: String, mirrors: List<String>): List<String> {
        if (mirrors.isEmpty() || !isWeb(sourceUrl)) return emptyList()
        val base = withSlash(decode(sourceUrl.trim()))
        val file = decode(downloadUrl.trim())
        if (!file.startsWith(base, ignoreCase = true)) return emptyList()
        val rest = file.substring(base.length)
        if (rest.isEmpty()) return emptyList()
        val encodedRest = rest.split('/').joinToString("/") { encodeSegment(it) }
        // A base typed with spaces would go out raw in the request line.
        return mirrors.map { withSlash(it.trim()).replace(" ", "%20") }
            .filter { isWeb(it) && !withSlash(decode(it)).equals(base, ignoreCase = true) }
            .distinct()
            .map { it + encodedRest }
    }

    /**
     * Mirrors typed in the source dialog: one per line (paths may hold spaces and commas), web
     * addresses only, without duplicates or the source's own address.
     */
    fun parse(text: String, sourceUrl: String = ""): List<String> {
        val own = withSlash(decode(sourceUrl.trim()))
        return text.split('\n')
            .map { it.trim() }
            .filter { isWeb(it) && !withSlash(decode(it)).equals(own, ignoreCase = true) }
            .distinct()
    }

    /** Lower-cased host of [url], or null when it has none (magnets, romm://, content://). */
    fun hostOf(url: String): String? {
        if (!isWeb(url)) return null
        val parsed = runCatching { URI(url.trim()).host }.getOrNull()
        return (parsed ?: url.trim().substringAfter("://").substringBefore('/').substringBefore(':'))
            .lowercase().takeIf { it.isNotEmpty() }
    }

    fun isWeb(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    private fun withSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

    private fun decode(url: String): String =
        runCatching { URLDecoder.decode(url.replace("+", "%2B"), StandardCharsets.UTF_8.name()) }.getOrDefault(url)

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
