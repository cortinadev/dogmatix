package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SourceReliabilityTest {

    private val now = 1_800_000_000_000L
    private val mb = 1024L * 1024
    private val day = 24L * 60 * 60 * 1000

    private fun stats(ok: Int, failed: Int, failure: DownloadFailure? = null, speed: Double = 0.0, at: Long = now): OriginStats {
        var s: OriginStats? = null
        repeat(ok) { s = SourceReliability.withAttempt(s, null, 0, 0, at - 10_000) }
        repeat(failed) { s = SourceReliability.withAttempt(s, failure ?: DownloadFailure.NETWORK, 0, 0, at) }
        return s!!.copy(bytesPerSecond = speed)
    }

    @Test
    fun `recent attempts weigh more than old ones`() {
        val old = SourceReliability.withAttempt(null, DownloadFailure.SERVER, 0, 0, now - 28 * day)
        val decayed = SourceReliability.decayed(old, now)
        assertEquals(0.25, decayed.failures, 0.001)
        val recovered = SourceReliability.withAttempt(old, null, 0, 0, now)
        assertTrue(SourceReliability.reliability(recovered, now) > SourceReliability.reliability(stats(1, 1), now))
    }

    @Test
    fun `speed is averaged over transfers long enough to tell`() {
        val first = SourceReliability.withAttempt(null, null, 10 * mb, 2_000, now)
        assertEquals(5.0 * mb, first.bytesPerSecond, 1.0)
        val tiny = SourceReliability.withAttempt(first, null, 1000, 10, now)
        assertEquals(first.bytesPerSecond, tiny.bytesPerSecond, 0.0)
        val slower = SourceReliability.withAttempt(first, DownloadFailure.NETWORK, 2 * mb, 2_000, now)
        assertEquals(5.0 * mb + 0.3 * (1.0 * mb - 5.0 * mb), slower.bytesPerSecond, 1.0)
        assertEquals(12 * mb, slower.totalBytes)
    }

    @Test
    fun `without history the order does not change`() {
        val urls = listOf("https://own.example/a.zip", "https://m1.example/a.zip", "https://m2.example/a.zip")
        assertEquals(urls, SourceReliability.order(urls, DownloadOrigins::forWeb, emptyMap(), now))
        assertEquals(urls, SourceReliability.order(urls, DownloadOrigins::forWeb, mapOf("other.example" to stats(3, 0)), now))
    }

    @Test
    fun `a recent hard failure goes last, reliability and then speed order the rest`() {
        val urls = listOf("https://own.example/a.zip", "https://m1.example/a.zip", "https://m2.example/a.zip")
        val gone = mapOf("own.example" to stats(5, 1, DownloadFailure.NOT_FOUND))
        assertEquals("https://own.example/a.zip", SourceReliability.order(urls, DownloadOrigins::forWeb, gone, now).last())
        assertTrue(SourceReliability.isDemoted(gone["own.example"], now))
        assertFalse(SourceReliability.isDemoted(gone["own.example"], now + day))
        // A transient failure is not a demotion: it only lowers the reliability.
        assertFalse(SourceReliability.isDemoted(stats(0, 1, DownloadFailure.TIMEOUT), now))

        val records = mapOf(
            "own.example" to stats(1, 1),
            "m1.example" to stats(4, 0, speed = 2.0 * mb),
            "m2.example" to stats(4, 0, speed = 6.0 * mb)
        )
        assertEquals(
            listOf("https://m2.example/a.zip", "https://m1.example/a.zip", "https://own.example/a.zip"),
            SourceReliability.order(urls, DownloadOrigins::forWeb, records, now)
        )
    }

    @Test
    fun `the device's own trouble is not the origin's`() {
        assertFalse(SourceReliability.countsAgainstOrigin(DownloadFailure.NO_SPACE))
        assertFalse(SourceReliability.countsAgainstOrigin(DownloadFailure.STORAGE))
        assertTrue(SourceReliability.countsAgainstOrigin(DownloadFailure.NOT_FOUND))
    }

    @Test
    fun `summary and speed text`() {
        assertNull(SourceReliability.summary(null, now))
        val s = stats(3, 1, speed = 4.1 * mb)
        val (percent, speed) = SourceReliability.summary(s, now)!!
        assertEquals(75, percent)
        assertEquals("4,1 MB/s", SourceReliability.speedText(speed!!, Locale.forLanguageTag("es")))
        assertEquals("4.1 MB/s", SourceReliability.speedText(speed, Locale.ENGLISH))
        assertEquals("12 MB/s", SourceReliability.speedText(12.4 * mb, Locale.ENGLISH))
        assertEquals("820 KB/s", SourceReliability.speedText(820.0 * 1024, Locale.ENGLISH))
    }

    @Test
    fun `origins of files and source addresses`() {
        val magnet = "magnet:?xt=urn:btih:C12FE1C06BBA254A9DC9F519B335AA7C1367A88A&dn=set"
        assertEquals("torrent:c12fe1c06bba254a9dc9f519b335aa7c1367a88a", DownloadOrigins.forTorrent(magnet))
        assertEquals(DownloadOrigins.forTorrent(magnet), DownloadOrigins.forSourceUrl(magnet, ""))
        assertEquals("torrent:https://x.example/set.torrent", DownloadOrigins.forSourceUrl("https://x.example/set.torrent", ""))
        assertEquals("myrient.example", DownloadOrigins.forSourceUrl("https://www.myrient.example/files/No-Intro/", ""))
        assertEquals("192.168.1.10", DownloadOrigins.forSourceUrl("romm://psp", "http://192.168.1.10:8090"))
        assertNull(DownloadOrigins.forSourceUrl("romm://psp", ""))
        assertEquals("debrid:torbox", DownloadOrigins.forDebrid("TORBOX"))
        assertEquals("myrient.example", DownloadOrigins.forFile("https://myrient.example/a/b.zip", null))
        assertEquals(DownloadOrigins.forTorrent(magnet), DownloadOrigins.forFile(magnet, magnet))
    }
}
