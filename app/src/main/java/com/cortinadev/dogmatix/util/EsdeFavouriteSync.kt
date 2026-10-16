package com.cortinadev.dogmatix.util

/**
 * Favourites shared with ES-DE, one system at a time: Dogmatix's ★ (the `favourites` table, per
 * library row) and ES-DE's `<favorite>true</favorite>` (per `<game>` of
 * `gamelists/<system>/gamelist.xml`).
 *
 * The two meet at the files on disk. A gamelist entry names a file of the system folder
 * (`./Advance Wars (USA).gba`); it belongs to the library rows of that folder's consoles with
 * the same name without extension (`Advance Wars (USA).zip`, unpacked into a `.gba`) or, for a
 * multi-disc `<game>.m3u` folder, to the rows of its discs. The ★ is per game, as in the library:
 * an entry counts as a Dogmatix favourite when any version of its game is starred.
 *
 * [reconcile] is the two-way sync; [push] only carries ★ just changed in Dogmatix over to ES-DE.
 * Gamelists are edited entry by entry as text, never rewritten as a whole: scraped metadata,
 * other games and ES-DE's own layout stay as they were.
 *
 * Pure JVM so it can be unit-tested.
 */
object EsdeFavouriteSync {

    /** A library row of one of the system's consoles; [starred] is its ★ right now. */
    data class Row(val consoleId: String, val fileName: String, val groupKey: String, val starred: Boolean) {
        val favouriteKey: String get() = "$consoleId|$fileName"
    }

    data class Plan(
        /** The new gamelist, or null when ES-DE needs no change. */
        val gamelist: String?,
        /** Rows to star in Dogmatix: favourites of ES-DE. */
        val star: List<Row> = emptyList(),
        /** Rows to un-star in Dogmatix: un-favourited in ES-DE since the last sync. */
        val unstar: List<Row> = emptyList(),
        /** Gamelist entries that gained / lost `<favorite>`. */
        val marked: Int = 0,
        val unmarked: Int = 0,
        /** Entries ([key]) seen as favourites on both sides: the `synced` of the next [reconcile]. */
        val synced: Set<String> = emptySet()
    )

    /**
     * Below this many entries in the last sync, a side that has none of them left is believed;
     * from it on, that side is taken as reset (gamelist deleted, sources re-imported under other
     * ids…) and its removals are ignored instead of wiping the other side.
     */
    private const val RESET_MIN = 2

    /**
     * Two-way sync of one system.
     *
     * [gamelist] is the system's `gamelist.xml` (null when there is none), [onDisk] the names at
     * the top of its folder that ES-DE lists as games (see [listsAsGame]), [rows] the library rows
     * of the consoles stored in that folder, and [synced] the entries both sides had as
     * favourites at the end of the previous sync ([Plan.synced]), which is how a removal is told
     * apart from an addition on the other side:
     *  - favourite in ES-DE only: starred here, unless it was synced and has been un-starred
     *    here since (then it is un-marked in ES-DE);
     *  - starred here only: marked in ES-DE (an entry is created when the file is on disk),
     *    unless it was synced and ES-DE shows it un-favourited (then it is un-starred here).
     *
     * ES-DE drops the whole `<game>` of an unscraped game when its favourite is removed, so an
     * entry that is missing while its file is on disk also counts as un-favourited. Entries just
     * marked here only join [Plan.synced] once a later run reads them back from ES-DE: an ES-DE
     * that was running meanwhile may still save its older copy of them, and that must look like
     * "not marked yet", never like "un-favourited".
     */
    fun reconcile(gamelist: String?, onDisk: List<String>, rows: List<Row>, synced: Set<String>): Plan {
        val library = Library(rows)
        val esde = gamelist?.let(::favourites).orEmpty()
        val disk = onDisk.associateBy(::key)
        val candidates = LinkedHashSet<String>().apply {
            addAll(esde.keys)
            addAll(synced)
            disk.keys.filterTo(this) { library.state(it) == true }
        }
        val esdeReset = gamelist == null || (synced.size >= RESET_MIN && synced.none { esde[it] == true })
        val dogmatixReset = synced.size >= RESET_MIN && synced.none { library.state(it) == true }

        val changes = LinkedHashMap<String, Boolean>()
        val star = mutableListOf<Row>()
        val unstar = mutableListOf<Row>()
        val nextSynced = mutableSetOf<String>()
        for (k in candidates) {
            val inEsde = esde[k]
            val here = library.state(k)
            val was = k in synced
            when {
                here == true && inEsde == true -> nextSynced += k
                here == true -> when {
                    was && !esdeReset && (inEsde == false || k in disk) -> unstar += library.starredOfGames(k)
                    // Only top-level entries: Dogmatix never writes deeper, so a deeper one is another copy.
                    '/' !in k && (inEsde == false || k in disk) -> changes[disk[k] ?: k] = true
                }
                inEsde == true -> if (here == false && was && !dogmatixReset) {
                    changes[disk[k] ?: k] = false
                } else {
                    if (here == false) star += library.rowsFor(k)
                    nextSynced += k
                }
            }
        }
        val edit = edit(gamelist, changes)
        return Plan(edit.content, star.distinct(), unstar.distinct(), edit.marked, edit.unmarked, nextSynced)
    }

