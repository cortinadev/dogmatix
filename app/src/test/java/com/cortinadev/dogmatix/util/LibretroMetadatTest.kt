package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibretroMetadatTest {

    private val snes = LibretroThumbnails.systemFor("nintendo_snes")!!

    @Test
    fun urlsUseTheDatabaseNameOfTheSystem() {
        assertEquals("Nintendo - Super Nintendo Entertainment System", LibretroMetadat.databaseName(snes))
        assertEquals(
            "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/genre/Nintendo%20-%20Super%20Nintendo%20Entertainment%20System.dat",
            LibretroMetadat.url(snes, LibretroMetadat.Field.GENRE)
        )
        assertTrue(LibretroMetadat.url(snes, LibretroMetadat.Field.YEAR).contains("/metadat/releaseyear/"))
    }

    private val genreDat = """
        clrmamepro (
        	name "Nintendo - Super Nintendo Entertainment System"
        	description "Nintendo - Super Nintendo Entertainment System"
        )

        game (
        	comment "Super Mario World (USA)"
        	genre "Platform"
        	rom ( crc B19ED489 )
        )

        game (
        	comment "Super Mario World (Europe) (Rev 1)"
        	genre "Action"
        	rom ( crc 2D4A0E3C )
        )

        game (
        	genre "Sports"
        	rom ( crc 05FBB855 )
        )

        game (
        	comment "Legend of Zelda, The - A Link to the Past (USA)"
        	genre "Role-playing (RPG)"
        	rom ( crc 777ACA6C )
        )
    """.trimIndent()

    private val yearDat = """
        game (
        	comment "Super Mario World (Europe) (Rev 1)"
        	releaseyear "1992"
        	rom ( crc 2D4A0E3C )
        )

        game (
        	comment "Super Mario World (USA)"
        	releaseyear "1991"
        )

        game (
        	comment "Tetris Attack (USA)"
        	releaseyear "199x"
        )
    """.trimIndent()

    @Test
    fun parseKeepsBlocksWithNameAndValue() {
        val genres = LibretroMetadat.parse(genreDat.lineSequence(), LibretroMetadat.Field.GENRE)
        assertEquals(
            listOf(
                "Super Mario World (USA)" to "Platform",
                "Super Mario World (Europe) (Rev 1)" to "Action",
                "Legend of Zelda, The - A Link to the Past (USA)" to "Role-playing (RPG)"
            ),
            genres
        )
        assertEquals(3, LibretroMetadat.parse(yearDat.lineSequence(), LibretroMetadat.Field.YEAR).size)
        assertEquals(emptyList<Pair<String, String>>(), LibretroMetadat.parse(genreDat.lineSequence(), LibretroMetadat.Field.YEAR))
    }

    @Test
    fun versionsOfAGameShareOneTrait() {
        val traits = LibretroMetadat.traitsByGroup(
            LibretroMetadat.parse(genreDat.lineSequence(), LibretroMetadat.Field.GENRE),
            LibretroMetadat.parse(yearDat.lineSequence(), LibretroMetadat.Field.YEAR)
        )
        val mario = traits.getValue("super mario world")
        assertEquals(listOf(LibraryGenre.ACTION, LibraryGenre.PLATFORM), LibraryGenre.inMask(mario.genres))
        assertEquals(1991, mario.year)
        val zelda = traits.getValue("legend of zelda, the - a link to the past")
        assertEquals(LibraryGenre.RPG.mask, zelda.genres)
        assertNull(zelda.year)
        // An unreadable year leaves nothing to keep.
        assertNull(traits["tetris attack"])
    }

    @Test
    fun datNamesGetTheGroupKeyTheIndexerGivesTheFile() {
        listOf(
            "Super Mario World (USA).sfc" to "Super Mario World (USA)",
            "Legend of Zelda, The - A Link to the Past (USA).zip" to "Legend of Zelda, The - A Link to the Past (USA)",
            "Super Mario Bros. 3 (USA) (Rev 1).nes" to "Super Mario Bros. 3 (USA)",
            "Dr. Mario (Japan, USA).nes" to "Dr. Mario (Japan, USA)"
        ).forEach { (file, dat) ->
            val indexed = VersionGrouping.groupKey(FileParsingUtils.extractNameAndTags(file).first)
            assertEquals(indexed, LibretroMetadat.groupKeyOf(dat))
        }
    }
}
