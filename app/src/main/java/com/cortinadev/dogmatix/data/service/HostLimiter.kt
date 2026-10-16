package com.cortinadev.dogmatix.data.service

/**
 * "Downloads per server" in Settings: caps how many web downloads talk to the same host at once
 * (some servers throttle or ban clients that open many connections). The limit itself is applied
 * by the download queue ([DownloadQueueService]); torrents and the debrid route are not limited,
 * they do not depend on the file's host.
 */
object HostLimiter {
    /** Choices offered in Settings; 0 = no per-server limit. */
    val CHOICES = listOf(0, 1, 2, 3, 4)
}
