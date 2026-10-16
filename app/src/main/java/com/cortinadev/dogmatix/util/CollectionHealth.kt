package com.cortinadev.dogmatix.util

import java.util.Locale

/** How much a finding matters: it stops a game or the library, it is worth a look, or it is tidying up. */
enum class HealthSeverity { HIGH, MEDIUM, LOW }

/** What a finding's action does: a fix of the Repair tool, another tool, or another section. */
enum class HealthFix { REPAIR, FREE_SPACE, OPEN_SETTINGS, OPEN_SOURCES, OPEN_LIBRARY }

/** Every rule of the Health tool, in the order its findings are listed within a severity. */
enum class HealthKind(val severity: HealthSeverity, val fix: HealthFix?) {
    /** The app lost the grant to a library folder (or the folder is gone). */
    PERMISSION_LOST(HealthSeverity.HIGH, HealthFix.OPEN_SETTINGS),
    /** A volume holding the library is nearly full. */
    LOW_SPACE(HealthSeverity.HIGH, HealthFix.FREE_SPACE),
    /** A game file of 0 bytes. */
    ZERO_BYTES(HealthSeverity.HIGH, HealthFix.REPAIR),
    /** A `.m3u` / `.cue` / `.gdi` names files that are not there. */
    BROKEN_REFERENCE(HealthSeverity.HIGH, HealthFix.OPEN_LIBRARY),
    /** Some discs of a multi-disc game are missing. */
    INCOMPLETE_SET(HealthSeverity.MEDIUM, HealthFix.OPEN_LIBRARY),
    /** A cartridge of one console in another console's folder. */
    WRONG_CONSOLE(HealthSeverity.MEDIUM, HealthFix.REPAIR),
    /** What an interrupted download left behind. */
    PARTIAL_DOWNLOAD(HealthSeverity.MEDIUM, HealthFix.REPAIR),
    /** A complete multi-disc game without its playlist. */
    MISSING_PLAYLIST(HealthSeverity.LOW, HealthFix.REPAIR),
    /** An archive next to what it was unpacked to. */
    EXTRACTED_ARCHIVE(HealthSeverity.LOW, HealthFix.REPAIR),
    /** A folder inside a console folder that holds nothing. */
    EMPTY_FOLDER(HealthSeverity.LOW, HealthFix.REPAIR),
    /** A file type the console's sources never list. Report only. */
    FOREIGN_EXTENSION(HealthSeverity.LOW, null),
    /** The sources have not been scanned for a long time (or ever). */
    STALE_SOURCES(HealthSeverity.LOW, HealthFix.OPEN_SOURCES)
}

/**
 * One problem. [subject] is what the line names (a file, a game, a folder, a volume). [missing] holds
 * the names a sheet refers to that are gone, or the numbers of the discs a set lacks; [count] a
 * number the sentence needs (discs of the set, days since the last scan; -1 = never).
 */
data class HealthFinding(
    val id: String,
    val kind: HealthKind,
    val subject: String,
    val consoleId: String? = null,
    val severity: HealthSeverity = kind.severity,
    val files: List<DiskFile> = emptyList(),
    val folder: DiskFolder? = null,
    val missing: List<String> = emptyList(),
    /** [HealthKind.WRONG_CONSOLE]: the console whose folder the file belongs in; null when Sources has none. */
    val targetConsoleId: String? = null,
    /** [HealthKind.MISSING_PLAYLIST]: what to write, next to (or inside) which folder. */
    val playlist: DiscPlaylist? = null,
    /** The folder the discs sit in ([playlist] creates `<game>.m3u/` in it), or the set folder itself. */
    val playlistParentUri: String? = null,
    /** The `<game>.m3u/` folder when it already exists (only the playlist is missing from it). */
    val setFolderUri: String? = null,
    /** [HealthKind.MISSING_PLAYLIST]: the loose files that go into the set folder (discs, tracks, sheets). */
    val moves: List<DiskFile> = emptyList(),
    val bytes: Long = 0,
    val count: Int = 0
)

