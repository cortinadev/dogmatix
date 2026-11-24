// Port of MAME's CHD/CD-ROM code (src/lib/util: chd.cpp, chdcodec.cpp, cdrom.cpp, huffman.cpp, bitstream.h),
// BSD-3-Clause, copyright-holders Aaron Giles, R. Belmont. Binary redistribution must reproduce the BSD-3 notice.
package com.cortinadev.dogmatix.data.service.convert.chd

/*
 * The compressed hunk map of a CHD v5 (chd.cpp: compress_v5_map / decompress_v5_map, BSD-3-Clause).
 * Each hunk has a type (codec 0-3, none, copy of an earlier hunk), a compressed length, a file offset
 * and a CRC-16 of its data. On disk: a 16-byte header, a Huffman tree for the RLE-coded types, the
 * types, then per hunk the length and CRC (or the referenced hunk). The writer uses a fixed 4-bit
 * code for all 16 symbols (a valid canonical tree); MAME builds an optimal one, which only saves a
 * few bytes. The reader understands any tree.
 */
object ChdMap {
    const val TYPE_0 = 0
    const val NONE = 4
    const val SELF = 5
    const val PARENT = 6
    private const val RLE_SMALL = 7
    private const val RLE_LARGE = 8
    private const val SELF_0 = 9
    private const val SELF_1 = 10
    private const val PARENT_SELF = 11
    private const val PARENT_0 = 12
    private const val PARENT_1 = 13

    /** Decoded map, one entry per hunk (the "raw map" of chd.cpp). */
    class Entries(val count: Int) {
        val type = ByteArray(count)
        val length = IntArray(count)
        val offset = LongArray(count)
        val crc = IntArray(count)

        /** The 12-byte-per-hunk raw map whose CRC-16 the file stores. */
        fun rawBytes(): ByteArray {
            val raw = ByteArray(count * 12)
            for (i in 0 until count) {
                val p = i * 12
                raw[p] = type[i]
                raw[p + 1] = (length[i] ushr 16).toByte()
                raw[p + 2] = (length[i] ushr 8).toByte()
                raw[p + 3] = length[i].toByte()
                for (b in 0 until 6) raw[p + 4 + b] = (offset[i] ushr (40 - 8 * b)).toByte()
                raw[p + 10] = (crc[i] ushr 8).toByte()
                raw[p + 11] = crc[i].toByte()
            }
            return raw
        }
    }

    private fun bitsFor(value: Long): Int {
        var v = value
        var bits = 0
        while (v != 0L) { v = v ushr 1; bits++ }
        return bits
    }

    /** The map as stored at the map offset: 16-byte header followed by the bitstream. */
    fun encode(map: Entries): ByteArray {
        val n = map.count
        val mapCrc = Crc16.of(map.rawBytes())
        val rle = ByteArray(n * 3 + 3)
        var rleLen = 0
        var maxSelf = 0L
        var lastSelf = 0L
        var maxComplen = 0
        var lastComp = 0
        var count = 0
        val promoted = IntArray(n)
        for (h in 0 until n) {
            var cur = map.type[h].toInt()
            if (cur == SELF) {
                val ref = map.offset[h]
                cur = when (ref) {
                    lastSelf -> SELF_0
                    lastSelf + 1 -> SELF_1
                    else -> { maxSelf = maxOf(maxSelf, ref); SELF }
                }
                lastSelf = ref
            } else if (cur != PARENT) {
                maxComplen = maxOf(maxComplen, map.length[h])
            }
            promoted[h] = cur
            if (cur == lastComp) count++
            if (cur != lastComp || h == n - 1) {
                while (count != 0) {
                    if (count < 3) {
                        rle[rleLen++] = lastComp.toByte(); count--
                    } else if (count <= 3 + 15) {
                        rle[rleLen++] = RLE_SMALL.toByte(); rle[rleLen++] = (count - 3).toByte(); count = 0
                    } else {
                        val this_ = minOf(count, 3 + 16 + 255)
                        rle[rleLen++] = RLE_LARGE.toByte()
                        rle[rleLen++] = ((this_ - 3 - 16) shr 4).toByte()
                        rle[rleLen++] = ((this_ - 3 - 16) and 15).toByte()
                        count -= this_
                    }
                }
                if (cur != lastComp) {
                    lastComp = cur
                    rle[rleLen++] = cur.toByte()
                }
            }
        }
        val lengthBits = bitsFor(maxComplen.toLong())
        val selfBits = bitsFor(maxSelf)
        val parentBits = 0

        val bits = BitWriter(16 + n * 8 + 64)
        // Tree: every one of the 16 codes is 4 bits long (written raw, a 4 is never the escape 1).
        repeat(16) { bits.write(4, 4) }
        for (i in 0 until rleLen) bits.write(rle[i].toInt(), 4)

        var src = 0
        count = 0
        lastComp = 0
        var firstOffs = 0L
        for (h in 0 until n) {
            if (count == 0) {
                val v = rle[src++].toInt()
                when (v) {
                    RLE_SMALL -> count = 2 + rle[src++]
                    RLE_LARGE -> { count = 2 + 16 + (rle[src++].toInt() shl 4); count += rle[src++] }
                    else -> lastComp = v
                }
            } else {
                count--
            }
            when (lastComp) {
                in TYPE_0..TYPE_0 + 3 -> {
                    bits.write(map.length[h], lengthBits)
                    bits.write(map.crc[h], 16)
                    if (firstOffs == 0L) firstOffs = map.offset[h]
                }
                NONE -> {
                    bits.write(map.crc[h], 16)
                    if (firstOffs == 0L) firstOffs = map.offset[h]
                }
                SELF -> bits.write(map.offset[h].toInt(), selfBits)
            }
        }
        val body = bits.finish()
        val out = ByteArray(16 + body.size)
        putInt(out, 0, body.size)
        for (b in 0 until 6) out[4 + b] = (firstOffs ushr (40 - 8 * b)).toByte()
        out[10] = (mapCrc ushr 8).toByte()
        out[11] = mapCrc.toByte()
        out[12] = lengthBits.toByte()
        out[13] = selfBits.toByte()
        out[14] = parentBits.toByte()
        out[15] = 0
        System.arraycopy(body, 0, out, 16, body.size)
        return out
    }

