package com.cortinadev.dogmatix.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

@OptIn(ExperimentalCoroutinesApi::class)
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
        // Nobody listening (a RomM server switched off) is not the same as a line cut half-way.
        assertEquals(ScanFailure.UNREACHABLE, ScanRetry.classify(ConnectException("failed to connect to /192.168.1.10 (port 8090): ECONNREFUSED")))
        assertEquals(ScanFailure.UNREACHABLE, ScanRetry.classify(IOException("RomM request failed", ConnectException("refused"))))
        assertEquals(ScanFailure.CONNECTION, ScanRetry.classify(SocketException("Connection reset")))
        assertEquals(ScanFailure.CONNECTION, ScanRetry.classify(EOFException("unexpected end of stream")))
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
        // The address in an "empty listing" message is not a status code (seen as "(HTTP 192)" on the K56).
        assertNull(ScanRetry.httpCodeOf(RuntimeException("Empty listing at http://192.168.50.235:8765/apache/")))
        assertNull(ScanRetry.httpCodeOf(RuntimeException("No listing at https://192.168.50.235/x")))
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
        assertFalse(ScanRetry.isRetryable(ScanFailure.UNREACHABLE))
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

    @Test
    fun `a 304 nobody asked for is an unexpected answer, said in words, and not retried`() {
        val e = ListingHttpException(304, null, url)
        assertEquals(ScanFailure.HTTP, ScanRetry.classify(e))
        assertEquals(304, ScanRetry.httpCodeOf(e))
        assertNull(ScanRetry.nextDelayMs(1, ScanRetry.classify(e), null, 0.0))
    }

    @Test
    fun `a time-out inside a coroutine counts as a time-out`() = runTest {
        val e = runCatching { withTimeout(10) { delay(1_000) } }.exceptionOrNull()!!
        assertEquals(ScanFailure.TIMEOUT, ScanRetry.classify(e))
    }

    // ---- The retry loop -------------------------------------------------------------------------

    private class Run(val result: Result<String>, val waits: List<Long>, val attempts: Int)

    /** Runs the loop over [outcomes] in order (null = the listing came), waiting in virtual time. */
    private suspend fun retry(vararg outcomes: Exception?, random: Double = 0.0): Run {
        val waits = mutableListOf<Long>()
        var attempts = 0
        val result = runCatching {
            ScanRetry.retrying(random = { random }, onRetry = { _, _, wait, _ -> waits += wait; delay(wait) }) {
                val outcome = outcomes.getOrNull(attempts++)
                if (outcome != null) throw outcome
                "listing"
            }
        }
        return Run(result, waits, attempts)
    }

    @Test
    fun `a 503 is tried again with growing waits until the listing comes`() = runTest {
        val run = retry(ListingHttpException(503, null, url), ListingHttpException(503, null, url), null)
        assertEquals("listing", run.result.getOrNull())
        assertEquals(3, run.attempts)
        assertEquals(listOf(1_000L, 2_000L), run.waits)
        assertEquals(3_000L, currentTime)
    }

    @Test
    fun `retry-after in seconds or as a date is what is waited`() = runTest {
        val inSeconds = retry(ListingHttpException(503, ScanRetry.parseRetryAfter("30", 0), url), null)
        assertEquals(listOf(30_000L), inSeconds.waits)
        val now = java.time.Instant.parse("2026-10-03T07:20:00Z").toEpochMilli()
        val asDate = retry(ListingHttpException(429, ScanRetry.parseRetryAfter("Sat, 03 Oct 2026 07:20:45 GMT", now), url), null)
        assertEquals(listOf(45_000L), asDate.waits)
        assertEquals("listing", asDate.result.getOrNull())
    }

    @Test
    fun `a server asking for more than two minutes is reported, not waited for`() = runTest {
        val error = ListingHttpException(429, 180_000, url)
        val run = retry(error, null)
        assertSame(error, run.result.exceptionOrNull())
        assertEquals(1, run.attempts)
        assertTrue(run.waits.isEmpty())
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a 429 without retry-after waits a growing, bounded, jittered pause`() = runTest {
        val error = ListingHttpException(429, null, url)
        val run = retry(error, error, error, null, random = 0.999)
        assertEquals("listing", run.result.getOrNull())
        assertEquals(3, run.waits.size)
        // Equal jitter: half the ceiling fixed, half random (2, 4, 8 s ceilings).
        assertTrue(run.waits[0] in 1_000L until 2_000L)
        assertTrue(run.waits[1] in 2_000L until 4_000L)
        assertTrue(run.waits[2] in 4_000L until 8_000L)
    }

    @Test
    fun `final answers are not tried again`() = runTest {
        for (error in listOf(ListingHttpException(404, null, url), ListingHttpException(403, null, url), ConnectException("refused"), NoListingException(url))) {
            val run = retry(error, null)
            assertSame(error, run.result.exceptionOrNull())
            assertEquals(1, run.attempts)
        }
    }

    @Test
    fun `time-outs and cut connections are tried again`() = runTest {
        assertEquals("listing", retry(SocketTimeoutException("Read timed out"), null).result.getOrNull())
        assertEquals("listing", retry(SocketException("Connection reset"), null).result.getOrNull())
    }

    @Test
    fun `after the last attempt the last error is thrown`() = runTest {
        val errors = (1..ScanRetry.MAX_ATTEMPTS).map { ListingHttpException(500 + it, null, url) }
        val run = retry(*errors.toTypedArray(), null)
        assertSame(errors.last(), run.result.exceptionOrNull())
        assertEquals(ScanRetry.MAX_ATTEMPTS, run.attempts)
        assertEquals(ScanRetry.MAX_ATTEMPTS - 1, run.waits.size)
    }

    @Test
    fun `a cancellation is never retried`() = runTest {
        val run = retry(CancellationException("scan stopped"), null)
        assertTrue(run.result.exceptionOrNull() is CancellationException)
        assertEquals(1, run.attempts)
    }

    @Test
    fun `retry-after is read through wrapping exceptions`() {
        assertEquals(5_000L, ScanRetry.retryAfterOf(IOException("wrapped", ListingHttpException(503, 5_000, url))))
        assertNull(ScanRetry.retryAfterOf(IOException("no http here")))
    }

    /** Matched by name, like the service's exception it stands for. */
    private class TorrentMetadataTimeoutException(message: String) : Exception(message)
}
