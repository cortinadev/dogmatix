package com.cortinadev.dogmatix.data.service

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
     */
    fun createConnection(
        downloadUrl: String,
        rangeStart: Long = 0L,
        headers: Map<String, String> = emptyMap(),
        ifRange: String? = null,
        configure: (HttpURLConnection) -> Unit = {}
    ): HttpURLConnection {
        val url = URL(downloadUrl)
        val connection = url.openConnection() as HttpURLConnection
        configure(connection)
        
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
        
        val redirectResponseCode = connection.responseCode
        // 416: the partial may already be the whole file (one that died between the last byte and
        // COMPLETED) or something else entirely; the caller reads Content-Range and decides.
        if (rangeStart > 0L && redirectResponseCode == HTTP_RANGE_NOT_SATISFIABLE) return connection
        if (redirectResponseCode != HttpURLConnection.HTTP_OK && redirectResponseCode != HttpURLConnection.HTTP_PARTIAL) {
            throw Exception("HTTP Error after redirect: $redirectResponseCode")
        }
        
        return connection
    }
}

/** What [HttpResume.plan] needs from an answer. */
fun HttpURLConnection.resumeResponse(): HttpResume.Response =
    HttpResume.Response(responseCode, getHeaderField("Content-Range"), contentLengthLong, contentType)
