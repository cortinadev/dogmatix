package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.model.LibraryGame
import com.cortinadev.dogmatix.data.model.PreferredRegion
import com.cortinadev.dogmatix.data.model.VersionPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

class TitleClonesTest {

    /** Excerpts of Retool's clone lists as they are on GitHub (format and entries, Nov 2026). */
    private val gba = """
        {
        	"description": {"name": "Nintendo - Game Boy Advance (No-Intro)", "lastUpdated": "2026-01-02 11:07:19", "minimumVersion": "2.4.8"},
        	"variants": [
        		{
        			"group": "Advance Wars",
        			"titles": [
        				{"searchTerm": "Advance Wars"},
        				{"searchTerm": "Lu-Hai-Kong Dazhan"}
        			]
        		},
        		{
        			"group": "Air, Land and Sea Rescue",
        			"compilations": [
        				{"searchTerm": "2 Game Pack! - Matchbox Missions - Emergency Response & Air, Land and Sea Rescue", "titlePosition": 2}
        			]
        		},
        		{
        			"group": "Pokemon - FireRed Version",
        			"titles": [
        				{"searchTerm": "Pocket Monsters - FireRed"},
        				{"searchTerm": "Pokemon - Edicion Rojo Fuego"},
        				{"searchTerm": "Pokemon - Feuerrote Edition"},
        				{"searchTerm": "Pokemon - FireRed Version"},
        				{"searchTerm": "Pokemon - Version Rouge Feu"},
        				{"searchTerm": "Pokemon - Versione Rosso Fuoco"}
        			]
        		},
        		{
        			"group": "Pokemon - LeafGreen Version",
        			"titles": [
        				{"searchTerm": "Pocket Monsters - LeafGreen"},
        				{"searchTerm": "Pokemon - Blattgruene Edition"},
        				{"searchTerm": "Pokemon - Edicion Verde Hoja"},
        				{"searchTerm": "Pokemon - LeafGreen Version"}
        			]
        		},
        		{
        			"group": "Strawberry Shortcake - Summertime Adventure",
        			"supersets": [
        				{"searchTerm": "Strawberry Shortcake - Summertime Adventure - Special Edition"}
        			]
        		}
        	]
        }
    """.trimIndent()

    private val snes = """
        {
        	"variants": [
        		{
        			"group": "Legend of Zelda, The - A Link to the Past",
        			"titles": [
        				{"searchTerm": "Legend of Zelda, The - A Link to the Past"},
        				{"searchTerm": "Zelda no Densetsu - Kamigami no Triforce"}
        			]
        		},
        		{
        			"group": "Casper",
        			"titles": [
        				{"searchTerm": "Casper", "filters": [{"conditions": {"matchRegions": ["Japan"]}, "results": {"group": "Casper (Japan)"}}]},
        				{"searchTerm": "Casper - Kyaspa"}
        			]
        		},
        		{
        			"group": "Blackthorne",
        			"titles": [
        				{"searchTerm": "Blackthorne"},
        				{"searchTerm": "Blackthorne (CES)", "priority": 2}
        			]
        		},
        		{"group": "Shared A", "titles": [{"searchTerm": "Same Title"}, {"searchTerm": "Only In A"}, {"searchTerm": "Also In A"}]},
        		{"group": "Shared B", "titles": [{"searchTerm": "Same Title"}, {"searchTerm": "Only In B"}]}
        	]
        }
    """.trimIndent()

    @After
    fun tearDown() {
        TitleClones.clear()
        TitleClones.enabled = true
    }

    private fun key(name: String) = VersionGrouping.groupKey(FileParsingUtils.extractNameAndTags(name).first)

