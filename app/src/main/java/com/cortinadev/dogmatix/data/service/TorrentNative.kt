package com.cortinadev.dogmatix.data.service

/**
 * The one way in for the libtorrent4j calls that can run OpenSSL on our own thread: parsing a
 * torrent (its info-hash is a SHA-1, and a SHA-256 for v2) and starting the session (its TLS
 * contexts).
 *
 * libtorrent4j 2.1.0-39 ships OpenSSL 3.5.5 built without thread support (`crypto/threads_none.c`:
 * its locks and run-once do nothing), so two threads setting OpenSSL up at the same time race on
 * its global tables. In a process that had not used OpenSSL yet, the full rescan after an import
 * parsed two cached torrents at once and died with SIGSEGV in `ossl_provider_doall_activated`.
 * Through here those calls never overlap, so the first setup is single-threaded; libtorrent's own
 * threads only exist once the session has started, which also goes through here.
 */
object TorrentNative {
    private val lock = Any()

    fun <T> serialized(block: () -> T): T = synchronized(lock, block)
}
