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

    private val segaCd = "sega_cd"
    private val dreamcast = "sega_dreamcast"
    private val saturn = "sega_saturn"

    /** `Game (USA).cue` + `Game (USA).bin` in [top]; [cueSize] and [binHash] tell copies apart. */
    private fun cueSet(top: String, console: String?, binHash: String?, catalogId: Long? = null, cueSize: Long = 120, folder: String = "") = arrayOf(
        file("${folder}Game (USA).cue", cueSize, console = console, top = top, refs = listOf("Game (USA).bin"), catalogId = catalogId),
        file("${folder}Game (USA).bin", 2_000_000, console = console, top = top, hash = binHash, catalogId = catalogId)
    )

    @Test
    fun `the same cue and bin in another console's folder are a copy, kept where the catalogue lists it`() {
        // The misplaced copy first: the walk order must not decide.
        val group = find(*cueSet("segacd", segaCd, "aa"), *cueSet("psx", PSX, "aa", catalogId = 7)).single()
        assertEquals(DuplicateKind.SAME_CONTENT, group.kind)
        val keep = group.keep!!
        assertEquals(PSX, keep.consoleId)
        assertEquals(PSX, group.consoleId)
        assertEquals(2, keep.files.size)
        val removable = group.removable().single()
        assertEquals(segaCd, removable.consoleId)
        assertEquals(listOf("Game (USA).bin", "Game (USA).cue"), removable.files.map { it.name })
        assertEquals(2_000_120, group.reclaimable())
    }

    @Test
    fun `a copy in another console joins the group its own console already has`() {
        val group = find(
            *cueSet("segacd", segaCd, "aa"),
            *cueSet("psx", PSX, "aa", catalogId = 7, folder = "Backup/"),
            *cueSet("psx", PSX, "aa", catalogId = 7)
        ).single()
        assertEquals(DuplicateKind.SAME_FILE, group.kind)
        assertEquals(3, group.games.size)
        val keep = group.keep!!
        assertEquals("Game (USA).bin", keep.files.first().path)
        assertEquals(PSX, keep.consoleId)
        assertEquals(setOf(PSX, segaCd), group.removable().map { it.consoleId }.toSet())
        assertEquals(4_000_240, group.reclaimable())
    }

    @Test
    fun `the same names with another bin or another sheet are not a copy`() {
        assertTrue(find(*cueSet("segacd", segaCd, "bb"), *cueSet("psx", PSX, "aa")).isEmpty())
        assertTrue(find(*cueSet("segacd", segaCd, "aa", cueSize = 130), *cueSet("psx", PSX, "aa")).isEmpty())
        val otherTrack = cueSet("segacd", segaCd, "aa").let { arrayOf(it[0].copy(references = listOf("Game (USA) (Track 1).bin")), it[1]) }
        assertTrue(find(*otherTrack, *cueSet("psx", PSX, "aa")).isEmpty())
    }

    @Test
    fun `a set is only compared once every big file was hashed`() {
        assertTrue(find(*cueSet("segacd", segaCd, null), *cueSet("psx", PSX, "aa")).isEmpty())
        // Name and size alone never make a copy: small files are not hashed.
        assertTrue(find(file("Tiny (USA).a26", 4_096, console = "atari_2600", top = "atari2600"), file("Tiny (USA).a26", 4_096, console = "atari_7800", top = "atari7800")).isEmpty())
    }

    @Test
    fun `a gdi set with plain track names is compared whole, and two such games never pair their tracks`() {
        fun gdi(top: String, console: String, game: String, hashes: List<String>, catalogId: Long? = null) = arrayOf(
            file("$game/$game.gdi", 90, console = console, top = top, refs = listOf("track01.bin", "track02.raw", "track03.bin"), catalogId = catalogId),
            file("$game/track01.bin", 1_400_000, console = console, top = top, hash = hashes[0]),
            file("$game/track02.raw", 900_000, console = console, top = top, hash = hashes[1]),
            file("$game/track03.bin", 600_000_000, console = console, top = top, hash = hashes[2])
        )
        val groups = find(
            *gdi("saturn", saturn, "Shenmue (Europe)", listOf("t1", "t2", "t3")),
            *gdi("dc", dreamcast, "Shenmue (Europe)", listOf("t1", "t2", "t3"), catalogId = 3),
            *gdi("dc", dreamcast, "Crazy Taxi (USA)", listOf("c1", "c2", "c3"))
        )
        val group = groups.single()
        assertEquals("Shenmue", group.title)
        assertEquals(dreamcast, group.keep!!.consoleId)
        assertEquals(4, group.removable().single().files.size)
        assertEquals(saturn, group.removable().single().consoleId)
    }

    @Test
    fun `a set folder in another console's folder is a copy with its folder`() {
        fun setFolder(top: String, console: String, catalogId: Long? = null) = arrayOf(
            file("Parasite Eve (USA).m3u/Parasite Eve (USA) (Disc 1).chd", 400_000_000, console = console, top = top, hash = "d1", catalogId = catalogId),
            file("Parasite Eve (USA).m3u/Parasite Eve (USA) (Disc 2).chd", 410_000_000, console = console, top = top, hash = "d2", catalogId = catalogId),
            file("Parasite Eve (USA).m3u/Parasite Eve (USA).m3u", 60, console = console, top = top,
                refs = listOf("Parasite Eve (USA) (Disc 1).chd", "Parasite Eve (USA) (Disc 2).chd"))
        )
        val group = find(*setFolder("segacd", segaCd), *setFolder("psx", PSX, catalogId = 1)).single()
        assertEquals(DuplicateKind.SAME_CONTENT, group.kind)
        assertEquals("root/psx/Parasite Eve (USA).m3u", group.keep!!.setFolderUri)
        assertEquals("root/segacd/Parasite Eve (USA).m3u", group.removable().single().setFolderUri)
    }

    @Test
    fun `free up space offers the other console's copy with all its files`() {
        val groups = find(*cueSet("segacd", segaCd, "aa"), *cueSet("psx", PSX, "aa", catalogId = 7))
        val candidate = CollectionReclaim.candidates(groups, { it.keep?.id }, emptyList(), null, null).single()
        assertEquals(ReclaimKind.DUPLICATE, candidate.kind)
        assertEquals(segaCd, candidate.consoleId)
        assertEquals(2_000_120, candidate.bytes)
        assertEquals(listOf("root/segacd/Game (USA).bin", "root/segacd/Game (USA).cue"), candidate.files.map { it.uri })
        assertEquals(PSX, candidate.kept!!.consoleId)
    }

    @Test
    fun `hashes go first to sizes shared across games or consoles, then to the rarest sizes`() {
        val files = listOf(
            file("A (USA).gba", 8_388_608), file("B (USA).gba", 8_388_608), file("C (USA).gba", 8_388_608),
            file("D (USA).gba", 4_194_304), file("Backup/D (USA).gba", 4_194_304),
            file("Game (USA).cue", 120, console = PSX, top = "psx"),
            file("Game (USA).bin", 2_000_000, console = PSX, top = "psx"),
            file("Game (USA).png", 2_000_000, console = PSX, top = "psx"),
            file("Unique (USA).bin", 3_000_000, console = PSX, top = "psx"),
            file("Game (USA).bin", 2_000_000, console = segaCd, top = "segacd")
        )
        assertEquals(
            listOf("psx/Game (USA).bin", "segacd/Game (USA).bin", "gba/A (USA).gba", "gba/B (USA).gba", "gba/C (USA).gba", "gba/D (USA).gba", "gba/Backup/D (USA).gba"),
            CollectionDuplicates.hashCandidates(files).map { it.uri.removePrefix("root/") }
        )
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