    @Test
    fun `fire red under every region's title is one game`() {
        val map = TitleClones.parse(gba)
        val canonical = TitleClones.canonicalIn(map, key("Pokemon - FireRed Version (USA, Europe) (Rev 1).gba"))
        assertEquals("pokemon - firered version", canonical)
        listOf(
            "Pokemon - Edicion Rojo Fuego (Spain).gba",
            "Pocket Monsters - FireRed (Japan) (Rev 1).gba",
            "Pokemon - Feuerrote Edition (Germany).gba",
            "Pokemon - Version Rouge Feu (France).gba",
            "Pokemon - Versione Rosso Fuoco (Italy).gba",
            // An accented dump name matches the ASCII list.
            "Pokémon - Edición Rojo Fuego (Spain).gba"
        ).forEach { assertEquals(it, canonical, TitleClones.canonicalIn(map, key(it))) }
    }

    @Test
    fun `games with close names stay apart`() {
        val map = TitleClones.parse(gba)
        assertEquals("pokemon - leafgreen version", TitleClones.canonicalIn(map, key("Pokemon - Edicion Verde Hoja (Spain).gba")))
        assertFalse(TitleClones.canonicalIn(map, key("Pokemon - Edicion Verde Hoja (Spain).gba")) == TitleClones.canonicalIn(map, key("Pokemon - Edicion Rojo Fuego (Spain).gba")))
        // Not in any list: left as it is.
        assertEquals("pokemon - ruby version", TitleClones.canonicalIn(map, key("Pokemon - Ruby Version (USA).gba")))
        assertEquals("pokemon - firered version - hack", TitleClones.canonicalIn(map, key("Pokemon - FireRed Version - Hack (USA).gba")))
        // Compilations and supersets are other games.
        assertNull(map[TitleClones.matchKey("2 game pack! - matchbox missions - emergency response & air, land and sea rescue")])
        assertNull(map[TitleClones.matchKey("strawberry shortcake - summertime adventure - special edition")])
    }

    @Test
    fun `zelda translated keeps the english group title as its key`() {
        val map = TitleClones.parse(snes)
        val usa = TitleClones.canonicalIn(map, key("Legend of Zelda, The - A Link to the Past (USA).sfc"))
        assertEquals("legend of zelda, the - a link to the past", usa)
        assertEquals(usa, TitleClones.canonicalIn(map, key("Zelda no Densetsu - Kamigami no Triforce (Japan).sfc")))
    }

    @Test
    fun `doubtful entries merge nothing`() {
        val map = TitleClones.parse(snes)
        // A title in two groups stays alone; the rest of each group still merges.
        assertNull(map["same title"])
        assertEquals("also in a", map["only in a"])
        // A group left with one title (the other one is region-conditional) does nothing.
        assertNull(map["casper - kyaspa"])
        assertNull(map["casper"])
        // A search term with a tag folds onto its own title: nothing to merge.
        assertNull(map["blackthorne"])
    }

    /** Entries copied from the SNES and Mega Drive lists (Nov 2026). */
    private val fallbacks = """
        {"variants": [
          {"group": "Jungle Book, The", "titles": [{"searchTerm": "Jungle Book, The"}, {"searchTerm": "Tarzan", "priority": 2}]},
          {"group": "Super Street Fighter II", "titles": [{"searchTerm": "Super Street Fighter II"}, {"searchTerm": "Super Street Fighter II - The New Challengers"}, {"searchTerm": "Street Fighter V", "priority": 2}]},
          {"group": "Donkey Kong Country", "titles": [{"searchTerm": "Donkey Kong Country"}, {"searchTerm": "Super Donkey Kong"}, {"searchTerm": "Donkey Kong Country - Competition Cartridge", "priority": 2}]},
          {"group": "Squirrel King", "titles": [{"searchTerm": "Squirrel King"}, {"searchTerm": "Super Mario Bros.", "priority": 2}, {"searchTerm": "Super Mario World", "priority": 3}]},
          {"group": "King of Fighters 98', The", "titles": [{"searchTerm": "King of Fighters 98', The"}, {"searchTerm": "King of Fighters 2000, The", "priority": 2}]},
          {"group": "King Arthur's World", "titles": [{"searchTerm": "King Arthur's World"}, {"searchTerm": "Royal Conquest"}, {"searchTerm": "Havoc (Demo)", "priority": 2}]},
          {"group": "Tetris", "titles": [{"searchTerm": "Tetris"}, {"searchTerm": "Simple 1500 Series Vol. 2 - The Tetris", "priority": 2}]},
          {"group": "Streets of Rage 2", "titles": [
            {"searchTerm": "Bare Knuckle II", "localNames": {"japanese": "x"}},
            {"searchTerm": "Streets of Rage 2"},
            {"searchTerm": "Streets of Rage II", "filters": [{"conditions": {"matchRegions": ["Europe", "Japan"]}, "results": {"localNames": {"english": "Street of Rage II"}}}]}
          ]},
          {"group": "Marble Madness", "titles": [
            {"searchTerm": "Marble Madness", "filters": [{"conditions": {"matchRegions": ["Japan"]}, "results": {"group": "Marble Madness (Japan)"}}]},
            {"searchTerm": "Marble Madness II"}, {"searchTerm": "Marble Madness Deluxe"}
          ]}
        ]}
    """.trimIndent()

