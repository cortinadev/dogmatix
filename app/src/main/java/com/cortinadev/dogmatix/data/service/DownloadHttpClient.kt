package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.util.Constants
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadHttpClient @Inject constructor() {
    fun createConnection(downloadUrl: String): HttpURLConnection {
        val url = URL(downloadUrl)
        val connection = url.openConnection() as HttpURLConnection
        
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Wget/1.25.0")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Connection", "Keep-Alive")
        connection.connectTimeout = Constants.CONNECTION_TIMEOUT_MS.toInt()
        connection.readTimeout = Constants.READ_TIMEOUT_MS.toInt()
        
        val redirectResponseCode = connection.responseCode
        if (redirectResponseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("HTTP Error after redirect: $redirectResponseCode")
        }
        
        return connection
    }
}
