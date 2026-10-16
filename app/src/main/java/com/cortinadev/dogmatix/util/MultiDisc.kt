package com.cortinadev.dogmatix.util

/** One disc of a multi-disc game read from its file name: `Final Fantasy VII (USA) (Disc 2 of 3).cue`. */
data class DiscRef(
    /** The name without the disc tag nor extension: `Final Fantasy VII (USA)`. */
    val setStem: String,
    val number: Int,
    /** From "Disc 2 of 3"; null when the name does not say. */
    val total: Int?
)

/**
 * What to do with a complete multi-disc game: ES-DE's "directory interpreted as file" layout,
 * `<console>/<set>.m3u/` holding the discs and a `<set>.m3u` playlist of the same name, so the
 * frontend lists one game and the emulator swaps discs from the playlist.
 */
data class DiscPlaylist(
    /** `Final Fantasy VII (USA).m3u`: the folder and the playlist inside it share the name. */
    val name: String,
    /** Disc images in disc order, one line each in the playlist. */
    val entries: List<String>,
    /** Files next to the folder that belong to the set (images, tracks, sheets) and move into it. */
    val moves: List<String>,
    /** A loose file already called [name] (an older playlist) that is in the folder's way. */
    val conflictingFile: String?
) {
    val content: String get() = entries.joinToString("\n", postfix = "\n")
}

object MultiDisc {

    private val discTag = Regex(
        """\s*[(\[]\s*(?:disc|disk|cd)\s*(\d{1,2}|[ivx]{1,4})(?:\s*(?:of|/)\s*(\d{1,2}))?\s*[)\]]""",
        RegexOption.IGNORE_CASE
    )
    /** Redump names each track of a disc after it: `Game (USA) (Disc 1) (Track 02).bin`. */
    private val trackTag = Regex("""\s*\(Track\s*\d{1,3}\)""", RegexOption.IGNORE_CASE)
    private val romanNumerals = listOf("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x")
    private val extension = Regex("""\.([A-Za-z0-9]{1,5})$""")

    /** Files that stand for a whole disc, best first; tracks (`.bin`, `.raw`…) hang off a sheet and are never listed. */
    private val discImages = listOf("cue", "gdi", "ccd", "mds", "chd", "iso", "pbp", "cso", "rvz", "gcz", "wbfs", "cdi", "nrg", "img", "d64", "adf", "dsk", "fdi", "hdm", "d88")

    /**
     * Systems whose usual emulators read `.m3u` playlists (RetroArch cores, DuckStation, Flycast,
     * Dolphin…), as normalized folder names / console keys. PS2 and Xbox are left out on purpose:
     * NetherSX2 and xemu cannot open a playlist, so the game would stop launching.
     */
    private val playlistSystems = setOf(
        "psx", "ps1", "ps", "playstation",
        "saturn", "segasaturn",
        "segacd", "megacd", "cd",
        "dreamcast", "dc", "segadreamcast",
        "pcenginecd", "pcecd", "tgcd", "turbografxcd", "turbografx16cd",
        "pcfx", "3do", "neogeocd", "ngcd",
        "gc", "ngc", "gamecube",
        "amiga", "amigacd32", "cd32", "cdtv",
        "c64", "msx", "msx2", "pc98", "x68000", "fmtowns"
    )

    fun discOf(fileName: String): DiscRef? {
        val base = stripExtension(fileName.replace('\\', '/').substringAfterLast('/'))
        val match = discTag.find(base) ?: return null
        val number = discNumber(match.groupValues[1]) ?: return null
        val stem = base.removeRange(match.range).replace(trackTag, "").replace(Regex("\\s{2,}"), " ").trim()
        if (stem.isEmpty()) return null
        return DiscRef(stem, number, match.groupValues[2].toIntOrNull())
    }

    /** [name] without its "(Disc n)" tag: what the discs of one release share. */
    fun withoutDiscTag(name: String): String = name.replace(discTag, "").replace(Regex("\\s{2,}"), " ").trim()

