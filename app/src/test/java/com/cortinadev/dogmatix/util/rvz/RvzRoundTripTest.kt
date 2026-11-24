package com.cortinadev.dogmatix.util.rvz

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.zip.CRC32

class RvzRoundTripTest {

    private class Converted(val rvz: File, val digest: DiscDigest)

    private fun temp(suffix: String) = File.createTempFile("rvz", suffix).apply { deleteOnExit() }

    private fun convert(image: ByteArray, wbfs: Boolean = false, threads: Int = 2): Converted {
        val iso = temp(if (wbfs) ".wbfs" else ".iso").apply { writeBytes(image) }
        val rvz = temp(".rvz")
        FileChannel.open(iso.toPath(), READ).use { input ->
            FileChannel.open(rvz.toPath(), WRITE, CREATE, TRUNCATE_EXISTING).use { output ->
                val source = if (wbfs) WbfsSource.open(input) else IsoSource(input)
                val layout = DiscLayout.analyze(source)
                val digest = RvzWriter(source, layout, output, RvzOptions(threads = threads)).write()
                return Converted(rvz, digest)
            }
        }
    }

    private fun decode(rvz: File): Pair<ByteArray, DiscDigest> {
        val out = ByteArrayOutputStream()
        val digest = FileChannel.open(rvz.toPath(), READ).use { ch ->
            RvzReader(ch).use { it.decode(threads = 3, sink = { b, o, l -> out.write(b, o, l) }) }
        }
        return out.toByteArray() to digest
    }

    private fun digestOf(b: ByteArray): DiscDigest {
        val crc = CRC32().apply { update(b) }
        return DiscDigest(b.size.toLong(), crc.value, MessageDigest.getInstance("SHA-1").digest(b))
    }

    @Test
    fun gameCubeRoundTripsBitForBit() {
        val image = SyntheticDiscs.gameCube()
        val c = convert(image)
        assertEquals(digestOf(image), c.digest)
        val (back, digest) = decode(c.rvz)
        assertArrayEquals(image, back)
        assertEquals(c.digest, digest)
        // Junk is stored as seeds: far smaller than the random half of the files plus headers.
        assertTrue("RVZ is ${c.rvz.length()} bytes for ${image.size}", c.rvz.length() < image.size / 2)
    }

    @Test
    fun wiiRoundTripsBitForBitWithPartitionDecrypted() {
        val wii = SyntheticDiscs.wii()
        val layout = DiscLayout.analyze(MemorySource(wii.bytes))
        assertNull(layout.rawReason)
        assertEquals(1, layout.partitions.size)
        assertEquals(wii.dataOffset, layout.partitions[0].dataOffset)
        val c = convert(wii.bytes)
        val (back, digest) = decode(c.rvz)
        assertArrayEquals(wii.bytes, back)
        assertEquals(digestOf(wii.bytes), digest)
        assertTrue("RVZ is ${c.rvz.length()} bytes for ${wii.bytes.size}", c.rvz.length() < wii.bytes.size / 2)
    }

    @Test
    fun wiiWithUpdateAndGamePartitionsRoundTrips() {
        val wii = SyntheticDiscs.wii(dataSectors = 64 + 3, updatePartition = true)
        val layout = DiscLayout.analyze(MemorySource(wii.bytes))
        assertNull(layout.rawReason)
        assertEquals(listOf(0x50000L, 0x100000L), layout.partitions.map { it.offset })
        val c = convert(wii.bytes)
        assertArrayEquals(wii.bytes, decode(c.rvz).first)
    }

    @Test
    fun wiiHashExceptionsRestoreTamperedHashes() {
        val wii = SyntheticDiscs.wii(dataSectors = 70) { sector, hashes, off ->
            when (sector % 5) {
                0 -> hashes[off + 0x26C + 3] = 0x5A // padding_0
                1 -> hashes[off + 0x330] = 1 // padding_1 (second half)
                2 -> hashes[off + 0x281] = (hashes[off + 0x281] + 1).toByte() // an H1 entry
                3 -> hashes[off + 0x3FF] = 7 // end of padding_2
                else -> hashes[off + 20 * 7] = (hashes[off + 20 * 7] + 1).toByte() // an H0 entry
            }
        }
        val c = convert(wii.bytes, threads = 3)
        val (back, _) = decode(c.rvz)
        assertArrayEquals(wii.bytes, back)
    }

