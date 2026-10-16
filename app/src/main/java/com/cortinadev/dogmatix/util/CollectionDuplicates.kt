package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.VersionPrefs
import java.util.Locale

enum class DuplicateKind {
    /** The same files (same names) in more than one folder. */
    SAME_FILE,
    /** Files with other names but the same size and content hash. */
    SAME_CONTENT,
    /** Different versions of one game in a console: regions, revisions, translations. */
    VERSIONS
}

/** Copies of one game on disk, with the one worth keeping when something sets it apart. */
data class DuplicateGroup(
    val id: String,
    val kind: DuplicateKind,
    val consoleId: String?,
    /** The game's name without tags (`Chrono Trigger`). */
    val title: String,
    /** Best first: the suggested copy leads when there is one. */
    val games: List<DiskGame>,
    /** The copy to keep; null when nothing sets one apart (two versions that rank the same). */
    val keep: DiskGame?
) {
    /** What goes when [keepId] is kept (nothing without a choice). */
    fun removable(keepId: String? = keep?.id): List<DiskGame> = if (keepId == null) emptyList() else games.filter { it.id != keepId }

    fun reclaimable(keepId: String? = keep?.id): Long = removable(keepId).sumOf { it.size }
}

/**
 * Finds games that are on disk more than once and proposes which copy to keep. Pure, for the
 * tests; the screen still shows every copy and asks before anything goes.
 *
 * Within a console, every copy with the same group key (the name without tags) is one group: the
 * same files in two folders, or several versions. Elsewhere only identical content (same size and
 * hash) counts, so two different games are never offered just because they share a size.
 */
object CollectionDuplicates {

    private val bios = Regex("(?i)^(scph\\d+\\w*|.*bios.*|dc_(boot|flash)|syscard\\d*|firmware.*)$")
    private val systemFolders = setOf("bios", "system", "firmware")

    fun find(games: List<DiskGame>, prefs: VersionPrefs, setFolders: Boolean = true): List<DuplicateGroup> {
        val candidates = games.filter { comparable(it) }
        val groups = ArrayList<DuplicateGroup>()
        val grouped = HashSet<String>()

        candidates.filter { it.consoleId != null && it.groupKey.isNotBlank() }
            .groupBy { it.consoleId + "|" + it.groupKey }
            .filterValues { it.size > 1 }
            .forEach { (key, copies) ->
                val names = copies.map { names(it) }
                val kind = if (names.size != names.toSet().size) DuplicateKind.SAME_FILE else DuplicateKind.VERSIONS
                groups += build("v:$key", kind, copies.first().consoleId, copies, prefs, setFolders)
                copies.forEach { grouped += it.id }
            }

        candidates.filter { it.id !in grouped }
            .mapNotNull { game -> signature(game)?.let { it to game } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .forEach { (signature, copies) ->
                groups += build("h:$signature", DuplicateKind.SAME_CONTENT, null, copies, prefs, setFolders)
            }

        return groups.sortedWith(
            compareByDescending<DuplicateGroup> { it.reclaimable() }
                .thenByDescending { group -> group.games.sumOf { it.size } }
                .thenBy { it.title.lowercase(Locale.ROOT) }
        )
    }

    private fun build(id: String, kind: DuplicateKind, consoleId: String?, copies: List<DiskGame>, prefs: VersionPrefs, setFolders: Boolean): DuplicateGroup {
        // Identical content: only the place matters. Versions: the best version, then the best place.
        val byVersion = compareBy<DiskGame> { versionScore(it, prefs) }
        val sorted = copies.sortedWith(
            if (kind == DuplicateKind.SAME_CONTENT) placeOrder(setFolders).then(byVersion) else byVersion.then(placeOrder(setFolders))
        )
        val top = sorted[0]
        val second = sorted[1]
        // Two versions that rank the same and are not the same files: the choice is the user's.
        val tie = versionScore(top, prefs) == versionScore(second, prefs) && !sameContent(top, second)
        val keep = if (kind == DuplicateKind.VERSIONS && tie) null else top
        val title = FileParsingUtils.extractNameAndTags(top.title).first.ifBlank { top.title }
        return DuplicateGroup(id, kind, consoleId ?: keep?.consoleId, title, sorted, keep)
    }

    /**
     * Comparable rank of a version (smaller is better), as [VersionRanking] orders the library:
     * released first, then region and languages, revision, verified dump, not an alternate.
     */
    internal fun versionScore(game: DiskGame, prefs: VersionPrefs): VersionScore {
        val tags = game.tags
        val name = game.primary.name
        return VersionScore(
            nonRelease = VersionRanking.isNonRelease(tags, name),
            points = -VersionRanking.preferencePoints(tags, prefs),
            revision = -VersionRanking.revision(tags),
            unverified = !name.contains("[!]"),
            alternate = tags.any { it.uppercase(Locale.ROOT).startsWith("ALT") }
        )
    }

    internal data class VersionScore(
        val nonRelease: Boolean,
        val points: Int,
        val revision: Double,
        val unverified: Boolean,
        val alternate: Boolean
    ) : Comparable<VersionScore> {
        override fun compareTo(other: VersionScore): Int =
            compareValuesBy(this, other, { it.nonRelease }, { it.points }, { it.revision }, { it.unverified }, { it.alternate })
    }

    /**
     * Among equal versions, the copy in the right place: a folder of the console its file type
     * belongs to, the `<game>.m3u/` layout when multi-disc playlists are on, the shallowest folder.
     */
    private fun placeOrder(setFolders: Boolean): Comparator<DiskGame> =
        compareBy<DiskGame> { game ->
            when (game.consoleId?.let { CollectionHealth.belongsTo(game.primary.extension, it) }) {
                true -> 0
                null -> 1
                false -> 2
            }
        }
            .thenBy { if (setFolders && it.files.size > 1) (if (it.setFolderUri != null) 0 else 1) else 0 }
            .thenBy { it.files.first().depth }
            .thenBy { it.files.first().path.lowercase(Locale.ROOT) }

    private fun comparable(game: DiskGame): Boolean =
        !bios.matches(game.title.trim()) &&
            ConsoleFolderAliases.normalize(game.files.first().topFolder) !in systemFolders &&
            game.size > 0

    /** The game's file names; a playlist does not count (the folder layout has one, loose discs may not). */
    private fun names(game: DiskGame): Set<String> =
        game.files.filter { it.extension != "m3u" }.mapTo(HashSet()) { it.name.lowercase(Locale.ROOT) }

    /** Size plus every file's hash; null unless all of them were hashed. */
    private fun signature(game: DiskGame): String? {
        if (game.files.any { it.hash == null }) return null
        return game.size.toString() + ":" + game.files.map { it.hash }.sortedBy { it }.joinToString(",")
    }

    private fun sameContent(a: DiskGame, b: DiskGame): Boolean =
        names(a) == names(b) || (signature(a) != null && signature(a) == signature(b))
}
