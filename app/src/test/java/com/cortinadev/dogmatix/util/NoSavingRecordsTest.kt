package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoSavingRecordsTest {

    private fun record(
        uri: String = "content://tree/doc/a.iso",
        main: Long = 100,
        format: ConversionFormat = ConversionFormat.CHD,
        profile: String = "BEST",
        download: String? = "a.zip",
        at: Long = 1
    ) = NoSavingRecord(uri, main, main + 10, format, profile, "A", "sony_playstation", download, "DOWNLOADS", at)

    @Test
    fun `round trips through its JSON, broken entries skipped`() {
        val records = listOf(record(at = 2), record(uri = "content://x/b", format = ConversionFormat.XISO, profile = "", download = null, at = 1))
        assertEquals(records, NoSavingRecords.decode(NoSavingRecords.encode(records)))
        val mixed = """[{"uri":"u","main":1,"input":2,"format":"RVZ","at":3},{"uri":"v","format":"NOPE","main":1,"input":1},{"main":1},7]"""
        val decoded = NoSavingRecords.decode(mixed)
        assertEquals(1, decoded.size)
        assertEquals(ConversionFormat.RVZ, decoded[0].format)
        assertEquals(null, decoded[0].downloadFileName)
        assertEquals(emptyList<NoSavingRecord>(), NoSavingRecords.decode("not json"))
        assertEquals(emptyList<NoSavingRecord>(), NoSavingRecords.decode(""))
    }

    @Test
    fun `a file gone or of another size no longer matches`() {
        assertTrue(NoSavingRecords.stillValid(record(main = 100)) { 100 })
        assertFalse(NoSavingRecords.stillValid(record(main = 100)) { 101 })
        assertFalse(NoSavingRecords.stillValid(record(main = 100)) { null })
    }

    @Test
    fun `a track that changed or went unlocks its sheet`() {
        val sheet = record(main = 100).copy(companions = mapOf("content://x/t1.bin" to 5_000L, "content://x/t2.bin" to 700L))
        val sizes = mapOf(sheet.uri to 100L, "content://x/t1.bin" to 5_000L, "content://x/t2.bin" to 700L)
        assertTrue(NoSavingRecords.stillValid(sheet) { sizes[it] })
        assertFalse(NoSavingRecords.stillValid(sheet) { (sizes + ("content://x/t1.bin" to 5_001L))[it] })
        assertFalse(NoSavingRecords.stillValid(sheet) { (sizes - "content://x/t2.bin")[it] })
        assertEquals(listOf(sheet), NoSavingRecords.decode(NoSavingRecords.encode(listOf(sheet))))
    }

    @Test
    fun `applies only with the profile it was tried with`() {
        val chd = record(profile = "BEST")
        val xiso = record(uri = "content://x/b", format = ConversionFormat.XISO, profile = "")
        val profile = { chdName: String -> { f: ConversionFormat -> NoSavingRecords.profileOf(f, chdName) } }
        assertEquals(setOf(chd.uri, xiso.uri), NoSavingRecords.applying(listOf(chd, xiso), profile("BEST")).keys)
        assertEquals(setOf(xiso.uri), NoSavingRecords.applying(listOf(chd, xiso), profile("FAST")).keys)
        // Tried with both: applies with either, the newest shown.
        val fast = record(profile = "FAST", at = 5)
        assertEquals(fast, NoSavingRecords.applying(listOf(chd, fast), profile("FAST"))[chd.uri])
        assertEquals(chd, NoSavingRecords.applying(listOf(chd, fast), profile("BEST"))[chd.uri])
    }

    @Test
    fun `adding replaces the same try and keeps the newest up to the cap`() {
        val first = record(at = 1)
        val again = record(at = 2)
        assertEquals(listOf(again), NoSavingRecords.add(listOf(first), again))
        val other = record(profile = "FAST", at = 3)
        assertEquals(listOf(other, again), NoSavingRecords.add(listOf(again), other))
        val many = (1..5).fold(emptyList<NoSavingRecord>()) { list, i -> NoSavingRecords.add(list, record(uri = "u$i", at = i.toLong()), max = 3) }
        assertEquals(listOf("u5", "u4", "u3"), many.map { it.uri })
    }

    @Test
    fun `forgets by file and by download`() {
        val a = record(uri = "a", download = "a.zip")
        val b = record(uri = "b", download = null)
        val c = record(uri = "c", download = "c.zip")
        assertEquals(listOf(b, c), NoSavingRecords.forgetUri(listOf(a, b, c), "a"))
        assertEquals(listOf(b), NoSavingRecords.forgetDownloads(listOf(a, b, c), setOf("a.zip", "c.zip")))
        assertEquals(listOf(a, b, c), NoSavingRecords.forgetDownloads(listOf(a, b, c), emptySet()))
    }
}
