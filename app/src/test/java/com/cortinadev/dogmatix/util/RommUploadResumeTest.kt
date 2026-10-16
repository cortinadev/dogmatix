package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.RommUploadResume.Continue
import com.cortinadev.dogmatix.util.RommUploadResume.Fresh
import com.cortinadev.dogmatix.util.RommUploadResume.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

class RommUploadResumeTest {

    private val mib = 1024 * 1024
    private val now = 1_800_000_000_000L
    private val hour = 60L * 60 * 1000

    private val wanted = Session(
        serverUrl = "https://romm.lan", platformId = 4, serverName = "Game (USA).zip",
        totalSize = 20L * mib, chunkSize = 8 * mib, totalChunks = 3, signature = "a|1|2|3|4"
    )
    private val saved = wanted.copy(uploadId = "6f1c", confirmedChunks = 2, startedAt = now - 2 * hour)

    @Test
    fun `a matching session carries on from the next chunk`() {
        assertEquals(Continue("6f1c", 2), RommUploadResume.decide(saved, wanted, now))
        // Every chunk landed but /complete never answered: only that is left.
        assertEquals(Continue("6f1c", 3), RommUploadResume.decide(saved.copy(confirmedChunks = 3), wanted, now))
        assertEquals(Continue("6f1c", 0), RommUploadResume.decide(saved.copy(confirmedChunks = 0), wanted, now))
    }

    @Test
    fun `anything that changed the bytes or the target starts afresh`() {
        assertEquals(Fresh, RommUploadResume.decide(null, wanted, now))
        assertEquals(Fresh, RommUploadResume.decide(saved.copy(uploadId = null), wanted, now))
        assertEquals(Fresh, RommUploadResume.decide(saved.copy(uploadId = " "), wanted, now))
        assertEquals(Fresh, RommUploadResume.decide(saved.copy(signature = "a|1|2|9|4"), wanted, now))
        assertEquals(Fresh, RommUploadResume.decide(saved.copy(totalSize = 21L * mib), wanted, now))
        assertEquals(Fresh, RommUploadResume.decide(saved.copy(serverUrl = "https://other"), wanted, now))
        assertEquals(Fresh, RommUploadResume.decide(saved.copy(platformId = 5), wanted, now))
        assertEquals(Fresh, RommUploadResume.decide(saved.copy(serverName = "Game (Europe).zip"), wanted, now))
        assertEquals(Fresh, RommUploadResume.decide(saved.copy(chunkSize = 4 * mib, totalChunks = 5), wanted, now))
        assertEquals(Fresh, RommUploadResume.decide(saved.copy(confirmedChunks = 4), wanted, now))
    }

    @Test
    fun `sessions near RomM's 24 hours are not worth continuing`() {
        assertEquals(Continue("6f1c", 2), RommUploadResume.decide(saved.copy(startedAt = now - 22 * hour), wanted, now))
        assertEquals(Fresh, RommUploadResume.decide(saved.copy(startedAt = now - 23 * hour - 1), wanted, now))
        assertEquals(Fresh, RommUploadResume.decide(saved.copy(startedAt = now - 30 * hour), wanted, now))
        // A clock that went back: the age is unknown.
        assertEquals(Fresh, RommUploadResume.decide(saved.copy(startedAt = now + hour), wanted, now))
    }

    @Test
    fun `chunk arithmetic`() {
        assertEquals(3, RommUploadResume.chunkCount(20L * mib, 8 * mib))
        assertEquals(1, RommUploadResume.chunkCount(8L * mib, 8 * mib))
        assertEquals(16L * mib, RommUploadResume.offsetOf(2, 8 * mib))
        assertEquals(8 * mib, RommUploadResume.chunkLength(0, 20L * mib, 8 * mib))
        assertEquals(8 * mib, RommUploadResume.chunkLength(1, 20L * mib, 8 * mib))
        assertEquals(4 * mib, RommUploadResume.chunkLength(2, 20L * mib, 8 * mib))
        assertEquals(0, RommUploadResume.chunkLength(3, 20L * mib, 8 * mib))
        // Over 2 GiB: offsets stay in Long.
        assertEquals(600L * 8 * mib, RommUploadResume.offsetOf(600, 8 * mib))
    }

    @Test
    fun `reads the chunks complete says are missing`() {
        assertEquals(listOf(1, 4), RommUploadResume.missingChunks("""{"detail":"Missing chunks: [1, 4]"}"""))
        assertEquals(listOf(0), RommUploadResume.missingChunks("""{"detail":"Missing chunks: [0]"}"""))
        assertEquals(emptyList<Int>(), RommUploadResume.missingChunks("""{"detail":"Upload session not found or expired"}"""))
        assertEquals(emptyList<Int>(), RommUploadResume.missingChunks(null))
    }

    @Test
    fun `server answers that end or keep a session`() {
        assertTrue(RommUploadResume.sessionGone(404))
        assertFalse(RommUploadResume.sessionGone(400))
        assertTrue(RommUploadResume.alreadyOnServer(400, """{"detail":"File Game (USA).zip already exists"}"""))
        assertTrue(RommUploadResume.alreadyOnServer(409, "File x already exists in the game folder"))
        assertFalse(RommUploadResume.alreadyOnServer(400, """{"detail":"Platform not found"}"""))
        assertFalse(RommUploadResume.alreadyOnServer(500, "already exists"))
        for (code in listOf(null, 401, 403, 408, 429, 500, 502, 503)) assertTrue("$code", RommUploadResume.keepsSession(code))
        for (code in listOf(400, 404, 409, 413, 422)) assertFalse("$code", RommUploadResume.keepsSession(code))
    }

    @Test
    fun `complete gets more time for bigger games`() {
        assertEquals(5 * 60_000, RommUploadResume.completeTimeoutMs(0L))
        assertTrue(RommUploadResume.completeTimeoutMs(4L * 1024 * mib) > RommUploadResume.completeTimeoutMs(700L * mib))
        assertEquals(60 * 60_000, RommUploadResume.completeTimeoutMs(100L * 1024 * 1024 * mib))
    }

    @Test
    fun `only the network and the server are retried on their own`() {
        assertTrue(RommUploadResume.isTransient(SocketTimeoutException("read timed out")))
        assertTrue(RommUploadResume.isTransient(IOException("HTTP 503: Service Unavailable")))
        assertTrue(RommUploadResume.isTransient(SSLHandshakeException("Connection reset by peer")))
        assertFalse(RommUploadResume.isTransient(IOException("HTTP 400: bad request")))
        assertFalse(RommUploadResume.isTransient(IOException("HTTP 401: Unauthorized")))
        assertFalse(RommUploadResume.isTransient(SSLHandshakeException("Chain validation failed").apply {
            initCause(CertificateException("Trust anchor for certification path not found."))
        }))
    }
}
