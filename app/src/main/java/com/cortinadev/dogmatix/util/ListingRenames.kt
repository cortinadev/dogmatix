package com.cortinadev.dogmatix.util

/**
 * File names of web rows that changed with [SourceScanCheck.LISTING_PARSER_VERSION] 2: before, a
 * row was named after its whole link (`/roms/snes/Game%20(Japan).zip`, `./Zelda:%20Link.zip`,
 * `https://cdn.example/snes/Game.zip?dl=1`); now after its last part ([FileParsingUtils.listingFileName]).
 * What is keyed by the old name (the ★, the Downloads row and what hangs from it) follows the file
 * to its new name, once, when the source is written again.
 */
object ListingRenames {

    /** A Downloads row as the renaming sees it. */
    data class Download(val consoleId: String, val status: String)

    /**
     * Old name → new name, paired by the last part of the old link and the new name, both
     * decoded. Only old names that were a longer link (they hold a `/`) are paired. A last part
     * that two old names share, or two new ones, pairs none of them: there is no telling which
     * is which. A new name that some old row already had is left alone (that row keeps it).
     */
    fun pairs(oldNames: Collection<String>, newNames: Collection<String>): Map<String, String> {
        val old = oldNames.toSet()
        val newByLeaf = newNames.groupBy { decode(it) }
        val oldByLeaf = old.filter { '/' in it }
            .mapNotNull { name -> FileParsingUtils.listingFileName(name)?.let { decode(it) to name } }
            .groupBy({ it.first }, { it.second })
        val result = LinkedHashMap<String, String>()
        for ((leaf, olds) in oldByLeaf) {
            val news = newByLeaf[leaf] ?: continue
            if (olds.size != 1 || news.size != 1) continue
            val from = olds.single()
            val to = news.single()
            if (from == to || to in old) continue
            result[from] = to
        }
        return result
    }

    /**
     * Of [pairs] (from [pairs]), those whose Downloads row moves to the new name, and with it what
     * is keyed by the same name (resume state, checksum verdict, storage redirect, RomM upload).
     * [history] holds the Downloads rows of the old and the new names, of any console. Only a row
     * of this console, not [busy] (a running job writes its row under the old name and would bring
     * it back), and only when no download has the new name already (the list is keyed by name alone).
     * Without a Downloads row there is nothing to carry: leftovers keyed by the old name stay.
     */
    fun forDownloads(pairs: Map<String, String>, consoleId: String, history: Map<String, Download>, busy: Set<String>): Map<String, String> =
        pairs.filter { (from, to) ->
            val before = history[from]
            before != null && before.consoleId == consoleId && before.status !in busy && to !in history
        }

    private fun decode(name: String): String = FileParsingUtils.decodeUrlEncodedFileName(name)
}
