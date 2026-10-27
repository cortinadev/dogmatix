package com.cortinadev.dogmatix.util

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * An Internet Archive item as a library source: `ia://<identifier>`, read through the metadata
 * API (`archive.org/metadata/<id>`). A ZIP of the item is not an `ia://` source but its "View
 * contents" listing, `https://archive.org/download/<id>/<zip>/`, read as a plain web listing.
 * Pure helpers shared by the scan, the source dialog, the downloads and the verifier.
 */
object IaSource {

    const val SCHEME = "ia://"
    const val HOST = "archive.org"
    private const val BASE = "https://archive.org"

    /** What the scan report keeps as the detail of a 401/403 sent with a saved session. */
    const val SESSION_EXPIRED_DETAIL = "ia-session-expired"

    /** Files Internet Archive lists the contents of (its ZIP viewer): one of these is a web source. */
    private val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "rar", "tar", "gz", "iso")

    /** `archive.org/details/<id>…` and `archive.org/download/<id>…`, with or without `www.`. */
    private val itemPage = Regex("""^https?://(?:www\.)?archive\.org/(?:details|download)/([^/?#]+)(?:/([^?#]*))?(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
    /** A data node's ZIP viewer: `ia601900.us.archive.org/view_archive.php?archive=/2/items/<id>/<path>`. */
    private val viewer = Regex("""^https?://[a-z0-9.-]+\.archive\.org/view_archive\.php\?(.*)$""", RegexOption.IGNORE_CASE)
    private val viewerArchive = Regex("""^/\d+/items/([^/]+)/(.+)$""")
    private val downloadAddress = Regex("""^https?://archive\.org/download/([^/?#]+)/([^?#]+)""", RegexOption.IGNORE_CASE)

    fun isSource(url: String): Boolean = url.trim().startsWith(SCHEME, ignoreCase = true)

    /** `ia://msdos_Prince_of_Persia_1990` → the identifier as typed (IA identifiers are case-sensitive). */
    fun itemOf(url: String): String? =
        url.trim().takeIf { isSource(it) }?.substring(SCHEME.length)?.trim()
            ?.substringBefore('/')?.substringBefore('?')?.substringBefore('#')?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * How a pasted address is stored: [source] is either `ia://<id>` (with [subpath], a folder
     * inside the item, when the address named one) or, for an archive inside the item, the stable
     * address of its listing `https://archive.org/download/<id>/<archive>/` (never a data node,
     * which changes). Null when [url] is not an Internet Archive address.
     */
    data class Normalized(val source: String, val subpath: String?)

    fun normalize(url: String): Normalized? {
        val trimmed = url.trim()
        val (id, rawPath) = when {
            isSource(trimmed) -> {
                val rest = trimmed.substring(SCHEME.length).trim()
                (itemOf(trimmed) ?: return null) to rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
            }
            itemPage.matches(trimmed) -> itemPage.find(trimmed)!!.groupValues.let { it[1] to it[2] }
            viewer.matches(trimmed) -> {
                val query = viewer.find(trimmed)!!.groupValues[1]
                val archive = query.split('&').firstOrNull { it.startsWith("archive=") }?.substringAfter('=') ?: return null
                val match = viewerArchive.find(decode(archive)) ?: return null
                // The viewer's path is already decoded: encode it back so the decode below is neutral.
                match.groupValues[1] to match.groupValues[2].split('/').joinToString("/") { encodeSegment(it) }
            }
            else -> return null
        }
        if (id.isBlank()) return null
        val path = decode(rawPath).split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (path.isNotEmpty() && extensionOf(path.last()) in ARCHIVE_EXTENSIONS) {
            return Normalized(downloadUrl(id, path.joinToString("/")) + "/", null)
        }
        return Normalized(SCHEME + id, path.joinToString("/").takeIf { it.isNotEmpty() })
    }

    /** Where Internet Archive serves [path] of item [id]: each segment encoded, the slashes kept. */
    fun downloadUrl(id: String, path: String): String =
        "$BASE/download/$id/" + path.split('/').joinToString("/") { encodeSegment(it) }

    /** Item and path (decoded) of an `archive.org/download/<id>/<path>` address; null for anything else. */
    fun itemAndPathOf(downloadUrl: String): Pair<String, String>? {
        val match = downloadAddress.find(downloadUrl.trim()) ?: return null
        val path = decode(match.groupValues[2]).trim('/')
        return if (path.isEmpty()) null else match.groupValues[1] to path
    }

    fun metadataUrl(id: String): String = "$BASE/metadata/$id"

    fun lastUpdatedUrl(id: String): String = "${metadataUrl(id)}/item_last_updated"

    /** A file listed inside an archive (the ZIP viewer): Internet Archive has no hashes of its own for it. */
    fun isInsideArchive(path: String): Boolean =
        path.split('/').dropLast(1).any { extensionOf(it) in ARCHIVE_EXTENSIONS }

    private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** Like a form encoder, but spaces as `%20` and parentheses left readable (both are fine in a path). */
    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20").replace("%28", "(").replace("%29", ")")

    /** Percent-decoding of a path: a literal `+` stays a `+`. */
    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
}