    @Test
    fun `a fallback title joins only when its words match the group's`() {
        val map = TitleClones.parse(fallbacks)
        // Unrelated names under priority 2 or 3 stay games of their own, in both directions…
        listOf("tarzan", "street fighter v", "super mario bros.", "super mario world", "king of fighters 2000, the").forEach { assertNull(it, map[it]) }
        assertNull(map["jungle book, the"])
        // …without breaking the rest of their group.
        assertEquals("super street fighter ii", map["super street fighter ii - the new challengers"])
        assertEquals("donkey kong country", map["super donkey kong"])
        // Related ones join: the group's name inside theirs, or theirs inside it.
        assertEquals("donkey kong country", map["donkey kong country - competition cartridge"])
        assertEquals("tetris", map["simple 1500 series vol. 2 - the tetris"])
        assertTrue(TitleClones.relatedByWords("tetris", "simple 1500 series vol. 2 - the tetris"))
        assertFalse(TitleClones.relatedByWords("super street fighter ii", "street fighter v"))
        assertFalse(TitleClones.relatedByWords("tetris", "tetrisphere"))
    }

    @Test
    fun `a search term with brackets adds no title`() {
        val map = TitleClones.parse(fallbacks)
        // "Havoc (Demo)" would claim every release called "Havoc".
        assertNull(map["havoc"])
        assertEquals("king arthur's world", map["royal conquest"])
    }

    @Test
    fun `filters that only name the release keep the title, those that move it do not`() {
        val map = TitleClones.parse(fallbacks)
        assertEquals("streets of rage 2", map["streets of rage ii"])
        assertEquals("streets of rage 2", map["bare knuckle ii"])
        assertNull(map["marble madness"])
        assertEquals(map["marble madness ii"], map["marble madness deluxe"])
    }

