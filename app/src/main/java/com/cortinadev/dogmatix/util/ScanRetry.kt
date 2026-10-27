package com.cortinadev.dogmatix.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException

/** Why a source could not be scanned; the scan report shows it in words. */
enum class ScanFailure {
    /** HTTP 429: the server asks to slow down. */
    RATE_LIMITED,
    /** HTTP 5xx. */
    SERVER,
    TIMEOUT,
    /** The connection was cut half-way (reset, closed early). */
    CONNECTION,
    /** The server refused the connection: nothing listens there (a RomM server switched off, a wrong port). */
    UNREACHABLE,
    /** Unknown host or no route: no network, or a mistyped address. */
    OFFLINE,
    /** TLS handshake / certificate problem. */
    SECURE,
    /** HTTP 404 / 410. */
    NOT_FOUND,
    /** HTTP 401 / 403, often a bot check in front of the site. */
    DENIED,
    /** Any other HTTP answer that is not a listing. */
    HTTP,
    /** The page came back without a file table (the site changed, or an error / challenge page). */
    NO_LISTING,
    /** The listing had no files (and the source's last good scan had some, or there was none): nothing written. */
    EMPTY,
    /** A torrent's file list did not arrive in time. */
    TORRENT_METADATA,
    OTHER;

    companion object {
        fun fromName(name: String?): ScanFailure? = entries.firstOrNull { it.name == name }
    }
}

/** An HTTP answer that is not a listing, with how long the server asked to wait (`Retry-After`). */
class ListingHttpException(val code: Int, val retryAfterMs: Long?, url: String) : IOException("HTTP $code for $url")

/** A page that loaded but holds no file table. */
class NoListingException(url: String) : IOException("No file table at $url")

/** A listing with no files: probably an error page with a table. */
class EmptyListingException(url: String) : IOException("Empty listing at $url")

/**
 * Retries of one scan request. 429, 5xx and timeouts (also a connection cut half-way) are tried
 * again up to [MAX_ATTEMPTS] times, waiting what the server asked for (`Retry-After`, in seconds
 * or as an HTTP date) or else a growing pause with jitter, so a dozen sources of one server do
 * not all come back at the same instant. 404, 403, a refused connection and the like are final.
 */
object ScanRetry {
    /** Attempts per address, the first one included. */
    const val MAX_ATTEMPTS = 4
    const val BASE_DELAY_MS = 2_000L
    const val MAX_DELAY_MS = 30_000L
    /** A server asking for longer than this is reported, not waited for. */
    const val MAX_RETRY_AFTER_MS = 120_000L
    /** Spread added to a `Retry-After` wait, so the sources of one server do not return together. */
    const val RETRY_AFTER_JITTER_MS = 1_000L

    /**
     * "HTTP 503", "HTTP error 429"… in messages of clients that do not throw [ListingHttpException].
     * Never the `http://` of a URL in the message (`http://192.168…` is not "HTTP 192").
     */
    private val httpInMessage = Regex("""\bHTTP(?!S?://)\b\D{0,40}?([1-5]\d\d)\b""", RegexOption.IGNORE_CASE)

    fun classify(error: Throwable): ScanFailure {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        chain.firstNotNullOfOrNull { it as? ListingHttpException }?.let { return fromHttpStatus(it.code) }
        return when {
            chain.any { it is NoListingException || it.javaClass.simpleName == "UnsupportedMimeTypeException" } -> ScanFailure.NO_LISTING
            chain.any { it is EmptyListingException } -> ScanFailure.EMPTY
            chain.any { it.javaClass.simpleName == "TorrentMetadataTimeoutException" } -> ScanFailure.TORRENT_METADATA
            chain.any { it is SocketTimeoutException || it is TimeoutCancellationException } -> ScanFailure.TIMEOUT
            chain.any { it is UnknownHostException || it is NoRouteToHostException } -> ScanFailure.OFFLINE
            chain.any { it is SSLException } -> ScanFailure.SECURE
            // Refused: nobody listens there. Cut: the server was there and dropped the line.
            chain.any { it is ConnectException } -> ScanFailure.UNREACHABLE
            chain.any { it is SocketException } -> ScanFailure.CONNECTION
            else -> chain.firstNotNullOfOrNull { e -> httpInMessage.find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() }
                ?.let(::fromHttpStatus)
                ?: if (chain.any { it is IOException }) ScanFailure.CONNECTION else ScanFailure.OTHER
        }
    }

