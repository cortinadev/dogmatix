package com.cortinadev.dogmatix.util

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Why a download failed. [transient] failures may go away on their own (the network, the server,
 * a torrent without peers yet), so they are worth an automatic retry; the rest need the user
 * (a file gone from the server, a full disk, a folder without access, a debrid account problem).
 * [local] failures are the device's side (its storage, unpacking what arrived whole): they say
 * nothing about the origin the file came from.
 */
enum class DownloadFailure(val transient: Boolean, val local: Boolean = false) {
    NETWORK(true),
    TIMEOUT(true),
    SERVER(true),
    RATE_LIMITED(true),
    NOT_FOUND(false),
    DENIED(false),
    HTTP(false),
    NO_SPACE(false, local = true),
    STORAGE(false, local = true),
    INCOMPLETE(true),
    TORRENT_METADATA(true),
    TORRENT(false),
    DEBRID_ACCOUNT(false),
    DEBRID(true),
    UNKNOWN(true),
    /** The server sent something else (a web page, an error document) where the file was expected. */
    WRONG_CONTENT(false),
    /** The archive arrived but could not be unpacked (damaged, unsupported, unsafe paths); it is kept, and a retry unpacks it again. */
    EXTRACTION(false, local = true),
    /** Unpacking ran out of room (in the app's cache or in the folder); the archive is kept. */
    EXTRACTION_NO_SPACE(false, local = true),
    /** The app lost its grant to the folder (revoked, or the folder was deleted or moved away). */
    PERMISSION_LOST(false, local = true),
    /** The storage holding the folder is not available (SD card removed, unmounted or failing). */
    STORAGE_GONE(false, local = true)
}

/** Works out a [DownloadFailure] from what the download code throws (its messages are ours or the JDK's). */
object DownloadFailures {

    /** "HTTP Error after redirect: 404", "HTTP 503", "HTTP error code: 429"… */
    private val httpCode = Regex("""\bHTTP\b\D{0,40}?([1-5]\d\d)\b""", RegexOption.IGNORE_CASE)

    fun classify(error: Throwable): DownloadFailure {
        var current: Throwable? = error
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            classifyOne(current)?.let { return it }
            current = current.cause
        }
        return DownloadFailure.UNKNOWN
    }

    private fun classifyOne(e: Throwable): DownloadFailure? {
        val message = e.message.orEmpty()
        return when {
            e is UnexpectedContentException -> DownloadFailure.WRONG_CONTENT
            e is ArchiveExtractionException -> e.failure
            e.javaClass.simpleName == "DebridAuthException" || message.contains("premium", ignoreCase = true) ->
                DownloadFailure.DEBRID_ACCOUNT
            message.contains("ENOSPC") || message.contains("No space left", ignoreCase = true) -> DownloadFailure.NO_SPACE
            e is SocketTimeoutException -> DownloadFailure.TIMEOUT
            e is UnknownHostException || e is ConnectException || e is NoRouteToHostException ||
                e is SSLException || e is SocketException -> DownloadFailure.NETWORK
            else -> httpCode.find(message)?.groupValues?.get(1)?.toIntOrNull()?.let(::fromHttpStatus)
                // SAF and the kernel: a grant lost, a card pulled out (network errors were told apart above).
                ?: StorageProblems.problemOf(e)?.let(::fromStorageProblem)
                ?: when {
                    message.contains("Download directory", ignoreCase = true) ||
                        message.contains("Failed to create", ignoreCase = true) ||
                        message.contains("output stream", ignoreCase = true) -> DownloadFailure.STORAGE
                    message.contains("Incomplete torrent data", ignoreCase = true) -> DownloadFailure.INCOMPLETE
                    message.contains("did not finish fetching", ignoreCase = true) -> DownloadFailure.DEBRID
                    // Anything else the network stack throws: a reset, a stream cut short…
                    e is IOException -> DownloadFailure.NETWORK
                    else -> null
                }
        }
    }

    fun fromStorageProblem(problem: StorageProblem): DownloadFailure = when (problem) {
        StorageProblem.NO_SPACE -> DownloadFailure.NO_SPACE
        StorageProblem.PERMISSION_LOST -> DownloadFailure.PERMISSION_LOST
        StorageProblem.VOLUME_GONE -> DownloadFailure.STORAGE_GONE
    }

    fun fromHttpStatus(code: Int): DownloadFailure = when (code) {
        404, 410 -> DownloadFailure.NOT_FOUND
        401, 403 -> DownloadFailure.DENIED
        408 -> DownloadFailure.TIMEOUT
        429 -> DownloadFailure.RATE_LIMITED
        in 500..599 -> DownloadFailure.SERVER
        else -> DownloadFailure.HTTP
    }

    /** Reads back what [DownloadFailure.name] stored; null for nothing or an unknown name. */
    fun fromName(name: String?): DownloadFailure? =
        name?.let { stored -> DownloadFailure.entries.firstOrNull { it.name == stored } }
}

/** When a failed download is retried on its own ("Automatic retries" in Settings). */
object AutoRetryPolicy {
    /** Choices offered in Settings: how many automatic retries, 0 = off. */
    val ATTEMPT_CHOICES = listOf(0, 1, 3, 5)
    const val DEFAULT_ATTEMPTS = 3

    /** Wait before each retry: 1, 5 and 15 minutes, then 30 and 60. */
    private val delaysMinutes = listOf(1L, 5L, 15L, 30L, 60L)

    fun delayMs(attemptsDone: Int): Long = delaysMinutes[attemptsDone.coerceIn(0, delaysMinutes.lastIndex)] * 60_000L

    /** Unknown failures count as transient: most of what we cannot name is the network. */
    fun shouldRetry(failure: DownloadFailure?, attemptsDone: Int, maxAttempts: Int): Boolean =
        maxAttempts > 0 && attemptsDone < maxAttempts && (failure?.transient ?: true)
}
