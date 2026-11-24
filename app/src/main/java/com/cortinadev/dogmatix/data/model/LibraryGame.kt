package com.cortinadev.dogmatix.data.model

/** One release of a game: a single file, or every disc of a multi-disc release, in disc order. */
data class GameVersion(val discs: List<DownloadableFileWithTags>) {
    val primary: DownloadableFileWithTags get() = discs.first()
    val totalSize: Long get() = discs.sumOf { it.file.fileSize }
}

/**
 * A game of the library with all its versions (regions, revisions, sources…), best first for the
 * user's region and languages: A downloads [preferred], the details card offers the others.
 */
data class LibraryGame(
    val consoleId: String,
    val groupKey: String,
    val versions: List<GameVersion>
) {
    val preferred: GameVersion get() = versions.first()
    val primary: DownloadableFileWithTags get() = preferred.primary
    val files: List<DownloadableFileWithTags> get() = versions.flatMap { it.discs }

    /**
     * What the A→Z list sorts the game by (the DAO's `queryGameGroups` rule, also its letter
     * buckets): the first name among the rows titled like [groupKey], else the first of all. A
     * game with several titles is keyed by the one it shows (TitleClones.gameKeys), so this is
     * [preferred]'s title.
     */
    val listName: String
        get() = files.filter { it.file.titleKey == groupKey }.minOfOrNull { it.file.name }
            ?: files.minOfOrNull { it.file.name }.orEmpty()
}

/** What the version ranking needs from Settings. */
data class VersionPrefs(val region: PreferredRegion, val languages: Set<String>)
