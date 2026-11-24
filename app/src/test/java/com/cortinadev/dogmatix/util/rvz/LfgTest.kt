package com.cortinadev.dogmatix.util.rvz

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LfgTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun id(s: String) = s.toByteArray()

    private fun junk(id: String, num: Int, offset: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        SyntheticDiscs.fillJunk(out, offset, offset, offset + len, id(id), num)
        return out
    }

    // Reference vectors from nod's lfg.rs tests (themselves checked against real discs).
    @Test
    fun matchesKnownJunk() {
        assertArrayEquals(
            bytes(0xE9, 0x47, 0x67, 0xBD, 0x41, 0x50, 0x4D, 0x5D, 0x61, 0x48, 0xB1, 0x99, 0xA0, 0x12, 0x0C, 0xBA),
            junk("GALE", 0, 0x600000, 16),
        )
        assertArrayEquals(
            bytes(0xE2, 0xBB, 0xBD, 0x77, 0xDA, 0xB2, 0x22, 0x42, 0x1C, 0x0C, 0x0B, 0xFC, 0xAC, 0x06, 0xEA, 0xD0),
            junk("GALE", 0, 0x608000, 16),
        )
        assertArrayEquals(
            bytes(0x97, 0xD8, 0x23, 0x0B, 0x12, 0xAA, 0x20, 0x45, 0xC2, 0xBD, 0x71, 0x8C, 0x30, 0x32, 0xC5, 0x2F),
            junk("GPIE", 0, 0x322904, 16),
        )
    }

    @Test
    fun reseedsAtSectorBoundaries() {
        assertArrayEquals(
            bytes(
                0xAD, 0x6F, 0x21, 0xBE, 0x05, 0x57, 0x10, 0xED, 0xEA, 0xB0, 0x8E, 0xFD, 0x91, 0x58, 0xA2, 0x0E,
                0xDC, 0x0D, 0x59, 0xC0, 0x02, 0x98, 0xA5, 0x00, 0x39, 0x5B, 0x68, 0xA6, 0x5D, 0x53, 0x2D, 0xB6,
            ),
            junk("GM8E", 0, 0x27FF0, 32),
        )
    }

    @Test
    fun recoversTheSeedOfAlignedJunk() {
        val data = junk("GKQJ", 0, 3L * 0x8000, 0x8000)
        val seed = IntArray(Lfg.SEED_SIZE)
        assertEquals(0x8000, Lfg().recoverSeed(data, 0, data.size, 0, seed))
        val check = Lfg().apply { initWithSeed(seed) }
        val out = ByteArray(0x8000)
        check.fill(out, 0, out.size)
        assertArrayEquals(data, out)
    }

    @Test
    fun recoversTheSeedMidSector() {
        val sector = junk("RELS", 1, 7L * 0x8000, 0x8000)
        val off = 0x1235
        val seed = IntArray(Lfg.SEED_SIZE)
        assertEquals(0x8000 - off, Lfg().recoverSeed(sector, off, 0x8000 - off, off, seed))
    }

    @Test
    fun rejectsDataThatIsNotJunk() {
        val seed = IntArray(Lfg.SEED_SIZE)
        assertEquals(0, Lfg().recoverSeed(ByteArray(0x8000) { 0xA5.toByte() }, 0, 0x8000, 0, seed))
        val short = junk("GKQJ", 0, 0, Lfg.K_BYTES - 4)
        assertEquals(0, Lfg().recoverSeed(short, 0, short.size, 0, seed))
    }

    @Test
    fun generatedSeedReproducesJunk() {
        val seed = IntArray(Lfg.SEED_SIZE)
        Lfg.generateSeed(seed, id("GALE"), 0, 0x600000 / 0x8000)
        val lfg = Lfg().apply { initWithSeed(seed) }
        val out = ByteArray(16)
        lfg.fill(out, 0, 16)
        assertArrayEquals(junk("GALE", 0, 0x600000, 16), out)
    }
}
