package com.cortinadev.dogmatix.util

import java.io.IOException
import java.util.zip.CRC32

/** Random-access reading: all the XDVDFS code needs from an image (a SAF file, or bytes in a test). */
interface SeekableSource {
    val size: Long

    /** Reads up to [length] bytes at [position] into [buffer]; fewer only at the end of the file. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/** Where a converted image is written, front to back. */
fun interface SequentialSink {
    fun write(buffer: ByteArray, offset: Int, length: Int)
}

/** The image is not an Xbox disc the converter can read: no file system where it should be, or broken. */
class XdvdfsException(message: String) : IOException(message)

/**
 * The Xbox file system (XDVDFS) and the XISO layout (what xemu and the frontends expect for the
 * original Xbox), in pure Kotlin so it runs in JVM tests on synthetic images.
 *
 * Format, as documented by the Xbox homebrew community and implemented by `extract-xiso`
 * (reimplemented here from the format description, no code copied):
 *  - 2048-byte sectors. The volume descriptor sits in sector 32 of the partition (offset 0x10000):
 *    `MICROSOFT*XBOX*MEDIA`, root directory sector and size (u32 LE each), an 8-byte FILETIME,
 *    padding, and the magic again in its last 20 bytes.
 *  - A Redump dump of an original Xbox disc (XGD1) starts with the video partition; the game
 *    partition, the one with the file system, starts at 0x18300000. An XISO is that partition
 *    alone, so its descriptor is at 0x10000 of the file.
 *  - A directory is a table of entries forming a binary search tree (AVL) over the names, compared
 *    without case; the root of the tree is the entry at offset 0. Each entry: left and right
 *    subtree offsets (u16, in 4-byte units from the start of the table, 0 = none), start sector,
 *    size (u32), attributes (0x10 = directory), name length and the name; padded to 4 bytes with
 *    0xFF, and never across a sector boundary (the rest of the sector is 0xFF). 0xFFFF where an
 *    entry should be marks an empty table.
 *
 * The rewrite ([plan] + [write]) follows `extract-xiso -r` (checked against it, see
 * `XdvdfsReferenceTest`): from sector 0x108, each table followed by its files and then its
 * folders, files padded with 0xFF, empty folders as one sector of 0xFF, empty files pointing at
 * the next free sector, attributes reduced to directory / archive, the ISO 9660 descriptors of
 * sectors 16–17, and the image padded to 64 KiB; without the video partition nor the unused space
 * of the game partition. Two differences, both harmless: each table is a perfectly balanced tree
 * (extract-xiso's AVL shape depends on its insertion order), and the XBE "media check" patch
 * extract-xiso applies by default is not applied (files stay byte for byte; xemu does not need it).
 * The order of the names is the source's own (its in-order walk).
 */
object Xdvdfs {
    const val SECTOR = 2048
    const val DESCRIPTOR_OFFSET = 0x10000L
    /** Start of the game partition in a Redump image of an original Xbox disc (XGD1). */
    const val REDUMP_GAME_PARTITION = 0x18300000L
    /** First sector of the tables in a rewritten image, as extract-xiso does. */
    const val FIRST_TABLE_SECTOR = 0x108L
    /** Rewritten images are padded to a multiple of this. */
    const val FILE_MODULUS = 0x10000L
    const val ATTR_DIRECTORY = 0x10
    const val ATTR_ARCHIVE = 0x20
    private const val ENTRY_HEADER = 14
    private const val PAD = 0xFF.toByte()
    /** A directory table bigger than this is not one (the largest real ones are a few KiB). */
    private const val MAX_TABLE = 16 * 1024 * 1024
    private const val MAX_DEPTH = 64
    private const val COPY_BLOCK = 1 shl 20

    val MAGIC: ByteArray = "MICROSOFT*XBOX*MEDIA".toByteArray(Charsets.US_ASCII)

    enum class Layout {
        /** Already an XISO: the file system starts at the beginning of the file. */
        XISO,
        /** A full disc dump: video partition first, game partition at [REDUMP_GAME_PARTITION]. */
        REDUMP,
        /** No Xbox file system where one is expected. */
        UNKNOWN
    }

    /** Where the file system of an image lives and what its descriptor says. */
    class Volume(val layout: Layout, val partition: Long, val rootSector: Long, val rootSize: Long, val fileTime: ByteArray)

    /**
     * One file or folder of the file system. [name] keeps the raw bytes (ISO-8859-1 maps each byte
     * to one char and back). [sector] is relative to the partition. [children] is null for a file.
     */
    class Entry(val name: String, val attributes: Int, val sector: Long, val size: Long, val children: List<Entry>?) {
        val isDirectory: Boolean get() = children != null
    }

