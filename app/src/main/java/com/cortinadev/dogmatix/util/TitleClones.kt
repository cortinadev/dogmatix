package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.VersionPrefs
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/**
 * One game under several titles: "Pokemon - Edicion Rojo Fuego", "Pokemon - FireRed Version" and
 * "Pocket Monsters - FireRed" are the same game in three regions, and their titles look nothing
 * alike. Retool's clone lists (github.com/unexpectedpanda/retool-clonelists-metadata, BSD-3-Clause,
 * one JSON per No-Intro / Redump system) say which titles go together: every `variants[]` entry
 * has a `group` and the `titles[].searchTerm` that belong to it (`compilations` and `supersets`
 * are other games and are left alone).
 *
 * Here a list becomes a map from a title's [VersionGrouping.groupKey] (its *title key*) to the
 * game's *canonical key*: one of the group's own title keys (the group name's when it is one of
 * them), the same whatever the region. Titles listed under two groups, region-conditional entries
 * (`filters`) and groups left with a single title are skipped: nothing is merged on a doubt.
 *
 * The rows' `groupKey` is not the canonical key itself but the title key the game *shows*
 * ([gameKeys]): the title of its preferred version for the user's region and languages, among the
 * console's rows. So the A→Z list, its paging and its letter jumps (all on the name of the rows
 * titled like the game, see the DAO's `queryGameGroups`) follow the title on screen: with region
 * Spain "Edicion Esmeralda" sorts under E, not next to "Emerald". It is still one of the group's
 * title keys, so it never collides with another game's; a change of region or languages regroups.
 *
 * The lists are downloaded per console by `TitleClonesService`; [install] feeds this process-wide
 * registry (like [ConsoleAliasRegistry]) so [canonical] works wherever a title is keyed.
 * Pure JVM for the tests.
 */
object TitleClones {

    private const val BASE = "https://raw.githubusercontent.com/unexpectedpanda/retool-clonelists-metadata/main/clonelists/"

    /** libretro-thumbnails repository ([LibretroThumbnails.systemFor]) → Retool clone list (checked Nov 2026). */
    private val listNames: Map<String, String> = mapOf(
        "Nintendo_-_Nintendo_Entertainment_System" to "Nintendo - Nintendo Entertainment System (No-Intro)",
        "Nintendo_-_Family_Computer_Disk_System" to "Nintendo - Family Computer Disk System (No-Intro)",
        "Nintendo_-_Super_Nintendo_Entertainment_System" to "Nintendo - Super Nintendo Entertainment System (No-Intro)",
        "Nintendo_-_Nintendo_64" to "Nintendo - Nintendo 64 (No-Intro)",
        "Nintendo_-_GameCube" to "Nintendo - GameCube (Redump)",
        "Nintendo_-_Wii" to "Nintendo - Wii (Redump)",
        "Nintendo_-_Game_Boy" to "Nintendo - Game Boy (No-Intro)",
        "Nintendo_-_Game_Boy_Color" to "Nintendo - Game Boy Color (No-Intro)",
        "Nintendo_-_Game_Boy_Advance" to "Nintendo - Game Boy Advance (No-Intro)",
        "Nintendo_-_Nintendo_DS" to "Nintendo - Nintendo DS (No-Intro)",
        "Nintendo_-_Nintendo_3DS" to "Nintendo - Nintendo 3DS (No-Intro)",
        "Nintendo_-_Virtual_Boy" to "Nintendo - Virtual Boy (No-Intro)",
        "Nintendo_-_Pokemon_Mini" to "Nintendo - Pokemon Mini (No-Intro)",
        "Sega_-_Master_System_-_Mark_III" to "Sega - Master System - Mark III (No-Intro)",
        "Sega_-_Mega_Drive_-_Genesis" to "Sega - Mega Drive - Genesis (No-Intro)",
        "Sega_-_Mega-CD_-_Sega_CD" to "Sega - Mega CD & Sega CD (Redump)",
        "Sega_-_32X" to "Sega - 32X (No-Intro)",
        "Sega_-_Saturn" to "Sega - Saturn (Redump)",
        "Sega_-_Dreamcast" to "Sega - Dreamcast (Redump)",
        "Sega_-_Game_Gear" to "Sega - Game Gear (No-Intro)",
        "Sega_-_SG-1000" to "Sega - SG-1000 (No-Intro)",
        "Sony_-_PlayStation" to "Sony - PlayStation (Redump)",
        "Sony_-_PlayStation_2" to "Sony - PlayStation 2 (Redump)",
        "Sony_-_PlayStation_Portable" to "Sony - PlayStation Portable (Redump)",
        "NEC_-_PC_Engine_-_TurboGrafx_16" to "NEC - PC Engine - TurboGrafx-16 (No-Intro)",
        "NEC_-_PC_Engine_CD_-_TurboGrafx-CD" to "NEC - PC Engine CD & TurboGrafx CD (Redump)",
        "SNK_-_Neo_Geo_Pocket_Color" to "SNK - NeoGeo Pocket Color (No-Intro)",
        "Bandai_-_WonderSwan" to "Bandai - WonderSwan (No-Intro)",
        "Bandai_-_WonderSwan_Color" to "Bandai - WonderSwan Color (No-Intro)",
        "Atari_-_2600" to "Atari - Atari 2600 (No-Intro)",
        "Atari_-_7800" to "Atari - Atari 7800 (No-Intro)",
        "Atari_-_Lynx" to "Atari - Atari Lynx (No-Intro)",
        "Atari_-_Jaguar" to "Atari - Atari Jaguar (No-Intro)",
        "Coleco_-_ColecoVision" to "Coleco - ColecoVision (No-Intro)",
        "Mattel_-_Intellivision" to "Mattel - Intellivision (No-Intro)",
        "Microsoft_-_MSX" to "Microsoft - MSX (No-Intro)",
        "Microsoft_-_MSX2" to "Microsoft - MSX2 (No-Intro)",
        "The_3DO_Company_-_3DO" to "Panasonic - 3DO Interactive Multiplayer (Redump)"
    )

