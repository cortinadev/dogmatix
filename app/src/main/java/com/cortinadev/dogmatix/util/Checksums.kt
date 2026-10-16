package com.cortinadev.dogmatix.util

import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

/** Hashes of one file, lower-case hex; [md5] only when it was asked for (RomM lists it). */
data class FileHashes(val size: Long, val crc32: String, val sha1: String, val md5: String? = null)

/** CRC-32, SHA-1 and optionally MD5 in a single pass over a stream. */
object Checksums {

    private const val BUFFER_SIZE = 1024 * 1024

    /**
     * Reads [input] to the end. [checkpoint] runs before every block (cancellation, a pause
     * for the rest of the system); [onBytes] after it with the bytes read so far.
     */
    fun hash(
        input: InputStream,
        withMd5: Boolean = false,
        checkpoint: () -> Unit = {},
        onBytes: (Long) -> Unit = {}
    ): FileHashes {
        val crc = CRC32()
        val sha1 = MessageDigest.getInstance("SHA-1")
        val md5 = if (withMd5) MessageDigest.getInstance("MD5") else null
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            checkpoint()
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            crc.update(buffer, 0, n)
            sha1.update(buffer, 0, n)
            md5?.update(buffer, 0, n)
            total += n
            onBytes(total)
        }
        return FileHashes(total, crcHex(crc.value), hex(sha1.digest()), md5?.let { hex(it.digest()) })
    }

    /**
     * Each file inside a ZIP kept as it is (automatic extraction off), by its path in the archive:
     * DATs and RomM describe what is inside, not the archive.
     */
    fun zipEntries(input: InputStream, withMd5: Boolean = false, checkpoint: () -> Unit = {}): List<Pair<String, FileHashes>> {
        val out = mutableListOf<Pair<String, FileHashes>>()
        ZipInputStream(input).use { zip ->
            while (true) {
                checkpoint()
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) out += entry.name to hash(zip, withMd5, checkpoint)
                zip.closeEntry()
            }
        }
        return out
    }

    /** A hash as DATs and APIs write it (any case, CRC without leading zeros) in our form; null when it is not hex. */
    fun normalize(hex: String?, length: Int): String? {
        val clean = hex?.trim()?.lowercase()?.removePrefix("0x") ?: return null
        if (clean.isEmpty() || clean.length > length || !clean.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return clean.padStart(length, '0')
    }

    fun crcHex(value: Long): String = "%08x".format(value and 0xffffffffL)

    fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and 0xff
            chars[i * 2] = HEX[v ushr 4]
            chars[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(chars)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
