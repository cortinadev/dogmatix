package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class DownloadFailuresTest {

    @Test
    fun `http status in our own messages`() {
        assertEquals(DownloadFailure.NOT_FOUND, DownloadFailures.classify(Exception("HTTP Error after redirect: 404")))
        assertEquals(DownloadFailure.SERVER, DownloadFailures.classify(Exception("HTTP Error after redirect: 503")))
        assertEquals(DownloadFailure.RATE_LIMITED, DownloadFailures.classify(Exception("HTTP error code: 429")))
        assertEquals(DownloadFailure.DENIED, DownloadFailures.classify(Exception("HTTP 403 Forbidden")))
        assertEquals(DownloadFailure.HTTP, DownloadFailures.classify(Exception("HTTP Error after redirect: 400")))
    }

    @Test
    fun `network errors and their causes`() {
        assertEquals(DownloadFailure.NETWORK, DownloadFailures.classify(UnknownHostException("myrient.erista.me")))
        assertEquals(DownloadFailure.TIMEOUT, DownloadFailures.classify(SocketTimeoutException("Read timed out")))
        assertEquals(DownloadFailure.NETWORK, DownloadFailures.classify(IOException("unexpected end of stream")))
        assertEquals(DownloadFailure.TIMEOUT, DownloadFailures.classify(Exception("wrapped", SocketTimeoutException())))
    }

    @Test
    fun `storage and disk`() {
        assertEquals(DownloadFailure.NO_SPACE, DownloadFailures.classify(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(DownloadFailure.STORAGE, DownloadFailures.classify(Exception("Download directory not configured or no longer accessible.")))
        assertEquals(DownloadFailure.STORAGE, DownloadFailures.classify(Exception("Failed to create file in storage.")))
        assertEquals(DownloadFailure.INCOMPLETE, DownloadFailures.classify(Exception("Incomplete torrent data for x: 0/10 bytes (disk full or write error)")))
    }

    @Test
    fun `debrid account problems are not retried`() {
        val failure = DownloadFailures.classify(Exception("Real-Debrid: premium account required"))
        assertEquals(DownloadFailure.DEBRID_ACCOUNT, failure)
        assertFalse(failure.transient)
        assertEquals(DownloadFailure.DEBRID, DownloadFailures.classify(Exception("TorBox did not finish fetching Game.zip in time")))
    }

    @Test
    fun `unknown is the fallback and names round-trip`() {
        assertEquals(DownloadFailure.UNKNOWN, DownloadFailures.classify(IllegalStateException("odd")))
        assertEquals(DownloadFailure.TIMEOUT, DownloadFailures.fromName("TIMEOUT"))
        assertNull(DownloadFailures.fromName("NOPE"))
        assertNull(DownloadFailures.fromName(null))
    }

    @Test
    fun `retry policy`() {
        assertTrue(AutoRetryPolicy.shouldRetry(DownloadFailure.NETWORK, attemptsDone = 0, maxAttempts = 3))
        assertTrue(AutoRetryPolicy.shouldRetry(null, attemptsDone = 2, maxAttempts = 3))
        assertFalse(AutoRetryPolicy.shouldRetry(DownloadFailure.NETWORK, attemptsDone = 3, maxAttempts = 3))
        assertFalse(AutoRetryPolicy.shouldRetry(DownloadFailure.NOT_FOUND, attemptsDone = 0, maxAttempts = 3))
        assertFalse(AutoRetryPolicy.shouldRetry(DownloadFailure.NETWORK, attemptsDone = 0, maxAttempts = 0))
        assertEquals(60_000L, AutoRetryPolicy.delayMs(0))
        assertEquals(5 * 60_000L, AutoRetryPolicy.delayMs(1))
        assertEquals(15 * 60_000L, AutoRetryPolicy.delayMs(2))
        assertEquals(60 * 60_000L, AutoRetryPolicy.delayMs(9))
    }
}