    /** Which layout [source] has, read from its descriptors; [Volume] with the root to walk. */
    fun detect(source: SeekableSource): Volume {
        volumeAt(source, 0L, Layout.XISO)?.let { return it }
        volumeAt(source, REDUMP_GAME_PARTITION, Layout.REDUMP)?.let { return it }
        return Volume(Layout.UNKNOWN, 0, 0, 0, ByteArray(8))
    }

    private fun volumeAt(source: SeekableSource, partition: Long, layout: Layout): Volume? {
        if (source.size < partition + DESCRIPTOR_OFFSET + SECTOR) return null
        val sector = readFully(source, partition + DESCRIPTOR_OFFSET, SECTOR) ?: return null
        if (!startsWith(sector, 0, MAGIC) || !startsWith(sector, SECTOR - MAGIC.size, MAGIC)) return null
        return Volume(layout, partition, u32(sector, 20), u32(sector, 24), sector.copyOfRange(28, 36))
    }

    /** The whole tree under the root of [volume], in each table's own order (the sorted one). */
    fun readTree(source: SeekableSource, volume: Volume, checkCancelled: () -> Unit = {}): List<Entry> {
        if (volume.layout == Layout.UNKNOWN) throw XdvdfsException("No Xbox file system")
        return readDirectory(source, volume.partition, volume.rootSector, volume.rootSize, 0, checkCancelled)
    }

    private fun readDirectory(source: SeekableSource, partition: Long, sector: Long, size: Long, depth: Int, checkCancelled: () -> Unit): List<Entry> {
        checkCancelled()
        if (depth > MAX_DEPTH) throw XdvdfsException("Folders nested too deep")
        if (size == 0L) return emptyList()
        if (size > MAX_TABLE) throw XdvdfsException("Directory table of $size bytes")
        val table = readFully(source, partition + sector * SECTOR, size.toInt()) ?: throw XdvdfsException("Directory table past the end of the image")
        // Padding where the root entry should be: an empty table.
        if (table.size >= 2 && u16(table, 0) == 0xFFFF) return emptyList()
        val out = ArrayList<Entry>()
        val seen = HashSet<Int>()
        // In-order walk with a stack: a table need not be balanced, so its depth is not bounded.
        val stack = ArrayDeque<Int>()
        var offset = 0
        while (true) {
            while (offset >= 0) {
                if (offset + ENTRY_HEADER > table.size) throw XdvdfsException("Entry outside its table")
                if (!seen.add(offset)) throw XdvdfsException("Directory tree loops")
                stack.addLast(offset)
                offset = u16(table, offset).let { if (it == 0) -1 else it * 4 }
            }
            val at = stack.removeLastOrNull() ?: break
            out += entryAt(source, table, at, partition, depth, checkCancelled)
            offset = u16(table, at + 2).let { if (it == 0) -1 else it * 4 }
        }
        return out
    }

    private fun entryAt(source: SeekableSource, table: ByteArray, at: Int, partition: Long, depth: Int, checkCancelled: () -> Unit): Entry {
        val nameLength = table[at + 13].toInt() and 0xFF
        if (nameLength == 0 || at + ENTRY_HEADER + nameLength > table.size) throw XdvdfsException("Bad entry name")
        val entrySector = u32(table, at + 4)
        val entrySize = u32(table, at + 8)
        val attributes = table[at + 12].toInt() and 0xFF
        val name = String(table, at + ENTRY_HEADER, nameLength, Charsets.ISO_8859_1)
        if (attributes and ATTR_DIRECTORY != 0) {
            return Entry(name, attributes, entrySector, entrySize, readDirectory(source, partition, entrySector, entrySize, depth + 1, checkCancelled))
        }
        if (entrySize > 0 && partition + entrySector * SECTOR + entrySize > source.size) {
            throw XdvdfsException("$name ends past the end of the image")
        }
        return Entry(name, attributes, entrySector, entrySize, null)
    }

    // ---- The rewrite ---------------------------------------------------------------------------

    /** One directory table of the new image: its entries (in order), where it goes and its bytes. */
    class TablePlan(val entries: List<EntryPlan>) {
        var sector: Long = 0
            internal set
        /** Bytes the entries take, before the padding to a whole sector: what the descriptor gives for the root. */
        var usedBytes: Int = 0
            internal set
        var bytes: ByteArray = ByteArray(0)
            internal set
    }

    /** An entry as the new image has it: [sector] and [size] there, [source] the entry it copies. */
    class EntryPlan(val path: String, val source: Entry) {
        var sector: Long = 0
            internal set
        var size: Long = source.size
            internal set
        /** A folder's own table. */
        var table: TablePlan? = null
            internal set
    }

