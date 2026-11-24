package com.cortinadev.dogmatix.data.service.convert

import com.cortinadev.dogmatix.data.service.convert.chd.CdSheetException
import com.cortinadev.dogmatix.data.service.convert.chd.CdSheets
import com.cortinadev.dogmatix.data.service.convert.chd.CdTrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected strings are what chdman 0.276 wrote for the same sheets (`chdman info -v`). */
class CdSheetsTest {

    private val sizes = mapOf(
        "Game (Track 1).bin" to 2352L * 1000,
        "Game (Track 2).bin" to 2352L * 650,
        "Game (Track 3).bin" to 2352L * 451,
        "One.bin" to 2352L * 1650,
        "track01.bin" to 2352L * 300,
        "track02.raw" to 2352L * 450,
        "track03.bin" to 2352L * 400,
    )

    @Test
    fun redumpMultiBinCueMatchesChdman() {
        val cue = """
            FILE "Game (Track 1).bin" BINARY
              TRACK 01 MODE2/2352
                INDEX 01 00:00:00
            FILE "Game (Track 2).bin" BINARY
              TRACK 02 AUDIO
                INDEX 00 00:00:00
                INDEX 01 00:02:00
            FILE "Game (Track 3).bin" BINARY
              TRACK 03 AUDIO
                INDEX 00 00:00:00
                INDEX 01 00:02:00
        """.trimIndent()
        val toc = CdSheets.parse("Game.cue", cue) { sizes[it] }
        assertEquals(
            listOf(
                "CHT2" to "TRACK:1 TYPE:MODE2_RAW SUBTYPE:NONE FRAMES:1000 PREGAP:0 PGTYPE:MODE1 PGSUB:NONE POSTGAP:0",
                "CHT2" to "TRACK:2 TYPE:AUDIO SUBTYPE:NONE FRAMES:650 PREGAP:150 PGTYPE:VAUDIO PGSUB:NONE POSTGAP:0",
                "CHT2" to "TRACK:3 TYPE:AUDIO SUBTYPE:NONE FRAMES:451 PREGAP:150 PGTYPE:VAUDIO PGSUB:NONE POSTGAP:0",
            ),
            toc.metadata(),
        )
        assertEquals(2104L, toc.totalFrames) // 1000 + 652 + 452
        assertEquals(5_150_592L, toc.logicalBytes)
        assertFalse(toc.tracks[0].swap)
        assertTrue(toc.tracks[1].swap)
        assertEquals(listOf("Game (Track 1).bin", "Game (Track 2).bin", "Game (Track 3).bin"), toc.files)
    }

    @Test
    fun singleBinCueSplitsAtIndexZero() {
        val cue = "﻿FILE \"One.bin\" BINARY\r\n  TRACK 01 MODE1/2352\r\n    INDEX 01 00:00:00\r\n" +
            "  TRACK 02 AUDIO\r\n    INDEX 00 00:13:25\r\n    INDEX 01 00:15:25\r\n"
        val toc = CdSheets.parse("One.cue", cue) { sizes[it] }
        assertEquals("TRACK:1 TYPE:MODE1_RAW SUBTYPE:NONE FRAMES:1000 PREGAP:0 PGTYPE:MODE1 PGSUB:NONE POSTGAP:0", toc.metadata()[0].second)
        assertEquals("TRACK:2 TYPE:AUDIO SUBTYPE:NONE FRAMES:650 PREGAP:150 PGTYPE:VAUDIO PGSUB:NONE POSTGAP:0", toc.metadata()[1].second)
        assertEquals(2352L * 1000, toc.tracks[1].offset)
        assertEquals(1652L, toc.totalFrames)
    }

    @Test
    fun gdiMatchesChdman() {
        val gdi = "3\n1 0 4 2352 track01.bin 0\n2 600 0 2352 track02.raw 0\n3 45000 4 2352 \"track03.bin\" 0\n"
        val toc = CdSheets.parse("disc.gdi", gdi) { sizes[it] }
        assertEquals(
            listOf(
                "CHGD" to "TRACK:1 TYPE:MODE1_RAW SUBTYPE:NONE FRAMES:600 PAD:300 PREGAP:0 PGTYPE:MODE1 PGSUB:NONE POSTGAP:0",
                "CHGD" to "TRACK:2 TYPE:AUDIO SUBTYPE:NONE FRAMES:44400 PAD:43950 PREGAP:0 PGTYPE:MODE1 PGSUB:NONE POSTGAP:0",
                "CHGD" to "TRACK:3 TYPE:MODE1_RAW SUBTYPE:NONE FRAMES:400 PAD:0 PREGAP:0 PGTYPE:MODE1 PGSUB:NONE POSTGAP:0",
            ),
            toc.metadata(),
        )
        assertEquals(45_400L, toc.totalFrames)
    }

    @Test
    fun isoIsOneMode1Track() {
        val toc = CdSheets.parseIso("game.iso", 2048L * 3501)
        assertEquals("TRACK:1 TYPE:MODE1 SUBTYPE:NONE FRAMES:3501 PREGAP:0 PGTYPE:MODE1 PGSUB:NONE POSTGAP:0", toc.metadata()[0].second)
        assertEquals(3504L * 2448, toc.logicalBytes)
        assertEquals(CdTrackType.MODE2_RAW, CdSheets.parseIso("x.bin", 2352L * 7).tracks[0].type)
    }

    @Test
    fun pregapCommandIsNotInTheFile() {
        val cue = "FILE \"a.bin\" BINARY\nTRACK 01 MODE1/2048\nPREGAP 00:02:00\nINDEX 01 00:00:00\n"
        val toc = CdSheets.parse("a.cue", cue) { 2048L * 10 }
        assertEquals("TRACK:1 TYPE:MODE1 SUBTYPE:NONE FRAMES:10 PREGAP:150 PGTYPE:MODE1 PGSUB:NONE POSTGAP:0", toc.metadata()[0].second)
    }

    @Test
    fun msfAndErrors() {
        assertEquals(150, CdSheets.msfToFrames("00:02:00"))
        assertEquals(4500 + 75 + 3, CdSheets.msfToFrames("01:01:03"))
        assertEquals(42, CdSheets.msfToFrames("42"))
        expectSheetError { CdSheets.parse("a.cue", "FILE \"a.wav\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\n") { 100L } }
        expectSheetError { CdSheets.parse("a.cue", "FILE \"a.bin\" BINARY\nTRACK 01 MODE1/2352\nINDEX 01 00:00:00\n") { null } }
        expectSheetError { CdSheets.parse("a.cue", "FILE \"a.bin\" BINARY\nTRACK 01 FOO\nINDEX 01 00:00:00\n") { 100L } }
        expectSheetError { CdSheets.parse("a.gdi", "2\n1 0 4 2352 a.bin 0\n") { 2352L } }
    }

    private fun expectSheetError(block: () -> Unit) {
        try {
            block()
        } catch (e: CdSheetException) {
            return
        }
        throw AssertionError("Expected CdSheetException")
    }
}
