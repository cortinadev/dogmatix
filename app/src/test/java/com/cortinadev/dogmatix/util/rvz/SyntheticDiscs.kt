package com.cortinadev.dogmatix.util.rvz

import java.nio.ByteBuffer
import java.util.Random

/**
 * Tiny, made-up GameCube and Wii images with the structures RVZ cares about: a disc header, a
 * boot header and FST, files (random and compressible), zero padding and LFG junk everywhere
 * else, and for Wii an encrypted, hashed partition with a ticket, TMD and H3 table.
 */
object SyntheticDiscs {
    private const val SECTOR = 0x8000

    private fun putAscii(b: ByteArray, at: Int, s: String) {
        s.forEachIndexed { i, c -> b[at + i] = c.code.toByte() }
    }

    private fun putInt(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
    }

    /** Junk for `[start, end)` of an address space seeded per 32 KiB sector. */
    fun fillJunk(b: ByteArray, bufBase: Long, start: Long, end: Long, id: ByteArray, discNum: Int) {
        val lfg = Lfg()
        var p = start
        while (p < end) {
            val n = minOf(end - p, SECTOR - p % SECTOR).toInt()
            lfg.initForOffset(id, discNum, p)
            lfg.fill(b, (p - bufBase).toInt(), n)
            p += n
        }
    }

    private class File(val offset: Long, val length: Int)

    /**
     * Fills [b] (an address space starting at 0) as a disc filesystem: header, boot header,
     * FST, [files] files and junk. [shift] is 2 for Wii (offsets stored >> 2).
     */
    private fun layoutFilesystem(b: ByteArray, id: String, wii: Boolean, files: Int, rnd: Random) {
        val shift = if (wii) 2 else 0
        putAscii(b, 0, id)
        if (wii) putInt(b, 0x18, 0x5D1C9EA3) else putInt(b, 0x1C, 0xC2339F3D.toInt())
        putAscii(b, 0x20, "DOGMATIX RVZ TEST")
        // FST at 0x2440 (after a fake apploader area).
        val fstOff = 0x2440
        val names = (0 until files).map { "file$it.bin" }
        val nameTable = names.joinToString("\u0000", postfix = "\u0000").toByteArray()
        val fstSize = (files + 1) * 12 + nameTable.size
        val list = ArrayList<File>()
        var cursor = alignUp((fstOff + fstSize).toLong(), 0x8000) + 0x1000
        for (i in 0 until files) {
            val len = 0x3000 + rnd.nextInt(0x30000)
            list += File(cursor, len)
            // Next file after some junk, at a 4-byte boundary, sometimes in the same sector.
            cursor = alignUp(cursor + len + rnd.nextInt(0x12000), 4)
        }
        require(cursor < b.size) { "Image too small for the files" }
        putInt(b, 0x424, fstOff ushr shift)
        putInt(b, 0x428, fstSize ushr shift)
        putInt(b, 0x42C, fstSize ushr shift)
        // Root entry, then files.
        b[fstOff] = 1
        putInt(b, fstOff + 8, files + 1)
        var nameOff = 0
        list.forEachIndexed { i, f ->
            val e = fstOff + (i + 1) * 12
            putInt(b, e, nameOff)
            putInt(b, e + 4, (f.offset ushr shift).toInt())
            putInt(b, e + 8, f.length)
            nameOff += names[i].length + 1
        }
        System.arraycopy(nameTable, 0, b, fstOff + (files + 1) * 12, nameTable.size)

        // Junk after the FST and between files; zeros up to 4 bytes after each file.
        val idBytes = id.toByteArray().copyOf(4)
        var junkFrom = alignUp((fstOff + fstSize).toLong(), 4)
        for (f in list) {
            fillJunk(b, 0, junkFrom, f.offset, idBytes, 0)
            list.indexOf(f).let { i ->
                if (i % 2 == 0) {
                    val random = ByteArray(f.length).also { rnd.nextBytes(it) }
                    System.arraycopy(random, 0, b, f.offset.toInt(), f.length)
                }
                else {
                    val text = "Dogmatix compressible payload $i. ".toByteArray()
                    for (k in 0 until f.length) b[f.offset.toInt() + k] = text[k % text.size]
                }
            }
            junkFrom = alignUp(f.offset + f.length, 4) + (if (rnd.nextBoolean()) 0x20 else 0)
        }
        fillJunk(b, 0, junkFrom, b.size.toLong(), idBytes, 0)
    }

