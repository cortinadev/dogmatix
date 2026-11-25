package com.cortinadev.dogmatix.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL

/**
 * Why a sources JSON could not be imported from an address (Sources → Import → From URL) or from a
 * file: a file uses [UNREADABLE], [NOT_SOURCES], [BUSY] and [OTHER].
 */
enum class SourcesUrlProblem { INVALID_ADDRESS, UNREACHABLE, HTTP, TOO_LARGE, NOT_SOURCES, BUSY, UNREADABLE, OTHER }

/** A failed import from an address or a file; [httpCode] only with [SourcesUrlProblem.HTTP]. */
class SourcesUrlException(val problem: SourcesUrlProblem, val httpCode: Int = 0) :
    Exception(if (httpCode > 0) "$problem $httpCode" else problem.name)

/**
 * Importing the sources JSON from a web address: what the user typed becomes the address to
 * download (GitHub and Gist pages turn into their raw file), the download is capped and the text
 * must be a sources document before anything is replaced.
 */
object SourcesUrl {
    const val MAX_MB = 5
    const val MAX_BYTES = MAX_MB * 1024L * 1024L
    const val MAX_REDIRECTS = 5
    /** U+FEFF, the byte order mark some editors put at the start of a UTF-8 file. */
    private const val BOM = "\uFEFF"

    /** A scheme at the start (`ftp:`, `magnet:`…); `host:8080` is a port, not a scheme. */
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):(?!\\d)")
    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    /**
     * The address to download for what was typed, or null when it is not a web address: trimmed,
     * `https://` added when there is no scheme, only http / https with a host. GitHub file pages
     * (`blob` / `raw`) and gists become their raw file; anything else is left as typed.
     */
    fun normalize(typed: String): String? {
        val text = typed.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val full = when (SCHEME.find(text)?.groupValues?.get(1)?.lowercase()) {
            null -> "https://$text"
            "http", "https" -> text
            else -> return null
        }
        val uri = runCatching { URI(full) }.getOrNull() ?: return null
        if (uri.host.isNullOrEmpty()) return null
        return rawOf(uri) ?: full
    }

    /** `github.com/<u>/<r>/blob|raw/<ref>/<path>` and `gist.github.com/<u>/<id>` as raw files; null for anything else. */
    private fun rawOf(uri: URI): String? {
        val host = uri.host.lowercase().removePrefix("www.")
        val parts = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        return when {
            host == "github.com" && parts.size >= 5 && (parts[2] == "blob" || parts[2] == "raw") ->
                "https://raw.githubusercontent.com/${parts[0]}/${parts[1]}/${parts.drop(3).joinToString("/")}"
            host == "gist.github.com" && parts.size == 2 ->
                "https://gist.githubusercontent.com/${parts[0]}/${parts[1]}/raw"
            else -> null
        }
    }

    /** Where a redirect from [current] leads ([location] may be relative), or null when it leaves http / https. */
    fun redirectTarget(current: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        val next = runCatching { URL(URL(current), location.trim()) }.getOrNull() ?: return null
        return next.toString().takeIf { next.protocol == "http" || next.protocol == "https" }
    }

    fun isRedirect(code: Int): Boolean = code in REDIRECTS

    /**
     * Reads [input] whole, as long as it is no bigger than [max] bytes: past that it stops at once
     * with [SourcesUrlProblem.TOO_LARGE], whatever the server said the length would be.
     * [onChunk] runs between reads (a cancelled import stops there).
     */
    fun readCapped(input: InputStream, max: Long = MAX_BYTES, onChunk: () -> Unit = {}): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            onChunk()
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > max) throw SourcesUrlException(SourcesUrlProblem.TOO_LARGE)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** The document as text: UTF-8, without a byte order mark. */
    fun decode(bytes: ByteArray): String = String(bytes, Charsets.UTF_8).removePrefix(BOM)

    /**
     * A sources document with at least one console that has a source address. A web page, an empty
     * file or another kind of JSON is not (nested objects alone can read as consoles, never as sources).
     */
    fun isSourcesDocument(text: String): Boolean = runCatching {
        SourcesJson.parseDocument(text).any { m -> m.consoles.any { it.urls.isNotEmpty() } }
    }.getOrDefault(false)

    /**
     * What a picked file needs to be imported: a sources document with at least one console (an
     * export may hold consoles without sources yet). Anything that does not parse is not one.
     */
    fun isImportableFile(text: String): Boolean = runCatching {
        SourcesJson.parseDocument(text).any { it.consoles.isNotEmpty() }
    }.getOrDefault(false)

    /** What the history calls an import from [url]: the file and its server, or just the server. */
    fun label(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host ?: return url
        val file = uri.path.orEmpty().trimEnd('/').substringAfterLast('/').takeIf { '.' in it }
        return if (file != null) "$file ($host)" else host
    }

    /** The problem behind a failed import: ours as they are, network trouble as [SourcesUrlProblem.UNREACHABLE]. */
    fun problemOf(e: Throwable): SourcesUrlProblem = when (e) {
        is SourcesUrlException -> e.problem
        is IOException -> SourcesUrlProblem.UNREACHABLE
        else -> SourcesUrlProblem.OTHER
    }

    /** The same for a file: there is no network, so only ours have a name; the rest is [SourcesUrlProblem.OTHER]. */
    fun fileProblemOf(e: Throwable): SourcesUrlProblem = (e as? SourcesUrlException)?.problem ?: SourcesUrlProblem.OTHER
}
