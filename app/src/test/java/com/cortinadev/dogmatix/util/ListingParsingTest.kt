package com.cortinadev.dogmatix.util

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Directory listings as servers write them (synthetic fixtures), and the broken pages a scan
 * meets: what [ListingParser] keeps, what it leaves out, and when there is no listing at all.
 */
class ListingParsingTest {

    private val base = "https://roms.example/files/No-Intro/Nintendo%20-%20SNES/"

    private fun parse(html: String, pageUrl: String = base, baseUrl: String = base): ListingParser.Parsed =
        ListingParser.parse(Jsoup.parse(html, pageUrl), baseUrl, pageUrl, "snes", "Game")

    private fun noListing(html: String) {
        try {
            parse(html)
            fail("Expected no listing for: ${html.take(60)}")
        } catch (_: NoListingException) {
        }
    }

    private val myrient = """
        <html><body><table id="list"><thead><tr>
          <th><a href="?C=N&amp;O=A">File Name</a></th><th><a href="?C=S&amp;O=A">File Size</a></th><th>Date</th></tr></thead>
        <tbody>
          <tr><td class="link"><a href="../" title="Parent directory">Parent directory/</a></td><td class="size">-</td><td class="date">-</td></tr>
          <tr><td class="link"><a href="Super%20Mario%20World%20%28USA%29.zip" title="Super Mario World (USA).zip">Super Mario World (USA).zip</a></td>
              <td class="size">371.9 KiB</td><td class="date">01-Jan-2024 10:00</td></tr>
          <tr><td class="link"><a href="Zelda%20%28Europe%29%20%28En%2CFr%2CDe%29.zip" title="Zelda (Europe) (En,Fr,De).zip">Zelda (Europe) (En,Fr,De).zip</a></td>
              <td class="size">1.0 MiB</td><td class="date">01-Jan-2024 10:00</td></tr>
        </tbody></table></body></html>
    """.trimIndent()

    @Test
    fun `myrient rows keep exactly the shape they always had`() {
        val parsed = parse(myrient)
        assertEquals(listOf("Super%20Mario%20World%20%28USA%29.zip", "Zelda%20%28Europe%29%20%28En%2CFr%2CDe%29.zip"), parsed.files.map { it.fileName })
        val mario = parsed.files[0]
        assertEquals(base + "Super%20Mario%20World%20%28USA%29.zip", mario.downloadUrl)
        assertEquals((371.9 * 1024).toLong(), mario.fileSize)
        assertEquals(".zip", mario.fileExtension)
        assertEquals(FileParsingUtils.extractNameAndTags("Super Mario World (USA).zip").first, mario.name)
        assertEquals(listOf("USA", "Game"), parsed.tags[0])
        assertEquals(listOf("Europe", "EN", "FR", "DE", "Game"), parsed.tags[1])
    }

    @Test
    fun `apache html table skips sort links, the parent and folders`() {
        val html = """
            <table>
             <tr><th valign="top"><img src="/icons/blank.gif" alt="[ICO]"></th><th><a href="?C=N;O=D">Name</a></th><th><a href="?C=M;O=A">Last modified</a></th><th><a href="?C=S;O=A">Size</a></th><th><a href="?C=D;O=A">Description</a></th></tr>
             <tr><th colspan="5"><hr></th></tr>
             <tr><td valign="top"><img src="/icons/back.gif" alt="[PARENTDIR]"></td><td><a href="/files/No-Intro/">Parent Directory</a></td><td>&nbsp;</td><td align="right">  - </td><td>&nbsp;</td></tr>
             <tr><td valign="top"><img src="/icons/folder.gif" alt="[DIR]"></td><td><a href="Extras/">Extras/</a></td><td align="right">2024-01-01 12:00  </td><td align="right">  - </td><td>&nbsp;</td></tr>
             <tr><td valign="top"><img src="/icons/compressed.gif" alt="[   ]"></td><td><a href="Game%20(Europe).zip">Game (Europe).zip</a></td><td align="right">2024-01-01 12:00  </td><td align="right"> 66M</td><td>&nbsp;</td></tr>
             <tr><td valign="top"><img src="/icons/compressed.gif" alt="[   ]"></td><td><a href="./Zelda:%20Link.zip">Zelda: Link.zip</a></td><td align="right">2024-01-01 12:00  </td><td align="right">1.2G</td><td>&nbsp;</td></tr>
             <tr><th colspan="5"><hr></th></tr>
            </table>
        """.trimIndent()
        val parsed = parse(html)
        assertEquals(listOf("Game%20(Europe).zip", "Zelda:%20Link.zip"), parsed.files.map { it.fileName })
        assertEquals(66L * 1024 * 1024, parsed.files[0].fileSize)
        // Apache writes "./" before a name with a colon, so it is not read as a scheme.
        assertEquals(base + "Zelda:%20Link.zip", parsed.files[1].downloadUrl)
        assertEquals((1.2 * 1024 * 1024 * 1024).toLong(), parsed.files[1].fileSize)
    }