    /** A GameCube image of [size] bytes (not necessarily a multiple of 32 KiB). */
    fun gameCube(size: Int = 0x600000 + 0x1234, files: Int = 12, seed: Long = 1): ByteArray {
        val b = ByteArray(size)
        layoutFilesystem(b, "GDTE01", wii = false, files = files, rnd = Random(seed))
        return b
    }

    class WiiImage(val bytes: ByteArray, val partitionOffset: Long, val dataOffset: Long, val dataSectors: Int)

    /**
     * A Wii image with one encrypted game partition of [dataSectors] sectors (and, with
     * [updatePartition], a small update partition before it, as on retail discs). [tamper] may
     * edit the plain hash blocks of the game partition before encryption (hash exceptions).
     */
    fun wii(
        dataSectors: Int = 64 * 2 + 10,
        files: Int = 10,
        seed: Long = 2,
        updatePartition: Boolean = false,
        tamper: ((sector: Int, hashBlock: ByteArray, off: Int) -> Unit)? = null,
    ): WiiImage {
        val rnd = Random(seed)
        val update = 0x50000
        val updateSectors = 9
        val partition = if (updatePartition) 0x100000 else 0x50000
        val dataOffset = partition + 0x20000
        val size = dataOffset + dataSectors * SECTOR + 4 * SECTOR
        val b = ByteArray(size)
        putAscii(b, 0, "RDTE01")
        putInt(b, 0x18, 0x5D1C9EA3)
        putAscii(b, 0x20, "DOGMATIX RVZ WII TEST")
        // Partition table: one group (game partition, type 0, and the update partition, type 1).
        val entries = if (updatePartition) listOf(partition to 0, update to 1) else listOf(partition to 0)
        putInt(b, 0x40000, entries.size)
        putInt(b, 0x40004, 0x40020 ushr 2)
        entries.forEachIndexed { i, (off, type) ->
            putInt(b, 0x40020 + i * 8, off ushr 2)
            putInt(b, 0x40024 + i * 8, type)
        }
        putInt(b, 0x4E000, 2) // region info
        if (updatePartition) {
            writePartition(b, update, updateSectors, "RDTE01", files = 0, rnd = rnd, titleTag = "UPDT", tamper = null)
            // Junk-free gap between the partitions, like the zeros on retail discs.
        }
        writePartition(b, partition, dataSectors, "RDTE01", minOf(files, dataSectors / 10), rnd, "RDTE", tamper)
        return WiiImage(b, partition.toLong(), dataOffset.toLong(), dataSectors)
    }

