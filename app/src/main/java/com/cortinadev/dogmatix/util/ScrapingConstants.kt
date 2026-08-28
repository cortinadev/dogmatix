package com.cortinadev.dogmatix.util

object ScrapingConstants {
    const val CONNECTION_TIMEOUT_MS = 30000L
    const val MAX_RETRIES = 3
    const val RETRY_DELAY_MS = 2000L
    const val REQUEST_DELAY_MS = 1000L
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    
    const val TABLE_SELECTOR = "table"
    const val LINK_CELL_SELECTOR = "td:first-child a"
    const val SIZE_CELL_SELECTOR = "td:nth-child(2)"
    
    const val PARENT_DIRECTORY = ".."
    const val CURRENT_DIRECTORY = "."
    const val UNKNOWN_FILE_SIZE = "?"
    const val DEFAULT_FILE_SIZE = "0B"
}
