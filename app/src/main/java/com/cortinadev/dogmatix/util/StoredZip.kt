package com.cortinadev.dogmatix.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.CRC32

/** One file of a [StoredZip]: its path inside the archive, size, CRC-32 and MS-DOS time ([StoredZip.dosTime]). */
data class ZipEntrySpec(val path: String, val size: Long, val crc32: Long, val dosTime: Long)

/** A file behind a [StoredZip] segment ended before the size it was planned with. */
class SourceChangedException(val entry: Int) : IOException("Entry $entry is shorter than planned")

/**
 * The bytes of an uncompressed ("stored") ZIP of some files, built on the fly: headers in
 * memory, file contents read from their source while streaming. Its size is known before the
 * first byte, it never needs a copy of the files, and the same entries always give the same
 * bytes, so an upload cut short can carry on from any offset. ZIP64 records appear only where a
 * size or offset needs them (games over 4 GiB). [single] sends one file as it is through the
 * same reader.
 */
class StoredZip private constructor(val segments: List<Segment>) {

    sealed interface Segment { val length: Long }
    class Bytes(val bytes: ByteArray) : Segment { override val length: Long get() = bytes.size.toLong() }
    /** [length] bytes of file [entry], from its start. */
    class Content(val entry: Int, override val length: Long) : Segment

    val size: Long = segments.sumOf { it.length }

    /**
     * The archive from byte [offset] on. [open] returns file [entry] positioned at its byte
     * offset (the second argument); each file is opened once, when the stream reaches it.
     */
    fun openAt(offset: Long, open: (entry: Int, offset: Long) -> InputStream): InputStream =
        SegmentStream(segments, offset.coerceIn(0L, size), open)

    companion object {
        private const val MAX32 = 0xFFFFFFFFL
        private const val MAX16 = 0xFFFF
        private const val UTF8_NAMES = 0x0800
        private const val VERSION = 20
        private const val VERSION_ZIP64 = 45
        /** 1980-01-01 00:00, the earliest MS-DOS time. */
        private const val DOS_EPOCH = (((1 shl 5) or 1).toLong() shl 16)

        /**
         * The archive of [entries], in their order. [zip64Threshold] is where 32-bit fields stop
         * being enough; only the tests lower it, to exercise the ZIP64 records on small files.
         */
        fun of(entries: List<ZipEntrySpec>, zip64Threshold: Long = MAX32): StoredZip {
            val segments = mutableListOf<Segment>()
            val central = Le()
            var offset = 0L
            entries.forEachIndexed { index, entry ->
                val name = entry.path.toByteArray(Charsets.UTF_8)
                val bigSize = entry.size >= zip64Threshold
                val bigOffset = offset >= zip64Threshold
                val time = entry.dosTime and 0xFFFF
                val date = (entry.dosTime ushr 16) and 0xFFFF

                val local = Le().apply {
                    int(0x04034b50)
                    short(if (bigSize) VERSION_ZIP64 else VERSION)
                    short(UTF8_NAMES)
                    short(0)                       // stored
                    short(time); short(date)
                    int(entry.crc32)
                    int(if (bigSize) MAX32 else entry.size)
                    int(if (bigSize) MAX32 else entry.size)
                    short(name.size)
                    short(if (bigSize) 20 else 0)
                    bytes(name)
                    if (bigSize) { short(0x0001); short(16); long(entry.size); long(entry.size) }
                }.toByteArray()
                segments += Bytes(local)
                segments += Content(index, entry.size)

                val zip64Fields = (if (bigSize) 2 else 0) + (if (bigOffset) 1 else 0)
                val zip64 = zip64Fields > 0
                central.apply {
                    int(0x02014b50)
                    short(if (zip64) VERSION_ZIP64 else VERSION)    // made by (MS-DOS attributes)
                    short(if (zip64) VERSION_ZIP64 else VERSION)    // needed
                    short(UTF8_NAMES)
                    short(0)
                    short(time); short(date)
                    int(entry.crc32)
                    int(if (bigSize) MAX32 else entry.size)
                    int(if (bigSize) MAX32 else entry.size)
                    short(name.size)
                    short(if (zip64) 4 + 8 * zip64Fields else 0)
                    short(0); short(0); short(0)                   // comment, disk, internal attributes
                    int(0)                                          // external attributes
                    int(if (bigOffset) MAX32 else offset)
                    bytes(name)
                    if (zip64) {
                        short(0x0001); short(8 * zip64Fields)
                        if (bigSize) { long(entry.size); long(entry.size) }
                        if (bigOffset) long(offset)
                    }
                }
                offset += local.size + entry.size
            }

            val centralBytes = central.toByteArray()
            val centralSize = centralBytes.size.toLong()
            val count = entries.size
            val zip64End = count >= MAX16 || centralSize >= zip64Threshold || offset >= zip64Threshold
            val end = Le().apply {
                if (zip64End) {
                    val recordOffset = offset + centralSize
                    int(0x06064b50); long(44)
                    short(VERSION_ZIP64); short(VERSION_ZIP64)
                    int(0); int(0)
                    long(count.toLong()); long(count.toLong())
                    long(centralSize); long(offset)
                    int(0x07064b50); int(0); long(recordOffset); int(1)
                }
                int(0x06054b50)
                short(0); short(0)
                short(if (count >= MAX16) MAX16 else count)
                short(if (count >= MAX16) MAX16 else count)
                int(if (zip64End && centralSize >= zip64Threshold) MAX32 else centralSize)
                int(if (zip64End && offset >= zip64Threshold) MAX32 else offset)
                short(0)
            }.toByteArray()
            segments += Bytes(centralBytes + end)
            return StoredZip(segments)
        }

        /** One file of [size] bytes as it is, no archive. */
        fun single(size: Long): StoredZip = StoredZip(listOf(Content(0, size)))

        /** [epochMillis] as an MS-DOS date (high 16 bits) and time (low 16, two-second steps), in [zone]. */
        fun dosTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
            val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
            if (t.year < 1980) return DOS_EPOCH
            if (t.year > 2107) return (((127 shl 9) or (12 shl 5) or 31).toLong() shl 16) or ((23 shl 11) or (59 shl 5) or 29).toLong()
            val date = ((t.year - 1980) shl 9) or (t.monthValue shl 5) or t.dayOfMonth
            val time = (t.hour shl 11) or (t.minute shl 5) or (t.second / 2)
            return (date.toLong() shl 16) or time.toLong()
        }