    /** The HTTP status behind [error], when there is one. */
    fun httpCodeOf(error: Throwable): Int? {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        return chain.firstNotNullOfOrNull { it as? ListingHttpException }?.code
            ?: chain.firstNotNullOfOrNull { e -> httpInMessage.find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() }
    }

    fun fromHttpStatus(code: Int): ScanFailure = when (code) {
        429 -> ScanFailure.RATE_LIMITED
        408 -> ScanFailure.TIMEOUT
        401, 403 -> ScanFailure.DENIED
        404, 410 -> ScanFailure.NOT_FOUND
        in 500..599 -> ScanFailure.SERVER
        else -> ScanFailure.HTTP
    }

    /** Worth another try within the same scan: the server or the line had a bad moment. */
    fun isRetryable(failure: ScanFailure): Boolean = when (failure) {
        ScanFailure.RATE_LIMITED, ScanFailure.SERVER, ScanFailure.TIMEOUT, ScanFailure.CONNECTION -> true
        else -> false
    }

    /**
     * How long to wait after [attemptsDone] failed attempts (1 = the first one failed), or null
     * to give up: not retryable, out of attempts, or the server asks for more than
     * [MAX_RETRY_AFTER_MS]. [random] in [0, 1) is the jitter.
     */
    fun nextDelayMs(attemptsDone: Int, failure: ScanFailure, retryAfterMs: Long?, random: Double): Long? {
        if (!isRetryable(failure) || attemptsDone >= MAX_ATTEMPTS) return null
        val r = random.coerceIn(0.0, 1.0)
        if (retryAfterMs != null && retryAfterMs >= 0) {
            if (retryAfterMs > MAX_RETRY_AFTER_MS) return null
            return retryAfterMs + (r * RETRY_AFTER_JITTER_MS).toLong()
        }
        // 2, 4, 8, 16 s… capped; half fixed, half random ("equal jitter").
        val ceiling = (BASE_DELAY_MS shl (attemptsDone - 1).coerceIn(0, 10)).coerceAtMost(MAX_DELAY_MS)
        return ceiling / 2 + (r * (ceiling / 2)).toLong()
    }

    /**
     * Runs [attempt] until it works or fails in a way not worth another try ([nextDelayMs] null):
     * then its last error is thrown. Before each new try, [onRetry] gets the error, its reason, the
     * wait and the attempts made so far, and does the waiting itself (a server that pushed back
     * makes its gate wait: [ScanHostGate.pushBack]; anything else, a plain delay). [random] gives
     * the jitter, in [0, 1). A cancellation is never retried.
     */
    suspend fun <T> retrying(
        random: () -> Double,
        onRetry: suspend (error: Exception, failure: ScanFailure, waitMs: Long, attemptsDone: Int) -> Unit,
        attempt: suspend () -> T
    ): T {
        var attemptsDone = 0
        while (true) {
            try {
                return attempt()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attemptsDone++
                val failure = classify(e)
                val wait = nextDelayMs(attemptsDone, failure, retryAfterOf(e), random()) ?: throw e
                onRetry(e, failure, wait, attemptsDone)
            }
        }
    }

    /** The `Retry-After` an HTTP error carried, in milliseconds. */
    fun retryAfterOf(error: Throwable): Long? =
        generateSequence(error) { it.cause }.take(8).firstNotNullOfOrNull { it as? ListingHttpException }?.retryAfterMs

    /** `Retry-After` as milliseconds from [nowMs]: a number of seconds, or an HTTP date. */
    fun parseRetryAfter(value: String?, nowMs: Long): Long? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        v.toLongOrNull()?.let { return if (it < 0) null else it * 1000 }
        return runCatching {
            val at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (at - nowMs).coerceAtLeast(0)
        }.getOrNull()
    }
}
