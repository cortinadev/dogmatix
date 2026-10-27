package com.cortinadev.dogmatix.util

import java.util.Locale

/**
 * What deleting files from the library takes along so no game is left half there (Collection →
 * Duplicates, Repair, Free up space):
 *  - the tracks a `.cue` / `.gdi` being deleted names, unless a sheet that stays names them too;
 *  - the playlists (`.m3u`) that list a disc being deleted: rewritten with the discs left, and
 *    deleted with them when none is left. A playlist with a single disc stays (one entry is a
 *    valid playlist for every emulator that reads them, and ES-DE keeps showing the
 *    `<game>.m3u/` folder as the game).
 * Pure over the analysis' files ([DiskFile.references] as read by the scan). The plan is made
 * before asking, so the confirmation shows what goes and exactly that is deleted.
 */
object DiscSetCleanup {

    /** [playlist] gets [content] (one disc per line) instead of what it had. */
    data class Rewrite(val playlist: DiskFile, val entries: List<String>) {
        val content: String get() = entries.joinToString("\n", postfix = "\n")
    }

    data class Plan(
        /** What to delete: what was asked, then the tracks and the emptied playlists it takes along. */
        val delete: List<DiskFile>,
        val rewrites: List<Rewrite>,
        /** How many of [delete] were asked for (the rest is taken along). */
        val asked: Int = delete.size
    ) {
        /** Files taken along: tracks of the sheets, playlists left without discs. */
        val extra: Int get() = delete.size - asked
        val bytes: Long get() = delete.sumOf { it.size }
    }

    fun plan(deleting: List<DiskFile>, all: List<DiskFile>): Plan {
        val byPath = all.associateBy { key(it, it.path) }
        val gone = LinkedHashMap<String, DiskFile>()
        deleting.forEach { gone[it.uri] = it }
        val asked = gone.size

        // Tracks of the sheets that go, unless a sheet that stays still needs them.
        val sheets = all.filter { it.extension == "cue" || it.extension == "gdi" }
        val kept = sheets.filter { it.uri !in gone }.flatMapTo(HashSet()) { sheet -> targets(sheet) }
        for (sheet in sheets.filter { it.uri in gone }) {
            for (target in targets(sheet)) {
                if (target in kept) continue
                byPath[target]?.let { gone.putIfAbsent(it.uri, it) }
            }
        }

        // Playlists that list a disc that goes.
        val goneKeys = gone.values.mapTo(HashSet()) { key(it, it.path) }
        val rewrites = ArrayList<Rewrite>()
        for (playlist in all.filter { it.extension == "m3u" && it.uri !in gone && it.references != null }) {
            val entries = playlist.references.orEmpty()
            val left = entries.filter { ref ->
                val path = CollectionHealth.resolve(playlist.folderPath, ref) ?: return@filter true
                key(playlist, path) !in goneKeys
            }
            when {
                left.size == entries.size -> Unit
                left.isEmpty() -> gone[playlist.uri] = playlist
                else -> rewrites += Rewrite(playlist, left)
            }
        }
        return Plan(gone.values.toList(), rewrites, asked)
    }

    /** Where the references of [sheet] point (keys of [key]); what cannot be judged is left out. */
    private fun targets(sheet: DiskFile): List<String> =
        sheet.references.orEmpty().mapNotNull { ref -> CollectionHealth.resolve(sheet.folderPath, ref)?.let { key(sheet, it) } }

    /** A path below [inFolderOf]'s console folder, compared without case (FAT, exFAT). */
    private fun key(inFolderOf: DiskFile, path: String): String =
        inFolderOf.rootUri + "|" + inFolderOf.topFolder + "|" + path.lowercase(Locale.ROOT)
}
