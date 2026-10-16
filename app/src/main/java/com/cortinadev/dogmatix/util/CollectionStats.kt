package com.cortinadev.dogmatix.util

import java.util.Locale

/** One console (or one folder that matches none) of the collection. */
data class ConsoleUsage(
    /** Null for loose files in the root and folders that match no console; [folder] names them. */
    val consoleId: String?,
    val folder: String,
    val games: Int,
    val files: Int,
    val bytes: Long,
    /** Games the catalogue lists for this console. */
    val catalogGames: Int,
    /** Of those, how many are on disk. */
    val ownedCatalogGames: Int
)

/** A region or language and how many games on disk carry it. */
data class TagCount(val tag: String, val games: Int)

/** The numbers of the Summary and Statistics tools. Pure, for the tests. */
object CollectionStats {

    /** Games, files and bytes per console, biggest first. Coverage counts distinct catalogue games. */
    fun byConsole(snapshot: LibrarySnapshot, games: List<DiskGame>): List<ConsoleUsage> {
        val catalogKeys = snapshot.catalog.groupBy { it.consoleId }.mapValues { (_, rows) -> rows.mapTo(HashSet()) { it.groupKey } }
        val filesByConsole = snapshot.files.filter { CollectionGames.isGameFile(it) }.groupBy { it.consoleId to it.topFolder }
        return games.groupBy { it.consoleId to folderOf(it) }.map { (key, inConsole) ->
            val (consoleId, folder) = key
            val owned = consoleId?.let { id ->
                val known = catalogKeys[id].orEmpty()
                inConsole.mapTo(HashSet()) { it.groupKey }.count { it in known }
            } ?: 0
            ConsoleUsage(
                consoleId = consoleId,
                folder = folder,
                games = inConsole.distinctBy { it.groupKey }.size,
                files = filesByConsole[key]?.size ?: inConsole.sumOf { it.files.size },
                bytes = inConsole.sumOf { it.size },
                catalogGames = consoleId?.let { snapshot.catalogGames[it] } ?: 0,
                ownedCatalogGames = owned
            )
        }.sortedWith(compareByDescending<ConsoleUsage> { it.bytes }.thenBy { it.folder.lowercase(Locale.ROOT) })
    }

    /** Distinct games: the same game in two folders of one console counts once. */
    fun gameCount(games: List<DiskGame>): Int = games.distinctBy { (it.consoleId ?: it.files.first().topFolder) + "|" + it.groupKey }.size

    fun biggest(games: List<DiskGame>, count: Int): List<DiskGame> =
        games.sortedWith(compareByDescending<DiskGame> { it.size }.thenBy { it.title.lowercase(Locale.ROOT) }).take(count)

    /** Region tags of the games on disk, most common first; games without one are not counted. */
    fun regions(games: List<DiskGame>): List<TagCount> = count(games) { tags ->
        tags.filter { it.uppercase(Locale.ROOT) in Constants.Tags.ALL_REGIONS }
    }

    /** Languages (stated, or implied by the region as the version ranking does), most common first. */
    fun languages(games: List<DiskGame>): List<TagCount> = count(games) { tags -> VersionRanking.languagesOf(tags) }

    private fun count(games: List<DiskGame>, pick: (List<String>) -> List<String>): List<TagCount> {
        val counts = HashMap<String, Int>()
        for (game in games) {
            pick(game.tags).map { it.uppercase(Locale.ROOT) }.distinct().forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        return counts.map { TagCount(it.key, it.value) }
            .sortedWith(compareByDescending<TagCount> { it.games }.thenBy { it.tag })
    }

    /** Share of [part] in [whole] as a 0..1 fraction (0 when there is nothing). */
    fun fraction(part: Long, whole: Long): Float = if (whole <= 0) 0f else (part.toFloat() / whole).coerceIn(0f, 1f)

    private fun folderOf(game: DiskGame): String = game.files.first().topFolder
}