        /** CRC-32 of everything [input] holds; [onRead] gets each block's size (for a progress bar). */
        fun crc32(input: InputStream, onRead: (Int) -> Unit = {}): Long {
            val crc = CRC32()
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                crc.update(buffer, 0, n)
                onRead(n)
            }
            return crc.value
        }
    }

    /** Little-endian writer for the headers. */
    private class Le : ByteArrayOutputStream() {
        fun short(v: Int) { write(v and 0xFF); write((v ushr 8) and 0xFF) }
        fun int(v: Long) { for (i in 0 until 4) write(((v ushr (8 * i)) and 0xFF).toInt()) }
        fun int(v: Int) = int(v.toLong() and MAX32)
        fun long(v: Long) { for (i in 0 until 8) write(((v ushr (8 * i)) and 0xFF).toInt()) }
        fun short(v: Long) = short(v.toInt())
        fun bytes(b: ByteArray) = write(b, 0, b.size)
    }

    private class SegmentStream(
        private val segments: List<Segment>,
        start: Long,
        private val open: (Int, Long) -> InputStream
    ) : InputStream() {
        private var index = 0
        private var within = 0L
        private var current: InputStream? = null

        init {
            var remaining = start
            while (index < segments.size && remaining >= segments[index].length) {
                remaining -= segments[index].length
                index++
            }
            within = remaining
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (index < segments.size) {
                val segment = segments[index]
                val left = segment.length - within
                if (left <= 0) { next(); continue }
                val want = minOf(len.toLong(), left).toInt()
                val n = when (segment) {
                    is Bytes -> { System.arraycopy(segment.bytes, within.toInt(), b, off, want); want }
                    is Content -> {
                        val input = current ?: open(segment.entry, within).also { current = it }
                        input.read(b, off, want).also { if (it < 0) throw SourceChangedException(segment.entry) }
                    }
                }
                within += n
                return n
            }
            return -1
        }

        private fun next() {
            current?.close()
            current = null
            index++
            within = 0
        }

        override fun close() {
            current?.close()
            current = null
            index = segments.size
        }
    }
}