    /**
     * Carries ★ just changed in Dogmatix ([changed]: `consoleId|fileName` keys, as in the
     * `favourites` table) over to ES-DE: every entry of those games is set to the game's current
     * ★. Nothing else is touched, so a favourite changed in ES-DE meanwhile waits for [reconcile].
     */
    fun push(gamelist: String?, onDisk: List<String>, rows: List<Row>, changed: Set<String>): Plan {
        val library = Library(rows)
        val games = rows.filter { it.favouriteKey in changed }.map(library::gameOf).toSet()
        if (games.isEmpty()) return Plan(null)
        val esde = gamelist?.let(::favourites).orEmpty()
        val disk = onDisk.associateBy(::key)
        val changes = LinkedHashMap<String, Boolean>()
        for (k in esde.keys + disk.keys) {
            if (library.gamesFor(k).none { it in games }) continue
            val here = library.state(k) == true
            val inEsde = esde[k]
            if (here && inEsde != true && '/' !in k && (inEsde == false || k in disk)) changes[disk[k] ?: k] = true
            if (!here && inEsde == true) changes[disk[k] ?: k] = false
        }
        val edit = edit(gamelist, changes)
        return Plan(edit.content, marked = edit.marked, unmarked = edit.unmarked)
    }

    /** Every `<game>` of [xml] by [key], and whether it is a favourite (a path listed twice is one if either is). */
    fun favourites(xml: String): Map<String, Boolean> {
        val out = LinkedHashMap<String, Boolean>()
        for (match in gameBlock.findAll(xml)) {
            val k = pathOf(match.value)?.let(::key) ?: continue
            out[k] = out[k] == true || isFavourite(match.value)
        }
        return out
    }

    /**
     * [xml] with the `<favorite>` of the entries in [changes] (path relative to the system
     * folder → favourite) set or removed; a favourite without an entry gets a new `<game>`. Null
     * when nothing changes, or when [xml] is null and there is nothing to add.
     */
    fun withFavourites(xml: String?, changes: Map<String, Boolean>): String? = edit(xml, changes).content

    /**
     * Whether ES-DE lists [name], at the top of a system folder, as a game: its extension is one
     * of the system's [extensions] (compared as written, like ES-DE does; folders named like a
     * file count too, e.g. a multi-disc `Game.m3u`), and it is neither hidden nor the shortcut.
     */
    fun listsAsGame(name: String, extensions: Collection<String>): Boolean {
        if (name.startsWith(".")) return false
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return false
        val extension = name.substring(dot)
        return extension in extensions && !extension.equals(".${DgmtxFile.EXTENSION}", ignoreCase = true)
    }

    /** The extensions a `<system>` block declares: `<extension>.sfc .SFC .zip</extension>`. */
    fun systemExtensions(block: String): List<String> =
        EsdeXml.tagText(block, "extension")?.split(Regex("\\s+"))?.filter { it.startsWith(".") }.orEmpty()

    /** Comparable form of a gamelist path or a file name: relative, `/`-separated, lower case. */
    fun key(path: String): String = path.trim().replace('\\', '/').removePrefix("./").lowercase()

    // ---- Matching library rows ---------------------------------------------------------------

    private class Library(rows: List<Row>) {
        private val byStem = rows.groupBy { stem(FileParsingUtils.decodeUrlEncodedFileName(it.fileName)) }
        private val bySet = rows.mapNotNull { row ->
            MultiDisc.discOf(FileParsingUtils.decodeUrlEncodedFileName(row.fileName))?.let { it.setStem.lowercase() to row }
        }.groupBy({ it.first }, { it.second })
        private val byGame = rows.groupBy(::gameOf)

        fun gameOf(row: Row): String = "${row.consoleId}|${row.groupKey.ifEmpty { "file:" + row.fileName }}"

        /** The versions an entry is a copy of: same name without extension (or, for `<game>.m3u`, its discs). */
        fun rowsFor(k: String): List<Row> {
            val stem = stem(k)
            val exact = byStem[stem].orEmpty()
            return if (k.endsWith(".m3u")) (exact + bySet[stem].orEmpty()).distinct() else exact
        }

        fun gamesFor(k: String): Set<String> = rowsFor(k).mapTo(HashSet(), ::gameOf)

