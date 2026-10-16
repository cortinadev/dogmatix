package com.cortinadev.dogmatix.util

import java.util.Locale

/**
 * One file under the ROM folders, as the collection analysis saw it (see LibraryScanService).
 * Plain strings only, so every rule over it runs in a JVM test.
 */
data class DiskFile(
    val name: String,
    /** Path below its console folder (or below the root, for loose files): `Game.m3u/Game (Disc 1).cue`. */
    val path: String,
    val size: Long,
    /** Last change (epoch millis) as the storage provider reports it; 0 when unknown. */
    val lastModified: Long,
    /** SAF document URIs of the file and of the folder holding it. */
    val uri: String,
    val parentUri: String,
    /** Console its folder belongs to; null for loose files in the root and folders matching no console. */
    val consoleId: String?,
    /** The console folder it sits in (its name), empty for loose files in the root. */
    val topFolder: String,
    /** Tree it was found through: the download directory or a per-console directory. */
    val rootUri: String,
    /** Folders between it and its console folder: 0 = directly in it. */
    val depth: Int,
    /** [VersionGrouping.groupKey] of its name: what ties it to the catalogue's game. */
    val groupKey: String,
    /** Catalogue row this file is (same name, or same base name for an unpacked archive); null = unknown. */
    val catalogId: Long? = null,
    /** Sampled content hash; only computed for files whose size another file shares. */
    val hash: String? = null,
    /** What a `.m3u` / `.cue` / `.gdi` refers to, as written in it; null when it was not read. */
    val references: List<String>? = null
) {
    val extension: String get() = CollectionGames.extensionOf(name)
    /** Path of the folder holding it, relative like [path] ("" = the console folder itself). */
    val folderPath: String get() = path.substringBeforeLast('/', "")
}

/** A folder below the ROM folders. Only [empty] ones matter to the rules (and to the repair). */
data class DiskFolder(
    val name: String,
    val path: String,
    val uri: String,
    val parentUri: String,
    val consoleId: String?,
    val topFolder: String,
    val rootUri: String,
    /** 0 = a console folder; 1 = a folder inside one, and so on. */
    val depth: Int,
    /** It holds nothing at all, hidden files included. */
    val empty: Boolean
)

/** One storage volume (internal storage, SD card…) with its usage. */
data class VolumeUsage(
    /** "primary" for the internal storage, the filesystem UUID otherwise. */
    val id: String,
    /** The system's description, in the device language (not the app's): the screen names a volume by [kind]. */
    val label: String,
    val removable: Boolean,
    val totalBytes: Long,
    val freeBytes: Long,
    /** The download directory or a per-console directory lives on it. */
    val hostsLibrary: Boolean
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)

    val kind: VolumeKind get() = when {
        id == PRIMARY -> VolumeKind.INTERNAL
        removable -> VolumeKind.SD_CARD
        else -> VolumeKind.EXTERNAL
    }

    companion object {
        const val PRIMARY = "primary"

        /** Two volumes of the same kind (two SD cards…) are told apart by their id. */
        fun needsId(volume: VolumeUsage, all: List<VolumeUsage>): Boolean = all.count { it.kind == volume.kind } > 1
    }
}

enum class VolumeKind { INTERNAL, SD_CARD, EXTERNAL }

/** A folder the library lives in: the download directory ([consoleId] null) or a per-console one. */
data class LibraryRoot(
    val uri: String,
    val consoleId: String?,
    /** The folder could be listed. */
    val accessible: Boolean,
    /** The app still holds the read/write grant the folder picker gave it. */
    val permission: Boolean
)

/** A catalogue row, as much of it as the analysis needs. */
data class CatalogRow(
    val id: Long,
    val consoleId: String,
    val groupKey: String,
    val fileName: String,
    val fileSize: Long
)

/** Everything one scan of the ROM folders found, plus the catalogue facts the tools compare it with. */
data class LibrarySnapshot(
    val files: List<DiskFile>,
    val folders: List<DiskFolder>,
    val volumes: List<VolumeUsage>,
    val roots: List<LibraryRoot>,
    /** Catalogue rows of the games found on disk (same console and group key). */
    val catalog: List<CatalogRow>,
    /** Games (distinct group keys) the catalogue lists, per console. */
    val catalogGames: Map<String, Int>,
    /** File extensions the catalogue lists, per console: lower-case, without dot. */
    val catalogExtensions: Map<String, Set<String>>,
    val scannedAt: Long,
    val version: Int = VERSION
) {
    companion object {
        /** Bumped whenever the shape changes: an older cached snapshot is then thrown away. */
        const val VERSION = 1
    }
}