    private fun writePartition(
        b: ByteArray,
        partition: Int,
        dataSectors: Int,
        id: String,
        files: Int,
        rnd: Random,
        titleTag: String,
        tamper: ((sector: Int, hashBlock: ByteArray, off: Int) -> Unit)?,
    ) {
        val dataOffset = partition + 0x20000
        val dataSize = dataSectors * SECTOR
        val titleKey = ByteArray(16).also { rnd.nextBytes(it) }
        val titleId = byteArrayOf(0, 1, 0, 0) + titleTag.toByteArray()
        // Ticket.
        putInt(b, partition, 0x00010001)
        putAscii(b, partition + 0x140, "Root-CA00000001-XS00000003")
        System.arraycopy(WiiCrypto.encryptTitleKey(titleKey, titleId), 0, b, partition + 0x1BF, 16)
        System.arraycopy(titleId, 0, b, partition + 0x1DC, 8)
        b[partition + 0x1F1] = 0
        // Header: TMD at 0x2C0, H3 at 0x8000, data at 0x20000.
        val tmdSize = 0x1E4 + 36
        putInt(b, partition + 0x2A4, tmdSize)
        putInt(b, partition + 0x2A8, 0x2C0 ushr 2)
        putInt(b, partition + 0x2B4, 0x8000 ushr 2)
        putInt(b, partition + 0x2B8, 0x20000 ushr 2)
        putInt(b, partition + 0x2BC, dataSize ushr 2)

        // Plain partition data.
        val plain = ByteArray(dataSectors * WiiCrypto.DATA_SIZE)
        layoutFilesystem(plain, id, wii = true, files = files, rnd = rnd)

        // Hash, tamper, encrypt; H3 table.
        val crypto = WiiCrypto(titleKey)
        val h3 = ByteArray(0x18000)
        var s0 = 0
        while (s0 < dataSectors) {
            val n = minOf(64, dataSectors - s0)
            val data = plain.copyOfRange(s0 * WiiCrypto.DATA_SIZE, (s0 + n) * WiiCrypto.DATA_SIZE)
            val hashes = ByteArray(64 * WiiCrypto.HASH_SIZE)
            crypto.hashGroup(data, n, hashes)
            System.arraycopy(RvzFormat.sha1(hashes, 0x340, 160), 0, h3, (s0 / 64) * 20, 20)
            for (s in 0 until n) {
                tamper?.invoke(s0 + s, hashes, s * WiiCrypto.HASH_SIZE)
                val sector = ByteArray(SECTOR)
                System.arraycopy(hashes, s * WiiCrypto.HASH_SIZE, sector, 0, WiiCrypto.HASH_SIZE)
                System.arraycopy(data, s * WiiCrypto.DATA_SIZE, sector, WiiCrypto.HASH_SIZE, WiiCrypto.DATA_SIZE)
                crypto.encryptSector(sector, 0, b, dataOffset + (s0 + s) * SECTOR)
            }
            s0 += n
        }
        System.arraycopy(h3, 0, b, partition + 0x8000, h3.size)

        // TMD with one content whose hash is the H3 table's.
        val tmd = partition + 0x2C0
        putInt(b, tmd, 0x00010001)
        putAscii(b, tmd + 0x140, "Root-CA00000001-CP00000004")
        System.arraycopy(titleId, 0, b, tmd + 0x18C, 8)
        b[tmd + 0x1DE] = 0; b[tmd + 0x1DF] = 1
        val content = tmd + 0x1E4
        b[content + 7] = 1 // type
        ByteBuffer.wrap(b, content + 8, 8).putLong(dataSize.toLong())
        System.arraycopy(RvzFormat.sha1(h3), 0, b, content + 16, 20)
    }

    /** Wraps a Wii image in a single-file WBFS (512-byte HD sectors, 2 MiB WBFS sectors). */
    fun wbfs(disc: ByteArray): ByteArray {
        val hdShift = 9
        val wbfsShift = 21
        val block = 1 shl wbfsShift
        val blocksPerDisc = ((143432L * 2 * 0x8000) shr wbfsShift).toInt()
        val discBlocks = (disc.size + block - 1) / block
        val used = (0 until discBlocks).filter { i ->
            val s = i * block
            (s until minOf(s + block, disc.size)).any { disc[it].toInt() != 0 }
        }
        val out = ByteArray((1 + used.size) * block)
        putAscii(out, 0, "WBFS")
        putInt(out, 4, out.size / 512)
        out[8] = hdShift.toByte(); out[9] = wbfsShift.toByte(); out[10] = 1
        out[0x0C] = 1
        val info = 1 shl hdShift
        System.arraycopy(disc, 0, out, info, 0x100)
        used.forEachIndexed { k, i ->
            val physical = 1 + k
            out[info + 0x100 + i * 2] = (physical ushr 8).toByte()
            out[info + 0x100 + i * 2 + 1] = physical.toByte()
            val s = i * block
            System.arraycopy(disc, s, out, physical * block, minOf(block, disc.size - s))
        }
        require(blocksPerDisc > discBlocks)
        return out
    }

    private fun alignUp(v: Long, a: Long) = (v + a - 1) / a * a
}
