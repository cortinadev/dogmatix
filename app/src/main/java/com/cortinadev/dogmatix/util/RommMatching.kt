package com.cortinadev.dogmatix.util

import java.util.Locale

/** A server ROM in the catalogue index, under one of the consoles its platform is mapped to. */
data class RommEntry(
    val romId: Int,
    val consoleId: String,
    val fsName: String,
    val name: String,
    /** [VersionGrouping.groupKey] of the ROM's name: the library's games of the same title share it. */
    val groupKey: String,
    /** [RommMatching.fileKey] of the ROM itself and of each of its files. */
    val fileKeys: Set<String>,
    val hashes: Set<String>,
    val coverSmall: String,
    val coverLarge: String,
    val playState: RommPlayState = RommPlayState.NONE
)

/** A game on this device, for "what is missing on the server". */
data class RommOwnedGame(val consoleId: String, val groupKey: String, val name: String, val fileNames: List<String>)

/**
 * How library games and server ROMs are paired: by console, then by hash (when both sides have
 * one), then by file name without extension (`Game (USA).zip` on one side is `Game (USA).sfc`
 * on the other), then by release ([releaseKey]: a multi-file game can be on the server as one
 * `Game (USA).zip` while the library lists `Game (USA) (Disc 1).cue`…), and for a whole game by
 * [VersionGrouping.groupKey] as well, so a game counts as on the server when any version is.
 */
object RommMatching {

    private val trackTag = Regex("""\(\s*track\s*\d{1,3}\s*\)""", RegexOption.IGNORE_CASE)
    private val spaces = Regex("\\s+")

    /** Lower-cased leaf name without extension; URL escapes decoded. */
    fun fileKey(fileName: String): String {
        val leaf = FileParsingUtils.decodeUrlEncodedFileName(fileName).replace('\\', '/').trimEnd('/').substringAfterLast('/')
        return MultiDisc.stripExtension(leaf.trim()).lowercase(Locale.ROOT)
    }

    /** [fileKey] without disc and track tags: shared by every file of one release. */
    fun releaseKey(fileName: String): String = releaseOfKey(fileKey(fileName))

    /** [releaseKey] of what already is a [fileKey] (which must not lose a second "extension"). */
    fun releaseOfKey(fileKey: String): String =
        MultiDisc.withoutDiscTag(fileKey.replace(trackTag, " ")).replace(spaces, " ").trim()

    /** The library's group key for a server file or folder name. */
    fun groupKeyOf(fsName: String): String =
        VersionGrouping.groupKey(FileParsingUtils.extractNameAndTags(fsName.trim()).first)

    fun entryOf(consoleId: String, rom: RommCatalogRom): RommEntry = RommEntry(
        romId = rom.romId,
        consoleId = consoleId,
        fsName = rom.fsName,
        name = rom.name,
        groupKey = groupKeyOf(rom.fsName),
        fileKeys = (listOf(rom.fsName) + rom.fileNames).map(::fileKey).filter { it.isNotEmpty() }.toSet(),
        hashes = rom.hashes.toSet(),
        coverSmall = rom.coverSmall,
        coverLarge = rom.coverLarge,
        playState = RommPlayState.of(rom.props)
    )

    /** The RomM id in a `…/api/roms/{id}/content/…` download URL of the server at [baseUrl]. */
    fun romIdFromUrl(baseUrl: String, downloadUrl: String): Int? {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty() || !downloadUrl.startsWith("$base/api/roms/", ignoreCase = true)) return null
        return downloadUrl.substring(base.length + "/api/roms/".length).substringBefore('/').toIntOrNull()
    }

    /** The games of [owned] that [index] does not have (under any version). */
    fun missingOnServer(owned: List<RommOwnedGame>, index: RommIndex): List<RommOwnedGame> =
        owned.filter { index.forGame(it.consoleId, it.groupKey, it.fileNames) == null }
}

/** The server catalogue, looked up by console + file, group key or hash. Immutable. */
class RommIndex(entries: Collection<RommEntry>) {

    private val all: List<RommEntry> = entries.toList()
    private val byFile = HashMap<String, RommEntry>()
    private val byRelease = HashMap<String, RommEntry>()
    private val byGroup = HashMap<String, MutableList<RommEntry>>()
    private val byHash = HashMap<String, RommEntry>()
    private val byRomId = HashMap<Int, MutableList<RommEntry>>()

    init {
        for (e in all) {
            e.fileKeys.forEach {
                byFile.putIfAbsent(key(e.consoleId, it), e)
                byRelease.putIfAbsent(key(e.consoleId, RommMatching.releaseOfKey(it)), e)
            }
            byGroup.getOrPut(key(e.consoleId, e.groupKey)) { ArrayList(1) } += e
            e.hashes.forEach { byHash.putIfAbsent(key(e.consoleId, it), e) }
            byRomId.getOrPut(e.romId) { ArrayList(1) } += e
        }
    }

    val size: Int get() = all.size
    val isEmpty: Boolean get() = all.isEmpty()
    val entries: List<RommEntry> get() = all

    /** The ROM that is this file: same hash, else same name without extension, else same release. */
    fun forFile(consoleId: String, fileName: String, hashes: Collection<String> = emptyList()): RommEntry? {
        hashes.firstNotNullOfOrNull { byHash[key(consoleId, it.lowercase(Locale.ROOT))] }?.let { return it }
        return byFile[key(consoleId, RommMatching.fileKey(fileName))]
            ?: byRelease[key(consoleId, RommMatching.releaseKey(fileName))]
    }

    /** A ROM of the game [groupKey]: one of its [fileNames] first, else any version of it. */
    fun forGame(consoleId: String, groupKey: String, fileNames: Collection<String>): RommEntry? =
        fileNames.firstNotNullOfOrNull { forFile(consoleId, it) }
            ?: byGroup[key(consoleId, groupKey)]?.firstOrNull()

    /** Every console entry of ROM [romId] (one per console mapped to its platform). */
    fun forRomId(romId: Int): List<RommEntry> = byRomId[romId].orEmpty()

    private fun key(consoleId: String, value: String) = "$consoleId|$value"

    companion object {
        val EMPTY = RommIndex(emptyList())
    }
}
