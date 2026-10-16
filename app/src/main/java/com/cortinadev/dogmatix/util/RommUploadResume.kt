package com.cortinadev.dogmatix.util

/**
 * When an upload to RomM can carry on where it stopped. RomM (5.x, `endpoints/roms/upload.py`)
 * keeps a chunked upload as a session in Redis for 24 hours after `/start` (sending chunks does not
 * extend it), stores each chunk as its own file, accepts the same chunk again (it overwrites it) and
 * answers 404 once the session is gone (expired, cancelled or already completed). So the app
 * remembers the session id and how many chunks the server acknowledged, and sends the rest. Pure,
 * for the tests.
 */
object RommUploadResume {

    /** Bytes per PUT: RomM refuses chunks over 64 MiB; 8 MiB keeps memory and a lost chunk small. */
    const val CHUNK_SIZE = 8 * 1024 * 1024

    /** RomM drops an upload session this long after `/start`, however busy it was. */
    const val SESSION_TTL_MS = 24L * 60 * 60 * 1000

    /** Not worth continuing a session this close to its end: it could expire half-way. */
    private const val SAFETY_MARGIN_MS = 60L * 60 * 1000

    /** One upload as the app records it (and as it would start now, with [uploadId] null). */
    data class Session(
        val serverUrl: String,
        val platformId: Int,
        val serverName: String,
        val totalSize: Long,
        val chunkSize: Int,
        val totalChunks: Int,
        /** The files and their checksums ([RommUploadSources.signature]): same signature, same bytes. */
        val signature: String,
        val uploadId: String? = null,
        /** Chunks acknowledged from the first: the index of the next one to send. */
        val confirmedChunks: Int = 0,
        val startedAt: Long = 0L
    )

    sealed interface Start
    /** Open a new session and send everything. */
    data object Fresh : Start
    /** Keep sending [uploadId] from chunk [fromChunk] (== the chunk count: only `/complete` is left). */
    data class Continue(val uploadId: String, val fromChunk: Int) : Start

    fun decide(saved: Session?, wanted: Session, now: Long): Start {
        val id = saved?.uploadId?.takeIf { it.isNotBlank() } ?: return Fresh
        val same = saved.serverUrl == wanted.serverUrl && saved.platformId == wanted.platformId &&
            saved.serverName == wanted.serverName && saved.totalSize == wanted.totalSize &&
            saved.chunkSize == wanted.chunkSize && saved.totalChunks == wanted.totalChunks &&
            saved.signature == wanted.signature
        if (!same || saved.confirmedChunks !in 0..saved.totalChunks) return Fresh
        val age = now - saved.startedAt
        if (age < 0 || age > SESSION_TTL_MS - SAFETY_MARGIN_MS) return Fresh
        return Continue(id, saved.confirmedChunks)
    }

    fun chunkCount(totalSize: Long, chunkSize: Int): Int = RommPlatformMapper.chunkCount(totalSize, chunkSize)

    fun offsetOf(index: Int, chunkSize: Int): Long = index.toLong() * chunkSize

    /** Bytes in chunk [index]: [chunkSize], except for a shorter last one. */
    fun chunkLength(index: Int, totalSize: Long, chunkSize: Int): Int =
        (totalSize - offsetOf(index, chunkSize)).coerceIn(0L, chunkSize.toLong()).toInt()

    /** RomM's `/complete` answer when chunks are missing: `{"detail":"Missing chunks: [1, 4]"}`. */
    fun missingChunks(body: String?): List<Int> {
        val list = body?.let { Regex("""Missing chunks:\s*\[([^\]]*)]""").find(it) }?.groupValues?.get(1) ?: return emptyList()
        return list.split(',').mapNotNull { it.trim().toIntOrNull() }.distinct()
    }

    /** The server no longer knows the session (expired, cancelled, or completed by a try whose answer got lost). */
    fun sessionGone(httpCode: Int): Boolean = httpCode == 404

    /** `/start` refused because the platform folder already holds the file: an earlier try landed. */
    fun alreadyOnServer(httpCode: Int, body: String?): Boolean =
        (httpCode == 400 || httpCode == 409) && body?.contains("already exists", ignoreCase = true) == true

    /**
     * Whether the server-side session is still worth keeping after a failure with [httpCode]
     * (null = no answer: the network, a refused certificate). A refused token (401/403) keeps it
     * too, so the upload carries on once the user fixes it; any other 4xx means start afresh.
     */
    fun keepsSession(httpCode: Int?): Boolean =
        httpCode == null || httpCode == 401 || httpCode == 403 || httpCode == 408 || httpCode == 429 || httpCode >= 500

    /** `/complete` copies every chunk into place before answering: give big games time. */
    fun completeTimeoutMs(totalSize: Long): Int =
        (5 * 60_000L + totalSize / (2L * 1024 * 1024) * 1000L).coerceAtMost(60 * 60_000L).toInt()

    /**
     * Whether a failed try is worth repeating on its own: the network or the server, not a
     * refused certificate, a refused request (4xx) or a missing file.
     */
    fun isTransient(error: Throwable): Boolean =
        !CertPin.isCertificateFailure(error) && DownloadFailures.classify(error).transient
}