    @Test
    fun `apache fancy index without a table is read line by line`() {
        val html = "<html><body><h1>Index of /snes</h1><pre>" +
            """<img src="/icons/blank.gif" alt="Icon "> <a href="?C=N;O=D">Name</a>                    <a href="?C=M;O=A">Last modified</a>      <a href="?C=S;O=A">Size</a>  <a href="?C=D;O=A">Description</a><hr>""" +
            """<img src="/icons/back.gif" alt="[PARENTDIR]"> <a href="/files/">Parent Directory</a>                             -   """ + "\n" +
            """<img src="/icons/folder.gif" alt="[DIR]"> <a href="Extras/">Extras/</a>                  2024-01-01 12:00    -   """ + "\n" +
            """<img src="/icons/compressed.gif" alt="[   ]"> <a href="Game%20(USA).zip">Game (USA).zip</a>          2024-01-01 12:00   66M  """ + "\n" +
            """<img src="/icons/compressed.gif" alt="[   ]"> <a href="Other%20(Japan).7z">Other (Japan).7z</a>        2024-01-02 13:00  512   """ + "\n" +
            "<hr></pre></body></html>"
        val parsed = parse(html)
        assertEquals(listOf("Game%20(USA).zip", "Other%20(Japan).7z"), parsed.files.map { it.fileName })
        assertEquals(listOf(66L * 1024 * 1024, 512L), parsed.files.map { it.fileSize })
        assertEquals(base + "Other%20(Japan).7z", parsed.files[1].downloadUrl)
        assertEquals(".7z", parsed.files[1].fileExtension)
    }

    @Test
    fun `nginx autoindex is read, long names come from the link and not the cut text`() {
        val html = "<html>\n<head><title>Index of /snes/</title></head>\n<body>\n<h1>Index of /snes/</h1><hr><pre>" +
            """<a href="../">../</a>""" + "\n" +
            """<a href="Extras/">Extras/</a>                                            01-Jan-2024 10:00                   -""" + "\n" +
            """<a href="Super%20Mario%20World%20%28USA%29.zip">Super Mario World (USA).zip</a>                        01-Jan-2024 10:00              380826""" + "\n" +
            """<a href="Legend%20of%20Zelda%2C%20The%20-%20A%20Link%20to%20the%20Past%20%28USA%29.zip">Legend of Zelda, The - A Link to the Past (U..&gt;</a> 01-Jan-2024 10:00             1048576""" + "\n" +
            "</pre><hr></body>\n</html>"
        val parsed = parse(html)
        assertEquals(2, parsed.files.size)
        assertEquals(listOf(380826L, 1048576L), parsed.files.map { it.fileSize })
        val zelda = parsed.files[1]
        assertEquals(FileParsingUtils.extractNameAndTags("Legend of Zelda, The - A Link to the Past (USA).zip").first, zelda.name)
        assertEquals(listOf("USA", "Game"), parsed.tags[1])
    }

    @Test
    fun `iis listings, with absolute paths and the size before the link`() {
        val iis = "https://iis.example/roms/snes/"
        val html = """<html><head><title>iis.example - /roms/snes/</title></head><body><H1>iis.example - /roms/snes/</H1><hr>""" +
            """<pre><A HREF="/roms/">[To Parent Directory]</A><br><br>""" +
            """ 1/1/2024 10:00 AM        &lt;dir&gt; <A HREF="/roms/snes/Extras/">Extras</A><br>""" +
            """ 1/1/2024 10:00 AM       380826 <A HREF="/roms/snes/Super%20Mario%20World%20(USA).zip">Super Mario World (USA).zip</A><br>""" +
            """ 1/2/2024  3:15 PM      1048576 <A HREF="/roms/snes/Zelda%20(Japan).sfc">Zelda (Japan).sfc</A><br></pre><hr></body></html>"""
        val parsed = parse(html, pageUrl = iis, baseUrl = iis)
        assertEquals(listOf("Super%20Mario%20World%20(USA).zip", "Zelda%20(Japan).sfc"), parsed.files.map { it.fileName })
        assertEquals(listOf(iis + "Super%20Mario%20World%20(USA).zip", iis + "Zelda%20(Japan).sfc"), parsed.files.map { it.downloadUrl })
        assertEquals(listOf(380826L, 1048576L), parsed.files.map { it.fileSize })
    }

