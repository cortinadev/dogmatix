package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ChecksumsTest {

    @Test
    fun `known hashes in one pass`() {
        val h = Checksums.hash(ByteArrayInputStream("abc".toByteArray()), withMd5 = true)
        assertEquals(3L, h.size)
        assertEquals("352441c2", h.crc32)
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", h.sha1)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", h.md5)
        val empty = Checksums.hash(ByteArrayInputStream(ByteArray(0)))
        assertEquals("00000000", empty.crc32)
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", empty.sha1)
        assertNull(empty.md5)
    }

    @Test
    fun `files inside a zip are hashed one by one`() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("Game (USA).gb")); zip.write("abc".toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("docs/")); zip.closeEntry()
                zip.putNextEntry(ZipEntry("docs/readme.txt")); zip.write(ByteArray(0)); zip.closeEntry()
            }
        }.toByteArray()
        val entries = Checksums.zipEntries(ByteArrayInputStream(bytes))
        assertEquals(listOf("Game (USA).gb", "docs/readme.txt"), entries.map { it.first })
        assertEquals("352441c2", entries[0].second.crc32)
    }

    @Test
    fun `hashes as DATs and APIs write them`() {
        assertEquals("0000abcd", Checksums.normalize("ABCD", 8))
        assertEquals("caf2e99f", Checksums.normalize(" CAF2E99F ", 8))
        assertNull(Checksums.normalize("xyz", 8))
        assertNull(Checksums.normalize("123456789", 8))
        assertNull(Checksums.normalize("", 40))
    }
}
