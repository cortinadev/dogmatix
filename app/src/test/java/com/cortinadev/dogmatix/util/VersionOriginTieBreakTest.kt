package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.model.GameVersion
import com.cortinadev.dogmatix.data.model.PreferredRegion
import com.cortinadev.dogmatix.data.model.VersionPrefs
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URLEncoder

class VersionOriginTieBreakTest {

    private val usa = VersionPrefs(PreferredRegion.USA, setOf("EN"))
    private val now = 1_800_000_000_000L

    private fun row(displayName: String, base: String): DownloadableFileWithTags {
        val (name, tags) = FileParsingUtils.extractNameAndTags(displayName)
        val href = URLEncoder.encode(displayName, "UTF-8").replace("+", "%20")
        return DownloadableFileWithTags(DownloadableFileEntity(name = name, fileName = href, consoleId = "nintendo_gb", downloadUrl = "$base/$href"), tags)
    }

    private fun good(speed: Double) = SourceReliability.withAttempt(null, null, 0, 0, now).copy(bytesPerSecond = speed)
    private val bad = SourceReliability.withAttempt(null, DownloadFailure.NOT_FOUND, 0, 0, now)

    private fun byOrigin(records: Map<String, OriginStats>): (List<GameVersion>) -> List<GameVersion> = { versions ->
        SourceReliability.order(versions, { DownloadOrigins.forFile(it.primary.file.downloadUrl, it.primary.file.torrentMagnet) }, records, now)
    }

    private fun hostOf(v: GameVersion) = DownloadOrigins.forWeb(v.primary.file.downloadUrl)

    @Test
    fun `copies of one release follow their sources' records`() {
        val rows = listOf(row("Tetris (World).zip", "https://slow.example/gb"), row("Tetris (World).zip", "https://fast.example/gb"))
        assertEquals("slow.example", hostOf(VersionGrouping.versionsOf(rows, usa)[0]))
        val records = mapOf("slow.example" to good(1.0e6), "fast.example" to good(8.0e6))
        assertEquals("fast.example", hostOf(VersionGrouping.versionsOf(rows, usa, byOrigin(records))[0]))
        assertEquals("fast.example", hostOf(VersionGrouping.versionsOf(rows, usa, byOrigin(mapOf("slow.example" to bad)))[0]))
    }

    @Test
    fun `the source never outranks region or release status`() {
        val rows = listOf(
            row("Zelda (Europe).zip", "https://fast.example/gb"),
            row("Zelda (USA) (Beta).zip", "https://fast.example/gb"),
            row("Zelda (USA).zip", "https://broken.example/gb")
        )
        val records = mapOf("fast.example" to good(9.0e6), "broken.example" to bad)
        val ranked = VersionGrouping.versionsOf(rows, usa, byOrigin(records))
        assertEquals("broken.example", hostOf(ranked[0]))
        assertEquals(listOf("USA"), ranked[0].primary.tags.filter { it == "USA" })
    }

    @Test
    fun `only the positions of one release are reshuffled`() {
        val ranked = listOf("a@1", "b@1", "a@2", "c@1")
        val out = VersionRanking.sameReleaseOrder(ranked, releaseOf = { it.substringBefore('@') }, order = { it.reversed() })
        assertEquals(listOf("a@2", "b@1", "a@1", "c@1"), out)
    }
}