    fun isDiscImage(fileName: String): Boolean = extensionOf(fileName) in discImages

    /** A library tag that only numbers the disc ("Disc 2", "Disk 1 of 3"). */
    fun isDiscTag(tag: String): Boolean = discTag.matches("(${tag.trim()})")

    /** True when any of [normalizedNames] (console key, folder name, aliases) is a system that plays `.m3u`. */
    fun supportsPlaylists(normalizedNames: Collection<String>): Boolean = normalizedNames.any { it in playlistSystems }

    /**
     * Plans the set folder for [setStem]. [loose] are the file names next to it (the console
     * folder), [inSet] the ones already inside `<set>.m3u/`. [expectedDiscs] is how many discs the
     * catalogue lists for this release. [sheetFiles] returns what a `.cue` / `.gdi` refers to.
     * Null until every disc from 1 to the expected count is on disk (and there are at least two).
     */
    fun plan(
        setStem: String,
        loose: List<String>,
        inSet: List<String>,
        expectedDiscs: Int,
        sheetFiles: (String) -> List<String> = { emptyList() }
    ): DiscPlaylist? {
        val name = "$setStem.m3u"
        val ofSet = (loose + inSet).mapNotNull { file -> discOf(file)?.takeIf { it.setStem.equals(setStem, ignoreCase = true) }?.let { file to it } }
        val best = sortedMapOf<Int, String>()
        for ((file, disc) in ofSet) {
            if (!isDiscImage(file)) continue
            val current = best[disc.number]
            if (current == null || rank(file) < rank(current)) best[disc.number] = file
        }
        if (best.isEmpty()) return null
        val stated = ofSet.mapNotNull { it.second.total }.maxOrNull() ?: 0
        val required = maxOf(expectedDiscs, stated, best.lastKey())
        if (required < 2 || (1..required).any { it !in best }) return null

        val entries = best.values.toList()
        val looseSet = loose.toSet()
        val moves = LinkedHashSet<String>()
        for (entry in entries) {
            val stem = stripExtension(entry)
            loose.filterTo(moves) { it == entry || it.startsWith("$stem.") || it.startsWith("$stem (") }
            sheetFiles(entry).map { it.replace('\\', '/').substringAfterLast('/') }.filterTo(moves) { it in looseSet }
        }
        return DiscPlaylist(name, entries, moves.toList(), conflictingFile = loose.firstOrNull { it.equals(name, ignoreCase = true) })
    }

    /** Files a cue sheet names: `FILE "Game (Track 1).bin" BINARY`. */
    fun cueFiles(text: String): List<String> =
        Regex("""(?im)^\s*FILE\s+(?:"([^"]+)"|(\S+))""").findAll(text)
            .map { it.groupValues[1].ifEmpty { it.groupValues[2] }.trim() }
            .filter { it.isNotEmpty() }.distinct().toList()

    /** Files a Dreamcast gdi names: a track count, then `1 0 4 2352 track01.bin 0` per track. */
    fun gdiFiles(text: String): List<String> =
        text.lineSequence().drop(1).map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
            Regex(""""([^"]+)"""").find(line)?.groupValues?.get(1) ?: line.split(Regex("\\s+")).getOrNull(4)
        }.distinct().toList()

    fun extensionOf(fileName: String): String? = extension.find(fileName)?.groupValues?.get(1)?.lowercase()

    /** Drops a short extension only, so "Super Mario Bros. 3" keeps its dot. */
    fun stripExtension(fileName: String): String = extension.find(fileName)?.let { fileName.substring(0, it.range.first) } ?: fileName

    private fun rank(fileName: String): Int = discImages.indexOf(extensionOf(fileName)).let { if (it < 0) Int.MAX_VALUE else it }

    private fun discNumber(value: String): Int? =
        value.toIntOrNull() ?: romanNumerals.indexOf(value.lowercase()).takeIf { it >= 0 }?.plus(1)
}
