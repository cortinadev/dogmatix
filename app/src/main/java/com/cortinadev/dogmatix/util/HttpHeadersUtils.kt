package com.cortinadev.dogmatix.util

import org.jsoup.Connection

object HttpHeadersUtils {
    
    fun configureBrowserHeaders(connection: Connection): Connection {
        return connection
            .userAgent(ScrapingConstants.USER_AGENT)
            .timeout(ScrapingConstants.CONNECTION_TIMEOUT_MS.toInt())
            .followRedirects(true)
            .maxBodySize(0)
    }
}
