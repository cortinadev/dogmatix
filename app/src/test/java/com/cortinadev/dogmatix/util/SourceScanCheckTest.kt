package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.ContentType
import com.cortinadev.dogmatix.data.model.UrlEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceScanCheckTest {

    private val web = UrlEntry("https://myrient.example/files/No-Intro/Nintendo%20-%20Game%20Boy/")
    private val mirror = "https://mirror.example/gb/"
    private val magnet = UrlEntry("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Set", folders = listOf("Sony - PS2"))

    private fun previous(entry: UrlEntry, rows: Int = 10, fileCount: Int = 10, servedBy: String? = null) = SourceScanCheck.Previous(
        fingerprint = SourceScanCheck.fingerprint(entry), etag = "\"abc\"", lastModified = "Tue, 13 Oct 2026 10:00:00 GMT",
        servedBy = servedBy, contentHash = "hash", fileCount = fileCount, rows = rows
    )

    private fun file(name: String, url: String = web.url + name) =
        DownloadableFileEntity(name = name.substringBeforeLast('.'), fileName = name, consoleId = "gb", downloadUrl = url, fileSize = 1024)

    @Test
    fun `fingerprint ignores folder order, case and trailing slashes but not the content type`() {
        val a = UrlEntry("magnet:x", folders = listOf("Sony - PS2/", "nintendo 64"))
        val b = UrlEntry("magnet:x", folders = listOf("Nintendo 64", "sony - ps2"))
        assertEquals(SourceScanCheck.fingerprint(a), SourceScanCheck.fingerprint(b))
        assertNotEquals(SourceScanCheck.fingerprint(a), SourceScanCheck.fingerprint(b.copy(contentType = ContentType.MISCELLANEOUS)))
        assertNotEquals(SourceScanCheck.fingerprint(a), SourceScanCheck.fingerprint(b.copy(folders = listOf("Nintendo 64"))))
    }

    @Test
    fun `mirrors and the enabled flag do not change the fingerprint`() {
        assertEquals(SourceScanCheck.fingerprint(web), SourceScanCheck.fingerprint(web.copy(mirrors = listOf(mirror), enabled = false)))
    }

    @Test
    fun `previous rows are reused only when nothing about them changed`() {
        assertTrue(SourceScanCheck.canReuse(web, previous(web), force = false))
        assertFalse(SourceScanCheck.canReuse(web, previous(web), force = true))
        assertFalse(SourceScanCheck.canReuse(web, null, force = false))
        // Rows went missing since (an import cleared them, a console refresh failed half-way…).
        assertFalse(SourceScanCheck.canReuse(web, previous(web, rows = 3), force = false))
        // No scan of this source ever succeeded.
        assertFalse(SourceScanCheck.canReuse(web, previous(web, rows = 0, fileCount = -1), force = false))
        // Set up differently since.
        assertFalse(SourceScanCheck.canReuse(web.copy(contentType = ContentType.RETROACHIEVEMENTS), previous(web), force = false))
    }

    @Test
    fun `an empty source can be reused too`() {
        assertTrue(SourceScanCheck.canReuse(web, previous(web, rows = 0, fileCount = 0), force = false))
    }

    @Test
    fun `conditional headers only go to the address that gave the validators`() {
        val headers = SourceScanCheck.conditionalHeaders(web, previous(web), force = false, target = web.url)
        assertEquals("\"abc\"", headers["If-None-Match"])
        assertEquals("Tue, 13 Oct 2026 10:00:00 GMT", headers["If-Modified-Since"])
        assertTrue(SourceScanCheck.conditionalHeaders(web, previous(web), force = false, target = mirror).isEmpty())
        assertTrue(SourceScanCheck.conditionalHeaders(web, previous(web), force = true, target = web.url).isEmpty())
        // Last answered by the mirror: its validators are only good for the mirror.
        val viaMirror = previous(web, servedBy = mirror)
        assertTrue(SourceScanCheck.conditionalHeaders(web, viaMirror, force = false, target = web.url).isEmpty())
        assertEquals("\"abc\"", SourceScanCheck.conditionalHeaders(web, viaMirror, force = false, target = mirror)["If-None-Match"])
    }

    @Test
    fun `no validators, no conditional headers`() {
        val bare = previous(web).copy(etag = null, lastModified = " ")
        assertTrue(SourceScanCheck.conditionalHeaders(web, bare, force = false, target = web.url).isEmpty())
    }

    @Test
    fun `a torrent indexed before is skipped unless its folders change or the scan is forced`() {
        assertTrue(SourceScanCheck.canSkipTorrent(magnet, previous(magnet), force = false))
        assertFalse(SourceScanCheck.canSkipTorrent(magnet, previous(magnet), force = true))
        assertFalse(SourceScanCheck.canSkipTorrent(magnet.copy(folders = listOf("Sony - PS1")), previous(magnet), force = false))
        assertFalse(SourceScanCheck.canSkipTorrent(web, previous(web), force = false))
        val local = UrlEntry("/data/user/0/app/files/torrents/uploaded_1.torrent")
        assertTrue(SourceScanCheck.canSkipTorrent(local, previous(local), force = false))
    }

    @Test
    fun `content hash follows files, their order and their tags`() {
        val files = listOf(file("Tetris (World).zip"), file("Zelda (USA).zip"))
        val tags = listOf(listOf("World", "Game"), listOf("USA", "Game"))
        val hash = SourceScanCheck.contentHash(files, tags)
        assertEquals(hash, SourceScanCheck.contentHash(files.map { it.copy(id = 99) }, tags))
        assertNotEquals(hash, SourceScanCheck.contentHash(files.reversed(), tags.reversed()))
        assertNotEquals(hash, SourceScanCheck.contentHash(files, listOf(listOf("World"), listOf("USA", "Game"))))
        assertNotEquals(hash, SourceScanCheck.contentHash(listOf(files[0], files[1].copy(fileSize = 2048)), tags))
        assertNotEquals(hash, SourceScanCheck.contentHash(files.take(1), tags.take(1)))
    }

    @Test
    fun `same content is only same when the rows can be reused`() {
        val prev = previous(web).copy(contentHash = "h1")
        assertTrue(SourceScanCheck.sameContent(web, prev, force = false, hash = "h1"))
        assertFalse(SourceScanCheck.sameContent(web, prev, force = false, hash = "h2"))
        assertFalse(SourceScanCheck.sameContent(web, prev, force = true, hash = "h1"))
    }

    @Test
    fun `a file listed by a mirror keeps the main address`() {
        assertEquals(
            "https://myrient.example/files/No-Intro/Nintendo%20-%20Game%20Boy/Tetris%20%28World%29.zip",
            SourceScanCheck.primaryUrl("https://mirror.example/gb/Tetris%20(World).zip", mirror, web.url)
        )
        // Already under the main address (a relative link): left as it is.
        val own = web.url + "Tetris%20(World).zip"
        assertEquals(own, SourceScanCheck.primaryUrl(own, mirror, web.url))
    }

    @Test
    fun `sources are routed like the scan always did`() {
        assertEquals(SourceKind.TORRENT, SourceKind.of(magnet.url))
        assertEquals(SourceKind.TORRENT, SourceKind.of("/files/torrents/x.torrent"))
        assertEquals(SourceKind.ROMM, SourceKind.of("romm://psp"))
        assertEquals(SourceKind.WEB, SourceKind.of(web.url))
    }
}