    @Test
    fun `a game is keyed by the title it shows for the region`() {
        val map = TitleClones.parse(gba)
        val rows = listOf(
            titled("Pokemon - FireRed Version (USA, Europe)"),
            titled("Pokemon - FireRed Version (USA, Europe) (Rev 1)"),
            titled("Pokemon - Edicion Rojo Fuego (Spain)"),
            titled("Pocket Monsters - FireRed (Japan)"),
            titled("Pokemon - Edicion Verde Hoja (Spain)"),
            titled("Advance Wars (USA)")
        )
        val titles = TitleClones.titlesByGame(map, rows.map { it.titleKey })
        assertEquals(setOf("pokemon - firered version", "pokemon - leafgreen version", "advance wars"), titles.keys)
        assertEquals(setOf("pokemon - firered version", "pokemon - edicion rojo fuego", "pocket monsters - firered"), titles["pokemon - firered version"])

        val spain = TitleClones.gameKeys(titles, rows, VersionPrefs(PreferredRegion.SPAIN, setOf("ES")))
        assertEquals("pokemon - edicion rojo fuego", spain["pokemon - firered version"])
        // One title in the console: that one, even when the list's group is named otherwise.
        assertEquals("pokemon - edicion verde hoja", spain["pokemon - leafgreen version"])
        assertEquals("advance wars", spain["advance wars"])
        assertEquals("pokemon - firered version", TitleClones.gameKeys(titles, rows, VersionPrefs(PreferredRegion.USA, setOf("EN")))["pokemon - firered version"])
        assertEquals("pocket monsters - firered", TitleClones.gameKeys(titles, rows, VersionPrefs(PreferredRegion.JAPAN, setOf("JA")))["pokemon - firered version"])

        // Installed, every title of the game (also ones the console has no row of) lands on that key.
        TitleClones.install("gba", map, spain)
        listOf("pokemon - firered version", "pocket monsters - firered", "pokemon - feuerrote edition").forEach {
            assertEquals(it, "pokemon - edicion rojo fuego", TitleClones.canonical("gba", it))
        }
        // A plan with these keys puts the Spanish title first in A→Z: its rows keep their key.
        val plan = TitleClones.plan(rows.mapIndexed { i, r -> TitleClones.Row(i + 1L, r.titleKey, r.titleKey) }) { TitleClones.canonical("gba", it) }
        assertEquals(mapOf("pokemon - edicion rojo fuego" to listOf(1L, 2L, 4L)), plan.updates)
    }

    @Test
    fun `a group title spelled with accents keys the game by that spelling`() {
        val map = TitleClones.parse(gba)
        val keys = listOf("pokémon - firered version")
        val titles = TitleClones.titlesByGame(map, keys)
        assertEquals(mapOf("pokemon - firered version" to "pokémon - firered version"), TitleClones.gameKeys(titles, emptyList(), VersionPrefs(PreferredRegion.SPAIN, emptySet())))
    }

    @Test
    fun `ties go to the first name, unreleased versions last`() {
        val rows = listOf(
            titled("Zelda no Densetsu - Kamigami no Triforce (Japan)"),
            titled("Legend of Zelda, The - A Link to the Past (Germany)"),
            titled("Legend of Zelda, The - A Link to the Past (Beta)")
        )
        // Neither Japan nor Germany is near Brazil: same points, first name wins.
        assertEquals("legend of zelda, the - a link to the past", TitleClones.shownTitle(rows, VersionPrefs(PreferredRegion.BRAZIL, emptySet())))
        val betaOnly = listOf(rows[2], titled("Zelda no Densetsu - Kamigami no Triforce (Japan)"))
        assertEquals("zelda no densetsu - kamigami no triforce", TitleClones.shownTitle(betaOnly, VersionPrefs(PreferredRegion.USA, setOf("EN"))))
        assertNull(TitleClones.shownTitle(emptyList(), VersionPrefs(PreferredRegion.USA, emptySet())))
    }

    private fun titled(displayName: String): TitleClones.TitledRow {
        val (name, tags) = FileParsingUtils.extractNameAndTags(displayName)
        return TitleClones.TitledRow(VersionGrouping.groupKey(name), name, URLEncoder.encode("$displayName.gba", "UTF-8").replace("+", "%20"), tags)
    }

    @Test
    fun `anything else parses to nothing`() {
        assertTrue(TitleClones.parse("").isEmpty())
        assertTrue(TitleClones.parse("<html>404</html>").isEmpty())
        assertTrue(TitleClones.parse("{\"variants\": 3}").isEmpty())
        assertTrue(TitleClones.parse("{\"variants\": [{\"titles\": []}, 4, {\"group\": \"X\"}]}").isEmpty())
    }

