package com.cortinadev.dogmatix.util

object TorrentConstants {
    /** Default for the "Metadata timeout" setting (seconds); the registry reads the live value. */
    const val DEFAULT_METADATA_TIMEOUT_S = 20
    const val MIN_METADATA_TIMEOUT_S = 10
    const val MAX_METADATA_TIMEOUT_S = 180
    const val METADATA_POLL_INTERVAL_MS = 500L
    const val SESSION_FLAGS = 0x003L

    val DHT_BOOTSTRAP_NODES = listOf(
        "router.bittorrent.com" to 6881,
        "dht.transmissionbt.com" to 6881,
        "router.utorrent.com" to 6881,
        "dht.aelitis.com" to 6881
    )

    const val PRIORITY_DO_NOT_DOWNLOAD = 0
    const val PRIORITY_NORMAL = 4

    const val MAX_TRACKERS_PER_MAGNET = 4
}