    @Test
    fun wiiWithAnUnknownKeyIsStoredRawAndStillLossless() {
        val wii = SyntheticDiscs.wii(dataSectors = 20)
        wii.bytes[(wii.partitionOffset + 0x1F1).toInt()] = 9 // no such common key
        val layout = DiscLayout.analyze(MemorySource(wii.bytes))
        assertNotNull(layout.rawReason)
        val c = convert(wii.bytes)
        assertArrayEquals(wii.bytes, decode(c.rvz).first)
    }

    @Test
    fun wbfsReadsAsTheScrubbedDisc() {
        val wii = SyntheticDiscs.wii(dataSectors = 20)
        val container = SyntheticDiscs.wbfs(wii.bytes)
        val f = temp(".wbfs").apply { writeBytes(container) }
        FileChannel.open(f.toPath(), READ).use { ch ->
            val src = WbfsSource.open(ch)
            assertEquals(WbfsSource.SINGLE_LAYER_SIZE, src.size)
            val got = ByteArray(wii.bytes.size)
            src.read(0, got, 0, got.size)
            assertArrayEquals(wii.bytes, got)
            val tail = ByteArray(0x1000) { 1 }
            src.read(src.size - 0x1000, tail, 0, tail.size)
            assertTrue(tail.all { it.toInt() == 0 })
            assertEquals(DiscKind.WII, DiscLayout.analyze(src).let { if (it.isWii) DiscKind.WII else DiscKind.GAMECUBE })
        }
    }

    @Test
    fun detectsWhatItCannotConvert() {
        fun head(vararg pairs: Pair<Int, String>) = ByteArray(0x440).also { b ->
            for ((at, s) in pairs) s.forEachIndexed { i, ch -> b[at + i] = ch.code.toByte() }
        }
        assertEquals(DiscKind.RVZ, DiscLayout.detect(head(0 to "RVZ\u0001")))
        assertEquals(DiscKind.WIA, DiscLayout.detect(head(0 to "WIA\u0001")))
        assertEquals(DiscKind.WBFS, DiscLayout.detect(head(0 to "WBFS")))
        assertEquals(DiscKind.OTHER_CONTAINER, DiscLayout.detect(head(0 to "CISO")))
        val nkit = SyntheticDiscs.gameCube(size = 0x100000, files = 1).copyOf(0x440)
        "NKIT".forEachIndexed { i, ch -> nkit[0x200 + i] = ch.code.toByte() }
        assertEquals(DiscKind.NKIT, DiscLayout.detect(nkit))
        assertEquals(DiscKind.GAMECUBE, DiscLayout.detect(SyntheticDiscs.gameCube(size = 0x100000, files = 1)))
        assertEquals(DiscKind.UNKNOWN, DiscLayout.detect(ByteArray(0x440)))
    }

    @Test
    fun corruptRvzIsRejected() {
        val c = convert(SyntheticDiscs.gameCube(size = 0x300000, files = 4))
        val bytes = c.rvz.readBytes()
        bytes[bytes.size - 9] = (bytes[bytes.size - 9] + 1).toByte() // inside the last group
        val bad = temp(".rvz").apply { writeBytes(bytes) }
        val ok = try {
            decode(bad).second == c.digest
        } catch (e: Exception) {
            false
        }
        assertFalse("A damaged RVZ must not decode to the original", ok)

        val header = c.rvz.readBytes().also { it[0x10] = 0x7F } // touches disc_hash
        try {
            FileChannel.open(temp(".rvz").apply { writeBytes(header) }.toPath(), READ).use { RvzReader(it) }
            fail("header hash not checked")
        } catch (_: RvzFormatException) {
        }
    }

    @Test
    fun cancellationStopsTheWriter() {
        val image = SyntheticDiscs.gameCube()
        val iso = temp(".iso").apply { writeBytes(image) }
        val rvz = temp(".rvz")
        var calls = 0
        try {
            FileChannel.open(iso.toPath(), READ).use { input ->
                FileChannel.open(rvz.toPath(), WRITE, CREATE).use { output ->
                    val src = IsoSource(input)
                    RvzWriter(src, DiscLayout.analyze(src), output).write(isCancelled = { ++calls > 5 })
                }
            }
            fail("not cancelled")
        } catch (_: RvzCancelledException) {
        }
    }