/** What the rules look at besides the snapshot. */
data class HealthInput(
    val snapshot: LibrarySnapshot,
    /** Every console in Sources, to say where a misplaced game belongs. */
    val consoleIds: Collection<String>,
    /** The Downloads list: decoded leaf file name, lower-case → status name. */
    val downloads: Map<String, String> = emptyMap(),
    /** A download, unpack or copy is running right now. */
    val downloadsActive: Boolean = false,
    /** Settings → Multi-disc games: playlists are only asked for when it is on. */
    val multiDiscPlaylists: Boolean = true,
    /** Some console has at least one enabled source. */
    val sourcesConfigured: Boolean = false,
    /** Epoch millis of the last finished sources scan; 0 = not known. */
    val lastRescanAt: Long = 0,
    val now: Long
)

/**
 * The Health tool's rules: pure over the snapshot so each one is tested with fake files. A rule
 * errs on the side of saying less, because most of them feed a fix that deletes or moves files.
 */
object CollectionHealth {
    private const val GIB = 1024L * 1024 * 1024
    private const val DAY = 24L * 60 * 60 * 1000

    /** Free space under this is a problem; under [LOW_SPACE_WARN] (or 5 % of the volume) worth a look. */
    const val LOW_SPACE_CRITICAL = 1 * GIB
    const val LOW_SPACE_WARN = 3 * GIB
    const val STALE_SOURCES_DAYS = 30
    /** A partial file this fresh may belong to a download that is still running elsewhere. */
    const val PARTIAL_MIN_AGE = 60L * 60 * 1000
    /** Sheets and playlists are only followed this deep below the console folder (the scan's depth). */
    const val MAX_DEPTH = 2

    /** Partial files of browsers, download managers and interrupted safe writes. */
    private val partialExtensions = setOf("part", "partial", "crdownload", "tmp", "aria2", "!qb", "!ut", "opdownload")
    private val archives = setOf("zip", "7z", "rar")

    /** What any console folder may hold: archives, disc images and their parts, containers. */
    private val universal = archives + setOf(
        "cue", "bin", "img", "sub", "ccd", "mds", "mdf", "gdi", "raw", "toc", "sbi", "m3u", "iso", "ecm",
        "chd", "cso", "zso", "pbp", "cdi", "nrg", "rvz", "gcz", "wbfs", "wia", "rom", "dsk", "wav", "flac", "ogg", "mp3"
    )

    /** Cartridge types that say which console they are for: the console keys ([ConsoleFormatter.consoleKey]) that run them, home system first. */
    private val cartridges: Map<String, List<String>> = mapOf(
        "gba" to listOf("gameboy_advance"),
        "gbc" to listOf("gameboy_color", "gameboy_advance"),
        "gb" to listOf("gameboy", "gameboy_color", "gameboy_advance"),
        "nes" to listOf("nintendo_entertainment_system"),
        "fds" to listOf("nintendo_entertainment_system"),
        "sfc" to listOf("super_nintendo_entertainment_system"),
        "smc" to listOf("super_nintendo_entertainment_system"),
        "n64" to listOf("nintendo_64"),
        "z64" to listOf("nintendo_64"),
        "v64" to listOf("nintendo_64"),
        "nds" to listOf("nintendo_ds", "nintendo_3ds"),
        "3ds" to listOf("nintendo_3ds"),
        "cia" to listOf("nintendo_3ds"),
        "sms" to listOf("master_system"),
        "gen" to listOf("genesis"),
        "smd" to listOf("genesis")
    )
    /** Only these consoles are judged by cartridge type; anything else may run more than it seems. */
    private val judged = cartridges.values.flatten().toSet()

    /** Arcade folders keep a game's archive next to its CHD folder: never "already unpacked". */
    private val arcadeFolders = setOf(
        "mame", "arcade", "fbneo", "fba", "fbalpha", "finalburn", "neogeo", "cps1", "cps2", "cps3",
        "naomi", "naomi2", "atomiswave", "model2", "model3", "daphne", "hbmame"
    )
    private val trackTag = Regex("""\s*\(Track\s*\d{1,3}\)""", RegexOption.IGNORE_CASE)

