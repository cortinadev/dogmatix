// Port of MAME's CHD/CD-ROM code (src/lib/util: chd.cpp, chdcodec.cpp, cdrom.cpp, huffman.cpp, bitstream.h),
// BSD-3-Clause, copyright-holders Aaron Giles, R. Belmont. Binary redistribution must reproduce the BSD-3 notice.
package com.cortinadev.dogmatix.data.service.convert.chd

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Random-access, read-only source of bytes (a SAF document opened as a seekable fd, a file...). */
interface ChdInput {
    val size: Long

    /** Reads exactly [len] bytes at [position], or throws [EOFException]/[IOException]. Thread-safe. */
    fun readFully(position: Long, dst: ByteArray, off: Int, len: Int)
}

/** A [FileChannel] as input; positional reads, so several threads may share it. The caller closes it. */
class ChannelInput(private val channel: FileChannel) : ChdInput {
    override val size: Long = channel.size()

    override fun readFully(position: Long, dst: ByteArray, off: Int, len: Int) {
        val buf = ByteBuffer.wrap(dst, off, len)
        var pos = position
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) throw EOFException("Unexpected end of input at $pos of $size")
            pos += n
        }
    }
}

/** An in-memory input, for tests and tiny images. */
class BytesInput(private val bytes: ByteArray) : ChdInput {
    override val size: Long get() = bytes.size.toLong()

    override fun readFully(position: Long, dst: ByteArray, off: Int, len: Int) {
        if (position < 0 || position + len > bytes.size) throw EOFException("Read past the end")
        System.arraycopy(bytes, position.toInt(), dst, off, len)
    }
}

/**
 * Lays out the CD frames of a [CdToc] like chdman's chd_cd_compressor::read_data: each frame is
 * [CdToc.FRAME_SIZE] bytes with the sector (and subcode, if any) read from its file at the start and
 * zeros after it; pad frames and the 4-frame track padding are zeros; split frames (GD-ROM cues)
 * continue in the next track's file; audio is byte-swapped to big-endian.
 */
internal class CdFrameSource(private val toc: CdToc, private val files: (String) -> ChdInput) {
    private class Span(val track: CdTrack, val next: CdTrack?, val start: Long, val end: Long)

    private val spans: List<Span>

    init {
        var start = 0L
        spans = toc.tracks.mapIndexed { i, t ->
            val end = start + (t.frames + t.extraFrames).toLong()
            Span(t, toc.tracks.getOrNull(i + 1), start, end).also { start = end }
        }
    }

    /** Fills [dst] with [count] frames starting at frame [first]; frames past the end are zeros. */
    fun read(first: Long, count: Int, dst: ByteArray) {
        java.util.Arrays.fill(dst, 0, count * CdToc.FRAME_SIZE, 0)
        var spanIndex = 0
        for (k in 0 until count) {
            val frame = first + k
            while (spanIndex < spans.size && frame >= spans[spanIndex].end) spanIndex++
            if (spanIndex >= spans.size) return
            val span = spans[spanIndex]
            val t = span.track
            val inTrack = frame - span.start
            if (inTrack >= t.frames) continue // 4-frame padding
            val bpf = t.bytesPerFrame
            val splitStart = t.frames - t.splitFrames
            val padStart = splitStart - t.padFrames
            val out = k * CdToc.FRAME_SIZE
            when {
                t.splitFrames > 0 && inTrack >= splitStart -> {
                    val next = span.next ?: throw IOException("Split track without a following track")
                    files(next.file).readFully((inTrack - splitStart) * bpf, dst, out, bpf)
                }
                inTrack >= padStart -> Unit // pad frames stay zero
                else -> files(t.file).readFully(t.offset + inTrack * bpf, dst, out, bpf)
            }
            if (t.swap) {
                var i = out
                while (i < out + CdToc.MAX_SECTOR_DATA) {
                    val a = dst[i]; dst[i] = dst[i + 1]; dst[i + 1] = a
                    i += 2
                }
            }
        }
    }
}