    @Test
    fun `h5ai absolute paths resolve against the server, not under the listing again`() {
        val h5ai = "https://h5ai.example/roms/snes/"
        val html = """
            <div id="fallback"><table>
            <tr><th class="fb-i"></th><th class="fb-n"><a href="?C=N;O=D">Name</a></th><th class="fb-d">Last modified</th><th class="fb-s">Size</th></tr>
            <tr><td class="fb-i"><img src="/_h5ai/public/images/fallback/folder-parent.png" alt="folder-parent"/></td><td class="fb-n"><a href="/roms/">Parent Directory</a></td><td class="fb-d"></td><td class="fb-s"></td></tr>
            <tr><td class="fb-i"><img src="/_h5ai/public/images/fallback/file.png" alt="file"/></td><td class="fb-n"><a href="/roms/snes/Game%20(Japan).zip">Game (Japan).zip</a></td><td class="fb-d">2024-01-01 12:00</td><td class="fb-s">12 MB</td></tr>
            </table></div>
        """.trimIndent()
        val parsed = parse(html, pageUrl = h5ai, baseUrl = h5ai)
        assertEquals(1, parsed.files.size)
        assertEquals("Game%20(Japan).zip", parsed.files[0].fileName)
        assertEquals(h5ai + "Game%20(Japan).zip", parsed.files[0].downloadUrl)
        assertEquals(12L * 1000 * 1000, parsed.files[0].fileSize)
    }

    @Test
    fun `a mirror's listing resolves absolute paths on the mirror, and the rows go back to the main address`() {
        val main = "https://main.example/files/snes/"
        val mirror = "https://mirror.example/pub/snes/"
        val html = """
            <table>
            <tr><td><a href="/pub/snes/Game%20(USA).zip">Game (USA).zip</a></td><td class="size">1 MiB</td></tr>
            <tr><td><a href="Other%20(USA).zip">Other (USA).zip</a></td><td class="size">1 MiB</td></tr>
            </table>
        """.trimIndent()
        val parsed = parse(html, pageUrl = mirror, baseUrl = main)
        assertEquals(listOf(mirror + "Game%20(USA).zip", main + "Other%20(USA).zip"), parsed.files.map { it.downloadUrl })
        assertEquals(
            listOf(main + "Game%20%28USA%29.zip", main + "Other%20(USA).zip"),
            parsed.files.map { SourceScanCheck.primaryUrl(it.downloadUrl, mirror, main) }
        )
    }

    @Test
    fun `links that are not files of the listing are left out`() {
        val junk = listOf(
            "", "#", "#Game.zip", "javascript:void(0)", "mailto:admin@roms.example", "magnet:?xt=urn:btih:0123",
            "ftp://roms.example/Game.zip", "../Other%20Console/Game.zip", "a/../../Game.zip", "%2E%2E/Game.zip",
            "Evil%2F..%2FGame.zip", "https://roms.example", "?C=S;O=A", "Folder/", "/files/"
        )
        val rows = junk.joinToString("\n") { """<tr><td><a href="$it">link</a></td><td class="size">1 KB</td></tr>""" } +
            """<tr><td>no link at all</td><td class="size">1 KB</td></tr>""" +
            """<tr><td><a href="sub/Game%20(USA).zip">Game (USA).zip</a></td><td class="size">2 KB</td></tr>""" +
            """<tr><td><a href="https://cdn.other.example/snes/Game%20(Japan).zip?download=1">Game (Japan).zip</a></td><td class="size">3 KB</td></tr>""" +
            """<tr><td><a href="//cdn.other.example/snes/Game%20(Europe).zip">Game (Europe).zip</a></td><td class="size">4 KB</td></tr>""" +
            """<tr><td><a href="HTTPS://CDN.OTHER.EXAMPLE/snes/Game%20(Brazil).zip">Game (Brazil).zip</a></td><td class="size">5 KB</td></tr>"""
        val parsed = parse("<table>$rows</table>")
        assertEquals(
            listOf("Game%20(USA).zip", "Game%20(Japan).zip", "Game%20(Europe).zip", "Game%20(Brazil).zip"),
            parsed.files.map { it.fileName }
        )
        assertEquals(
            listOf(
                base + "sub/Game%20(USA).zip",
                // Another server's link is kept as it is (a download host next to the listing).
                "https://cdn.other.example/snes/Game%20(Japan).zip?download=1",
                "https://cdn.other.example/snes/Game%20(Europe).zip",
                "HTTPS://CDN.OTHER.EXAMPLE/snes/Game%20(Brazil).zip"
            ),
            parsed.files.map { it.downloadUrl }
        )
        assertEquals(listOf(".zip", ".zip", ".zip", ".zip"), parsed.files.map { it.fileExtension })
    }