    @Test
    fun `consoles map to their clone list`() {
        assertEquals("Nintendo - Game Boy Advance (No-Intro)", TitleClones.listNameFor("nintendo_gameboy_advance"))
        assertEquals("Sony - PlayStation 2 (Redump)", TitleClones.listNameFor("sony_playstation_2"))
        assertEquals("Sega - Mega CD & Sega CD (Redump)", TitleClones.listNameFor("sega_cd"))
        assertEquals("Nintendo - Super Nintendo Entertainment System (No-Intro)", TitleClones.listNameFor("super_nintendo_entertainment_system"))
        assertNull(TitleClones.listNameFor("microsoft_xbox"))
        assertEquals(
            "https://raw.githubusercontent.com/unexpectedpanda/retool-clonelists-metadata/main/clonelists/Sega%20-%20Mega%20CD%20%26%20Sega%20CD%20%28Redump%29.json",
            TitleClones.url("Sega - Mega CD & Sega CD (Redump)")
        )
    }

    @Test
    fun `the registry keys by console and follows the setting`() {
        TitleClones.install("gba", TitleClones.parse(gba))
        assertEquals("pokemon - firered version", TitleClones.gameKey("gba", "Pokemon - Edicion Rojo Fuego"))
        // Another console, no console, or off: the title's own key.
        assertEquals("pokemon - edicion rojo fuego", TitleClones.gameKey("nds", "Pokemon - Edicion Rojo Fuego"))
        assertEquals("pokemon - edicion rojo fuego", TitleClones.gameKey(null, "Pokemon - Edicion Rojo Fuego"))
        TitleClones.enabled = false
        assertEquals("pokemon - edicion rojo fuego", TitleClones.gameKey("gba", "Pokemon - Edicion Rojo Fuego"))
    }

    // ---- Regrouping ---------------------------------------------------------------------------

    private val map get() = TitleClones.parse(gba)

    private fun rows(vararg titles: String) = titles.mapIndexed { i, t -> TitleClones.Row(i + 1L, key(t), key(t)) }

    @Test
    fun `merging moves the translated rows and drops their old games`() {
        val rows = rows("Pokemon - FireRed Version (USA)", "Pokemon - Edicion Rojo Fuego (Spain)", "Pocket Monsters - FireRed (Japan)", "Pokemon - Ruby Version (USA)")
        val plan = TitleClones.plan(rows) { TitleClones.canonicalIn(map, it) }
        assertEquals(mapOf("pokemon - firered version" to listOf(2L, 3L)), plan.updates)
        assertEquals(
            listOf(
                TitleClones.Move("pocket monsters - firered", setOf("pokemon - firered version"), dropFrom = true),
                TitleClones.Move("pokemon - edicion rojo fuego", setOf("pokemon - firered version"), dropFrom = true)
            ),
            plan.moves
        )
        // Applied, a second pass has nothing to do.
        val applied = rows.map { r -> plan.updates.entries.firstOrNull { r.id in it.value }?.let { r.copy(groupKey = it.key) } ?: r }
        assertTrue(TitleClones.plan(applied) { TitleClones.canonicalIn(map, it) }.isEmpty)
    }

    @Test
    fun `splitting copies the game's entries to every title and keeps the one still in use`() {
        val merged = listOf(
            TitleClones.Row(1, "pokemon - firered version", "pokemon - firered version"),
            TitleClones.Row(2, "pokemon - edicion rojo fuego", "pokemon - firered version"),
            TitleClones.Row(3, "pocket monsters - firered", "pokemon - firered version")
        )
        val plan = TitleClones.plan(merged) { it }
        assertEquals(setOf(2L, 3L), plan.updates.values.flatten().toSet())
        assertEquals(
            listOf(TitleClones.Move("pokemon - firered version", setOf("pokemon - edicion rojo fuego", "pocket monsters - firered"), dropFrom = false)),
            plan.moves
        )
        // Without the USA row the old key goes once its entries are copied.
        val withoutUsa = TitleClones.plan(merged.drop(1)) { it }
        assertTrue(withoutUsa.moves.single().dropFrom)
    }

