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
}

/** What the version ranking needs from Settings. */
data class VersionPrefs(val region: PreferredRegion, val languages: Set<String>)