    /**
     * The new image: [root] table, every table and file with its new sector, and [totalBytes]
     * (padded to [FILE_MODULUS]). [files] in the order they are written.
     */
    class Plan(val volume: Volume, val root: TablePlan, val tables: List<TablePlan>, val files: List<EntryPlan>, val totalBytes: Long) {
        val dataBytes: Long get() = files.sumOf { it.size }
    }

    /** Lays the tree of [volume] out as a compact XISO; nothing is written. */
    fun plan(source: SeekableSource, volume: Volume, checkCancelled: () -> Unit = {}): Plan =
        plan(volume, readTree(source, volume, checkCancelled))

    fun plan(volume: Volume, tree: List<Entry>): Plan {
        val tables = ArrayList<TablePlan>()
        val files = ArrayList<EntryPlan>()
        var cursor = FIRST_TABLE_SECTOR
        // The order of extract-xiso -r: a table, then its files, then each of its folders the same
        // way, entries taken in the order the table stores them (each node before its subtrees).
        // An empty folder gets one sector of padding; an empty file points where the next starts.
        fun place(entries: List<Entry>, prefix: String): TablePlan {
            val table = TablePlan(entries.map { EntryPlan(prefix + it.name, it) })
            tables += table
            table.sector = cursor
            table.usedBytes = usedSize(entries)
            cursor += sectorsFor(tableSize(entries).toLong())
            val order = preorder(entries.size).map { table.entries[it] }
            for (plan in order) if (plan.source.children == null) {
                plan.sector = cursor
                cursor += sectorsFor(plan.size)
                files += plan
            }
            for (plan in order) plan.source.children?.let { children ->
                val child = place(children, plan.path + "/")
                plan.table = child
                plan.sector = child.sector
                plan.size = tableSize(children).toLong()
            }
            return table
        }
        val root = place(tree, "")
        for (table in tables) table.bytes = encodeTable(table.entries)
        return Plan(volume, root, tables, files, roundUp(cursor * SECTOR, FILE_MODULUS))
    }

    private fun sectorsFor(bytes: Long): Long = (bytes + SECTOR - 1) / SECTOR

    private fun roundUp(value: Long, step: Long): Long = (value + step - 1) / step * step

    private fun entryLength(name: String): Int = (ENTRY_HEADER + name.length + 3) and 3.inv()

    /** Bytes of a table holding [entries], whole sectors; an empty folder still has one (all padding). */
    private fun tableSize(entries: List<Entry>): Int =
        if (entries.isEmpty()) SECTOR else roundUp(usedSize(entries).toLong(), SECTOR.toLong()).toInt()

    /** Bytes the entries of a table take, padding between sectors included, not after the last. */
    private fun usedSize(entries: List<Entry>): Int {
        if (entries.isEmpty()) return SECTOR
        var offset = 0
        for (index in preorder(entries.size)) {
            val length = entryLength(entries[index].name)
            if (offset % SECTOR + length > SECTOR) offset = roundUp(offset.toLong(), SECTOR.toLong()).toInt()
            offset += length
        }
        return offset
    }

    /**
     * The balanced search tree over [count] sorted entries, as the order the table stores them in
     * (each node before its subtrees, so the root lands at offset 0). A perfectly balanced tree is
     * a valid AVL tree.
     */
    private fun preorder(count: Int): List<Int> {
        val out = ArrayList<Int>(count)
        fun walk(lo: Int, hi: Int) {
            if (lo > hi) return
            val mid = (lo + hi) ushr 1
            out += mid
            walk(lo, mid - 1)
            walk(mid + 1, hi)
        }
        walk(0, count - 1)
        return out
    }

    private fun encodeTable(entries: List<EntryPlan>): ByteArray {
        if (entries.isEmpty()) return ByteArray(SECTOR) { PAD }
        val order = preorder(entries.size)
        val offsets = IntArray(entries.size)
        var offset = 0
        for (index in order) {
            val length = entryLength(entries[index].source.name)
            if (offset % SECTOR + length > SECTOR) offset = roundUp(offset.toLong(), SECTOR.toLong()).toInt()
            offsets[index] = offset
            offset += length
        }
        val bytes = ByteArray(roundUp(offset.toLong(), SECTOR.toLong()).toInt()) { PAD }
        // Same subtrees as [preorder]: the node of lo..hi is their middle.
        fun node(lo: Int, hi: Int) {
            val mid = (lo + hi) ushr 1
            val left = if (lo < mid) (lo + mid - 1) ushr 1 else -1
            val right = if (mid < hi) (mid + 1 + hi) ushr 1 else -1
            val entry = entries[mid]
            val at = offsets[mid]
            putU16(bytes, at, if (left >= 0) offsets[left] / 4 else 0)
            putU16(bytes, at + 2, if (right >= 0) offsets[right] / 4 else 0)
            putU32(bytes, at + 4, entry.sector)
            putU32(bytes, at + 8, entry.size)
            bytes[at + 12] = attributesOf(entry.source).toByte()
            val name = entry.source.name.toByteArray(Charsets.ISO_8859_1)
            bytes[at + 13] = name.size.toByte()
            name.copyInto(bytes, at + ENTRY_HEADER)
            if (left >= 0) node(lo, mid - 1)
            if (right >= 0) node(mid + 1, hi)
        }
        node(0, entries.size - 1)
        return bytes
    }

