package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.model.PreferredRegion
import com.cortinadev.dogmatix.data.model.VersionPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.util.Locale

class VersionGroupingTest {

    private val spain = VersionPrefs(PreferredRegion.SPAIN, setOf("ES", "EN"))
    private val usa = VersionPrefs(PreferredRegion.USA, setOf("EN"))

    /** A library row as the scraper stores it: name without (…) tags, URL-encoded file name. */
    private fun row(displayName: String, base: String = "https://host.example/psx") : DownloadableFileWithTags {
        val (name, tags) = FileParsingUtils.extractNameAndTags(displayName)
        val href = URLEncoder.encode(displayName, "UTF-8").replace("+", "%20")
        val file = DownloadableFileEntity(name = name, fileName = href, consoleId = "sony_playstation", downloadUrl = "$base/$href")
        return DownloadableFileWithTags(file, tags)
    }

    @Test
    fun `group key ignores tags, flags and extension`() {
        val a = row("Final Fantasy VII (USA) (Disc 1).zip").file.name
        val b = row("Final Fantasy VII (Europe) (En,Fr,De) (Disc 2).zip").file.name
        assertEquals(VersionGrouping.groupKey(a), VersionGrouping.groupKey(b))
        assertEquals("final fantasy vii", VersionGrouping.groupKey(a))
        assertEquals("super mario bros. 3", VersionGrouping.groupKey("Super Mario Bros. 3 [!].nes"))
        assertEquals("tetris", VersionGrouping.groupKey("Tetris [b1].gb"))
    }

    @Test
    fun `discs of one release form one version, sorted`() {
        val rows = listOf(
            row("Final Fantasy VII (USA) (Disc 3).zip"),
            row("Final Fantasy VII (USA) (Disc 1).zip"),
            row("Final Fantasy VII (USA) (Disc 2).zip"),
            row("Final Fantasy VII (Europe) (En,Fr,De,Es) (Disc 1).zip"),
            row("Final Fantasy VII (Europe) (En,Fr,De,Es) (Disc 2).zip"),
            row("Final Fantasy VII (Europe) (En,Fr,De,Es) (Disc 3).zip")
        )
        val versions = VersionGrouping.versionsOf(rows, spain)
        assertEquals(2, versions.size)
        assertTrue(versions[0].primary.tags.contains("Europe"))
        assertEquals(3, versions[0].discs.size)
        assertEquals(listOf(1, 2, 3), versions[1].discs.map { MultiDisc.discOf(java.net.URLDecoder.decode(it.file.fileName, "UTF-8"))!!.number })
        assertFalse(VersionGrouping.displayTags(versions[0]).any { it.startsWith("Disc") })
        // An American picks the USA discs instead.
        assertTrue(VersionGrouping.versionsOf(rows, usa)[0].primary.tags.contains("USA"))
    }

    @Test
    fun `same release from two sources stays two versions`() {
        val rows = listOf(row("Tetris (World).zip", "https://a.example/gb"), row("Tetris (World).zip", "https://b.example/gb"))
        assertEquals(2, VersionGrouping.versionsOf(rows, usa).size)
    }

    @Test
    fun `ranking prefers releases, region, language, revision and verified dumps`() {
        val japan = row("Game (Japan).zip")
        val usaRow = row("Game (USA).zip")
        val usaRev = row("Game (USA) (Rev 1).zip")
        val demo = row("Game (Spain) (Demo).zip")
        val spainRow = row("Game (Spain).zip")
        val europe = row("Game (Europe) (Fr,De).zip")
        val all = listOf(japan, usaRow, demo, europe, spainRow, usaRev)
        val forSpain = VersionGrouping.versionsOf(all, spain).map { it.primary }
        assertEquals(spainRow, forSpain[0])
        assertEquals(demo, forSpain.last())
        // A European release only in French and German loses to an English one for a Spanish
        // user with English among the favourite languages; the revision breaks the USA tie.
        assertTrue(forSpain.indexOf(usaRev) < forSpain.indexOf(usaRow))
        assertTrue(forSpain.indexOf(usaRow) < forSpain.indexOf(europe))
        val forUsa = VersionGrouping.versionsOf(all, usa).map { it.primary }
        assertEquals(usaRev, forUsa[0])
        assertEquals(usaRow, forUsa[1])
        assertTrue(forUsa.indexOf(japan) > forUsa.indexOf(europe))
    }

    @Test
    fun `language tags and revisions are read as such`() {
        assertEquals(25, VersionRanking.preferencePoints(listOf("ES"), VersionPrefs(PreferredRegion.JAPAN, setOf("ES"))))
        assertEquals(0, VersionRanking.preferencePoints(listOf("PAL"), VersionPrefs(PreferredRegion.JAPAN, setOf("PAL"))))
        assertEquals(2.0, VersionRanking.revision(listOf("Rev b")), 0.0)
        assertEquals(1.1, VersionRanking.revision(listOf("V1.1")), 0.0)
        assertTrue(VersionRanking.isNonRelease(listOf("Beta 2"), "x"))
        assertTrue(VersionRanking.isNonRelease(emptyList(), "Game [b1].nes"))
    }

    @Test
    fun `default region follows the device`() {
        assertEquals(PreferredRegion.SPAIN, PreferredRegion.forLocale(Locale("es", "ES")))
        assertEquals(PreferredRegion.USA, PreferredRegion.forLocale(Locale("es", "MX")))
        assertEquals(PreferredRegion.EUROPE, PreferredRegion.forLocale(Locale("nl", "NL")))
        assertEquals(PreferredRegion.JAPAN, PreferredRegion.forLocale(Locale("ja")))
        assertEquals(PreferredRegion.USA, PreferredRegion.forLocale(Locale("en")))
    }
}