    fun check(input: HealthInput): List<HealthFinding> {
        val snapshot = input.snapshot
        val out = ArrayList<HealthFinding>()
        out += permissions(snapshot.roots)
        out += lowSpace(snapshot.volumes)
        out += zeroBytes(snapshot.files)
        out += brokenReferences(snapshot.files)
        out += discSets(input)
        out += misplaced(input)
        out += partials(input)
        out += extractedArchives(snapshot)
        out += emptyFolders(snapshot.folders)
        staleSources(input)?.let { out += it }
        return out.distinctBy { it.id }
            .sortedWith(compareBy({ it.severity.ordinal }, { it.kind.ordinal }, { it.subject.lowercase(Locale.ROOT) }))
    }

    /**
     * Whether a file of type [extension] belongs in [consoleId]'s folder: true / false for the
     * cartridge types, null when the type says nothing about the console.
     */
    fun belongsTo(extension: String, consoleId: String): Boolean? {
        val key = ConsoleFormatter.consoleKey(consoleId)
        val accepted = cartridges[extension.lowercase(Locale.ROOT)] ?: return null
        return if (key in judged) key in accepted else null
    }

    internal fun permissions(roots: List<LibraryRoot>): List<HealthFinding> =
        roots.filter { !it.permission || !it.accessible }.map { root ->
            HealthFinding("perm:${root.uri}", HealthKind.PERMISSION_LOST, root.uri, root.consoleId)
        }

    internal fun lowSpace(volumes: List<VolumeUsage>): List<HealthFinding> =
        volumes.filter { it.hostsLibrary && it.totalBytes > 0 }.mapNotNull { volume ->
            val severity = when {
                volume.freeBytes < LOW_SPACE_CRITICAL -> HealthSeverity.HIGH
                volume.freeBytes < LOW_SPACE_WARN || volume.freeBytes * 20 < volume.totalBytes -> HealthSeverity.MEDIUM
                else -> return@mapNotNull null
            }
            HealthFinding("space:${volume.id}", HealthKind.LOW_SPACE, volume.label, severity = severity, bytes = volume.freeBytes)
        }

    /**
     * Roots whose provider seems not to report sizes: most of their (ten or more) game files are
     * 0 bytes. A real empty file is rare; such a provider would otherwise flag every game.
     */
    internal fun sizelessRoots(files: List<DiskFile>): Set<String> =
        files.filter { CollectionGames.isGameFile(it) }.groupBy { it.rootUri }
            .filter { (_, inRoot) -> inRoot.size >= 10 && inRoot.count { it.size == 0L } * 2 > inRoot.size }.keys

    internal fun zeroBytes(files: List<DiskFile>): List<HealthFinding> {
        val sizeless = sizelessRoots(files)
        return files.filter { it.size == 0L && it.rootUri !in sizeless && CollectionGames.isGameFile(it) && !isPartialName(it.name) }
            .map { HealthFinding("zero:${it.uri}", HealthKind.ZERO_BYTES, it.name, it.consoleId, files = listOf(it)) }
    }

    internal fun brokenReferences(files: List<DiskFile>): List<HealthFinding> {
        val paths = files.groupBy { it.rootUri + "|" + it.topFolder }
            .mapValues { (_, inFolder) -> inFolder.mapTo(HashSet()) { it.path.lowercase(Locale.ROOT) } }
        return files.filter { it.references != null && it.size > 0 }.mapNotNull { sheet ->
            val known = paths[sheet.rootUri + "|" + sheet.topFolder].orEmpty()
            val missing = sheet.references.orEmpty().filter { ref ->
                val path = resolve(sheet.folderPath, ref) ?: return@filter false
                path.lowercase(Locale.ROOT) !in known
            }.distinct()
            if (missing.isEmpty()) null
            else HealthFinding("ref:${sheet.uri}", HealthKind.BROKEN_REFERENCE, sheet.name, sheet.consoleId, files = listOf(sheet), missing = missing)
        }
    }