    @Test
    fun `odd sizes are read or taken as unknown, never break the row`() {
        val sizes = listOf("-", "1.2G", "1,234", "12,345,678", "1,5 MB", "", "12.3k", "4 GiB", "?", "n/a")
        val rows = sizes.mapIndexed { i, size -> """<tr><td class="link"><a href="Game$i.zip">Game$i.zip</a></td><td class="size">$size</td></tr>""" }
        val parsed = parse("<table>${rows.joinToString("")}</table>")
        assertEquals(sizes.size, parsed.files.size)
        assertEquals(
            listOf(0L, (1.2 * 1024 * 1024 * 1024).toLong(), 1_234L, 12_345_678L, 1_500_000L, 0L, (12.3 * 1024).toLong(), 4L * 1024 * 1024 * 1024, 0L, 0L),
            parsed.files.map { it.fileSize }
        )
    }

    @Test
    fun `a name the listing shows blank or cut comes from the link`() {
        val html = """
            <table>
            <tr><td><a href="Game%20(USA).zip"><img src="/icons/file.png"></a></td><td><a href="Game%20(USA).zip">Game (USA).zip</a></td><td>1 KB</td></tr>
            <tr><td class="link"><a href="A%20Very%20Long%20Name%20(Japan).zip">A Very Long Na..&gt;</a></td><td class="size">1 KB</td></tr>
            </table>
        """.trimIndent()
        val parsed = parse(html)
        assertEquals(FileParsingUtils.extractNameAndTags("Game (USA).zip").first, parsed.files[0].name)
        assertEquals(1_000L, parsed.files[0].fileSize)
        assertEquals(FileParsingUtils.extractNameAndTags("A Very Long Name (Japan).zip").first, parsed.files[1].name)
        assertEquals(listOf("Japan", "Game"), parsed.tags[1])
    }

    @Test
    fun `a truncated page keeps the rows that arrived whole`() {
        val cut = myrient.substring(0, myrient.indexOf("Zelda%20") + 8)
        val parsed = parse(cut)
        assertEquals(listOf("Super%20Mario%20World%20%28USA%29.zip"), parsed.files.map { it.fileName })
    }

    @Test
    fun `a table or pre without files is an empty listing, not an error`() {
        assertTrue(parse("<table></table>").files.isEmpty())
        assertTrue(parse("<table><tr><td>Nothing here</td></tr></table>").files.isEmpty())
        // An emptied folder: only the parent and a subfolder.
        assertTrue(parse("""<table><tr><td><a href="../">Parent</a></td></tr><tr><td><a href="Extras/">Extras/</a></td><td>-</td></tr></table>""").files.isEmpty())
        assertTrue(parse("""<pre><a href="../">../</a>${"\n"}</pre>""").files.isEmpty())
    }

    @Test
    fun `a page without a listing is told apart from an empty one`() {
        noListing("""{"files": ["Game (USA).zip"], "total": 1}""")
        noListing("Index of /snes\nGame (USA).zip\nOther (USA).zip\n")
        noListing("<html><head><title>502 Bad Gateway</title></head><body><center><h1>502 Bad Gateway</h1></center><hr><center>nginx</center></body></html>")
        noListing("""<html><body><form action="/login"><input name="user"><a href="/help">Help</a></form></body></html>""")
        // A stack trace in a <pre>, without links.
        noListing("<html><body><pre>java.lang.IllegalStateException: boom\n\tat Main.main(Main.java:1)</pre></body></html>")
    }

    @Test
    fun `a layout table without files does not hide the pre listing after it`() {
        val html = """<table><tr><td><b>My ROM server</b></td></tr></table><pre><a href="../">../</a>""" + "\n" +
            """<a href="Game%20(USA).zip">Game (USA).zip</a>    01-Jan-2024 10:00    1024""" + "\n</pre>"
        val parsed = parse(html)
        assertEquals(listOf("Game%20(USA).zip"), parsed.files.map { it.fileName })
        assertEquals(1024L, parsed.files[0].fileSize)
    }

    @Test
    fun `sizes written apart from their unit in a pre line are read whole`() {
        val html = """<pre><a href="Game.zip">Game.zip</a>   2024-01-01 12:00   1.5 MB""" + "\n</pre>"
        assertEquals(1_500_000L, parse(html).files.single().fileSize)
    }
}
