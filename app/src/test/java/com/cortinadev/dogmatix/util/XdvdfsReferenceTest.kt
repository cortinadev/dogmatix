package com.cortinadev.dogmatix.util

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Cross-check with extract-xiso, run by hand: with `XDVDFS_REF_DIR` pointing at a folder holding
 * `ref.xiso` (an XISO extract-xiso created with `-c`), it wraps it as a Redump image in memory
 * (0x18300000 zero bytes in front, 1 MiB behind), converts that, and writes `mine.xiso` next to it
 * for `extract-xiso -l / -x / -r` to compare. Skipped otherwise.
 */
class XdvdfsReferenceTest {

    private class Wrapped(private val file: RandomAccessFile) : SeekableSource {
        private val length = file.length()
        override val size: Long = Xdvdfs.REDUMP_GAME_PARTITION + length + (1 shl 20)
        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            val n = minOf(length.toLong(), size - position).toInt()
            buffer.fill(0, offset, offset + n)
            val start = maxOf(position, Xdvdfs.REDUMP_GAME_PARTITION)
            val end = minOf(position + n, Xdvdfs.REDUMP_GAME_PARTITION + this.length)
            if (start < end) {
                file.seek(start - Xdvdfs.REDUMP_GAME_PARTITION)
                file.readFully(buffer, offset + (start - position).toInt(), (end - start).toInt())
            }
            return n
        }
    }

    @Test
    fun `a redump wrap of an extract-xiso image converts and checks`() {
        val dir = System.getenv("XDVDFS_REF_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "ref.xiso").isFile)
        RandomAccessFile(File(dir, "ref.xiso"), "r").use { ref ->
            val source = Wrapped(ref)
            val volume = Xdvdfs.detect(source)
            assertEquals(Xdvdfs.Layout.REDUMP, volume.layout)
            val plan = Xdvdfs.plan(source, volume)
            val out = File(dir, "mine.xiso")
            val written = out.outputStream().buffered().use { stream -> Xdvdfs.write(source, plan, { b, o, l -> stream.write(b, o, l) }) }
            RandomAccessFile(out, "r").use { mine ->
                val check = object : SeekableSource {
                    override val size = mine.length()
                    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                        mine.seek(position)
                        return mine.read(buffer, offset, length)
                    }
                }
                assertTrue(Xdvdfs.verify(check, plan, written))
            }
        }
    }
}
