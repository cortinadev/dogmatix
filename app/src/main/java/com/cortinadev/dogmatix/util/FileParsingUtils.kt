package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.local.entity.FileTagEntity
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object FileParsingUtils {

    // Compiled once: every indexed file of every source goes through these.
    private val TAG_GROUP = Regex("\\(([^)]+)\\)")
    private val SPACES = Regex("\\s+")

    /** A table cell holding nothing but a size: `1.2 MiB`, `12 MB`, `66M` (Apache), `512` (bytes). */
    internal val SIZE_CELL_TEXT = Regex("""\d[\d.,]*\s*(?:[KMGTPE](?:i?B)?|B)?""", RegexOption.IGNORE_CASE)

    /** The scheme of an address with a host (`https://`, `ftp://`…). */
    private val HOST_SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://""")

    /**
     * Link schemes without a host that are never a file of the listing. Any other `word:` start is
     * taken as part of a file name (a server that does not encode `Zelda: …`), as before.
     */
    private val NON_FILE_SCHEMES = listOf("javascript", "mailto", "data", "tel", "sms", "magnet", "about", "blob", "file", "urn", "news")

    fun toUserReadablePath(uriString: String): String {
        if (uriString.isBlank()) return ""
        return try {
            val decodedPath = URLDecoder.decode(uriString, "UTF-8")
            val pathSegment = decodedPath.substringAfterLast(":")
            ".../$pathSegment"
        } catch (_: Exception) {
            uriString
        }
    }
    fun sanitizeFolderName(name: String): String {
        val sanitized = name.replace(Regex("[<>:\"/|?*]"), "")
        return sanitized.trim().replace(Regex("\\s{2,}"), " ")
    }

    fun buildDownloadUrl(baseUrl: String, href: String): String {
        return if (MirrorUrls.isWeb(href)) {
            href
        } else {
            val base = baseUrl.trimEnd('/')
            val path = href.trimStart('/')
            val fullUrl = "$base/$path"

            normalizeUrl(fullUrl)
        }
    }

    private fun normalizeUrl(url: String): String {
        val parts = url.split("/")
        val normalized = mutableListOf<String>()

        for (part in parts) {
            when (part) {
                ".." -> {
                    if (normalized.isNotEmpty() && normalized.last() != "") {
                        normalized.removeAt(normalized.size - 1)
                    }
                }
                "." -> {
                    continue
                }
                else -> {
                    normalized.add(part)
                }
            }
        }

        return normalized.joinToString("/")
    }

    fun extractNameAndTags(displayName: String): Pair<String, List<String>> {
        val tags = mutableListOf<String>()
        var cleanName = displayName

        val matches = TAG_GROUP.findAll(displayName)

        for (match in matches) {
            val content = match.groupValues[1].trim()

            // Split by comma to handle multiple values in parentheses
            val values = content.split(",").map { it.trim() }

            for (value in values) {
                if (isValidTag(value)) {
                    tags.add(normalizeTag(value))
                }
            }

            // Remove the entire parentheses group from the name
            cleanName = cleanName.replace(match.value, "").trim()
        }

        // Clean up any extra spaces that might be left
        cleanName = cleanName.replace(SPACES, " ").trim()

        return Pair(cleanName, tags)
    }

    fun isValidTag(tag: String): Boolean {
        if (tag.length < 2) return false

        val cleanTag = tag.trim().uppercase()

        if (cleanTag in Constants.Tags.VIDEO_STANDARDS) return true

        if (cleanTag in Constants.Tags.CONTENT_TYPES) return true

        if (cleanTag.matches(Constants.Tags.LANGUAGE_CODE_PATTERN)) return true

        if (cleanTag.matches(Constants.Tags.VERSION_PATTERN)) return true

        if (cleanTag in Constants.Tags.ALL_REGIONS) return true

        for (matcher in Constants.Tags.PARTIAL_MATCHERS) {
            if (cleanTag.contains(matcher)) return true
        }

        return false
    }

    fun normalizeTag(tag: String): String {
        val trimmed = tag.trim()
        val upper = trimmed.uppercase()

        if (upper in Constants.Tags.VIDEO_STANDARDS) {
            return upper
        }

        if (trimmed.length <= 3) {
            return upper
        }

        return trimmed.lowercase().replaceFirstChar { it.uppercase() }
    }

    fun decodeUrlEncodedFileName(fileName: String): String {
        return try {
            URLDecoder.decode(fileName, StandardCharsets.UTF_8.toString())
        } catch (_: Exception) {
            fileName
        }
    }

    /**
     * [link]: the row's link when the caller already found it (saves selecting it again).
     * [pageUrl]: the address that served the listing, when not [baseUrl] (a mirror); see [fileFromLink].
     */
    fun parseFileFromRow(
        row: Element,
        baseUrl: String,
        consoleId: String,
        link: Element? = null,
        pageUrl: String = baseUrl
    ): Pair<DownloadableFileEntity?, List<FileTagEntity>> {
        val linkCell = link ?: row.select("td.link a").first() ?: row.select("a").first()
            ?: return Pair(null, emptyList())
        val (fileEntity, tags) = fileFromLink(linkCell, sizeCellOf(row)?.text(), baseUrl, pageUrl, consoleId)
            ?: return Pair(null, emptyList())
        // fileId is set after the file is inserted.
        return Pair(fileEntity, tags.map { FileTagEntity(fileId = 0L, tag = it) })
    }

    /**
     * One file of a web listing from its link, or null when the link is not a file of the listing
     * ([listingFileName]). Relative links resolve against [baseUrl] (the source's own address, so
     * rows keep it whichever mirror answered); absolute paths and scheme-relative links against the
     * host of [pageUrl], the page that holds them. [sizeText]: the size as the listing shows it.
     * Returns the row and its tags (from the name).
     */
    fun fileFromLink(link: Element, sizeText: String?, baseUrl: String, pageUrl: String, consoleId: String): Pair<DownloadableFileEntity, List<String>>? {
        val href = link.attr("href").trim()
        val leaf = listingFileName(href) ?: return null
        val linkText = link.text().trim()
        // The name the listing shows, unless it is cut ("Very long na..>", nginx and Apache) or missing (an icon link).
        val shown = link.attr("title").trim().ifEmpty { linkText.takeUnless { it.isEmpty() || it.endsWith("..>") }.orEmpty() }
        val displayName = shown.ifEmpty { decodeUrlEncodedFileName(leaf) }
        val (cleanName, tags) = extractNameAndTags(displayName)
        val fileSize = sizeText?.trim()?.takeIf { it != ScrapingConstants.UNKNOWN_FILE_SIZE } ?: ScrapingConstants.DEFAULT_FILE_SIZE
        val extension = leaf.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val entity = DownloadableFileEntity(
            name = cleanName,
            // As the listing links it (a plain relative link is kept exactly); only the last part of a longer path.
            fileName = leaf,
            consoleId = consoleId,
            downloadUrl = resolveHref(baseUrl, pageUrl, href),
            fileSize = FileSizeUtils.parseFileSize(fileSize),
            fileExtension = extension
        )
        return Pair(entity, tags)
    }

    /**
     * The file name a listing link stands for (as linked, still URL-encoded), or null when the link
     * is not a file of this listing: empty, an anchor (`#…`), a sort link (`?C=N;O=D`), a folder
     * (`…/`), another scheme (`javascript:`, `mailto:`, `ftp://`…), a way out of the folder (`..`),
     * or a name that would not be one file on disk (an encoded `/`). A plain relative link comes
     * back as it is; a longer one (`sub/x.zip`, `/roms/x.zip`, `https://host/x.zip?dl=1`) as its last part.
     */
    fun listingFileName(href: String): String? {
        val link = href.trim()
        if (link.isEmpty() || link == ScrapingConstants.PARENT_DIRECTORY || link == ScrapingConstants.CURRENT_DIRECTORY) return null
        if (link.startsWith("#") || link.startsWith("?")) return null
        if (NON_FILE_SCHEMES.any { link.startsWith("$it:", ignoreCase = true) }) return null
        val scheme = HOST_SCHEME.find(link)?.groupValues?.get(1)
        if (scheme != null && !scheme.equals("http", true) && !scheme.equals("https", true)) return null
        // The path of an address with a host: what follows the host (nothing = the site itself).
        val path = if (scheme != null || link.startsWith("//")) {
            link.substringAfter("//").let { rest -> rest.indexOf('/').let { if (it < 0) "" else rest.substring(it) } }
        } else {
            link
        }.substringBefore('#').substringBefore('?')
        if (path.isEmpty() || path.endsWith("/")) return null
        val segments = path.split('/')
        if (segments.any { it == ".." || decodeUrlEncodedFileName(it) == ".." }) return null
        val leaf = segments.last()
        val decoded = decodeUrlEncodedFileName(leaf)
        if (leaf.isEmpty() || decoded.isBlank() || decoded == "." || decoded.contains('/') || decoded.contains('\\')) return null
        return leaf
    }

    /** Where a listing link points: see [fileFromLink]. */
    fun resolveHref(baseUrl: String, pageUrl: String, href: String): String = when {
        MirrorUrls.isWeb(href) -> href
        href.startsWith("//") -> pageUrl.substringBefore("://", "https") + ":" + href
        href.startsWith("/") -> originOf(pageUrl) + normalizeUrl(href)
        else -> buildDownloadUrl(baseUrl, href)
    }

    /** `scheme://host[:port]` of [url]. */
    private fun originOf(url: String): String {
        val scheme = url.substringBefore("://")
        val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        return "$scheme://$authority"
    }

    /**
     * The size column of a listing row: `td.size` (Myrient and similar), `td.fb-s` (h5ai), else
     * the last cell without a link that reads as a size (Apache: icon, name, date, size,
     * description), else the second cell as before.
     */
    fun sizeCellOf(row: Element): Element? {
        val cells = row.select("td")
        return row.selectFirst("td.size, td.fb-s")
            ?: cells.lastOrNull { it.select("a").isEmpty() && SIZE_CELL_TEXT.matches(it.text().trim()) }
            ?: cells.getOrNull(1)
    }

    /**
     * Magnet links with many trackers can slow down metadata fetching in libtorrent.
     * This trims the tracker list (tr parameters) to a reasonable limit.
     */
    fun optimizeMagnetUri(uri: String): String {
        if (!uri.startsWith("magnet:?")) return uri
        
        val parts = uri.split("&")
        val base = parts[0]
        val params = parts.drop(1)
        
        val trackers = params.filter { it.startsWith("tr=") }
        val otherParams = params.filter { !it.startsWith("tr=") }
        
        if (trackers.size <= TorrentConstants.MAX_TRACKERS_PER_MAGNET) return uri
        
        val optimizedTrackers = trackers.take(TorrentConstants.MAX_TRACKERS_PER_MAGNET)
        val optimizedParams = (otherParams + optimizedTrackers).joinToString("&")
        
        return if (optimizedParams.isEmpty()) base else "$base&$optimizedParams"
    }
}