    /** Attributes written: directory or archive, as extract-xiso writes them (the disc's own are not kept). */
    private fun attributesOf(entry: Entry): Int = if (entry.isDirectory) ATTR_DIRECTORY else ATTR_ARCHIVE

    /** CRC-32 of every file written, by path: what [verify] compares the new image with. */
    class Written(val totalBytes: Long, val crcs: Map<String, Long>)

    /**
     * Writes the image [plan] lays out into [sink], copying each file from [source]. [onProgress]
     * gets the share of file data copied (0..1); [checkCancelled] is called between blocks.
     */
    fun write(source: SeekableSource, plan: Plan, sink: SequentialSink, onProgress: (Float) -> Unit = {}, checkCancelled: () -> Unit = {}): Written {
        var position = 0L
        val zeros = ByteArray(COPY_BLOCK)
        fun pad(to: Long) {
            while (position < to) {
                val n = minOf(zeros.size.toLong(), to - position).toInt()
                sink.write(zeros, 0, n)
                position += n
            }
        }
        fun put(bytes: ByteArray, length: Int = bytes.size) {
            sink.write(bytes, 0, length)
            position += length
        }
        put(header(plan))
        // Tables and files in the order of their sectors (they interleave, as extract-xiso lays them out).
        val chunks = (plan.tables.map { it.sector to it } + plan.files.filter { it.size > 0 }.map { it.sector to it }).sortedBy { it.first }
        val total = plan.dataBytes.coerceAtLeast(1)
        var copied = 0L
        val buffer = ByteArray(COPY_BLOCK)
        val crcs = LinkedHashMap<String, Long>(plan.files.size)
        plan.files.filter { it.size == 0L }.forEach { crcs[it.path] = 0L }
        for ((sector, chunk) in chunks) {
            pad(sector * SECTOR)
            if (chunk is TablePlan) {
                put(chunk.bytes)
                continue
            }
            val file = chunk as EntryPlan
            val crc = CRC32()
            var at = plan.volume.partition + file.source.sector * SECTOR
            var left = file.size
            while (left > 0) {
                checkCancelled()
                val n = minOf(buffer.size.toLong(), left).toInt()
                val read = readInto(source, at, buffer, n)
                if (read < n) throw XdvdfsException("${file.path} is cut short in the image")
                crc.update(buffer, 0, n)
                put(buffer, n)
                at += n
                left -= n
                copied += n
                onProgress(copied.toFloat() / total)
            }
            crcs[file.path] = crc.value
            // The rest of its last sector is 0xFF, as extract-xiso pads.
            val tail = ((SECTOR - file.size % SECTOR) % SECTOR).toInt()
            if (tail > 0) {
                buffer.fill(PAD, 0, tail)
                put(buffer, tail)
            }
        }
        pad(plan.totalBytes)
        onProgress(1f)
        return Written(position, crcs)
    }