    /** Parses a stored map ([data] starts at the 16-byte header). Throws [ChdFormatException]. */
    fun decode(data: ByteArray, hunkCount: Int, hunkBytes: Int): Entries {
        if (data.size < 16) throw ChdFormatException("Map header truncated")
        val mapBytes = getInt(data, 0)
        var firstOffs = 0L
        for (b in 0 until 6) firstOffs = (firstOffs shl 8) or (data[4 + b].toLong() and 0xFF)
        val mapCrc = ((data[10].toInt() and 0xFF) shl 8) or (data[11].toInt() and 0xFF)
        val lengthBits = data[12].toInt()
        val selfBits = data[13].toInt()
        val parentBits = data[14].toInt()
        if (mapBytes < 0 || 16L + mapBytes > data.size) throw ChdFormatException("Map truncated")
        val bits = BitReader(data, 16, mapBytes)
        val huffman = Huffman.importTreeRle(bits)

        val map = Entries(hunkCount)
        var lastComp = 0
        var rep = 0
        for (h in 0 until hunkCount) {
            if (rep > 0) {
                map.type[h] = lastComp.toByte(); rep--
            } else {
                when (val v = huffman.decode(bits)) {
                    RLE_SMALL -> { map.type[h] = lastComp.toByte(); rep = 2 + huffman.decode(bits) }
                    RLE_LARGE -> {
                        map.type[h] = lastComp.toByte()
                        rep = 2 + 16 + (huffman.decode(bits) shl 4)
                        rep += huffman.decode(bits)
                    }
                    else -> { lastComp = v; map.type[h] = v.toByte() }
                }
            }
        }
        var cur = firstOffs
        var lastSelf = 0L
        var lastParent = 0L
        for (h in 0 until hunkCount) {
            var offset = cur
            var length = 0
            var crc = 0
            when (val t = map.type[h].toInt()) {
                in TYPE_0..TYPE_0 + 3 -> { length = bits.read(lengthBits); cur += length; crc = bits.read(16) }
                NONE -> { length = hunkBytes; cur += length; crc = bits.read(16) }
                SELF -> { lastSelf = bits.read(selfBits).toLong(); offset = lastSelf }
                PARENT -> { lastParent = bits.readLong(parentBits); offset = lastParent }
                SELF_1, SELF_0 -> {
                    if (t == SELF_1) lastSelf++
                    map.type[h] = SELF.toByte(); offset = lastSelf
                }
                PARENT_SELF -> {
                    map.type[h] = PARENT.toByte()
                    lastParent = h.toLong() * hunkBytes; offset = lastParent
                }
                PARENT_1, PARENT_0 -> {
                    if (t == PARENT_1) lastParent += hunkBytes
                    map.type[h] = PARENT.toByte(); offset = lastParent
                }
                else -> throw ChdFormatException("Unknown map entry type $t")
            }
            map.length[h] = length
            map.offset[h] = offset
            map.crc[h] = crc
        }
        if (bits.overflow) throw ChdFormatException("Map bitstream truncated")
        if (Crc16.of(map.rawBytes()) != mapCrc) throw ChdFormatException("Map CRC mismatch")
        return map
    }

    internal fun putInt(b: ByteArray, p: Int, v: Int) {
        b[p] = (v ushr 24).toByte(); b[p + 1] = (v ushr 16).toByte(); b[p + 2] = (v ushr 8).toByte(); b[p + 3] = v.toByte()
    }

