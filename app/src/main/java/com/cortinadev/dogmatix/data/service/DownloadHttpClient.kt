package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.util.Constants
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadHttpClient @Inject constructor() {
    /** [rangeStart] > 0 asks for the rest of the file; the caller checks for 206 before appending. */
    fun createConnection(downloadUrl: String, rangeStart: Long = 0L, headers: Map<String, String> = emptyMap()): HttpURLConnection {
        val url = URL(downloadUrl)
        val connection = url.openConnection() as HttpURLConnection
        
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Wget/1.25.0")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Connection", "Keep-Alive")
        if (rangeStart > 0L) connection.setRequestProperty("Range", "bytes=$rangeStart-")
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        connection.connectTimeout = Constants.CONNECTION_TIMEOUT_MS.toInt()
        connection.readTimeout = Constants.READ_TIMEOUT_MS.toInt()
        
        val redirectResponseCode = connection.responseCode
        if (redirectResponseCode != HttpURLConnection.HTTP_OK && redirectResponseCode != HttpURLConnection.HTTP_PARTIAL) {
            throw Exception("HTTP Error after redirect: $redirectResponseCode")
        }
        
        return connection
    }
}
