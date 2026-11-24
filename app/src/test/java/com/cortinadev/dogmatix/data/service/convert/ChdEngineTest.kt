package com.cortinadev.dogmatix.data.service.convert

import com.cortinadev.dogmatix.data.service.convert.chd.BytesInput
import com.cortinadev.dogmatix.data.service.convert.chd.CdEcc
import com.cortinadev.dogmatix.data.service.convert.chd.ChannelInput
import com.cortinadev.dogmatix.data.service.convert.chd.ChdMap
import com.cortinadev.dogmatix.data.service.convert.chd.ChdReader
import com.cortinadev.dogmatix.data.service.convert.chd.Crc16
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.random.Random

class ChdEngineTest {
    private val files = mutableListOf<File>()

    @After
    fun cleanUp() = files.forEach { it.delete() }

    private fun temp(): File = File.createTempFile("chd", ".chd").also { files += it }

    private fun create(source: ChdSource, options: ChdOptions = ChdOptions(threads = 2), cancel: () -> Boolean = { false }): Pair<ChdResult, File> {
        val f = temp()
        val result = RandomAccessFile(f, "rw").use { raf -> ChdEngine.create(source, raf.channel, options, isCancelled = cancel) }
        return result to f
    }

    private fun readAll(f: File): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        RandomAccessFile(f, "r").use { raf ->
            ChdReader(ChannelInput(raf.channel)).verify(threads = 2, sink = { _, data, logical -> out.write(data, 0, logical) })
        }
        return out.toByteArray()
    }

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    /** Text-like data: compresses, but not to nothing. */
    private fun compressible(rnd: Random, n: Int): ByteArray {
        val words = listOf("mario ", "zelda ", "sonic ", "\u0000\u0000\u0000\u0000", "ABCD").map { it.toByteArray() }
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < n) {
            if (rnd.nextInt(10) < 8) out.write(words[rnd.nextInt(words.size)]) else out.write(rnd.nextInt(256))
        }
        return out.toByteArray().copyOf(n)
    }

    private fun mode1Sector(lba: Int, data: ByteArray): ByteArray {
        val s = ByteArray(2352)
        CdEcc.SYNC_HEADER.copyInto(s)
        val m = lba + 150
        fun bcd(n: Int) = ((n / 10) shl 4 or (n % 10)).toByte()
        s[12] = bcd(m / 4500); s[13] = bcd(m / 75 % 60); s[14] = bcd(m % 75); s[15] = 1
        data.copyInto(s, 16, 0, 2048)
        CdEcc.generate(s)
        return s
    }

    @Test
    fun dvdRoundTripIsBitExactAndDeduplicates() {
        val rnd = Random(1)
        val iso = compressible(rnd, 2048 * 300) + ByteArray(2048 * 101) + Random(2).nextBytes(2048 * 20)
        for (compression in ChdCompression.entries) {
            val (result, f) = create(ChdSource.Dvd(BytesInput(iso)), ChdOptions(compression, threads = 3))
            result as ChdResult.Success
            assertEquals(sha1(iso), result.summary.rawSha1)
            assertTrue(result.summary.hunksByKind.getValue("self") > 40)
            assertTrue(result.summary.fileBytes < iso.size / 2)
            assertArrayEquals(iso, readAll(f))
            val verify = RandomAccessFile(f, "r").use { ChdEngine.verify(ChannelInput(it.channel), result.summary.rawSha1) }
            assertTrue(verify is ChdResult.Success)
            RandomAccessFile(f, "r").use { raf ->
                val r = ChdReader(ChannelInput(raf.channel))
                assertEquals("DVD ", r.metadata.single().tag)
                assertEquals(1, r.metadata.single().data.size)
                assertEquals(4096, r.hunkBytes)
            }
        }
    }

    @Test
    fun dvdOddSizeEndsInAPartialHunk() {
        val iso = compressible(Random(3), 2048 * 5)
        val (result, f) = create(ChdSource.Dvd(BytesInput(iso)), ChdOptions(hunkBytes = 2048 * 4, threads = 1))
        assertTrue(result is ChdResult.Success)
        assertArrayEquals(iso, readAll(f))
    }

    @Test
    fun cdCueRoundTripStripsEccAndSwapsAudio() {
        val rnd = Random(4)
        val track1 = (0 until 40).map { mode1Sector(it, compressible(rnd, 2048)) }.reduce { a, b -> a + b }
        val audio = ByteArray(2352 * 25) { (it % 7 * 13).toByte() }
        val cue = "FILE \"Game (Track 1).bin\" BINARY\n TRACK 01 MODE1/2352\n  INDEX 01 00:00:00\n" +
            "FILE \"Game (Track 2).bin\" BINARY\n TRACK 02 AUDIO\n  INDEX 00 00:00:00\n  INDEX 01 00:00:05\n"
        val source = ChdSource.Sheet("Game.cue", cue, mapOf(
            "game (track 1).BIN" to BytesInput(track1), // matched ignoring case
            "/some/folder/Game (Track 2).bin" to BytesInput(audio), // matched by base name
        ))
        val (result, f) = create(source)
        result as ChdResult.Success
        val data = readAll(f)
        assertEquals((40 + 28) * 2448, data.size)
        for (i in 0 until 40) assertArrayEquals(track1.copyOfRange(i * 2352, i * 2352 + 2352), data.copyOfRange(i * 2448, i * 2448 + 2352))
        // Audio is stored big-endian.
        val first = data.copyOfRange(40 * 2448, 40 * 2448 + 4)
        assertArrayEquals(byteArrayOf(audio[1], audio[0], audio[3], audio[2]), first)
        // Padding to a multiple of four frames is zeros.
        assertTrue(data.copyOfRange(65 * 2448, 68 * 2448).all { it.toInt() == 0 })
        RandomAccessFile(f, "r").use { raf ->
            val r = ChdReader(ChannelInput(raf.channel))
            assertEquals(listOf("CHT2", "CHT2"), r.metadata.map { it.tag })
            assertEquals("TRACK:2 TYPE:AUDIO SUBTYPE:NONE FRAMES:25 PREGAP:5 PGTYPE:VAUDIO PGSUB:NONE POSTGAP:0", r.metadata[1].text)
            assertEquals(0, r.metadata[1].data.last().toInt())
        }
    }

    @Test
    fun missingTrackAndBadInputFailWithAReason() {
        val cue = "FILE \"a.bin\" BINARY\nTRACK 01 MODE1/2352\nINDEX 01 00:00:00\n"
        assertEquals(ChdFailure.MISSING_FILE, ChdEngine.inspect(ChdSource.Sheet("a.cue", cue, emptyMap()))?.reason)
        val ok = ChdSource.Sheet("a.cue", cue, mapOf("a.bin" to BytesInput(ByteArray(2352 * 5))))
        assertEquals(null, ChdEngine.inspect(ok))
        assertEquals(8L * 2448, ChdEngine.logicalBytes(ok))
        val (missing, _) = create(ChdSource.Sheet("a.cue", cue, emptyMap()))
        assertEquals(ChdFailure.MISSING_FILE, (missing as ChdResult.Failure).reason)
        val (odd, _) = create(ChdSource.Dvd(BytesInput(ByteArray(1000))))
        assertEquals(ChdFailure.BAD_INPUT, (odd as ChdResult.Failure).reason)
        val (wave, _) = create(ChdSource.Sheet("a.cue", "FILE \"a.wav\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\n", emptyMap()))
        assertEquals(ChdFailure.UNSUPPORTED, (wave as ChdResult.Failure).reason)
    }

    @Test
    fun cancellingLeavesAnEmptyOutput() {
        var calls = 0
        val (result, f) = create(ChdSource.Dvd(BytesInput(ByteArray(2048 * 400))), cancel = { ++calls > 10 })
        assertEquals(ChdResult.Cancelled, result)
        assertEquals(0L, f.length())
    }

    @Test
    fun verifyCatchesCorruption() {
        val iso = compressible(Random(5), 2048 * 64)
        val (result, f) = create(ChdSource.Dvd(BytesInput(iso)))
        result as ChdResult.Success
        val bytes = f.readBytes()
        bytes[200] = (bytes[200].toInt() xor 0x55).toByte() // inside the first hunk's data
        val bad = ChdEngine.verify(BytesInput(bytes))
        assertTrue(bad is ChdResult.Failure)
        assertEquals(ChdFailure.CORRUPT, (bad as ChdResult.Failure).reason)
        val wrongSource = ChdEngine.verify(BytesInput(f.readBytes()), expectedRawSha1 = "00".repeat(20))
        assertEquals(ChdFailure.CORRUPT, (wrongSource as ChdResult.Failure).reason)
    }

    @Test
    fun eccRoundTripAndMode2Header() {
        val s = mode1Sector(16, compressible(Random(6), 2048))
        assertTrue(CdEcc.verify(s))
        val copy = s.copyOf()
        CdEcc.clear(copy)
        CdEcc.generate(copy)
        assertArrayEquals(s, copy)
        s[100] = (s[100] + 1).toByte()
        assertTrue(!CdEcc.verify(s))
        // Mode 2: the four header bytes count as zero.
        val m2 = ByteArray(2352).also { CdEcc.SYNC_HEADER.copyInto(it); it[15] = 2; Random(7).nextBytes(it, 16, 2076) }
        CdEcc.generate(m2)
        m2[12] = 0x55
        assertTrue(CdEcc.verify(m2))
    }

    @Test
    fun mapRoundTripWithRunsAndCopies() {
        val n = 600
        val map = ChdMap.Entries(n)
        var offset = 124L
        for (h in 0 until n) {
            when {
                h % 97 == 50 -> { map.type[h] = ChdMap.NONE.toByte(); map.length[h] = 4096 }
                h > 10 && h % 5 == 0 -> { map.type[h] = ChdMap.SELF.toByte(); map.offset[h] = (h % 7).toLong(); continue }
                h in 200..450 -> { map.type[h] = ChdMap.SELF.toByte(); map.offset[h] = (h - 150).toLong(); continue }
                else -> { map.type[h] = (h / 100 % 2).toByte(); map.length[h] = 100 + h % 300 }
            }
            map.offset[h] = offset
            map.crc[h] = Crc16.of(byteArrayOf(h.toByte(), (h shr 8).toByte()))
            offset += map.length[h]
        }
        // Self references must point backwards to stored hunks; fix the ones the pattern made up.
        for (h in 0 until n) if (map.type[h].toInt() == ChdMap.SELF) {
            var ref = map.offset[h].toInt()
            while (map.type[ref].toInt() == ChdMap.SELF) ref--
            map.offset[h] = ref.toLong()
        }
        val decoded = ChdMap.decode(ChdMap.encode(map), n, 4096)
        assertArrayEquals(map.rawBytes(), decoded.rawBytes())
    }

    @Test
    fun crc16IsCcittFalse() {
        assertEquals(0x29B1, Crc16.of("123456789".toByteArray()))
    }

    @Test
    fun profiles() {
        assertEquals(ChdProfile.CD, ChdProfile.forSystem("psx"))
        assertEquals(ChdProfile.CD, ChdProfile.forSystem("Dreamcast"))
        assertEquals(ChdProfile.PS2, ChdProfile.forSystem("ps2"))
        assertEquals(2048, ChdProfile.forSystem("psp")?.hunkBytes)
        assertEquals(null, ChdProfile.forSystem("snes"))
    }
}
