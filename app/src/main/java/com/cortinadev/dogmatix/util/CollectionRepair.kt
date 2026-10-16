package com.cortinadev.dogmatix.util

import java.util.Locale

/** The fixes the Repair tool can run, in the order they run (playlists move files before folders are cleared). */
enum class RepairKind(val destructive: Boolean) {
    CREATE_PLAYLIST(false),
    MOVE_TO_CONSOLE(false),
    DELETE_EMPTY_FILE(true),
    DELETE_PARTIAL(true),
    DELETE_EXTRACTED_ARCHIVE(true),
    DELETE_EMPTY_FOLDER(true)
}

/** One fix: what a Health finding needs done. */
data class RepairAction(val kind: RepairKind, val finding: HealthFinding) {
    val id: String get() = finding.id
    val subject: String get() = finding.subject
    val consoleId: String? get() = finding.consoleId
    /** Space a deletion frees (0 for the others). */
    val bytes: Long get() = if (kind.destructive) finding.files.sumOf { it.size } else 0L
}

/** Turns the Health findings that can be fixed without a human choice into repairs. Pure, for the tests. */
object CollectionRepair {

    fun kindFor(kind: HealthKind): RepairKind? = when (kind) {
        HealthKind.MISSING_PLAYLIST -> RepairKind.CREATE_PLAYLIST
        HealthKind.WRONG_CONSOLE -> RepairKind.MOVE_TO_CONSOLE
        HealthKind.ZERO_BYTES -> RepairKind.DELETE_EMPTY_FILE
        HealthKind.PARTIAL_DOWNLOAD -> RepairKind.DELETE_PARTIAL
        HealthKind.EXTRACTED_ARCHIVE -> RepairKind.DELETE_EXTRACTED_ARCHIVE
        HealthKind.EMPTY_FOLDER -> RepairKind.DELETE_EMPTY_FOLDER
        else -> null
    }

    fun plan(findings: List<HealthFinding>): List<RepairAction> =
        findings.mapNotNull { finding ->
            val kind = kindFor(finding.kind) ?: return@mapNotNull null
            when (kind) {
                // Without a console to take it, a misplaced file has nowhere to go.
                RepairKind.MOVE_TO_CONSOLE -> if (finding.targetConsoleId == null) return@mapNotNull null
                RepairKind.CREATE_PLAYLIST -> if (finding.playlist == null || finding.playlistParentUri == null) return@mapNotNull null
                RepairKind.DELETE_EMPTY_FOLDER -> if (finding.folder == null) return@mapNotNull null
                else -> if (finding.files.isEmpty()) return@mapNotNull null
            }
            RepairAction(kind, finding)
        }.sortedWith(compareBy({ it.kind.ordinal }, { it.subject.lowercase(Locale.ROOT) }))

    /** The repairs a Health group stands for (what "Repair these" ticks). */
    fun idsFor(actions: List<RepairAction>, kind: HealthKind): Set<String> =
        actions.filter { it.finding.kind == kind }.mapTo(LinkedHashSet()) { it.id }

    /** How many of [actions] there are of each kind, in run order: the confirmation's preview. */
    fun counts(actions: List<RepairAction>): List<Pair<RepairKind, Int>> =
        actions.groupingBy { it.kind }.eachCount().toList().sortedBy { it.first.ordinal }
}

enum class ReclaimKind { DUPLICATE, EXTRACTED_ARCHIVE, PARTIAL_DOWNLOAD, TORRENT_CACHE, IMAGE_CACHE }

/** Something the Free up space tool can delete, and what it frees. */
data class ReclaimCandidate(
    val id: String,
    val kind: ReclaimKind,
    val title: String,
    val consoleId: String?,
    val bytes: Long,
    val files: List<DiskFile> = emptyList(),
    /** [ReclaimKind.DUPLICATE]: the game's `<game>.m3u/` folder, removed with it. */
    val setFolderUri: String? = null,
    /** [ReclaimKind.DUPLICATE]: the copy that stays. */
    val kept: DiskGame? = null
)

/** The Free up space list: biggest first, each file offered once. Pure, for the tests. */
object CollectionReclaim {

    const val TORRENT_CACHE_ID = "cache:torrent"
    const val IMAGE_CACHE_ID = "cache:images"

    /**
     * @param keepOf the copy kept in each duplicate group (the suggestion, or the user's pick); groups without one offer nothing.
     * @param torrentCacheBytes the torrent cache when nothing uses it; null while a torrent downloads or seeds.
     */
    fun candidates(
        duplicates: List<DuplicateGroup>,
        keepOf: (DuplicateGroup) -> String?,
        findings: List<HealthFinding>,
        torrentCacheBytes: Long?,
        imageCacheBytes: Long?
    ): List<ReclaimCandidate> {
        val out = ArrayList<ReclaimCandidate>()
        val offered = HashSet<String>()
        for (finding in findings) {
            val kind = when (finding.kind) {
                HealthKind.PARTIAL_DOWNLOAD -> ReclaimKind.PARTIAL_DOWNLOAD
                HealthKind.EXTRACTED_ARCHIVE -> ReclaimKind.EXTRACTED_ARCHIVE
                else -> continue
            }
            if (finding.files.any { it.uri in offered }) continue
            finding.files.forEach { offered += it.uri }
            out += ReclaimCandidate(finding.id, kind, finding.subject, finding.consoleId, finding.files.sumOf { it.size }, finding.files)
        }
        for (group in duplicates) {
            val keepId = keepOf(group) ?: continue
            val kept = group.games.firstOrNull { it.id == keepId } ?: continue
            for (game in group.removable(keepId)) {
                if (game.files.any { it.uri in offered }) continue
                game.files.forEach { offered += it.uri }
                out += ReclaimCandidate("dup:${game.id}", ReclaimKind.DUPLICATE, game.title, game.consoleId, game.size, game.files, game.setFolderUri, kept)
            }
        }
        torrentCacheBytes?.takeIf { it > 0 }?.let { out += ReclaimCandidate(TORRENT_CACHE_ID, ReclaimKind.TORRENT_CACHE, "", null, it) }
        imageCacheBytes?.takeIf { it > 0 }?.let { out += ReclaimCandidate(IMAGE_CACHE_ID, ReclaimKind.IMAGE_CACHE, "", null, it) }
        return out.filter { it.bytes > 0 }
            .sortedWith(compareByDescending<ReclaimCandidate> { it.bytes }.thenBy { it.title.lowercase(Locale.ROOT) }.thenBy { it.id })
    }

    fun total(candidates: List<ReclaimCandidate>, ids: Set<String>): Long = candidates.filter { it.id in ids }.sumOf { it.bytes }
}
