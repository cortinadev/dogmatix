package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IaFilesTest {

    private val id = "test_item_1990"

    // A reduced item: what the metadata API lists for an upload of one game (values made up).
    private val files = """
        [
          {"name": "Game 1990.zip", "source": "original", "format": "ZIP", "size": "345000",
           "md5": "0123456789ABCDEF0123456789ABCDEF", "sha1": "00112233445566778899aabbccddeeff00112233", "crc32": "1a2b3c"},
          {"name": "screenshot.jpg", "source": "original", "format": "JPEG", "size": "12000"},
          {"name": "screenshot_thumb.jpg", "source": "derivative", "format": "JPEG Thumb", "size": "2000"},
          {"name": "${id}_meta.xml", "source": "original", "format": "Metadata", "size": "900"},
          {"name": "__ia_thumb.jpg", "source": "original", "format": "Item Tile", "size": "8000"},
          {"name": "${id}_archive.torrent", "source": "metadata", "format": "Archive BitTorrent", "size": "3000"},
          {"name": "roms/Game (USA).zip", "source": "original", "format": "ZIP", "size": "26574277",
           "sha1": "FFEEDDCCBBAA99887766554433221100FFEEDDCC", "crc32": "DEADBEEF"},
          {"name": "Empty.bin", "source": "original", "format": "Unknown", "size": "0"},
          {"name": ".hidden.bin", "source": "original", "format": "Unknown", "size": "10"}
        ]
    """.trimIndent()
    private val metadata = """{"created": 1, "item_last_updated": 1700000000, "files": $files, "metadata": {"identifier": "$id"}}"""

    @Test
    fun `parses both forms of the file list and an unknown item`() {
        val full = IaFiles.parse(metadata)
        assertEquals(9, full.size)
        assertEquals(full, IaFiles.parse("""{"result": $files}"""))
        assertEquals(345000L, full.first().size)
        assertEquals("roms/Game (USA).zip", full[6].name)
        assertTrue(IaFiles.parse("{}").isEmpty())
        assertTrue(IaFiles.parse("not json").isEmpty())
    }

    @Test
    fun `reads item_last_updated from the whole metadata or the single field`() {
        assertEquals(1700000000L, IaFiles.lastUpdated(metadata))
        assertEquals(1700000123L, IaFiles.lastUpdated("""{"result": 1700000123}"""))
        assertNull(IaFiles.lastUpdated("{}"))
        assertNull(IaFiles.lastUpdated("[]"))
    }

    @Test
    fun `selects only what was uploaded, and the folders asked for`() {
        val all = IaFiles.parse(metadata)
        assertEquals(listOf("Game 1990.zip", "roms/Game (USA).zip"), IaFiles.select(all, id, emptyList()).map { it.name })
        assertEquals(listOf("roms/Game (USA).zip"), IaFiles.select(all, id, listOf("roms")).map { it.name })
        assertEquals(listOf("roms/Game (USA).zip"), IaFiles.select(all, id, listOf("ROMS/")).map { it.name })
        assertTrue(IaFiles.select(all, id, listOf("other")).isEmpty())
    }

    @Test
    fun `builds rows named like a torrent's and downloaded from the item`() {
        val rows = IaFiles.rows(IaFiles.select(IaFiles.parse(metadata), id, listOf("roms")), id, "nes", "Game")
        val (file, tags) = rows.single()
        assertEquals("Game (USA).zip", file.fileName)
        assertEquals("Game", file.name)
        assertEquals(listOf("USA", "Game"), tags)
        assertEquals("https://archive.org/download/$id/roms/Game%20(USA).zip", file.downloadUrl)
        assertEquals(26574277L, file.fileSize)
        assertEquals(".zip", file.fileExtension)
        assertEquals("nes", file.consoleId)
        assertNull(file.torrentFileIndex)
    }

    @Test
    fun `a file without extension or tags keeps its leaf as its name`() {
        val rows = IaFiles.rows(listOf(IaFile("disks/GAMEDISK", 10, "Unknown", "original")), id, "c64", "Game")
        assertEquals("GAMEDISK", rows.single().first.name)
        assertEquals("", rows.single().first.fileExtension)
        assertEquals(listOf("Game"), rows.single().second)
    }

    @Test
    fun `expected files carry normalised hashes and include the archives`() {
        val expected = IaFiles.expected(IaFiles.select(IaFiles.parse(metadata), id, emptyList()))
        assertEquals(2, expected.size)
        val zip = expected.first()
        assertEquals("Game 1990.zip", zip.name)
        assertEquals(345000L, zip.size)
        assertEquals("001a2b3c", zip.crc)
        assertEquals("00112233445566778899aabbccddeeff00112233", zip.sha1)
        assertEquals("0123456789abcdef0123456789abcdef", zip.md5)
        assertEquals("deadbeef", expected[1].crc)
        assertEquals("ffeeddccbbaa99887766554433221100ffeeddcc", expected[1].sha1)
        assertNull(expected[1].md5)
    }
}
