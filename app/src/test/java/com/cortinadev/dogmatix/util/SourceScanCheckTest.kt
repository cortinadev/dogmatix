package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.ContentType
import com.cortinadev.dogmatix.data.model.ScrapeRecipe
import com.cortinadev.dogmatix.data.model.UrlEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceScanCheckTest {

    private val web = UrlEntry("https://myrient.example/files/No-Intro/Nintendo%20-%20Game%20Boy/")
    private val mirror = "https://mirror.example/gb/"
    private val magnet = UrlEntry("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Set", folders = listOf("Sony - PS2"))
    /** A torrent's file list in the session, the metadata cache or a local file; or only in the swarm. */
    private val atHand = { true }
    private val notAtHand = { false }

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
    fun `web rows built by the older listing parser are read in full once`() {
        // As 1.3 stored it: no listing version.
        val old = previous(web).copy(fingerprint = "v1|GAME|")
        assertFalse(SourceScanCheck.canReuse(web, old, force = false))
        assertTrue(SourceScanCheck.conditionalHeaders(web, old, force = false, target = web.url).isEmpty())
        assertFalse(SourceScanCheck.sameContent(web, old.copy(contentHash = "h1"), force = false, hash = "h1", files = 10))
        // Scanned once with this version: incremental again.
        assertEquals("\"abc\"", SourceScanCheck.conditionalHeaders(web, previous(web), force = false, target = web.url)["If-None-Match"])
    }

    @Test
    fun `torrents and RomM keep the fingerprint they had, so they are still skipped`() {
        assertEquals("v1|GAME|sony - ps2", SourceScanCheck.fingerprint(magnet))
        assertTrue(SourceScanCheck.canSkipTorrent(magnet, previous(magnet).copy(fingerprint = "v1|GAME|sony - ps2"), force = false, listAtHand = atHand))
        assertEquals("v1|GAME|", SourceScanCheck.fingerprint(UrlEntry("romm://snes")))
        assertEquals("v1|GAME|", SourceScanCheck.fingerprint(UrlEntry("/data/user/0/app/files/torrents/uploaded_1.torrent")))
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
        assertTrue(SourceScanCheck.canSkipTorrent(magnet, previous(magnet), force = false, listAtHand = atHand))
        assertFalse(SourceScanCheck.canSkipTorrent(magnet, previous(magnet), force = true, listAtHand = atHand))
        assertFalse(SourceScanCheck.canSkipTorrent(magnet.copy(folders = listOf("Sony - PS1")), previous(magnet), force = false, listAtHand = atHand))
        assertFalse(SourceScanCheck.canSkipTorrent(web, previous(web), force = false, listAtHand = atHand))
        val local = UrlEntry("/data/user/0/app/files/torrents/uploaded_1.torrent")
        assertTrue(SourceScanCheck.canSkipTorrent(local, previous(local), force = false, listAtHand = atHand))
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
        assertTrue(SourceScanCheck.sameContent(web, prev, force = false, hash = "h1", files = 10))
        assertFalse(SourceScanCheck.sameContent(web, prev, force = false, hash = "h2", files = 10))
        assertFalse(SourceScanCheck.sameContent(web, prev, force = true, hash = "h1", files = 10))
    }

    @Test
    fun `a source left without rows is written again, whatever its last result says`() {
        val files = listOf(file("Tetris (World).zip"), file("Zelda (USA).zip"))
        val hash = SourceScanCheck.contentHash(files, listOf(listOf("World"), listOf("USA")))
        // The rows are gone (an import cleared them, a run was cut short…) and the record says 2.
        assertFalse(SourceScanCheck.sameContent(web, previous(web, rows = 0, fileCount = 2).copy(contentHash = hash), force = false, hash = hash, files = 2))
        // A record that says "empty" next to a listing that is not: written, never "no changes".
        assertFalse(SourceScanCheck.sameContent(web, previous(web, rows = 0, fileCount = 0).copy(contentHash = hash), force = false, hash = hash, files = 2))
        // Rows there and the same files: nothing to write.
        assertTrue(SourceScanCheck.sameContent(web, previous(web, rows = 2, fileCount = 2).copy(contentHash = hash), force = false, hash = hash, files = 2))
        // Empty before, empty now: nothing to write either.
        val empty = SourceScanCheck.contentHash(emptyList(), emptyList())
        assertTrue(SourceScanCheck.sameContent(web, previous(web, rows = 0, fileCount = 0).copy(contentHash = empty), force = false, hash = empty, files = 0))
    }

    @Test
    fun `a torrent that gave no files is read again only when its file list is at hand`() {
        val none = previous(magnet, rows = 0, fileCount = 0)
        // In the session, the metadata cache or a local file: read again (never from the swarm).
        assertEquals(SourceScanCheck.Reread.NO_ROWS, SourceScanCheck.torrentReread(magnet, none, force = false, listAtHand = atHand))
        assertFalse(SourceScanCheck.canSkipTorrent(magnet, none, force = false, listAtHand = atHand))
        // Only in the swarm: kept as it is (its info-hash fixes the content, the fingerprint the set-up).
        assertNull(SourceScanCheck.torrentReread(magnet, none, force = false, listAtHand = notAtHand))
        assertTrue(SourceScanCheck.canSkipTorrent(magnet, none, force = false, listAtHand = notAtHand))
        // Rows lost since the last scan (12 written, none left): read again, from the swarm if need be.
        assertEquals(SourceScanCheck.Reread.ROWS_CHANGED,
            SourceScanCheck.torrentReread(magnet, previous(magnet, rows = 0, fileCount = 12), force = false, listAtHand = notAtHand))
        assertFalse(SourceScanCheck.canSkipTorrent(magnet, previous(magnet, rows = 0, fileCount = 12), force = false, listAtHand = notAtHand))
        // Never read well: tried again.
        assertEquals(SourceScanCheck.Reread.NEVER_READ,
            SourceScanCheck.torrentReread(magnet, previous(magnet, rows = 0, fileCount = -1), force = false, listAtHand = notAtHand))
        // "Full rescan" reads everything.
        assertEquals(SourceScanCheck.Reread.FORCED, SourceScanCheck.torrentReread(magnet, none, force = true, listAtHand = notAtHand))
        assertTrue(SourceScanCheck.canSkipTorrent(magnet, previous(magnet, rows = 12, fileCount = 12), force = false, listAtHand = atHand))
    }

    @Test
    fun `a torrent with rows is decided without asking for its file list`() {
        var asked = 0
        val ask = { asked++; true }
        assertNull(SourceScanCheck.torrentReread(magnet, previous(magnet, rows = 12, fileCount = 12), force = false, listAtHand = ask))
        assertEquals(SourceScanCheck.Reread.NO_RECORD, SourceScanCheck.torrentReread(magnet, null, force = false, listAtHand = ask))
        assertEquals(SourceScanCheck.Reread.SET_UP_CHANGED,
            SourceScanCheck.torrentReread(magnet.copy(folders = listOf("Sony - PS1")), previous(magnet), force = false, listAtHand = ask))
        assertEquals(SourceScanCheck.Reread.ROWS_CHANGED,
            SourceScanCheck.torrentReread(magnet, previous(magnet, rows = 11, fileCount = 12), force = false, listAtHand = ask))
        assertEquals(SourceScanCheck.Reread.FORCED, SourceScanCheck.torrentReread(magnet, previous(magnet), force = true, listAtHand = ask))
        assertEquals(0, asked)
    }

    @Test
    fun `a listing gives the same reasons, and an empty one stays reusable`() {
        assertNull(SourceScanCheck.rereadReason(web, previous(web), force = false))
        assertNull(SourceScanCheck.rereadReason(web, previous(web, rows = 0, fileCount = 0), force = false))
        assertEquals(SourceScanCheck.Reread.ROWS_CHANGED, SourceScanCheck.rereadReason(web, previous(web, rows = 3), force = false))
        assertEquals(SourceScanCheck.Reread.SET_UP_CHANGED, SourceScanCheck.rereadReason(web, previous(web).copy(fingerprint = "v1|GAME|"), force = false))
        assertEquals(SourceScanCheck.Reread.NO_RECORD, SourceScanCheck.rereadReason(web, null, force = false))
        assertEquals(SourceScanCheck.Reread.FORCED, SourceScanCheck.rereadReason(web, previous(web), force = true))
    }

    @Test
    fun `validators never go to another address, even on the same server`() {
        val sameHostOtherPath = "https://myrient.example/mirror/gb/"
        assertTrue(SourceScanCheck.conditionalHeaders(web, previous(web), force = false, target = sameHostOtherPath).isEmpty())
        val viaMirror = previous(web, servedBy = mirror)
        assertTrue(SourceScanCheck.conditionalHeaders(web, viaMirror, force = false, target = "https://second.example/gb/").isEmpty())
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
    fun `mirror links with encoded names land on the same file under the main address`() {
        assertEquals(
            web.url + "Pok%C3%A9mon%20-%20Red%20Version%20%28USA%2C%20Europe%29.zip",
            SourceScanCheck.primaryUrl("https://mirror.example/gb/Pok%C3%A9mon%20-%20Red%20Version%20(USA,%20Europe).zip", mirror, web.url)
        )
        // The mirror typed with spaces, its listing linking them encoded.
        assertEquals(
            web.url + "Tetris.zip",
            SourceScanCheck.primaryUrl("https://mirror.example/No%20Intro/gb/Tetris.zip", "https://mirror.example/No Intro/gb/", web.url)
        )
        // A link outside the mirror's folder (a CDN) has no main-address equivalent: left alone.
        val cdn = "https://cdn.example/files/Tetris.zip"
        assertEquals(cdn, SourceScanCheck.primaryUrl(cdn, mirror, web.url))
    }

    @Test
    fun `a mirror whose address is a prefix of the main one does not move links twice`() {
        val main = "https://host.example/roms/snes/"
        val root = "https://host.example/"
        assertEquals(main + "Game.zip", SourceScanCheck.primaryUrl(main + "Game.zip", root, main))
        assertEquals(main + "Game.zip", SourceScanCheck.primaryUrl(root + "Game.zip", root, main))
    }

    @Test
    fun `sources are routed like the scan always did`() {
        assertEquals(SourceKind.TORRENT, SourceKind.of(magnet.url))
        assertEquals(SourceKind.TORRENT, SourceKind.of("/files/torrents/x.torrent"))
        assertEquals(SourceKind.ROMM, SourceKind.of("romm://psp"))
        assertEquals(SourceKind.WEB, SourceKind.of(web.url))
    }

    private val recipe = ScrapeRecipe(item = "ul.roms li", size = "span.s", pages = "1-2", ext = "zip")

    @Test
    fun `a page recipe only changes the route of a web address`() {
        assertEquals(SourceKind.SCRAPE, SourceKind.of(web.copy(scrape = recipe)))
        assertEquals(SourceKind.WEB, SourceKind.of(web))
        assertEquals(SourceKind.TORRENT, SourceKind.of(magnet.copy(scrape = recipe)))
        assertEquals(SourceKind.ROMM, SourceKind.of(UrlEntry("romm://psp", scrape = recipe)))
    }

    @Test
    fun `internet archive items have their own route, a zip viewer stays web`() {
        assertEquals(SourceKind.IA, SourceKind.of("ia://x"))
        assertEquals(SourceKind.IA, SourceKind.of(UrlEntry("ia://msdos_Prince_of_Persia_1990")))
        assertEquals(SourceKind.IA, SourceKind.of(UrlEntry("ia://x", scrape = recipe)))
        assertEquals(SourceKind.WEB, SourceKind.of("https://archive.org/download/x/Set.zip/"))
        assertEquals("v1|GAME|roms", SourceScanCheck.fingerprint(UrlEntry("ia://x", folders = listOf("roms/"))))
    }

    @Test
    fun `only a web listing carries the listing parser version`() {
        assertEquals("v1|listing${SourceScanCheck.LISTING_PARSER_VERSION}|GAME|", SourceScanCheck.fingerprint(web))
        // A ZIP viewer of Internet Archive is read as a web listing too.
        assertEquals("v1|listing${SourceScanCheck.LISTING_PARSER_VERSION}|GAME|", SourceScanCheck.fingerprint(UrlEntry("https://archive.org/download/x/Set.zip/")))
        // Page recipes and Internet Archive items do not depend on the listing parser.
        assertEquals("v1|GAME||scrape|ul.roms li|||span.s|1-2|zip", SourceScanCheck.fingerprint(web.copy(scrape = recipe)))
        assertEquals("v1|GAME|", SourceScanCheck.fingerprint(UrlEntry("ia://msdos_Prince_of_Persia_1990")))
        // A recipe on something that is not a web address is not read, so it does not count either.
        assertEquals("v1|GAME|sony - ps2", SourceScanCheck.fingerprint(magnet.copy(scrape = recipe)))
        assertEquals("v1|GAME|", SourceScanCheck.fingerprint(UrlEntry("ia://x", scrape = recipe)))
    }

    @Test
    fun `page recipes and archive items scanned before the listing version are still reused`() {
        // As the selectors / Internet Archive build without the listing version stored them.
        val page = web.copy(scrape = recipe)
        val storedPage = previous(page).copy(fingerprint = "v1|GAME||scrape|ul.roms li|||span.s|1-2|zip")
        assertTrue(SourceScanCheck.canReuse(page, storedPage, force = false))
        assertTrue(SourceScanCheck.sameContent(page, storedPage, force = false, hash = "hash", files = 10))
        val item = UrlEntry("ia://x")
        val storedItem = previous(item).copy(fingerprint = "v1|GAME|")
        assertTrue(SourceScanCheck.canReuse(item, storedItem, force = false))
        // A web listing with that same old fingerprint is read in full once.
        assertFalse(SourceScanCheck.canReuse(web, previous(web).copy(fingerprint = "v1|GAME|"), force = false))
    }

    @Test
    fun `the recipe is part of the fingerprint, mirrors are not`() {
        val page = web.copy(scrape = recipe)
        assertEquals("v1|GAME||scrape|ul.roms li|||span.s|1-2|zip", SourceScanCheck.fingerprint(page))
        assertNotEquals(SourceScanCheck.fingerprint(web), SourceScanCheck.fingerprint(page))
        assertEquals(SourceScanCheck.fingerprint(page), SourceScanCheck.fingerprint(page.copy(mirrors = listOf(mirror))))
        assertEquals(SourceScanCheck.fingerprint(page), SourceScanCheck.fingerprint(page.copy(scrape = recipe.copy(size = " span.s "))))
        assertNotEquals(SourceScanCheck.fingerprint(page), SourceScanCheck.fingerprint(page.copy(scrape = recipe.copy(name = "a@title"))))
        assertNotEquals(SourceScanCheck.fingerprint(page), SourceScanCheck.fingerprint(page.copy(scrape = recipe.copy(ext = "7z"))))
    }

    @Test
    fun `new selectors make the next scan read the page in full`() {
        val page = web.copy(scrape = recipe)
        assertTrue(SourceScanCheck.canReuse(page, previous(page), force = false))
        assertFalse(SourceScanCheck.canReuse(page.copy(scrape = recipe.copy(item = "table tr")), previous(page), force = false))
        // Adding or removing the recipe changes how the rows are built too.
        assertFalse(SourceScanCheck.canReuse(page, previous(web), force = false))
        assertFalse(SourceScanCheck.canReuse(web, previous(page), force = false))
    }
}