    internal fun getInt(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
            ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
}

/** MSB-first bit writer (bitstream_out). */
internal class BitWriter(initial: Int) {
    private var buf = ByteArray(maxOf(16, initial))
    private var bytes = 0
    private var acc = 0L
    private var nbits = 0

    fun write(value: Int, count: Int) {
        if (count == 0) return
        acc = (acc shl count) or (value.toLong() and ((1L shl count) - 1))
        nbits += count
        while (nbits >= 8) {
            put((acc ushr (nbits - 8)).toInt())
            nbits -= 8
        }
        acc = acc and ((1L shl nbits) - 1)
    }

    private fun put(b: Int) {
        if (bytes == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[bytes++] = b.toByte()
    }

    fun finish(): ByteArray {
        if (nbits > 0) put((acc shl (8 - nbits)).toInt())
        nbits = 0
        return buf.copyOf(bytes)
    }
}

/** MSB-first bit reader (bitstream_in): reading past the end yields zeros and sets [overflow]. */
internal class BitReader(private val data: ByteArray, private val start: Int, private val length: Int) {
    private var pos = 0L // in bits
    var overflow = false
        private set

    fun read(count: Int): Int = readLong(count).toInt()

    fun readLong(count: Int): Long {
        var v = 0L
        repeat(count) { v = (v shl 1) or bit(pos++).toLong() }
        return v
    }

    fun peek(count: Int): Int {
        var v = 0
        for (i in 0 until count) v = (v shl 1) or bit(pos + i)
        return v
    }

    fun skip(count: Int) { pos += count; if (pos > length * 8L) overflow = true }

    private fun bit(p: Long): Int {
        val byte = (p ushr 3).toInt()
        if (byte >= length) { overflow = true; return 0 }
        return (data[start + byte].toInt() ushr (7 - (p and 7).toInt())) and 1
    }
}

/** huffman_decoder<16, 8> with the RLE tree import of MAME's huffman.cpp. */
internal class Huffman private constructor(private val lookup: IntArray, private val maxBits: Int) {

    fun decode(bits: BitReader): Int {
        val entry = lookup[bits.peek(maxBits)]
        bits.skip(entry and 0x1F)
        return entry ushr 5
    }

    companion object {
        private const val CODES = 16
        private const val MAX_BITS = 8

        fun importTreeRle(bits: BitReader): Huffman {
            val numBits = 4 // maxbits 8 → 4 bits per entry
            val lengths = IntArray(CODES)
            var cur = 0
            while (cur < CODES) {
                var nodeBits = bits.read(numBits)
                if (nodeBits != 1) {
                    lengths[cur++] = nodeBits
                } else {
                    nodeBits = bits.read(numBits)
                    if (nodeBits == 1) {
                        lengths[cur++] = nodeBits
                    } else {
                        var rep = bits.read(numBits) + 3
                        while (rep-- > 0) {
                            if (cur >= CODES) throw ChdFormatException("Huffman tree overflow")
                            lengths[cur++] = nodeBits
                        }
                    }
                }
            }
            // Canonical codes, longest first (assign_canonical_codes).
            val histo = IntArray(33)
            for (l in lengths) {
                if (l > MAX_BITS) throw ChdFormatException("Huffman code too long")
                histo[l]++
            }
            var start = 0
            for (len in 32 downTo 1) {
                val next = (start + histo[len]) shr 1
                if (len != 1 && next * 2 != start + histo[len]) throw ChdFormatException("Inconsistent Huffman tree")
                histo[len] = start
                start = next
            }
            val codes = IntArray(CODES)
            for (c in 0 until CODES) if (lengths[c] > 0) codes[c] = histo[lengths[c]]++
            val lookup = IntArray(1 shl MAX_BITS)
            for (c in 0 until CODES) {
                val len = lengths[c]
                if (len == 0) continue
                val shift = MAX_BITS - len
                val first = codes[c] shl shift
                val last = ((codes[c] + 1) shl shift) - 1
                for (i in first..last) if (i in lookup.indices) lookup[i] = (c shl 5) or len
            }
            if (bits.overflow) throw ChdFormatException("Huffman tree truncated")
            return Huffman(lookup, MAX_BITS)
        }
    }
}

/** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF), MAME's crc16_creator. */
object Crc16 {
    private val table = IntArray(256) { i ->
        var c = i shl 8
        repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 }
        c and 0xFFFF
    }

    fun of(data: ByteArray, off: Int = 0, len: Int = data.size - off): Int {
        var crc = 0xFFFF
        for (i in off until off + len) crc = ((crc shl 8) xor table[((crc ushr 8) xor data[i].toInt()) and 0xFF]) and 0xFFFF
        return crc
    }
}
