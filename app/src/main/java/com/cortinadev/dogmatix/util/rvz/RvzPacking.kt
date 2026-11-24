package com.cortinadev.dogmatix.util.rvz

import java.io.ByteArrayOutputStream

/**
 * «RVZ packing»: runs of LFG junk become `size | 0x80000000` + 68-byte seed, the rest is
 * stored as `size` + bytes. Detection follows nod's packer (MIT OR Apache-2.0, see [Lfg]): first
 * the seed the mastering tool derived from the disc ID, then seed recovery from the data itself;
 * a run never crosses a 32 KiB sector because the junk stream is reseeded there.
 */
object RvzPacking {
    private const val SECTOR = Lfg.SECTOR_SIZE
    private const val MIN_RUN = Lfg.SEED_BYTES + 4

    /**
     * Packs `data[0, len)`, which starts at [sectionOffset] (disc offset for raw data, hash-less
     * partition offset for Wii partition data). Returns null when no junk was found.
     */
    fun pack(data: ByteArray, len: Int, sectionOffset: Long, junk: JunkInfo, lfg: Lfg = Lfg()): ByteArray? {
        // (start, length, seed) of each junk run, in order.
        val runs = ArrayList<Triple<Int, Int, IntArray>>()
        val sectorBuf = ByteArray(SECTOR)
        var lfgSector = -1L
        var offset = sectionOffset
        var pos = 0
        val recovered = IntArray(Lfg.SEED_SIZE)
        while (pos < len) {
            val sector = offset / SECTOR
            val inSector = (offset % SECTOR).toInt()
            if (sector != lfgSector) {
                lfg.initForOffset(junk.discId, junk.discNum, sector * SECTOR)
                lfg.fill(sectorBuf, 0, SECTOR)
                lfgSector = sector
            }

            // Skip zeros (padding after a file), unless the junk itself has them here.
            var zeros = 0
            while (pos + zeros < len && data[pos + zeros].toInt() == 0) zeros++
            if (zeros > 0) {
                var lfgZeros = 0
                while (inSector + lfgZeros < SECTOR && sectorBuf[inSector + lfgZeros].toInt() == 0) lfgZeros++
                if (zeros > lfgZeros) {
                    offset += zeros
                    pos += zeros
                    continue
                }
            }

            val n = minOf(SECTOR - inSector, len - pos)
            val sectorEnd = offset + n
            var match = 0
            while (match < n && data[pos + match] == sectorBuf[inSector + match]) match++
            if (match > MIN_RUN) {
                val seed = IntArray(Lfg.SEED_SIZE)
                Lfg.generateSeed(seed, junk.discId, junk.discNum, sector.toInt())
                runs += Triple(pos, match, seed)
                offset += match
                pos += match
            } else {
                val got = lfg.recoverSeed(data, pos, n, inSector, recovered)
                lfgSector = -1 // recoverSeed reused the generator
                if (got > MIN_RUN) {
                    runs += Triple(pos, got, recovered.copyOf())
                    offset += got
                    pos += got
                }
            }

            if (offset < sectorEnd) {
                // Junk may start right after a file that ends inside this sector.
                val next = junk.nextFileEnd(offset, sectorEnd).let { if (it < 0) sectorEnd else it }
                pos += (next - offset).toInt()
                offset = next
            }
        }
        if (runs.isEmpty()) return null

        val out = ByteArrayOutputStream(len)
        var last = 0
        fun raw(from: Int, to: Int) {
            if (to <= from) return
            out.writeInt(to - from)
            out.write(data, from, to - from)
        }
        for ((start, length, seed) in runs) {
            raw(last, start)
            out.writeInt(length or RvzFormat.COMPRESSED_BIT)
            for (w in seed) out.writeInt(w)
            last = start + length
        }
        raw(last, len)
        return out.toByteArray()
    }

    /** Decodes packed data into `out[0, outLen)`; [sectionOffset] as in [pack]. */
    fun unpack(src: ByteArray, srcOff: Int, srcLen: Int, out: ByteArray, outLen: Int, sectionOffset: Long, lfg: Lfg = Lfg()) {
        var p = srcOff
        val end = srcOff + srcLen
        var written = 0
        val seed = IntArray(Lfg.SEED_SIZE)
        while (p + 4 <= end) {
            val word = Lfg.readIntBE(src, p)
            p += 4
            val size = word and RvzFormat.COMPRESSED_BIT.inv()
            if (size > outLen - written) throw RvzFormatException("Packed data larger than the group")
            if (word and RvzFormat.COMPRESSED_BIT != 0) {
                if (p + Lfg.SEED_BYTES > end) throw RvzFormatException("Truncated junk seed")
                for (i in 0 until Lfg.SEED_SIZE) seed[i] = Lfg.readIntBE(src, p + i * 4)
                p += Lfg.SEED_BYTES
                lfg.initWithSeed(seed)
                lfg.skip(((sectionOffset + written) % SECTOR).toInt())
                lfg.fill(out, written, size)
            } else {
                if (p + size > end) throw RvzFormatException("Truncated packed data")
                System.arraycopy(src, p, out, written, size)
                p += size
            }
            written += size
        }
        if (written != outLen || p != end) throw RvzFormatException("Packed data size mismatch")
    }

    private fun ByteArrayOutputStream.writeInt(v: Int) {
        write(v ushr 24); write(v ushr 16); write(v ushr 8); write(v)
    }
}
