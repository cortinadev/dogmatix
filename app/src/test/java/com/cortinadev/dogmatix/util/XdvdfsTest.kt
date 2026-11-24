package com.cortinadev.dogmatix.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class XdvdfsTest {

    // ---- Synthetic images ------------------------------------------------------------------------

    /** A file or folder of a test disc. */
    private sealed class Node(val name: String) {
        class File(name: String, val data: ByteArray) : Node(name)
        class Dir(name: String, val children: List<Node>) : Node(name)
    }

    /** Bytes in memory after [offset] zero bytes that take no memory (the video partition of a Redump image). */
    private class Sparse(private val offset: Long, private val data: ByteArray) : SeekableSource {
        override val size: Long = offset + data.size
        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            val n = minOf(length.toLong(), size - position).toInt()
            for (i in 0 until n) {
                val at = position + i
                buffer[offset + i] = if (at < this.offset) 0 else data[(at - this.offset).toInt()]
            }
            return n
        }
    }

    private class Bytes(val data: ByteArray) : SeekableSource {
        override val size: Long = data.size.toLong()
        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            val n = minOf(length.toLong(), size - position).toInt()
            data.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
            return n
        }
    }

    /**
     * Writes a game partition the way a mastering tool might, deliberately unlike the rewrite: each
     * table is a chain where every entry's right child is the next one (a valid search tree, not a
     * balanced one), tables start at sector 40, and files are spread out with gaps between them.
     */
    private fun partition(root: List<Node>): ByteArray {
        val sector = Xdvdfs.SECTOR
        val image = ByteArray(16 * 1024 * 1024)
        var next = 40L
        fun alloc(bytes: Int): Long = next.also { next += (bytes + sector - 1) / sector + 3 }
        fun sorted(nodes: List<Node>) = nodes.sortedWith { a, b -> compareNames(a.name, b.name) }
        fun table(nodes: List<Node>): Pair<Long, Int> {
            val entries = sorted(nodes)
            if (entries.isEmpty()) return 0L to 0
            val located = entries.map { node ->
                when (node) {
                    is Node.File -> {
                        val at = alloc(node.data.size)
                        node.data.copyInto(image, (at * sector).toInt())
                        Triple(node, at, node.data.size.toLong())
                    }
                    is Node.Dir -> {
                        val (at, size) = table(node.children)
                        Triple(node, at, size.toLong())
                    }
                }
            }
            var offset = 0
            val offsets = located.map { (node, _, _) ->
                val length = (14 + node.name.length + 3) and 3.inv()
                if (offset % sector + length > sector) offset = (offset / sector + 1) * sector
                offset.also { offset += length }
            }
            val size = (offset + sector - 1) / sector * sector
            val at = alloc(size)
            val base = (at * sector).toInt()
            image.fill(0xFF.toByte(), base, base + size)
            located.forEachIndexed { i, (node, entrySector, entrySize) ->
                val o = base + offsets[i]
                putU16(image, o, 0)
                putU16(image, o + 2, if (i + 1 < located.size) offsets[i + 1] / 4 else 0)
                putU32(image, o + 4, entrySector)
                putU32(image, o + 8, entrySize)
                image[o + 12] = if (node is Node.Dir) 0x10 else 0x20
                image[o + 13] = node.name.length.toByte()
                node.name.toByteArray(Charsets.ISO_8859_1).copyInto(image, o + 14)
            }
            return at to size
        }
        val (rootSector, rootSize) = table(root)
        val descriptor = 0x10000
        Xdvdfs.MAGIC.copyInto(image, descriptor)
        putU32(image, descriptor + 20, rootSector)
        putU32(image, descriptor + 24, rootSize.toLong())
        for (i in 0 until 8) image[descriptor + 28 + i] = (i + 1).toByte()
        Xdvdfs.MAGIC.copyInto(image, descriptor + sector - 20)
        return image.copyOf(((next + 8) * sector).toInt())
    }

    private fun compareNames(a: String, b: String): Int {
        for (i in 0 until minOf(a.length, b.length)) {
            val c = a[i].uppercaseChar().compareTo(b[i].uppercaseChar())
            if (c != 0) return c
        }
        return a.length - b.length
    }

    private fun putU16(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte(); b[at + 1] = (v ushr 8).toByte()
    }

    private fun putU32(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte()
    }

    private fun random(size: Int, seed: Int) = Random(seed).nextBytes(size)

    private val disc = listOf(
        Node.File("default.xbe", random(70_000, 1)),
        Node.Dir("media", listOf(
            Node.File("intro.xmv", random(300_000, 2)),
            Node.File("Logo.bmp", random(2048, 3)),
            Node.Dir("sound", listOf(Node.File("a.wav", random(5, 4)), Node.File("B.wav", random(4097, 5)))),
            Node.Dir("empty", emptyList())
        )),
        Node.File("zero.bin", ByteArray(0)),
        Node.File("Readme.TXT", random(10, 6))
    )

    private fun redump(nodes: List<Node> = disc): Sparse = Sparse(Xdvdfs.REDUMP_GAME_PARTITION, partition(nodes))

    private fun convert(source: SeekableSource): Triple<ByteArray, Xdvdfs.Plan, Xdvdfs.Written> {
        val volume = Xdvdfs.detect(source)
        val plan = Xdvdfs.plan(source, volume)
        val out = ByteArrayOutputStream()
        val written = Xdvdfs.write(source, plan, { b, o, l -> out.write(b, o, l) })
        return Triple(out.toByteArray(), plan, written)
    }

    private fun contents(source: SeekableSource, volume: Xdvdfs.Volume): Map<String, ByteArray> =
        Xdvdfs.flatten(Xdvdfs.readTree(source, volume), "").filter { !it.second.isDirectory }.associate { (path, entry) ->
            val bytes = ByteArray(entry.size.toInt())
            source.read(volume.partition + entry.sector * Xdvdfs.SECTOR, bytes, 0, bytes.size)
            path to bytes
        }

    // ---- Tests -----------------------------------------------------------------------------------

    @Test
    fun `a redump image is told apart from an xiso and from anything else`() {
        assertEquals(Xdvdfs.Layout.REDUMP, Xdvdfs.detect(redump()).layout)
        val (xiso, _, _) = convert(redump())
        assertEquals(Xdvdfs.Layout.XISO, Xdvdfs.detect(Bytes(xiso)).layout)
        assertEquals(Xdvdfs.Layout.UNKNOWN, Xdvdfs.detect(Bytes(random(300_000, 9))).layout)
        assertEquals(Xdvdfs.Layout.UNKNOWN, Xdvdfs.detect(Bytes(ByteArray(10))).layout)
    }

    @Test
    fun `the tree is read in name order whatever shape the source tables have`() {
        val source = redump()
        val tree = Xdvdfs.readTree(source, Xdvdfs.detect(source))
        assertEquals(listOf("default.xbe", "media", "Readme.TXT", "zero.bin"), tree.map { it.name })
        val media = tree.first { it.name == "media" }.children!!
        assertEquals(listOf("empty", "intro.xmv", "Logo.bmp", "sound"), media.map { it.name })
        assertTrue(media.first { it.name == "empty" }.children!!.isEmpty())
    }

    @Test
    fun `the rewrite keeps every file byte for byte and drops the video partition`() {
        val source = redump()
        val (xiso, plan, written) = convert(source)
        assertEquals(plan.totalBytes, xiso.size.toLong())
        assertEquals(written.totalBytes, xiso.size.toLong())
        assertEquals(0L, xiso.size % Xdvdfs.FILE_MODULUS)
        assertTrue(xiso.size < source.size)
        val before = contents(source, Xdvdfs.detect(source))
        val after = contents(Bytes(xiso), Xdvdfs.detect(Bytes(xiso)))
        assertEquals(before.keys.toList(), after.keys.toList())
        before.forEach { (path, bytes) -> assertArrayEquals(path, bytes, after.getValue(path)) }
        assertTrue(Xdvdfs.verify(Bytes(xiso), plan, written))
    }

    @Test
    fun `the descriptor of the rewrite points at its root and keeps the disc's date`() {
        val (xiso, plan, _) = convert(redump())
        val volume = Xdvdfs.detect(Bytes(xiso))
        assertEquals(Xdvdfs.FIRST_TABLE_SECTOR, volume.rootSector)
        assertEquals(plan.root.usedBytes.toLong(), volume.rootSize)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), volume.fileTime)
        // ISO 9660 descriptor and terminator in sectors 16 and 17.
        assertEquals("CD001", String(xiso, 0x8001, 5, Charsets.US_ASCII))
        assertEquals(0xFF.toByte(), xiso[0x8800])
        // Tables and files never overlap, and each starts on a sector.
        val spans = plan.tables.map { it.sector until it.sector + it.bytes.size / Xdvdfs.SECTOR } +
            plan.files.filter { it.size > 0 }.map { it.sector until it.sector + (it.size + 2047) / 2048 }
        val sorted = spans.sortedBy { it.first }
        sorted.zipWithNext().forEach { (a, b) -> assertTrue(a.last < b.first) }
        assertEquals(Xdvdfs.FIRST_TABLE_SECTOR, sorted.first().first)
    }

    @Test
    fun `tables are balanced search trees with no entry across a sector`() {
        val many = (1..400).map { Node.File("file_${it.toString().padStart(3, '0')}_" + "x".repeat(it % 180), random(it, it)) }
        val source = redump(listOf(Node.Dir("big", many), Node.File("a", random(3, 1))))
        val (xiso, plan, written) = convert(source)
        for (table in plan.tables) checkTable(table.bytes)
        assertTrue(plan.tables.any { it.bytes.size > Xdvdfs.SECTOR })
        assertTrue(Xdvdfs.verify(Bytes(xiso), plan, written))
    }

    /** Walks a table from offset 0: search order without case, AVL balance, no entry across a sector. */
    private fun checkTable(table: ByteArray) {
        fun u16(at: Int) = (table[at].toInt() and 0xFF) or ((table[at + 1].toInt() and 0xFF) shl 8)
        fun walk(offset: Int): Pair<Int, List<String>> {
            val nameLength = table[offset + 13].toInt() and 0xFF
            val length = (14 + nameLength + 3) and 3.inv()
            assertTrue("entry at $offset crosses a sector", offset % Xdvdfs.SECTOR + length <= Xdvdfs.SECTOR)
            val name = String(table, offset + 14, nameLength, Charsets.ISO_8859_1)
            val (lh, ln) = u16(offset).takeIf { it != 0 }?.let { walk(it * 4) } ?: (0 to emptyList())
            val (rh, rn) = u16(offset + 2).takeIf { it != 0 }?.let { walk(it * 4) } ?: (0 to emptyList())
            assertTrue("unbalanced at $name", kotlin.math.abs(lh - rh) <= 1)
            assertTrue(ln.all { compareNames(it, name) < 0 } && rn.all { compareNames(it, name) > 0 })
            return (maxOf(lh, rh) + 1) to (ln + name + rn)
        }
        if (u16(0) == 0xFFFF) return
        val names = walk(0).second
        assertEquals(names.sortedWith { a, b -> compareNames(a, b) }, names)
    }

    @Test
    fun `a changed byte in the output fails the check`() {
        val (xiso, plan, written) = convert(redump())
        val file = plan.files.first { it.size > 100 }
        xiso[(file.sector * Xdvdfs.SECTOR + 50).toInt()] = (xiso[(file.sector * Xdvdfs.SECTOR + 50).toInt()] + 1).toByte()
        assertFalse(Xdvdfs.verify(Bytes(xiso), plan, written))
        assertFalse(Xdvdfs.verify(Bytes(xiso.copyOf(xiso.size - 2048)), plan, written))
    }

    @Test
    fun `an xiso converts again into the same image`() {
        val (once, _, _) = convert(redump())
        val (twice, _, _) = convert(Bytes(once))
        assertArrayEquals(once, twice)
    }

    @Test
    fun `progress reaches the end`() {
        val source = redump()
        val plan = Xdvdfs.plan(source, Xdvdfs.detect(source))
        var last = 0f
        Xdvdfs.write(source, plan, { _, _, _ -> }, onProgress = { assertTrue(it >= last); last = it })
        assertEquals(1f, last)
    }

    @Test
    fun `a file past the end of the image is refused`() {
        val image = partition(disc)
        val source = Sparse(Xdvdfs.REDUMP_GAME_PARTITION, image.copyOf(image.size - 300_000))
        val volume = Xdvdfs.detect(source)
        try {
            Xdvdfs.readTree(source, volume)
            fail("truncated image read")
        } catch (_: XdvdfsException) {
        }
    }

    @Test
    fun `a table that loops is refused`() {
        val image = partition(listOf(Node.File("a", random(10, 1)), Node.File("b", random(10, 2))))
        val root = ((image[0x10000 + 20].toInt() and 0xFF) or ((image[0x10000 + 21].toInt() and 0xFF) shl 8)) * Xdvdfs.SECTOR
        // The chain: "a" at 0, its right child "b"; make "b" its own left child.
        val second = (image[root + 2].toInt() and 0xFF) * 4
        putU16(image, root + second, second / 4)
        val source = Sparse(Xdvdfs.REDUMP_GAME_PARTITION, image)
        try {
            Xdvdfs.readTree(source, Xdvdfs.detect(source))
            fail("looping table read")
        } catch (_: XdvdfsException) {
        }
    }
}
