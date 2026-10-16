package com.cortinadev.dogmatix.util

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Test

/** Sizes read from the HTML directory listings the HTTP scraper understands. */
class ListingSizeTest {

    private fun row(html: String): Element = Jsoup.parse("<table>$html</table>").select("tr").first()!!

    private fun sizeOf(html: String): Long =
        FileParsingUtils.parseFileFromRow(row(html), "https://example.org/roms/", "nes").first!!.fileSize

    @Test
    fun myrientSizeColumn() {
        val html = """<tr><td class="link"><a href="Game%20(USA).zip" title="Game (USA).zip">Game (USA).zip</a></td>
            <td class="size">1.5 MiB</td><td class="date">01-Jan-2024 10:00</td></tr>"""
        assertEquals((1.5 * 1024 * 1024).toLong(), sizeOf(html))
    }

    @Test
    fun apacheIndexSkipsIconNameAndDate() {
        val html = """<tr><td valign="top"><img src="/icons/compressed.gif" alt="[   ]"></td>
            <td><a href="Game%20(Europe).zip">Game (Europe).zip</a></td>
            <td align="right">2024-01-01 12:00  </td><td align="right"> 66M</td><td>&nbsp;</td></tr>"""
        assertEquals(66L * 1024 * 1024, sizeOf(html))
    }

    @Test
    fun apacheHeaderSortLinksAreNotFiles() {
        val html = """<tr><th valign="top"><img src="/icons/blank.gif" alt="[ICO]"></th><th><a href="?C=N;O=D">Name</a></th>
            <th><a href="?C=M;O=A">Last modified</a></th><th><a href="?C=S;O=A">Size</a></th><th><a href="?C=D;O=A">Description</a></th></tr>"""
        assertEquals(null, FileParsingUtils.parseFileFromRow(row(html), "https://example.org/roms/", "nes").first)
    }

    @Test
    fun apacheSmallFileInPlainBytes() {
        val html = """<tr><td><img src="/icons/text.gif"></td><td><a href="readme.txt">readme.txt</a></td>
            <td align="right">2024-01-01 12:00</td><td align="right">512</td><td>&nbsp;</td></tr>"""
        assertEquals(512L, sizeOf(html))
    }

    @Test
    fun h5aiSizeColumn() {
        val html = """<tr><td class="fb-i"><img src="/_h5ai/file.svg"></td>
            <td class="fb-n"><a href="/roms/Game%20(Japan).zip">Game (Japan).zip</a></td>
            <td class="fb-d">2024-01-01 12:00</td><td class="fb-s">12 MB</td></tr>"""
        assertEquals(12L * 1000 * 1000, sizeOf(html))
    }

    @Test
    fun shortUnitsArePowersOf1024() {
        assertEquals(1536L, FileSizeUtils.parseFileSize("1.5K"))
        assertEquals(4L * 1024 * 1024 * 1024, FileSizeUtils.parseFileSize("4.0G"))
        assertEquals(2048L, FileSizeUtils.parseFileSize("2 KiB"))
        assertEquals(0L, FileSizeUtils.parseFileSize("-"))
        assertEquals(0L, FileSizeUtils.parseFileSize("2024-01-01 12:00"))
    }

    @Test
    fun namesStillLoseTheirTags() {
        assertEquals("Game" to listOf("USA", "EN", "FR"), FileParsingUtils.extractNameAndTags("Game (USA) (En,Fr)"))
    }
}