    /**
     * The first 0x10800 bytes: zeros, the ECMA-119 (ISO 9660) volume descriptor and its terminator
     * in sectors 16 and 17 as extract-xiso writes them (total sectors, blank names and dates), and
     * the XDVDFS descriptor in sector 32 (root table, its size, the disc's FILETIME). extract-xiso's
     * "optimized" tag at offset 31337 is left out: it only tells extract-xiso itself not to rewrite.
     */
    private fun header(plan: Plan): ByteArray {
        val bytes = ByteArray((DESCRIPTOR_OFFSET + SECTOR).toInt())
        val ecma = 0x8000
        byteArrayOf(1, 'C'.code.toByte(), 'D'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(), '1'.code.toByte(), 1).copyInto(bytes, ecma)
        val sectors = plan.totalBytes / SECTOR
        putU32(bytes, ecma + 80, sectors)
        for (i in 0 until 4) bytes[ecma + 84 + i] = (sectors ushr (8 * (3 - i))).toByte()
        byteArrayOf(1, 0, 0, 1, 1, 0, 0, 1, 0, 8, 8, 0).copyInto(bytes, ecma + 120)
        bytes.fill(0x20, ecma + 190, ecma + 813)
        // Four dates, each sixteen '0' digits and a zero byte, then the file structure version.
        for (i in 0 until 4) bytes.fill('0'.code.toByte(), ecma + 813 + 17 * i, ecma + 813 + 17 * i + 16)
        bytes[ecma + 813 + 68] = 1
        byteArrayOf(0xFF.toByte(), 'C'.code.toByte(), 'D'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(), '1'.code.toByte(), 1).copyInto(bytes, ecma + SECTOR)
        val d = DESCRIPTOR_OFFSET.toInt()
        MAGIC.copyInto(bytes, d)
        putU32(bytes, d + 20, plan.root.sector)
        // The root's size as its entries take it (no padding), like extract-xiso; entries of folders give whole sectors.
        putU32(bytes, d + 24, plan.root.usedBytes.toLong())
        plan.volume.fileTime.copyInto(bytes, d + 28, 0, 8)
        MAGIC.copyInto(bytes, d + SECTOR - MAGIC.size)
        return bytes
    }

    /**
     * Whether [output] is the XISO [plan] laid out, holding what [written] copied: an XISO at the
     * start, the same tree (paths, sizes, attributes, order) and every file with the same CRC-32.
     * Reads every file once; [onProgress] 0..1.
     */
    fun verify(output: SeekableSource, plan: Plan, written: Written, onProgress: (Float) -> Unit = {}, checkCancelled: () -> Unit = {}): Boolean {
        if (output.size != plan.totalBytes) return false
        val volume = runCatching { detect(output) }.getOrNull() ?: return false
        if (volume.layout != Layout.XISO) return false
        val tree = runCatching { readTree(output, volume, checkCancelled) }.getOrElse { return false }
        val expected = flatten(plan.root.entries.map { it.source }, "")
        val actual = flatten(tree, "")
        if (expected.size != actual.size) return false
        for (i in expected.indices) {
            val (p1, e1) = expected[i]
            val (p2, e2) = actual[i]
            if (p1 != p2 || e1.isDirectory != e2.isDirectory || e2.attributes != attributesOf(e1)) return false
            if (!e1.isDirectory && e1.size != e2.size) return false
        }
        val total = plan.dataBytes.coerceAtLeast(1)
        var read = 0L
        val buffer = ByteArray(COPY_BLOCK)
        for ((path, entry) in actual) {
            if (entry.isDirectory) continue
            val crc = CRC32()
            var at = entry.sector * SECTOR
            var left = entry.size
            while (left > 0) {
                checkCancelled()
                val n = minOf(buffer.size.toLong(), left).toInt()
                if (readInto(output, at, buffer, n) < n) return false
                crc.update(buffer, 0, n)
                at += n
                left -= n
                read += n
                onProgress(read.toFloat() / total)
            }
            if (written.crcs[path] != crc.value) return false
        }
        onProgress(1f)
        return true
    }

    /** Every entry below [entries] with its path, depth first in table order. */
    fun flatten(entries: List<Entry>, prefix: String): List<Pair<String, Entry>> = buildList {
        for (entry in entries) {
            add(prefix + entry.name to entry)
            entry.children?.let { addAll(flatten(it, prefix + entry.name + "/")) }
        }
    }

    // ---- Bytes ----------------------------------------------------------------------------------

    private fun readFully(source: SeekableSource, position: Long, length: Int): ByteArray? {
        val buffer = ByteArray(length)
        return if (readInto(source, position, buffer, length) == length) buffer else null
    }

    private fun readInto(source: SeekableSource, position: Long, buffer: ByteArray, length: Int): Int {
        var filled = 0
        while (filled < length) {
            val n = source.read(position + filled, buffer, filled, length - filled)
            if (n <= 0) break
            filled += n
        }
        return filled
    }

    private fun startsWith(bytes: ByteArray, at: Int, prefix: ByteArray): Boolean =
        prefix.indices.all { bytes[at + it] == prefix[it] }

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun putU16(b: ByteArray, at: Int, value: Int) {
        b[at] = value.toByte()
        b[at + 1] = (value ushr 8).toByte()
    }

    private fun putU32(b: ByteArray, at: Int, value: Long) {
        b[at] = value.toByte()
        b[at + 1] = (value ushr 8).toByte()
        b[at + 2] = (value ushr 16).toByte()
        b[at + 3] = (value ushr 24).toByte()
    }
}