/**
 * One game on disk: the files in one folder that belong to one image (`Game.cue` + `Game (Track 2).bin`,
 * every disc of a set, an archive next to what it unpacked to), or a whole `<game>.m3u/` folder.
 * Counted, compared and deleted as a unit.
 */
data class DiskGame(
    val consoleId: String?,
    val groupKey: String,
    /** The name shown: file name without extension, track nor disc tag (`Final Fantasy VII (USA)`). */
    val title: String,
    val files: List<DiskFile>,
    /** The game is an ES-DE `<game>.m3u/` folder: this is its URI, so the folder goes with it. */
    val setFolderUri: String? = null
) {
    val id: String get() = setFolderUri ?: files.first().uri
    val size: Long get() = files.sumOf { it.size }
    /** The biggest file: the image itself rather than its sheet or playlist. */
    val primary: DiskFile get() = files.maxBy { it.size }
    val tags: List<String> get() = FileParsingUtils.extractNameAndTags(title).second
}

/** Groups the files on disk into games ([DiskGame]); what is not a game (artwork, saves, shortcuts…) stays out. */
object CollectionGames {

    /** Files that sit next to games but are none: shortcuts, artwork, notes, saves, states, patches, checksums. */
    val companionExtensions = setOf(
        "dgmtx", "png", "jpg", "jpeg", "gif", "webp", "bmp", "mp4", "mkv", "pdf",
        "txt", "nfo", "md", "xml", "json", "html", "htm", "url", "lnk", "ini", "cfg", "db", "log",
        "sav", "srm", "state", "sta", "mcr", "mcd", "rtc", "eep", "sra", "fla", "mpk", "dsv", "brm", "auto",
        "ips", "bps", "ups", "xdelta", "ppf", "cht", "sfv", "md5", "sha1", "crc"
    )
    private val numberedState = Regex("^(state|ss|st)\\d+$")
    private val trackTag = Regex("""\s*\(Track\s*\d{1,3}\)""", RegexOption.IGNORE_CASE)

    fun isGameFile(file: DiskFile): Boolean = isGameFile(file.name)

    fun isGameFile(name: String): Boolean {
        if (name.startsWith(".")) return false
        val ext = extensionOf(name)
        return ext !in companionExtensions && !numberedState.matches(ext)
    }

    /** Lower-case extension without the dot; empty when the name has none (`Super Mario Bros. 3`). */
    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").takeIf { ext -> ext.length in 1..8 && ext.all { it.isLetterOrDigit() || it == '!' } }
            ?.lowercase(Locale.ROOT).orEmpty()

    /** What the files of one image share: the name without extension, track nor disc tag, lower-case. */
    fun entryKey(name: String): String =
        MultiDisc.withoutDiscTag(MultiDisc.stripExtension(name).replace(trackTag, "")).lowercase(Locale.ROOT)

    /** The title of a game made of [files]: the shortest stem, without track nor disc tag. */
    fun titleOf(files: List<DiskFile>): String =
        MultiDisc.withoutDiscTag(MultiDisc.stripExtension(files.minBy { it.name.length }.name).replace(trackTag, "").trim())

    /** True for a folder in the ES-DE multi-disc layout (`<game>.m3u/`). */
    fun isSetFolder(name: String): Boolean = name.lowercase(Locale.ROOT).endsWith(".m3u")

    fun group(files: List<DiskFile>): List<DiskGame> {
        val games = ArrayList<DiskGame>()
        val (inSets, others) = files.filter { isGameFile(it) }.partition { it.depth >= 1 && isSetFolder(it.folderPath.substringAfterLast('/')) }
        inSets.groupBy { it.parentUri }.forEach { (folderUri, setFiles) ->
            val first = setFiles.first()
            games += DiskGame(
                consoleId = first.consoleId,
                groupKey = VersionGrouping.groupKey(first.folderPath.substringAfterLast('/')),
                title = MultiDisc.stripExtension(first.folderPath.substringAfterLast('/')),
                files = setFiles.sortedBy { it.name.lowercase(Locale.ROOT) },
                setFolderUri = folderUri
            )
        }
        others.groupBy { it.parentUri + "|" + entryKey(it.name) }.forEach { (_, group) ->
            // A playlist alone points at discs counted elsewhere (or nowhere): not a game of its own.
            if (group.all { it.extension == "m3u" }) return@forEach
            val first = group.first()
            games += DiskGame(
                consoleId = first.consoleId,
                groupKey = first.groupKey,
                title = titleOf(group),
                files = group.sortedBy { it.name.lowercase(Locale.ROOT) }
            )
        }
        return games
    }
}
