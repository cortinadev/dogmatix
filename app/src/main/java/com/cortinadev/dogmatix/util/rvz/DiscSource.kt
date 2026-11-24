package com.cortinadev.dogmatix.util.rvz

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Positional, thread-safe reads of a disc image as the plain ISO it represents. */
interface DiscSource {
    val size: Long

    /** Fills `out[off, off + len)` with the disc bytes at [position]; past the end reads zeros. */
    fun read(position: Long, out: ByteArray, off: Int, len: Int)
}

/** Reads all of `[position, position + len)` from a channel or throws [EOFException]. */
internal fun FileChannel.readFully(position: Long, out: ByteArray, off: Int, len: Int) {
    val buf = ByteBuffer.wrap(out, off, len)
    var p = position
    while (buf.hasRemaining()) {
        val n = read(buf, p)
        if (n < 0) throw EOFException("Unexpected end of file at $p")
        p += n
    }
}

internal fun FileChannel.writeFully(position: Long, data: ByteArray, off: Int = 0, len: Int = data.size - off) {
    val buf = ByteBuffer.wrap(data, off, len)
    var p = position
    while (buf.hasRemaining()) p += write(buf, p)
}

/** A plain `.iso` / `.gcm`. */
class IsoSource(private val channel: FileChannel) : DiscSource {
    override val size: Long = channel.size()

    override fun read(position: Long, out: ByteArray, off: Int, len: Int) {
        val avail = (size - position).coerceIn(0, len.toLong()).toInt()
        if (avail > 0) channel.readFully(position, out, off, avail)
        if (avail < len) out.fill(0, off + avail, off + len)
    }
}

/**
 * A single-file WBFS container (the format USB loaders use). Blocks that WBFS dropped (scrubbed
 * padding) read as zeros, so the result is the scrubbed disc, as with Dolphin.
 */
class WbfsSource private constructor(
    private val channel: FileChannel,
    private val blockSize: Int,
    private val table: IntArray,
    override val size: Long,
) : DiscSource {

    override fun read(position: Long, out: ByteArray, off: Int, len: Int) {
        var pos = position
        var o = off
        val end = off + len
        while (o < end) {
            val block = (pos / blockSize).toInt()
            val inBlock = (pos % blockSize).toInt()
            val n = minOf(end - o, blockSize - inBlock)
            val physical = if (block < table.size) table[block] else 0
            if (physical == 0 || pos >= size) {
                out.fill(0, o, o + n)
            } else {
                channel.readFully(physical.toLong() * blockSize + inBlock, out, o, n)
            }
            o += n
            pos += n
        }
    }

    companion object {
        private const val WII_SECTORS_SINGLE = 143432L
        private const val WII_SECTORS_DOUBLE = WII_SECTORS_SINGLE * 2
        private const val WII_SECTOR = 0x8000L
        const val SINGLE_LAYER_SIZE = WII_SECTORS_SINGLE * WII_SECTOR
        const val DOUBLE_LAYER_SIZE = 8_511_160_320L

        fun isWbfs(head: ByteArray): Boolean =
            head.size >= 4 && head[0] == 'W'.code.toByte() && head[1] == 'B'.code.toByte() &&
                head[2] == 'F'.code.toByte() && head[3] == 'S'.code.toByte()

        fun open(channel: FileChannel): WbfsSource {
            val head = ByteArray(0x200)
            channel.readFully(0, head, 0, minOf(head.size.toLong(), channel.size()).toInt())
            if (!isWbfs(head)) throw IOException("Not a WBFS file")
            val hdSectorShift = head[8].toInt() and 0xFF
            val wbfsSectorShift = head[9].toInt() and 0xFF
            if (hdSectorShift !in 9..12 || wbfsSectorShift !in 15..30 || wbfsSectorShift < hdSectorShift) {
                throw IOException("Unsupported WBFS geometry")
            }
            val hdSector = 1 shl hdSectorShift
            val blockSize = 1 shl wbfsSectorShift
            val blocksPerDisc = ((WII_SECTORS_DOUBLE * WII_SECTOR) shr wbfsSectorShift).toInt()
            val discInfoSize = alignUp(0x100L + blocksPerDisc * 2L, hdSector.toLong())
            // Disc table starts at 0x0C; take the first used slot.
            var slot = -1
            for (i in 0x0C until hdSector) {
                if (i >= head.size) break
                if (head[i].toInt() != 0) { slot = i - 0x0C; break }
            }
            if (slot < 0) throw IOException("The WBFS file holds no disc")
            val infoOffset = hdSector + slot * discInfoSize
            val raw = ByteArray(blocksPerDisc * 2)
            channel.readFully(infoOffset + 0x100, raw, 0, raw.size)
            val table = IntArray(blocksPerDisc) { ((raw[it * 2].toInt() and 0xFF) shl 8) or (raw[it * 2 + 1].toInt() and 0xFF) }
            var lastUsed = -1
            for (i in table.indices) {
                if (table[i] != 0) {
                    lastUsed = i
                    if ((table[i].toLong() + 1) * blockSize > channel.size()) {
                        throw EOFException("The WBFS file is incomplete (split files are not supported)")
                    }
                }
            }
            if (lastUsed < 0) throw IOException("The WBFS disc is empty")
            val used = (lastUsed + 1).toLong() * blockSize
            val size = if (used > SINGLE_LAYER_SIZE) DOUBLE_LAYER_SIZE else SINGLE_LAYER_SIZE
            return WbfsSource(channel, blockSize, table, size)
        }

        private fun alignUp(v: Long, a: Long) = (v + a - 1) / a * a
    }
}
