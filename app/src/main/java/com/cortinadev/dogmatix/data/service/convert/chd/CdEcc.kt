// Port of MAME's CHD/CD-ROM code (src/lib/util: chd.cpp, chdcodec.cpp, cdrom.cpp, huffman.cpp, bitstream.h),
// BSD-3-Clause, copyright-holders Aaron Giles, R. Belmont. Binary redistribution must reproduce the BSD-3 notice.
package com.cortinadev.dogmatix.data.service.convert.chd

/**
 * CD-ROM sector ECC (Reed-Solomon P and Q parity), as MAME's cdrom.cpp computes it for the cdzl/cdlz
 * codecs: a sector whose sync header and ECC can be regenerated is stored with them zeroed and a bit
 * set, and the decoder rebuilds them. The P/Q layout is the standard one (ECMA-130); in mode 2 the
 * four header bytes count as zero.
 */
object CdEcc {
    const val SYNC_SIZE = 12
    private const val MODE_OFFSET = 15
    private const val P_OFFSET = 2076
    private const val P_BYTES = 86
    private const val P_COMP = 24
    private const val Q_OFFSET = 2248
    private const val Q_BYTES = 52
    private const val Q_COMP = 43

    val SYNC_HEADER = byteArrayOf(0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0)

    /** GF(2^8) multiply by 2 (MAME's ecclow) and its "divide" partner (ecchigh). */
    private val low = IntArray(256)
    private val high = IntArray(256)

    /** Byte offsets (relative to the end of the sync) for every P and Q vector. */
    private val pRows = Array(P_BYTES) { IntArray(P_COMP) }
    private val qRows = Array(Q_BYTES) { IntArray(Q_COMP) }

    init {
        for (i in 0 until 256) {
            val j = ((i shl 1) xor (if (i and 0x80 != 0) 0x11D else 0)) and 0xFF
            low[i] = j
            high[i xor j] = i
        }
        for (major in 0 until P_BYTES) for (minor in 0 until P_COMP) pRows[major][minor] = major + 86 * minor
        val qSize = Q_BYTES * Q_COMP
        for (major in 0 until Q_BYTES) {
            var index = (major shr 1) * 86 + (major and 1)
            for (minor in 0 until Q_COMP) {
                qRows[major][minor] = index
                index += 88
                if (index >= qSize) index -= qSize
            }
        }
    }

    private fun source(sector: ByteArray, base: Int, offset: Int): Int =
        if (sector[base + MODE_OFFSET].toInt() == 2 && offset < 4) 0
        else sector[base + SYNC_SIZE + offset].toInt() and 0xFF

    /** Returns val1 in the low byte and val2 in the next one. */
    private fun compute(sector: ByteArray, base: Int, row: IntArray): Int {
        var v1 = 0
        var v2 = 0
        for (o in row) {
            val b = source(sector, base, o)
            v1 = v1 xor b
            v2 = v2 xor b
            v1 = low[v1]
        }
        v1 = high[low[v1] xor v2]
        v2 = v2 xor v1
        return v1 or (v2 shl 8)
    }

    fun verify(sector: ByteArray, base: Int = 0): Boolean {
        for (b in 0 until P_BYTES) {
            val v = compute(sector, base, pRows[b])
            if ((sector[base + P_OFFSET + b].toInt() and 0xFF) != (v and 0xFF) ||
                (sector[base + P_OFFSET + P_BYTES + b].toInt() and 0xFF) != (v shr 8)) return false
        }
        for (b in 0 until Q_BYTES) {
            val v = compute(sector, base, qRows[b])
            if ((sector[base + Q_OFFSET + b].toInt() and 0xFF) != (v and 0xFF) ||
                (sector[base + Q_OFFSET + Q_BYTES + b].toInt() and 0xFF) != (v shr 8)) return false
        }
        return true
    }

    /** P is written before Q is computed: Q covers the P bytes. */
    fun generate(sector: ByteArray, base: Int = 0) {
        for (b in 0 until P_BYTES) {
            val v = compute(sector, base, pRows[b])
            sector[base + P_OFFSET + b] = v.toByte()
            sector[base + P_OFFSET + P_BYTES + b] = (v shr 8).toByte()
        }
        for (b in 0 until Q_BYTES) {
            val v = compute(sector, base, qRows[b])
            sector[base + Q_OFFSET + b] = v.toByte()
            sector[base + Q_OFFSET + Q_BYTES + b] = (v shr 8).toByte()
        }
    }

    fun clear(sector: ByteArray, base: Int = 0) {
        java.util.Arrays.fill(sector, base + P_OFFSET, base + P_OFFSET + 2 * P_BYTES, 0)
        java.util.Arrays.fill(sector, base + Q_OFFSET, base + Q_OFFSET + 2 * Q_BYTES, 0)
    }

    fun hasSync(sector: ByteArray, base: Int = 0): Boolean {
        for (i in 0 until SYNC_SIZE) if (sector[base + i] != SYNC_HEADER[i]) return false
        return true
    }
}
