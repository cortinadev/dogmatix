package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.util.AuthRedirects
import com.cortinadev.dogmatix.util.Constants
import com.cortinadev.dogmatix.util.HttpResume
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val HTTP_RANGE_NOT_SATISFIABLE = 416

@Singleton
class DownloadHttpClient @Inject constructor() {
    /**
     * [rangeStart] > 0 asks for the rest of the file, with [ifRange] (the first response's
     * validator) so a file that changed comes back whole. The caller decides what to do with the
     * answer before touching the partial (`HttpResume.plan`); a 416 is returned to it as well.
     * [configure] adjusts the connection before it opens (the RomM server's TLS trust, see [RommTls.secure]).
     * [authRedirects] follows redirects by hand, with [headers] (the archive.org session) going on
     * only to archive.org hosts over HTTPS ([AuthRedirects]); the connection returned is the last one.
     */
    fun createConnection(
        downloadUrl: String,
        rangeStart: Long = 0L,
        headers: Map<String, String> = emptyMap(),
        ifRange: String? = null,
        configure: (HttpURLConnection) -> Unit = {},
        authRedirects: Boolean = false
    ): HttpURLConnection {
        var current = downloadUrl
        var sent = headers
        var manual = authRedirects
        repeat(AuthRedirects.MAX_REDIRECTS + 1) {
            val connection = open(current, rangeStart, sent, ifRange, configure, followRedirects = !manual)
            val code = connection.responseCode
            if (manual && code in AuthRedirects.REDIRECT_CODES) {
                val next = connection.getHeaderField("Location")?.let { AuthRedirects.resolve(current, it) }
                connection.disconnect()
                if (next == null) throw Exception("HTTP Error after redirect: $code")
                if (!AuthRedirects.keepAuth(current, next)) {
                    sent = AuthRedirects.withoutCredentials(sent)
                    manual = false
                }
                current = next
                return@repeat
            }
            // 416: the partial may already be the whole file (one that died between the last byte and
            // COMPLETED) or something else entirely; the caller reads Content-Range and decides.
            if (rangeStart > 0L && code == HTTP_RANGE_NOT_SATISFIABLE) return connection
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                connection.disconnect()
                throw Exception("HTTP Error after redirect: $code")
            }
            return connection
        }
        throw Exception("HTTP Error after redirect: too many redirects")
    }

    private fun open(
        address: String,
        rangeStart: Long,
        headers: Map<String, String>,
        ifRange: String?,
        configure: (HttpURLConnection) -> Unit,
        followRedirects: Boolean
    ): HttpURLConnection {
        val connection = URL(address).openConnection() as HttpURLConnection
        configure(connection)
        connection.instanceFollowRedirects = followRedirects

        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Wget/1.25.0")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Connection", "Keep-Alive")
        if (rangeStart > 0L) {
            connection.setRequestProperty("Range", "bytes=$rangeStart-")
            ifRange?.let { connection.setRequestProperty("If-Range", it) }
        }
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        connection.connectTimeout = Constants.CONNECTION_TIMEOUT_MS.toInt()
        connection.readTimeout = Constants.READ_TIMEOUT_MS.toInt()
        return connection
    }
}

/** What [HttpResume.plan] and [HttpResume.canResume] need from an answer (the last one, after redirects). */
fun HttpURLConnection.resumeResponse(): HttpResume.Response =
    HttpResume.Response(responseCode, getHeaderField("Content-Range"), contentLengthLong, contentType, getHeaderField("Accept-Ranges"))