    @Test
    fun `rows without a title key are left alone`() {
        val plan = TitleClones.plan(listOf(TitleClones.Row(1, "", "legacy"))) { "something else" }
        assertTrue(plan.isEmpty)
    }

    // ---- The library game ----------------------------------------------------------------------

    private fun file(displayName: String, gameKey: String): DownloadableFileWithTags {
        val (name, tags) = FileParsingUtils.extractNameAndTags(displayName)
        val href = URLEncoder.encode("$displayName.gba", "UTF-8").replace("+", "%20")
        return DownloadableFileWithTags(
            DownloadableFileEntity(name = name, fileName = href, consoleId = "gba", downloadUrl = "https://host.example/gba/$href", groupKey = gameKey),
            tags
        )
    }

    @Test
    fun `the preferred version picks the title, and the list name is the game key's`() {
        val canonical = "pokemon - firered version"
        val files = listOf(
            file("Pocket Monsters - FireRed (Japan)", canonical),
            file("Pokemon - Edicion Rojo Fuego (Spain)", canonical),
            file("Pokemon - FireRed Version (USA, Europe)", canonical),
            file("Pokemon - Feuerrote Edition (Germany)", canonical)
        )
        val spain = LibraryGame("gba", canonical, VersionGrouping.versionsOf(files, VersionPrefs(PreferredRegion.SPAIN, setOf("ES"))))
        assertEquals("Pokemon - Edicion Rojo Fuego", spain.primary.file.name)
        assertEquals(4, spain.versions.size)
        val japan = LibraryGame("gba", canonical, VersionGrouping.versionsOf(files, VersionPrefs(PreferredRegion.JAPAN, setOf("JA"))))
        assertEquals("Pocket Monsters - FireRed", japan.primary.file.name)
        val usa = LibraryGame("gba", canonical, VersionGrouping.versionsOf(files, VersionPrefs(PreferredRegion.USA, setOf("EN"))))
        assertEquals("Pokemon - FireRed Version", usa.primary.file.name)
        // Sorted by the title of the game's key: the clone list's group title here; keyed by the
        // shown title (TitleClones.gameKeys) it is that one, as the next game shows.
        assertEquals("Pokemon - FireRed Version", spain.listName)
        val keyedBySpain = LibraryGame("gba", "pokemon - edicion rojo fuego", spain.versions)
        assertEquals("Pokemon - Edicion Rojo Fuego", keyedBySpain.listName)
        val noUsa = LibraryGame("gba", canonical, VersionGrouping.versionsOf(files.filterNot { "USA" in it.tags }, VersionPrefs(PreferredRegion.SPAIN, setOf("ES"))))
        assertEquals("Pocket Monsters - FireRed", noUsa.listName)
    }

    @Test
    fun `discs of releases under different titles never count together`() {
        // One game (same canonical key), two releases with their own discs: the disc count goes by set stem.
        val names = listOf(
            "Ore no Shikabane wo Koete (Japan) (Disc 1).cue", "Ore no Shikabane wo Koete (Japan) (Disc 2).cue",
            "Over My Dead Body (USA) (Disc 1).cue", "Over My Dead Body (USA) (Disc 2).cue", "Over My Dead Body (USA) (Disc 3).cue"
        )
        val usaDisc = MultiDisc.discOf("Over My Dead Body (USA) (Disc 1).cue")!!
        val counted = names.mapNotNull { MultiDisc.discOf(it) }.filter { it.setStem.equals(usaDisc.setStem, ignoreCase = true) }.map { it.number }.distinct()
        assertEquals(listOf(1, 2, 3), counted)
        val jpDisc = MultiDisc.discOf("Ore no Shikabane wo Koete (Japan) (Disc 2).cue")!!
        assertEquals(2, names.mapNotNull { MultiDisc.discOf(it) }.count { it.setStem.equals(jpDisc.setStem, ignoreCase = true) })
    }
}