        /** True when the entry's game is starred, false when it is not, null when no row matches. */
        fun state(k: String): Boolean? {
            val games = gamesFor(k)
            if (games.isEmpty()) return null
            return games.any { game -> byGame[game].orEmpty().any { it.starred } }
        }

        fun starredOfGames(k: String): List<Row> = gamesFor(k).flatMap { byGame[it].orEmpty() }.filter { it.starred }

        private fun stem(name: String): String =
            LibraryKeys.baseName(name.replace('\\', '/').trimEnd('/').substringAfterLast('/').lowercase())
    }

    // ---- Gamelist text surgery ----------------------------------------------------------------

    private class Edit(val content: String?, val marked: Int, val unmarked: Int)

    private val gameBlock = Regex("""<game(?:\s[^>]*)?>.*?</game>""", RegexOption.DOT_MATCHES_ALL)
    private val pathTag = Regex("""<path>(.*?)</path>""", RegexOption.DOT_MATCHES_ALL)
    private val favouriteTag = Regex("""<favorite>(.*?)</favorite>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val emptyGameList = Regex("""<gameList\s*/>""")
    private const val NEW_GAMELIST = "<?xml version=\"1.0\"?>\n<gameList>\n</gameList>\n"

    private fun edit(xml: String?, changes: Map<String, Boolean>): Edit {
        if (changes.isEmpty()) return Edit(null, 0, 0)
        val wanted = changes.entries.associate { (path, favourite) -> key(path) to (path to favourite) }
        val seen = HashSet<String>()
        var marked = 0
        var unmarked = 0
        var content = (xml?.takeIf { it.isNotBlank() } ?: NEW_GAMELIST).replace(emptyGameList, "<gameList>\n</gameList>")
        content = gameBlock.replace(content) { match ->
            val block = match.value
            val k = pathOf(block)?.let(::key)
            val change = k?.let(wanted::get) ?: return@replace block
            seen += k
            val patched = withFavourite(block, change.second)
            when {
                patched == block -> Unit
                change.second -> marked++
                else -> unmarked++
            }
            patched
        }
        val added = wanted.filter { (k, change) -> change.second && k !in seen }.values
        val end = content.lastIndexOf("</gameList>")
        if (added.isNotEmpty() && end >= 0) {
            content = content.substring(0, end) + added.joinToString("") { (path, _) -> newEntry(path) } + content.substring(end)
            marked += added.size
        }
        return Edit(if (marked + unmarked > 0) content else null, marked, unmarked)
    }

    private fun newEntry(path: String): String =
        "\t<game>\n\t\t<path>./${escape(path.removePrefix("./"))}</path>\n\t\t<favorite>true</favorite>\n\t</game>\n"

    /** [block] with its `<favorite>` set to true, or removed (ES-DE leaves defaults out). */
    private fun withFavourite(block: String, favourite: Boolean): String {
        val existing = favouriteTag.find(block)
        if (favourite) {
            if (existing != null) {
                return if (existing.groupValues[1].trim().equals("true", ignoreCase = true)) block
                else block.replaceRange(existing.range, "<favorite>true</favorite>")
            }
            val close = block.lastIndexOf("</game>")
            val lineStart = block.lastIndexOf('\n', close - 1) + 1
            // On its own line, indented like the entry's other children, when the entry is laid out that way.
            return if (lineStart > 0 && block.substring(lineStart, close).isBlank()) {
                val indent = Regex("""\n([ \t]*)<""").find(block)?.groupValues?.get(1) ?: "\t\t"
                block.substring(0, lineStart) + indent + "<favorite>true</favorite>\n" + block.substring(lineStart)
            } else {
                block.substring(0, close) + "<favorite>true</favorite>" + block.substring(close)
            }
        }
        existing ?: return block
        var start = existing.range.first
        var end = existing.range.last + 1
        val lineStart = block.lastIndexOf('\n', start - 1) + 1
        if (lineStart > 0 && block.substring(lineStart, start).isBlank()) {
            val newline = when {
                block.startsWith("\r\n", end) -> 2
                block.startsWith("\n", end) -> 1
                else -> 0
            }
            if (newline > 0) {
                start = lineStart
                end += newline
            }
        }
        return block.removeRange(start, end)
    }

    private fun isFavourite(block: String): Boolean =
        favouriteTag.find(block)?.groupValues?.get(1)?.trim()?.equals("true", ignoreCase = true) == true

    private fun pathOf(block: String): String? =
        pathTag.find(block)?.groupValues?.get(1)?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun unescape(text: String): String =
        Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(text) { m ->
            when (val entity = m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> runCatching {
                    val code = if (entity.startsWith("#x")) entity.substring(2).toInt(16) else entity.substring(1).toInt()
                    String(Character.toChars(code))
                }.getOrDefault(m.value)
            }
        }
}