    /** The clone list for a library console; null for systems without one. */
    fun listNameFor(consoleId: String): String? = LibretroThumbnails.systemFor(consoleId)?.let { listNames[it.repo] }

    fun url(listName: String): String =
        BASE + URLEncoder.encode("$listName.json", "UTF-8").replace("+", "%20")

    /** How a title key is compared with the list: accents and letter case do not count, runs of spaces are one. */
    fun matchKey(titleKey: String): String =
        Normalizer.normalize(titleKey, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .lowercase(Locale.ROOT)
            .replace(spaces, " ")
            .trim()

    private val combiningMarks = Regex("\\p{M}+")
    private val spaces = Regex("\\s+")

    /** What a filter may change and still leave the title in its group: names and categories, not the group. */
    private val harmlessFilterResults = setOf("localNames", "categories")

    /**
     * A clone list (Retool's JSON) as [matchKey] of each title → canonical key. Empty for
     * anything that is not one; never throws. Per title, only the safe cases count:
     *  - a `searchTerm` with `(…)` or `[…]` is left out: Retool tells those releases apart by the
     *    bracket ("Havoc (Demo)", "Probotector (60 Hz)"), which the library's title key drops, so
     *    the entry would claim every release of a generic title;
     *  - `filters` only when all their results are [harmlessFilterResults] (a filter that moves
     *    releases to another group or superset leaves the title out);
     *  - `priority` 2 or more (Retool's "use only if nothing better": reskins, hacks, bootlegs
     *    under a different name next to real renames) only when its words contain, or are
     *    contained in, those of the group name or of a priority-1 title ([relatedByWords]).
     */
    fun parse(json: String): Map<String, String> {
        val variants = runCatching { JsonParser.parseString(json).asJsonObject.getAsJsonArray("variants") }.getOrNull()
            ?: return emptyMap()
        val groups = ArrayList<Pair<String, List<String>>>()
        for (element in variants) {
            val variant = element as? JsonObject ?: continue
            val group = variant.text("group") ?: continue
            val titles = runCatching { variant.getAsJsonArray("titles") }.getOrNull() ?: continue
            val groupKey = matchKey(VersionGrouping.groupKey(group))
            val main = ArrayList<String>()
            val fallback = ArrayList<String>()
            for (title in titles) {
                val obj = title as? JsonObject ?: continue
                val term = obj.text("searchTerm") ?: continue
                if ('(' in term || '[' in term) continue
                if (!filtersAreHarmless(obj)) continue
                val key = matchKey(VersionGrouping.groupKey(term)).takeIf { it.isNotEmpty() } ?: continue
                if (priorityOf(obj) >= 2) fallback += key else main += key
            }
            val anchors = (main + groupKey).distinct()
            val keys = (main + fallback.filter { key -> anchors.any { relatedByWords(key, it) } }).distinct()
            groups += groupKey to keys
        }
        // A title in two groups is ambiguous: it stays a game of its own.
        val seen = HashMap<String, Int>()
        groups.forEach { (_, keys) -> keys.forEach { seen.merge(it, 1, Int::plus) } }
        val out = HashMap<String, String>()
        for ((group, all) in groups) {
            val keys = all.filter { seen[it] == 1 }
            if (keys.size < 2) continue
            val canonical = if (group in keys) group else keys.min()
            keys.forEach { out[it] = canonical }
        }
        return out
    }

    private fun filtersAreHarmless(title: JsonObject): Boolean {
        val filters = title.get("filters") ?: return true
        if (!filters.isJsonArray) return false
        return filters.asJsonArray.all { filter ->
            val results = (filter as? JsonObject)?.get("results") as? JsonObject ?: return@all false
            results.keySet().all { it in harmlessFilterResults }
        }
    }

    private fun priorityOf(title: JsonObject): Int =
        runCatching { title.get("priority")?.asInt }.getOrNull() ?: 1

    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    private fun words(key: String): List<String> = key.split(nonWord).filter { it.isNotEmpty() }

    /**
     * Whether one key's words appear, in a row and whole, in the other's: "simple 1500 series vol.
     * 2 - the tetris" holds "tetris", "street fighter v" does not hold "super street fighter ii".
     */
    fun relatedByWords(a: String, b: String): Boolean {
        val wa = words(a)
        val wb = words(b)
        return contains(wa, wb) || contains(wb, wa)
    }

    private fun contains(outer: List<String>, inner: List<String>): Boolean {
        if (inner.isEmpty() || inner.size > outer.size) return false
        return (0..outer.size - inner.size).any { start -> inner.indices.all { outer[start + it] == inner[it] } }
    }

    private fun JsonObject.text(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    /** The canonical key of [titleKey] in [map] ([parse]'s), or [titleKey] itself when the list does not group it. */
    fun canonicalIn(map: Map<String, String>, titleKey: String): String =
        if (map.isEmpty()) titleKey else map[matchKey(titleKey)] ?: titleKey

    /** One library row as the regrouping sees it. */
    data class Row(val id: Long, val titleKey: String, val groupKey: String)

    /**
     * What keyed by game follows a regrouping: the stored entries of game [from] (genre and year,
     * collection membership, RomM pairing) are copied to each game in [to], and dropped from
     * [from] when [dropFrom] (no row is left under it).
     */
    data class Move(val from: String, val to: Set<String>, val dropFrom: Boolean)

    /** Rows to re-key ([updates]: new key → row ids) and the [moves] that go with it. */
    data class Plan(val updates: Map<String, List<Long>>, val moves: List<Move>) {
        val isEmpty: Boolean get() = updates.isEmpty()
    }

    /**
     * Regrouping one console's [rows] so each lands on [keyOf] of its title key: both merging
     * (a list arrives or grows) and splitting (the setting is turned off, a title leaves a group).
     * Rows already under their key are left alone, so a second pass changes nothing.
     */
    fun plan(rows: List<Row>, keyOf: (String) -> String): Plan {
        val updates = HashMap<String, MutableList<Long>>()
        val targets = HashMap<String, MutableSet<String>>()
        val remaining = HashSet<String>()
        for (row in rows) {
            if (row.titleKey.isEmpty()) { remaining += row.groupKey; continue }
            val wanted = keyOf(row.titleKey)
            remaining += wanted
            if (wanted == row.groupKey) continue
            updates.getOrPut(wanted) { ArrayList() } += row.id
            targets.getOrPut(row.groupKey) { LinkedHashSet() } += wanted
        }
        val moves = targets.map { (from, to) -> Move(from, to, dropFrom = from !in remaining) }
        return Plan(updates, moves.sortedBy { it.from })
    }

    /** Canonical key → the console's title keys ([titleKeys]) that [map] puts in that game. Titles it does not list are left out. */
    fun titlesByGame(map: Map<String, String>, titleKeys: Collection<String>): Map<String, Set<String>> {
        if (map.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableSet<String>>()
        for (key in titleKeys) {
            if (key.isEmpty()) continue
            val canonical = map[matchKey(key)] ?: continue
            out.getOrPut(canonical) { java.util.TreeSet() } += key
        }
        return out
    }

    /** One row of a game with several titles, as [shownTitle] ranks it. */
    data class TitledRow(val titleKey: String, val name: String, val fileName: String, val tags: List<String>)

    /**
     * The title key a game shows: its preferred version's, ranked as the library ranks versions
     * ([VersionRanking.rank] on the tags and file name) with ties to the first name, as the list
     * reads its rows. Null without rows.
     */
    fun shownTitle(rows: List<TitledRow>, prefs: VersionPrefs): String? =
        VersionRanking.rank(
            rows.sortedBy { it.name }, prefs,
            tagsOf = { it.tags },
            fileNameOf = { FileParsingUtils.decodeUrlEncodedFileName(it.fileName) }
        ).firstOrNull()?.titleKey

    /**
     * Canonical key → the key the game's rows take: the title it shows. A game with one title in
     * the console keeps that title's key (accents included: "pokémon - …"); with several,
     * [shownTitle] of their [rows] (those of [titles] with more than one), else the smallest.
     */
    fun gameKeys(titles: Map<String, Set<String>>, rows: List<TitledRow>, prefs: VersionPrefs): Map<String, String> {
        val byTitle = rows.groupBy { it.titleKey }
        return titles.mapValues { (_, keys) ->
            if (keys.size == 1) keys.first()
            else shownTitle(keys.flatMap { byTitle[it].orEmpty() }, prefs) ?: keys.min()
        }
    }

    // Process-wide registry

    @Volatile
    private var lists: Map<String, Map<String, String>> = emptyMap()

    /** Per console, canonical key → the key its rows take ([gameKeys]). */
    @Volatile
    private var keyed: Map<String, Map<String, String>> = emptyMap()

    /** Off (Settings → Library): every title is a game of its own again. */
    @Volatile
    var enabled: Boolean = true

    /** Makes [map] the clone list of [consoleId] (an empty map: the console has none), with its games' keys ([gameKeys]). */
    @Synchronized
    fun install(consoleId: String, map: Map<String, String>, gameKeys: Map<String, String> = emptyMap()) {
        lists = lists + (consoleId to map)
        keyed = keyed + (consoleId to gameKeys)
    }

    /** Whether [consoleId]'s list is known (downloaded or cached), even if empty. */
    fun installed(consoleId: String): Boolean = consoleId in lists

    @Synchronized
    fun clear() {
        lists = emptyMap()
        keyed = emptyMap()
    }

    /** The key the library groups [titleKey] of [consoleId] under. */
    fun canonical(consoleId: String?, titleKey: String): String {
        if (!enabled || consoleId == null) return titleKey
        val canonical = canonicalIn(lists[consoleId] ?: return titleKey, titleKey)
        return keyed[consoleId]?.get(canonical) ?: canonical
    }

    /** [canonical] of a display name (the indexed name, without tags). */
    fun gameKey(consoleId: String?, name: String): String = canonical(consoleId, VersionGrouping.groupKey(name))
}