    /**
     * Where [ref] (as written in a sheet in [folder]) points, relative to the console folder; null
     * when that cannot be judged: absolute paths, paths that leave the console folder or go deeper
     * than the scan looked.
     */
    internal fun resolve(folder: String, ref: String): String? {
        val clean = ref.trim().trim('"').replace('\\', '/')
        if (clean.isEmpty() || clean.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(clean) || "://" in clean) return null
        val parts = ArrayList(folder.split('/').filter { it.isNotEmpty() })
        for (segment in clean.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> parts += segment
            }
        }
        if (parts.isEmpty() || parts.size - 1 > MAX_DEPTH) return null
        return parts.joinToString("/")
    }

    /** Multi-disc games: the ones missing discs, and the complete ones without a playlist. */
    internal fun discSets(input: HealthInput): List<HealthFinding> {
        val snapshot = input.snapshot
        val files = snapshot.files
        val discs = files.mapNotNull { f -> MultiDisc.discOf(f.name)?.takeIf { MultiDisc.isDiscImage(f.name) }?.let { f to it } }
        val out = ArrayList<HealthFinding>()
        val byParent = files.groupBy { it.parentUri }
        for ((_, set) in discs.groupBy { (f, d) -> f.rootUri + "|" + f.topFolder + "|" + d.setStem.lowercase(Locale.ROOT) }) {
            val (first, disc) = set.first()
            val numbers = set.map { it.second.number }.toSet()
            val stated = set.mapNotNull { it.second.total }.maxOrNull() ?: 0
            val required = maxOf(stated, catalogDiscs(snapshot, first.consoleId, set.map { it.first.groupKey }.toSet(), disc.setStem), numbers.max())
            if (required < 2) continue
            val setFiles = set.map { it.first }
            val missing = (1..required).filter { it !in numbers }
            if (missing.isNotEmpty()) {
                out += HealthFinding(
                    "set:${first.rootUri}|${first.topFolder}|${disc.setStem}", HealthKind.INCOMPLETE_SET, disc.setStem, first.consoleId,
                    files = setFiles, missing = missing.map { it.toString() }, count = required
                )
                continue
            }
            if (!input.multiDiscPlaylists || !playsPlaylists(first.consoleId, first.topFolder)) continue
            playlistFinding(disc.setStem, setFiles, required, byParent, snapshot.folders)?.let { out += it }
        }
        return out
    }

    /**
     * A complete set without its playlist: discs loose in one folder (the playlist folder goes
     * next to them), or already inside `<game>.m3u/` with only the playlist missing. Discs spread
     * over other folders are left alone.
     */
    private fun playlistFinding(
        setStem: String,
        discs: List<DiskFile>,
        expected: Int,
        byParent: Map<String, List<DiskFile>>,
        folders: List<DiskFolder>
    ): HealthFinding? {
        val playlistName = "$setStem.m3u"
        val parents = discs.map { it.parentUri }.toSet()
        val setFolderOf = { parent: String -> folders.firstOrNull { it.parentUri == parent && it.name.equals(playlistName, ignoreCase = true) } }
        val inSetFolder = discs.filter { CollectionGames.isSetFolder(it.folderPath.substringAfterLast('/')) && it.folderPath.substringAfterLast('/').equals(playlistName, ignoreCase = true) }
        val loose = discs - inSetFolder.toSet()
        val setFolderUri = inSetFolder.map { it.parentUri }.distinct().singleOrNull()
        if (inSetFolder.isNotEmpty() && setFolderUri == null) return null
        // Where the set folder lives (or will): the discs' own folder when loose, the set folder's parent otherwise.
        val parentUri = when {
            loose.isNotEmpty() -> loose.map { it.parentUri }.distinct().singleOrNull() ?: return null
            else -> folders.firstOrNull { it.uri == setFolderUri }?.parentUri ?: return null
        }
        if (parents.any { it != parentUri && it != setFolderUri }) return null
        val looseNames = byParent[parentUri].orEmpty().map { it.name }
        val existingFolder = setFolderUri ?: setFolderOf(parentUri)?.uri
        val inSetNames = existingFolder?.let { uri -> byParent[uri].orEmpty().map { it.name } }.orEmpty()
        // A playlist already in place (a loose one next to the discs, or inside the folder) is fine.
        if (looseNames.any { it.equals(playlistName, ignoreCase = true) } || inSetNames.any { it.equals(playlistName, ignoreCase = true) }) return null
        val sheets = (byParent[parentUri].orEmpty() + existingFolder?.let { byParent[it] }.orEmpty()).associateBy { it.name }
        val plan = MultiDisc.plan(setStem, looseNames, inSetNames, expected) { sheet -> sheets[sheet]?.references.orEmpty() } ?: return null
        val first = discs.first()
        val moving = byParent[parentUri].orEmpty().filter { it.name in plan.moves }
        return HealthFinding(
            "m3u:$parentUri|$setStem", HealthKind.MISSING_PLAYLIST, setStem, first.consoleId,
            files = discs, playlist = plan, playlistParentUri = parentUri, setFolderUri = existingFolder, moves = moving,
            count = plan.entries.size
        )
    }

    /** Discs the catalogue lists for this release (its rows of the same game whose name differs only in the disc tag). */
    private fun catalogDiscs(snapshot: LibrarySnapshot, consoleId: String?, groupKeys: Set<String>, setStem: String): Int {
        consoleId ?: return 0
        return snapshot.catalog.asSequence()
            .filter { it.consoleId == consoleId && it.groupKey in groupKeys }
            .mapNotNull { MultiDisc.discOf(leaf(it.fileName)) }
            .filter { it.setStem.equals(setStem, ignoreCase = true) }
            .map { it.number }
            .distinct().count()
    }

    private fun playsPlaylists(consoleId: String?, topFolder: String): Boolean =
        MultiDisc.supportsPlaylists(consoleId?.let { ConsoleFolderAliases.candidatesFor(it) }.orEmpty() + ConsoleFolderAliases.normalize(topFolder))

    /** Cartridges in another console's folder, and file types a console's sources never list. */
    internal fun misplaced(input: HealthInput): List<HealthFinding> {
        val out = ArrayList<HealthFinding>()
        val byKey = input.consoleIds.groupBy { ConsoleFormatter.consoleKey(it) }
        for (file in input.snapshot.files) {
            val consoleId = file.consoleId ?: continue
            val ext = file.extension
            if (ext.isEmpty() || !CollectionGames.isGameFile(file) || isPartialName(file.name)) continue
            val key = ConsoleFormatter.consoleKey(consoleId)
            val accepted = cartridges[ext]
            if (accepted != null && key in judged) {
                if (key !in accepted) {
                    val target = accepted.firstNotNullOfOrNull { byKey[it]?.firstOrNull() }
                    out += HealthFinding("wrong:${file.uri}", HealthKind.WRONG_CONSOLE, file.name, consoleId, files = listOf(file), targetConsoleId = target)
                }
                continue
            }
            val listed = input.snapshot.catalogExtensions[consoleId]?.takeIf { it.isNotEmpty() } ?: continue
            if (ext !in listed && ext !in universal && accepted?.contains(key) != true) {
                out += HealthFinding("ext:${file.uri}", HealthKind.FOREIGN_EXTENSION, file.name, consoleId, files = listOf(file))
            }
        }
        return out
    }

    /**
     * Partial files of other download apps (old enough not to be running), and the file of a
     * download the Downloads list shows as failed or stopped that is smaller than the catalogue says.
     */
    internal fun partials(input: HealthInput): List<HealthFinding> {
        val snapshot = input.snapshot
        val sizes = snapshot.catalog.associate { it.id to it.fileSize }
        return snapshot.files.mapNotNull { file ->
            val partialName = isPartialName(file.name)
            val stale = if (file.lastModified > 0L) input.now - file.lastModified >= PARTIAL_MIN_AGE else !input.downloadsActive
            val interrupted = !partialName && input.downloads[file.name.lowercase(Locale.ROOT)].let { it == "FAILED" || it == "STOPPED" } &&
                file.catalogId?.let { sizes[it] }.let { expected -> expected == null || expected <= 0 || file.size < expected * 98 / 100 }
            if ((partialName && stale) || interrupted) {
                HealthFinding("part:${file.uri}", HealthKind.PARTIAL_DOWNLOAD, file.name, file.consoleId, files = listOf(file), bytes = file.size)
            } else null
        }
    }

    /** An archive whose content sits next to it: a file (or disc) of the same name, or its `<game>.m3u/` folder. */
    internal fun extractedArchives(snapshot: LibrarySnapshot): List<HealthFinding> {
        val byParent = snapshot.files.groupBy { it.parentUri }
        val setFolders = snapshot.folders.filter { CollectionGames.isSetFolder(it.name) }.groupBy { it.parentUri }
        return snapshot.files.filter { it.extension in archives && ConsoleFolderAliases.normalize(it.topFolder) !in arcadeFolders }.mapNotNull { archive ->
            val discTagged = MultiDisc.discOf(archive.name) != null
            val key = if (discTagged) stemKey(archive.name) else CollectionGames.entryKey(archive.name)
            val keyOf = { name: String -> if (discTagged) stemKey(name) else CollectionGames.entryKey(name) }
            val content = byParent[archive.parentUri].orEmpty().filter { it.uri != archive.uri && it.extension !in archives && CollectionGames.isGameFile(it) }
            val inSets = setFolders[archive.parentUri].orEmpty().flatMap { folder ->
                if (!discTagged && CollectionGames.entryKey(folder.name) == key) listOf(folder.name) else byParent[folder.uri].orEmpty().map { it.name }
            }
            val unpacked = content.any { keyOf(it.name) == key } || inSets.any { keyOf(it) == key }
            if (unpacked) HealthFinding("zip:${archive.uri}", HealthKind.EXTRACTED_ARCHIVE, archive.name, archive.consoleId, files = listOf(archive), bytes = archive.size)
            else null
        }
    }

    internal fun emptyFolders(folders: List<DiskFolder>): List<HealthFinding> =
        folders.filter { it.empty && it.depth >= 1 && !it.name.startsWith(".") }
            .map { HealthFinding("empty:${it.uri}", HealthKind.EMPTY_FOLDER, it.name, it.consoleId, folder = it) }

    internal fun staleSources(input: HealthInput): HealthFinding? {
        if (!input.sourcesConfigured) return null
        if (input.lastRescanAt > 0L) {
            val days = ((input.now - input.lastRescanAt).coerceAtLeast(0) / DAY).toInt()
            return if (days >= STALE_SOURCES_DAYS) HealthFinding("sources", HealthKind.STALE_SOURCES, "", count = days) else null
        }
        // Never scanned as far as we know: only said when the catalogue is empty too.
        return if (input.snapshot.catalogGames.isEmpty()) HealthFinding("sources", HealthKind.STALE_SOURCES, "", count = -1) else null
    }

    fun isPartialName(name: String): Boolean = !name.startsWith(".") && name.substringAfterLast('.', "").lowercase(Locale.ROOT) in partialExtensions

    /** The name without extension nor track tag, lower-case: the disc tag stays. */
    private fun stemKey(name: String): String = MultiDisc.stripExtension(name).replace(trackTag, "").trim().lowercase(Locale.ROOT)

    private fun leaf(fileName: String): String = FileParsingUtils.decodeUrlEncodedFileName(fileName).replace('\\', '/').substringAfterLast('/')
}