    @Test
    fun packingRoundTripsAndFindsJunkAfterFiles() {
        val image = SyntheticDiscs.gameCube(size = 0x200000, files = 3)
        val junk = JunkInfo("GDTE".toByteArray(), 0, DiscLayout.fileEnds(image, isWii = false) { p, o, l -> System.arraycopy(image, p.toInt(), o, 0, l) })
        for (start in listOf(0, 0x20000, 0x60000, 0x1E0000)) {
            val len = minOf(0x20000, image.size - start)
            val chunk = image.copyOfRange(start, start + len)
            val packed = RvzPacking.pack(chunk, len, start.toLong(), junk)
            if (packed != null) {
                val out = ByteArray(len)
                RvzPacking.unpack(packed, 0, packed.size, out, len, start.toLong())
                assertArrayEquals("chunk at $start", chunk, out)
            }
        }
        val tail = image.copyOfRange(0x1E0000, 0x200000)
        val packed = RvzPacking.pack(tail, tail.size, 0x1E0000, junk)
        assertNotNull(packed)
        assertTrue(packed!!.size < 2000)
    }

    /** Writes reference images for the Dolphin cross-check when RVZ_REF_DIR is set. */
    @Test
    fun exportForDolphinCheck() {
        val dir = System.getenv("RVZ_REF_DIR")?.let(::File) ?: return
        dir.mkdirs()
        val cases = mapOf(
            "gc" to SyntheticDiscs.gameCube(),
            "gc-big" to SyntheticDiscs.gameCube(size = 0x1800000 + 0x8000 * 3 + 0x100, files = 40, seed = 9),
            "wii" to SyntheticDiscs.wii().bytes,
            "wii-2parts" to SyntheticDiscs.wii(dataSectors = 64 * 3 + 5, updatePartition = true, seed = 7).bytes,
            "wii-tampered" to SyntheticDiscs.wii(dataSectors = 70) { s, h, o -> if (s % 3 == 0) h[o + 0x3E5] = 9 else if (s % 3 == 1) h[o + 0x26C] = 1 }.bytes,
        )
        for ((name, bytes) in cases) {
            File(dir, "$name.iso").writeBytes(bytes)
            val c = convert(bytes)
            c.rvz.copyTo(File(dir, "$name.dogmatix.rvz"), overwrite = true)
            File(dir, "$name.sha1").writeText(c.digest.sha1Hex + "\n")
        }
        val wbfsDisc = SyntheticDiscs.wii(dataSectors = 20, seed = 5).bytes
        File(dir, "wiiw.wbfs").writeBytes(SyntheticDiscs.wbfs(wbfsDisc))
        val c = convert(File(dir, "wiiw.wbfs").readBytes(), wbfs = true)
        c.rvz.copyTo(File(dir, "wiiw.dogmatix.rvz"), overwrite = true)
        File(dir, "wiiw.sha1").writeText(c.digest.sha1Hex + "\n")
    }

    /** Decodes RVZ files written by dolphin-tool (RVZ_REF_DIR/<name>.dolphin.rvz). */
    @Test
    fun decodesDolphinsRvz() {
        val dir = System.getenv("RVZ_REF_DIR")?.let(::File) ?: return
        val files = dir.listFiles { f -> f.name.endsWith(".dolphin.rvz") }.orEmpty()
        for (f in files) {
            val name = f.name.removeSuffix(".dolphin.rvz")
            val want = File(dir, "$name.sha1").readText().trim()
            val got = decode(f).second.sha1Hex
            println("DOLPHIN-RVZ $name decoded sha1 $got (want $want)")
            assertEquals(name, want, got)
        }
    }

    private class MemorySource(private val b: ByteArray) : DiscSource {
        override val size = b.size.toLong()
        override fun read(position: Long, out: ByteArray, off: Int, len: Int) {
            val avail = (size - position).coerceIn(0, len.toLong()).toInt()
            if (avail > 0) System.arraycopy(b, position.toInt(), out, off, avail)
            if (avail < len) out.fill(0, off + avail, off + len)
        }
    }
}
