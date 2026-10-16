package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.PreferredRegion
import com.cortinadev.dogmatix.data.model.VersionPrefs
import com.cortinadev.dogmatix.util.CollectionFixtures.GBA
import com.cortinadev.dogmatix.util.CollectionFixtures.PSX
import com.cortinadev.dogmatix.util.CollectionFixtures.SNES
import com.cortinadev.dogmatix.util.CollectionFixtures.file
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionDuplicatesTest {

    private val spain = VersionPrefs(PreferredRegion.SPAIN, setOf("ES"))

    private fun find(vararg files: DiskFile, prefs: VersionPrefs = spain) =
        CollectionDuplicates.find(CollectionGames.group(files.toList()), prefs)

    @Test
    fun `the same file in two folders keeps the shallow copy`() {
        val group = find(file("Golden Sun (USA).gba", 8_000), file("Backup/Golden Sun (USA).gba", 8_000)).single()
        assertEquals(DuplicateKind.SAME_FILE, group.kind)
        assertEquals("Golden Sun", group.title)
        assertEquals("Golden Sun (USA).gba", group.keep!!.files.single().path)
        assertEquals(8_000, group.reclaimable())
    }

    @Test
    fun `versions keep the one for the user's region and languages`() {
        val group = find(file("Golden Sun (USA).gba", 8_000), file("Golden Sun (Europe) (En,Fr,De,Es,It).gba", 8_100), file("Golden Sun (Japan).gba", 7_900)).single()
        assertEquals(DuplicateKind.VERSIONS, group.kind)
        assertEquals("Golden Sun (Europe) (En,Fr,De,Es,It)", group.keep!!.title)
        assertEquals(15_900, group.reclaimable())
        assertEquals(group.keep, group.games.first())
    }

    @Test
    fun `released and newer revisions win, equal versions are left to the user`() {
        val beta = find(file("Zelda (USA) (Beta).gba"), file("Zelda (USA).gba")).single()
        assertEquals("Zelda (USA)", beta.keep!!.title)
        val rev = find(file("Zelda (Europe).gba"), file("Zelda (Europe) (Rev 1).gba")).single()
        assertEquals("Zelda (Europe) (Rev 1)", rev.keep!!.title)
        val tie = find(file("Zelda (Japan).gba"), file("Zelda (Korea).gba")).single()
        assertNull(tie.keep)
        assertEquals(0, tie.reclaimable())
        assertEquals(1, tie.removable(tie.games[0].id).size)
    }

    @Test
    fun `a set folder and the same loose discs keep the folder layout`() {
        val group = find(
            file("Parasite Eve (USA) (Disc 1).chd", console = PSX, top = "psx"),
            file("Parasite Eve (USA) (Disc 2).chd", console = PSX, top = "psx"),
            file("Parasite Eve (USA).m3u/Parasite Eve (USA) (Disc 1).chd", console = PSX, top = "psx"),
            file("Parasite Eve (USA).m3u/Parasite Eve (USA) (Disc 2).chd", console = PSX, top = "psx"),
            file("Parasite Eve (USA).m3u/Parasite Eve (USA).m3u", 60, console = PSX, top = "psx")
        ).single()
        assertEquals(DuplicateKind.SAME_FILE, group.kind)
        assertEquals("root/psx/Parasite Eve (USA).m3u", group.keep!!.setFolderUri)
    }

    @Test
    fun `identical content under other names, kept in the right console`() {
        val group = find(
            file("Metroid Fusion (USA).gba", 4_000, hash = "abc"),
            file("mf.gba", 4_000, console = SNES, top = "snes", hash = "abc"),
            file("Other.gba", 4_000, hash = "def")
        ).single()
        assertEquals(DuplicateKind.SAME_CONTENT, group.kind)
        assertEquals(GBA, group.keep!!.consoleId)
        assertEquals(listOf("mf.gba"), group.removable().map { it.title + ".gba" })
    }

    @Test
    fun `bios files and empty games are never offered`() {
        assertTrue(find(file("gba_bios.bin"), file("Backup/gba_bios.bin")).isEmpty())
        assertTrue(find(file("Game (USA).gba", 0), file("Backup/Game (USA).gba", 0)).isEmpty())
        assertTrue(find(file("Game (USA).gba", 10, console = null, top = ""), file("Game (Europe).gba", 10, console = null, top = "")).isEmpty())
    }

    @Test
    fun `groups with the most to win come first`() {
        val groups = find(
            file("Small (USA).gba", 100), file("Small (Europe).gba", 100),
            file("Big (USA).gba", 9_000), file("Big (Europe).gba", 9_000)
        )
        assertEquals(listOf("Big", "Small"), groups.map { it.title })
    }
}
