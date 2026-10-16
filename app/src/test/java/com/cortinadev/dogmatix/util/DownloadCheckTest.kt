package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCheckTest {

    private val sha = "6e4e9be9a07580ef267be9c2ea1bd0730b3be44a"
    private val gba = HashedFile("007 - Everything or Nothing (Japan).gba", FileHashes(8388608, "caf2e99f", sha, "55354d9e3bc9c1fa682b5110e5ed1544"))

    @Test
    fun `same bytes under any name match, the same name with other bytes does not`() {
        assertEquals(FileVerdict.Match, DownloadCheck.check(gba, listOf(ExpectedFile("renamed.gba", 8388608, "caf2e99f", sha))))
        assertEquals(FileVerdict.Match, DownloadCheck.check(gba, listOf(ExpectedFile("x.gba", 8388608, null, null, "55354d9e3bc9c1fa682b5110e5ed1544"))))
        assertEquals(
            FileVerdict.Mismatch(HashKind.SHA1, "1111111111111111111111111111111111111111", sha),
            DownloadCheck.check(gba, listOf(ExpectedFile(gba.path, 8388608, "caf2e99f", "1111111111111111111111111111111111111111")))
        )
        assertEquals(
            FileVerdict.Mismatch(HashKind.SIZE, "8388600", "8388608"),
            DownloadCheck.check(gba, listOf(ExpectedFile("Disc/" + gba.path, 8388600, null, null)))
        )
        assertEquals(FileVerdict.NotListed, DownloadCheck.check(gba, listOf(ExpectedFile("other.gba", 1, "00000001", null))))
        // Nothing but an equal size is not a verdict.
        assertEquals(FileVerdict.NotListed, DownloadCheck.check(gba, listOf(ExpectedFile(gba.path, 8388608, null, null))))
    }

    @Test
    fun `a dat streamed through the matcher`() {
        val track = HashedFile("Game (Disc 1)/Game (Track 1).bin", FileHashes(1000, "0000abcd", "aa".repeat(20)))
        val bad = HashedFile("Other (USA).gba", FileHashes(16, "00000002", "bb".repeat(20)))
        val matcher = DatMatcher(listOf(gba, track, bad))
        listOf(
            DatRom("Something.gba", 8388608, "caf2e99f", sha),
            DatRom("Game (Track 1).bin", 1000, "0000abcd", null),
            DatRom("Other (USA).gba", 16, "00000003", null),
            DatRom("Noise.bin", 5, "0000abcd", null)
        ).forEach(matcher::offer)
        assertEquals(4, matcher.entries)
        val verdicts = matcher.verdicts()
        assertEquals(FileVerdict.Match, verdicts[0])
        assertEquals(FileVerdict.Match, verdicts[1])
        assertEquals(FileVerdict.Mismatch(HashKind.CRC32, "00000003", "00000002"), verdicts[2])

        val result = DownloadCheck.combine(DownloadCheck.NO_INTRO, listOf(gba, track, bad), verdicts)!!
        assertEquals(CheckState.MISMATCH, result.state)
        assertEquals("Other (USA).gba", result.mismatch!!.file)
        assertEquals(2, result.verified)
    }

    @Test
    fun `combining references`() {
        assertNull(DownloadCheck.combine(DownloadCheck.REDUMP, listOf(gba), listOf(FileVerdict.NotListed)))
        val ok = DownloadCheck.combine(DownloadCheck.REDUMP, listOf(gba), listOf(FileVerdict.Match))!!
        assertEquals(CheckState.VERIFIED, ok.state)
        val mismatch = DownloadCheck.combine("dat:Old", listOf(gba), listOf(FileVerdict.Mismatch(HashKind.CRC32, "1", "2")))
        // A match in one DAT beats a mismatch in an older one.
        assertEquals(ok, DownloadCheck.best(listOf(mismatch, null, ok)))
        assertEquals(mismatch, DownloadCheck.best(listOf(null, mismatch)))
        assertNull(DownloadCheck.best(listOf(null)))
    }

    @Test
    fun `what DATs describe`() {
        assertTrue(DownloadCheck.datDescribes("Game (USA).gba"))
        assertTrue(DownloadCheck.datDescribes("Disc 1/Game (Track 1).bin"))
        assertFalse(DownloadCheck.datDescribes("Game (USA).cue"))
        assertFalse(DownloadCheck.datDescribes("Game (USA).chd"))
        assertFalse(DownloadCheck.datDescribes("Game.m3u"))
    }

    @Test
    fun `romm hashes, archives left out`() {
        val json = """
            {"id": 12, "fs_name": "Tetris (World).zip", "fs_size_bytes": 3000, "has_multiple_files": false,
             "crc_hash": "deadbeef", "md5_hash": "", "sha1_hash": "",
             "files": [
               {"file_name": "Tetris (World).zip", "file_size_bytes": 3000, "crc_hash": "deadbeef", "sha1_hash": null,
                "archive_members": [{"name": "Tetris (World).gb", "size": 32768, "crc_hash": "46DF91AD", "md5_hash": null, "sha1_hash": "74591CC9501AF93873F9A5D3EB12DA12C0723BBC"}]}
             ]}
        """.trimIndent()
        val expected = DownloadCheck.rommExpectedFiles(json)
        assertEquals(listOf(ExpectedFile("Tetris (World).gb", 32768, "46df91ad", "74591cc9501af93873f9a5d3eb12da12c0723bbc", null)), expected)

        val single = DownloadCheck.rommExpectedFiles("""{"fs_name": "Game.iso", "fs_size_bytes": 10, "sha1_hash": "${"ab".repeat(20)}", "files": [{"filename": "Game.iso", "size": 10, "sha1_hash": "${"ab".repeat(20)}"}]}""")
        assertEquals(1, single.size)
        assertTrue(DownloadCheck.rommExpectedFiles("not json").isEmpty())
        assertEquals(12, DownloadCheck.rommRomIdOf("http://192.168.1.10:8090/api/roms/12/content/Tetris%20(World).zip"))
        assertNull(DownloadCheck.rommRomIdOf("https://myrient.example/files/x.zip"))
    }

    @Test
    fun `stored form round trip`() {
        val results = listOf(
            CheckResult(CheckState.CHECKING, files = listOf("a.bin", "Disc 1/b.bin")),
            CheckResult(CheckState.VERIFIED, DownloadCheck.ROMM, listOf("a.gba"), 1, 1),
            CheckResult(CheckState.MISMATCH, "dat:Nintendo - Game Boy", listOf("a.gb"), 0, 1, MismatchDetail("a.gb", HashKind.SHA1, "11", "22")),
            CheckResult(CheckState.UNKNOWN, DownloadCheck.REDUMP, listOf("a.chd"), reason = UnknownReason.NOT_LISTED)
        )
        results.forEach { assertEquals(it, DownloadCheck.decode(DownloadCheck.encode(it))) }
        assertNull(DownloadCheck.decode("{}"))
        assertNull(DownloadCheck.decode(null))
    }
}
