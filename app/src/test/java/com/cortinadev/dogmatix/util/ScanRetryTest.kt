package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ScanRetryTest {

    private val url = "https://myrient.example/files/"

    @Test
    fun `http answers are classified by status`() {
        assertEquals(ScanFailure.RATE_LIMITED, ScanRetry.classify(ListingHttpException(429, null, url)))
        assertEquals(ScanFailure.SERVER, ScanRetry.classify(ListingHttpException(503, 30_000, url)))
        assertEquals(ScanFailure.NOT_FOUND, ScanRetry.classify(ListingHttpException(404, null, url)))
        assertEquals(ScanFailure.DENIED, ScanRetry.classify(ListingHttpException(403, null, url)))
        assertEquals(ScanFailure.TIMEOUT, ScanRetry.classify(ListingHttpException(408, null, url)))
        assertEquals(ScanFailure.HTTP, ScanRetry.classify(ListingHttpException(418, null, url)))
        assertEquals(503, ScanRetry.httpCodeOf(RuntimeException("wrapped", ListingHttpException(503, null, url))))
    }

    @Test
    fun `network errors are told apart`() {
        assertEquals(ScanFailure.TIMEOUT, ScanRetry.classify(SocketTimeoutException("Read timed out")))
        assertEquals(ScanFailure.OFFLINE, ScanRetry.classify(UnknownHostException("myrient.example")))
        assertEquals(ScanFailure.CONNECTION, ScanRetry.classify(ConnectException("refused")))
        assertEquals(ScanFailure.CONNECTION, ScanRetry.classify(SocketException("Connection reset")))
        assertEquals(ScanFailure.SECURE, ScanRetry.classify(SSLHandshakeException("bad cert")))
        assertEquals(ScanFailure.CONNECTION, ScanRetry.classify(IOException("unexpected end of stream")))
    }

    @Test
    fun `listing problems and torrent timeouts have their own reason`() {
        assertEquals(ScanFailure.NO_LISTING, ScanRetry.classify(NoListingException(url)))
        assertEquals(ScanFailure.EMPTY, ScanRetry.classify(EmptyListingException(url)))
        assertEquals(ScanFailure.TORRENT_METADATA, ScanRetry.classify(TorrentMetadataTimeoutException("timed out")))
        assertEquals(ScanFailure.OTHER, ScanRetry.classify(IllegalStateException("RomM server not configured")))
    }

    @Test
    fun `status codes in other clients' messages are read`() {
        assertEquals(ScanFailure.DENIED, ScanRetry.classify(RuntimeException("HTTP 401 Unauthorized")))
        assertEquals(401, ScanRetry.httpCodeOf(RuntimeException("HTTP 401 Unauthorized")))
        assertNull(ScanRetry.httpCodeOf(RuntimeException("no code here")))
    }

    @Test
    fun `only passing troubles are retried`() {
        assertTrue(ScanRetry.isRetryable(ScanFailure.RATE_LIMITED))
        assertTrue(ScanRetry.isRetryable(ScanFailure.SERVER))
        assertTrue(ScanRetry.isRetryable(ScanFailure.TIMEOUT))
        assertTrue(ScanRetry.isRetryable(ScanFailure.CONNECTION))
        assertFalse(ScanRetry.isRetryable(ScanFailure.NOT_FOUND))
        assertFalse(ScanRetry.isRetryable(ScanFailure.DENIED))
        assertFalse(ScanRetry.isRetryable(ScanFailure.OFFLINE))
        assertFalse(ScanRetry.isRetryable(ScanFailure.NO_LISTING))
        assertNull(ScanRetry.nextDelayMs(1, ScanFailure.NOT_FOUND, null, 0.5))
    }

    @Test
    fun `waits grow, are capped and stop after the last attempt`() {
        // Without jitter (random 0) the wait is half the ceiling: 1, 2, 4 s…
        assertEquals(1_000L, ScanRetry.nextDelayMs(1, ScanFailure.SERVER, null, 0.0))
        assertEquals(2_000L, ScanRetry.nextDelayMs(2, ScanFailure.SERVER, null, 0.0))
        assertEquals(4_000L, ScanRetry.nextDelayMs(3, ScanFailure.SERVER, null, 0.0))
        assertNull(ScanRetry.nextDelayMs(ScanRetry.MAX_ATTEMPTS, ScanFailure.SERVER, null, 0.0))
        // Never past the cap, whatever the attempt number.
        val far = generateSequence(1) { it + 1 }.take(ScanRetry.MAX_ATTEMPTS - 1).mapNotNull { ScanRetry.nextDelayMs(it, ScanFailure.TIMEOUT, null, 0.999) }
        assertTrue(far.all { it <= ScanRetry.MAX_DELAY_MS })
    }

    @Test
    fun `jitter spreads a wait within its ceiling`() {
        val low = ScanRetry.nextDelayMs(2, ScanFailure.TIMEOUT, null, 0.0)!!
        val high = ScanRetry.nextDelayMs(2, ScanFailure.TIMEOUT, null, 0.999)!!
        assertTrue(high > low)
        assertTrue(high < 4_000L)
    }

    @Test
    fun `retry-after is honoured, and too long a wait is not waited for`() {
        val wait = ScanRetry.nextDelayMs(1, ScanFailure.RATE_LIMITED, 30_000, 0.5)!!
        assertTrue(wait in 30_000L..30_000L + ScanRetry.RETRY_AFTER_JITTER_MS)
        assertNull(ScanRetry.nextDelayMs(1, ScanFailure.RATE_LIMITED, ScanRetry.MAX_RETRY_AFTER_MS + 1, 0.5))
        assertEquals(0L, ScanRetry.nextDelayMs(1, ScanFailure.SERVER, 0, 0.0))
    }

    @Test
    fun `retry-after reads seconds and http dates`() {
        val now = 1_791_000_000_000L
        assertEquals(120_000L, ScanRetry.parseRetryAfter("120", now))
        assertEquals(0L, ScanRetry.parseRetryAfter("0", now))
        assertNull(ScanRetry.parseRetryAfter("-5", now))
        assertNull(ScanRetry.parseRetryAfter("soon", now))
        assertNull(ScanRetry.parseRetryAfter(null, now))
        // 2026-10-03T07:20:00Z and 90 s later.
        val at = java.time.Instant.parse("2026-10-03T07:20:00Z").toEpochMilli()
        assertEquals(90_000L, ScanRetry.parseRetryAfter("Sat, 03 Oct 2026 07:21:30 GMT", at))
        // A date already gone means "now".
        assertEquals(0L, ScanRetry.parseRetryAfter("Sat, 03 Oct 2026 07:00:00 GMT", at))
    }

    /** Matched by name, like the service's exception it stands for. */
    private class TorrentMetadataTimeoutException(message: String) : Exception(message)
}
