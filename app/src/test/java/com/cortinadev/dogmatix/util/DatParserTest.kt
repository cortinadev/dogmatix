package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DatParserTest {

    private val clrMamePro = """
        clrmamepro (
        	name "Nintendo - Game Boy Advance"
        	description "Nintendo - Game Boy Advance"
        	version "2026.10.07"
        )

        game (
        	name "007 - Everything or Nothing (Japan)"
        	region "Japan"
        	rom ( name "007 - Everything or Nothing (Japan).gba" size 8388608 crc CAF2E99F md5 55354D9E3BC9C1FA682B5110E5ED1544 sha1 6E4E9BE9A07580EF267BE9C2EA1BD0730B3BE44A serial "BJBJ" )
        )
        game (
        	name "Saturn (Japan)"
        	rom ( name "Saturn (Japan) (Track 01).bin" size 38088288 crc 02954D99 )
        	rom ( name "Saturn (Japan) (Track 02).bin" size 1000 crc 1 )
        )
    """.trimIndent()

    @Test
    fun `clrmamepro text as libretro-database ships it`() {
        val (header, roms) = DatParser.parse(clrMamePro)
        assertEquals(DatHeader("Nintendo - Game Boy Advance", "2026.10.07"), header)
        assertEquals(3, roms.size)
        assertEquals(DatRom("007 - Everything or Nothing (Japan).gba", 8388608, "caf2e99f", "6e4e9be9a07580ef267be9c2ea1bd0730b3be44a"), roms[0])
        assertNull(roms[1].sha1)
        assertEquals("00000001", roms[2].crc)
    }

    private val logiqx = """
        ﻿<?xml version="1.0"?>
        <!DOCTYPE datafile PUBLIC "-//Logiqx//DTD ROM Management Datafile//EN" "http://www.logiqx.com/Dats/datafile.dtd">
        <datafile>
        	<header>
        		<name>Sony - PlayStation</name>
        		<description>Sony - PlayStation - Discs (10432) (2026-10-01)</description>
        		<version>2026-10-01 12-00-00</version>
        	</header>
        	<!-- a comment with <rom name="fake" size="1" crc="00000000"/> inside -->
        	<game name="Ape Escape (USA)">
        		<category>Games</category>
        		<description>Ape Escape (USA)</description>
        		<rom name="Ape Escape (USA).cue" size="91" crc="3c5b9b5a" md5="x" sha1="1111111111111111111111111111111111111111"/>
        		<rom name='Ape Escape (USA) (Track 1).bin' size="575741520" crc="F2F6E8C7" sha1="2222222222222222222222222222222222222222" />
        	</game>
        	<machine name="Tom &amp; Jerry (Europe)">
        		<rom name="Tom &amp; Jerry (Europe) (Track 1).bin" size="10" crc="abcdef01"/>
        	</machine>
        </datafile>
    """.trimIndent()

    @Test
    fun `logiqx xml with entities, comments and both quote styles`() {
        val (header, roms) = DatParser.parse(logiqx)
        assertEquals(DatHeader("Sony - PlayStation", "2026-10-01 12-00-00"), header)
        assertEquals(listOf("Ape Escape (USA).cue", "Ape Escape (USA) (Track 1).bin", "Tom & Jerry (Europe) (Track 1).bin"), roms.map { it.name })
        assertEquals(575741520L, roms[1].size)
        assertEquals("f2f6e8c7", roms[1].crc)
        assertEquals("2222222222222222222222222222222222222222", roms[1].sha1)
    }

    @Test
    fun `streams without holding the entries`() {
        var count = 0
        val text = buildString {
            append("clrmamepro ( name \"Big\" )\n")
            repeat(5000) { append("game ( name \"G$it\" rom ( name \"G$it.bin\" size $it crc ${"%08X".format(it)} ) )\n") }
        }
        val header = DatParser.parse(text.reader()) { count++ }
        assertEquals("Big", header.name)
        assertEquals(5000, count)
        assertEquals(0, DatParser.parse("not a dat at all").second.size)
    }
}
