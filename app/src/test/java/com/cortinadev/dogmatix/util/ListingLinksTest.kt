package com.cortinadev.dogmatix.util

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Test

/** Links of the HTML listings the HTTP scraper reads, as they end up in the library. */
class ListingLinksTest {

    private fun row(html: String): Element = Jsoup.parse("<table>$html</table>").select("tr").first()!!

    /** A row of Internet Archive's ZIP viewer: scheme-relative link, size in exact bytes. */
    private val iaRow = """<tr><td><a href="//archive.org/download/item/Set.zip/Game%20%28USA%29.zip">Game (USA).zip</a><td><td>1996-12-24 23:32<td id="size">26574277</tr>"""

    @Test
    fun `scheme-relative links take the listing's scheme`() {
        val (file, tags) = FileParsingUtils.parseFileFromRow(row(iaRow), "https://ia601900.us.archive.org/view_archive.php?archive=/2/items/item/Set.zip", "n64")
        file!!
        assertEquals("https://archive.org/download/item/Set.zip/Game%20%28USA%29.zip", file.downloadUrl)
        assertEquals(26574277L, file.fileSize)
        assertEquals(".zip", file.fileExtension)
        assertEquals("Game%20%28USA%29.zip", file.fileName)
        assertEquals(listOf("USA"), tags.map { it.tag })
    }

    @Test
    fun `scheme-relative links keep plain http`() {
        // Since 1.4 a scheme-relative link takes the scheme of the page that holds it (resolveHref).
        assertEquals("http://archive.org/download/x/a.zip", FileParsingUtils.resolveHref("http://example.org/roms/", "http://example.org/roms/", "//archive.org/download/x/a.zip"))
        assertEquals("https://archive.org/download/x/a.zip", FileParsingUtils.resolveHref("example.org/roms/", "example.org/roms/", "//archive.org/download/x/a.zip"))
    }

    @Test
    fun `the ZIP viewer page reads as a listing`() {
        val page = Jsoup.parse(
            """<html><body><table class="archext"><thead><tr><th>Name<th>Date<th>Size</tr></thead>""" +
                iaRow +
                """<tr><td><a href="//archive.org/download/item/Set.zip/Other%20%28Europe%29.zip">Other (Europe).zip</a><td><td>1997-01-02 10:00<td id="size">1024</tr>""" +
                "</table></body></html>"
        )
        val viewer = "https://ia601900.us.archive.org/view_archive.php?archive=/2/items/item/Set.zip"
        val parsed = ListingParser.parse(page, baseUrl = "https://archive.org/download/item/Set.zip/", pageUrl = viewer, consoleId = "n64", contentTypeTag = "game")
        assertEquals(
            listOf("https://archive.org/download/item/Set.zip/Game%20%28USA%29.zip", "https://archive.org/download/item/Set.zip/Other%20%28Europe%29.zip"),
            parsed.files.map { it.downloadUrl }
        )
        assertEquals(listOf("Game%20%28USA%29.zip", "Other%20%28Europe%29.zip"), parsed.files.map { it.fileName })
        assertEquals(listOf(26574277L, 1024L), parsed.files.map { it.fileSize })
        assertEquals(listOf(listOf("USA", "game"), listOf("Europe", "game")), parsed.tags)
    }

    @Test
    fun `relative and absolute links are left as they were`() {
        assertEquals("https://example.org/roms/Game.zip", FileParsingUtils.buildDownloadUrl("https://example.org/roms/", "Game.zip"))
        assertEquals("https://other.example/Game.zip", FileParsingUtils.buildDownloadUrl("https://example.org/roms/", "https://other.example/Game.zip"))
    }
}
