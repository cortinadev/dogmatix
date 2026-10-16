package com.cortinadev.dogmatix.util

import java.io.IOException

/** The server answered with something that is not the file (a web page, an error document). */
class UnexpectedContentException(message: String) : IOException(message)

/**
 * Carrying on an interrupted web download. The request goes out with `Range: bytes=<n>-` and,
 * when the first response gave one, `If-Range: <validator>`, so a file that changed on the
 * server comes back whole. [plan] reads the answer *before* the partial file is touched and
 * says what to do with it: append, start from zero, ask again, nothing left to fetch, or fail.
 */
object HttpResume {

    /** `Content-Range`: [start] and [end] are null when only the total is given (a 416), [total] for an unknown length. */
    data class ContentRange(val start: Long?, val end: Long?, val total: Long?)

    /** What came back, as far as the decision goes ([contentLength] -1 = not given). */
    data class Response(val code: Int, val contentRange: String?, val contentLength: Long, val contentType: String?)

    sealed interface Plan {
        /** A 206 that continues the partial exactly: append its body. */
        data class Append(val offset: Long, val total: Long) : Plan
        /** The whole file (a 200, or a 206 covering all of it): write the body from zero. */
        data class Fresh(val total: Long?) : Plan
        /** This body is no use (another range, another file): ask again without `Range`, then start from zero. */
        data object Refetch : Plan
        /** A 416 for exactly the bytes on disk: the partial already is the whole file. */
        data object Complete : Plan
        /** Not the file at all: fail and leave the partial as it is. */
        data class Reject(val reason: String) : Plan
    }

    private val contentRangeShape = Regex("""^\s*bytes\s+(?:(\d+)-(\d+)|\*)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE)

    /**
     * `bytes 100-199/1000`, with an asterisk for an unknown total, or an asterisk for the range and
     * only the total (a 416); null when it is not that shape.
     */
    fun parseContentRange(header: String?): ContentRange? {
        val m = contentRangeShape.find(header ?: return null) ?: return null
        val start = m.groupValues[1].takeIf { it.isNotEmpty() }?.toLongOrNull()
        val end = m.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull()
        val total = m.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
        if (m.groupValues[1].isNotEmpty() && (start == null || end == null || end < start)) return null
        if (total != null && end != null && end >= total) return null
        if (start == null && total == null) return null
        return ContentRange(start, end, total)
    }

    /** What to send back as `If-Range`: a strong ETag, else Last-Modified (a weak ETag is not allowed there). */
    fun validator(etag: String?, lastModified: String?): String? =
        etag?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }
            ?: lastModified?.trim()?.takeIf { it.isNotEmpty() }

    private val pageTypes = setOf("text/html", "application/xhtml+xml", "application/json", "application/problem+json")
    private val pageExtensions = setOf("html", "htm", "xhtml", "json")

    /**
     * A login, captcha or error page served with 200 where a ROM was expected. Only HTML and JSON
     * count: plain text is what many servers send for any unknown extension (and `.cue` is text).
     */
    fun isWebPage(contentType: String?, fileName: String): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return type in pageTypes && fileName.substringAfterLast('.', "").lowercase() !in pageExtensions
    }

    /**
     * [partialBytes]: what is on disk (0 = nothing, no `Range` was sent). [expectedTotal]: the
     * length the first response of this transfer gave, null when unknown.
     */
    fun plan(partialBytes: Long, expectedTotal: Long?, response: Response, fileName: String): Plan {
        val code = response.code
        if (code == 416) {
            val total = parseContentRange(response.contentRange)?.total
            val whole = partialBytes > 0 && total == partialBytes && (expectedTotal == null || expectedTotal == partialBytes)
            return if (whole) Plan.Complete else Plan.Refetch
        }
        if (code != 200 && code != 206) return Plan.Reject("HTTP $code")
        if (isWebPage(response.contentType, fileName)) {
            return Plan.Reject("the server sent a web page (${response.contentType?.substringBefore(';')?.trim()}) instead of the file")
        }
        val length = response.contentLength.takeIf { it >= 0 }
        if (code == 200) {
            if (length == 0L && (expectedTotal ?: 0L) > 0L) return Plan.Reject("the server sent an empty response instead of the file")
            return Plan.Fresh(length)
        }

        // 206: only one that ends at the last byte is any use (a capped range would leave a short file).
        val unusable = if (partialBytes > 0) Plan.Refetch else Plan.Reject("the server sent part of the file without being asked for it")
        val range = parseContentRange(response.contentRange) ?: return unusable
        val start = range.start ?: return unusable
        val end = range.end ?: return unusable
        val total = range.total ?: expectedTotal ?: return unusable
        if (end != total - 1 || (length != null && length != end - start + 1)) return unusable
        if (start == 0L) return Plan.Fresh(total)
        if (partialBytes <= 0 || start != partialBytes) return unusable
        // Same name, different length: another file now. Start again.
        if (expectedTotal != null && total != expectedTotal) return Plan.Refetch
        return Plan.Append(partialBytes, total)
    }
}
