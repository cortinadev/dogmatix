package com.cortinadev.dogmatix.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.time.ZoneOffset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.random.Random

class StoredZipTest {

    private val files = linkedMapOf(
        "Game (USA).cue" to "FILE \"Game (USA) (Track 1).bin\" BINARY\n".toByteArray(),
        "Disc 1/Game (USA) (Track 1).bin" to Random(7).nextBytes(70_000),
        "Pokémon – Edición Oro.txt" to "ñ".toByteArray(),
        "empty.dat" to ByteArray(0)
    )
    private val contents = files.values.toList()
    private val time = StoredZip.dosTime(1_760_000_000_000L, ZoneOffset.UTC)

    private fun entries() = files.map { (path, bytes) -> ZipEntrySpec(path, bytes.size.toLong(), crc(bytes), time) }

    private fun crc(bytes: ByteArray) = CRC32().apply { update(bytes) }.value

    private fun opener(entry: Int, offset: Long): InputStream =
        ByteArrayInputStream(contents[entry]).also { it.skip(offset) }

    private fun bytesOf(zip: StoredZip, from: Long = 0L): ByteArray = zip.openAt(from, ::opener).use { it.readBytes() }

    private fun asFile(bytes: ByteArray): File = File.createTempFile("stored", ".zip").apply {
        deleteOnExit()
        writeBytes(bytes)
    }

    private fun checkWithZipFile(bytes: ByteArray) {
        ZipFile(asFile(bytes)).use { zip ->
            assertEquals(files.size, zip.size())
            for ((path, content) in files) {
                val entry = zip.getEntry(path) ?: return fail("missing $path")
                assertEquals(ZipEntry.STORED, entry.method)
                assertEquals(content.size.toLong(), entry.size)
                assertEquals(crc(content), entry.crc)
                assertArrayEquals(content, zip.getInputStream(entry).use { it.readBytes() })
            }
        }
    }

    private fun checkWithZipInputStream(bytes: ByteArray) {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val seen = mutableListOf<String>()
            while (true) {
                val entry = zip.nextEntry ?: break
                seen += entry.name
                assertArrayEquals(files.getValue(entry.name), zip.readBytes())
            }
            assertEquals(files.keys.toList(), seen)
        }
    }

    @Test
    fun `stored archive opens with both zip readers`() {
        val zip = StoredZip.of(entries())
        val bytes = bytesOf(zip)
        assertEquals(zip.size, bytes.size.toLong())
        checkWithZipFile(bytes)
        checkWithZipInputStream(bytes)
    }

    @Test
    fun `zip64 records are readable`() {
        // Every size and offset past the threshold: the layout of a game over 4 GiB, on small files.
        val zip = StoredZip.of(entries(), zip64Threshold = 0L)
        val bytes = bytesOf(zip)
        assertEquals(zip.size, bytes.size.toLong())
        checkWithZipFile(bytes)
        checkWithZipInputStream(bytes)
    }

    @Test
    fun `same entries give the same bytes and any offset continues them`() {
        val full = bytesOf(StoredZip.of(entries()))
        assertArrayEquals(full, bytesOf(StoredZip.of(entries())))
        val zip = StoredZip.of(entries())
        for (offset in listOf(0L, 1L, 29L, 30L, 100L, 50_000L, full.size - 23L, full.size - 1L, full.size.toLong())) {
            assertArrayEquals("from $offset", full.copyOfRange(offset.toInt(), full.size), bytesOf(zip, offset))
        }
    }

    @Test
    fun `chunks read one after another rebuild the archive`() {
        val zip = StoredZip.of(entries())
        val full = bytesOf(zip)
        val chunk = 8_192
        val out = java.io.ByteArrayOutputStream()
        zip.openAt(0L, ::opener).use { input ->
            val buffer = ByteArray(chunk)
            while (true) {
                var filled = 0
                while (filled < chunk) {
                    val n = input.read(buffer, filled, chunk - filled)
                    if (n < 0) break
                    filled += n
                }
                out.write(buffer, 0, filled)
                if (filled < chunk) break
            }
        }
        assertArrayEquals(full, out.toByteArray())
    }

    @Test
    fun `a single file goes through as it is`() {
        val data = Random(3).nextBytes(10_000)
        val zip = StoredZip.single(data.size.toLong())
        assertEquals(data.size.toLong(), zip.size)
        val read = zip.openAt(4_000L) { _, offset -> ByteArrayInputStream(data).also { it.skip(offset) } }.use { it.readBytes() }
        assertArrayEquals(data.copyOfRange(4_000, data.size), read)
    }

    @Test(expected = SourceChangedException::class)
    fun `a file shorter than planned is an error, not a broken archive`() {
        val zip = StoredZip.of(listOf(ZipEntrySpec("a.bin", 100, 0, time)))
        zip.openAt(0L) { _, _ -> ByteArrayInputStream(ByteArray(40)) }.use { it.readBytes() }
    }

    @Test
    fun `dos time packs date and two-second time`() {
        // 2026-10-14 17:23:54 UTC.
        val t = StoredZip.dosTime(1_791_998_634_000L, ZoneOffset.UTC)
        val date = (t ushr 16).toInt()
        val clock = (t and 0xFFFF).toInt()
        assertEquals(2026, (date shr 9) + 1980)
        assertEquals(10, (date shr 5) and 0x0F)
        assertEquals(14, date and 0x1F)
        assertEquals(17, clock shr 11)
        assertEquals(23, (clock shr 5) and 0x3F)
        assertEquals(27, clock and 0x1F)   // 54 s
        // Before 1980 there is no MS-DOS time: 1980-01-01.
        assertEquals(((1 shl 5) or 1).toLong() shl 16, StoredZip.dosTime(0L, ZoneOffset.UTC))
    }

    @Test
    fun `crc32 matches the jdk and reports progress`() {
        val data = Random(11).nextBytes(200_000)
        var seen = 0L
        assertEquals(crc(data), StoredZip.crc32(ByteArrayInputStream(data)) { seen += it })
        assertEquals(data.size.toLong(), seen)
    }

    @Test
    fun `empty archive is still a valid zip`() {
        val bytes = bytesOf(StoredZip.of(emptyList()))
        assertEquals(22, bytes.size)
        ZipInputStream(ByteArrayInputStream(bytes)).use { assertNull(it.nextEntry) }
    }
}
