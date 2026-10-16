package com.cortinadev.dogmatix.util

import java.util.Locale

/**
 * Cover images on the RomM server, and the batch that copies them into ES-DE's
 * `downloaded_media/<system>/covers/<rom without extension>.<ext>`.
 */
object RommCovers {

    /** Where RomM serves its resources (covers, screenshots). */
    const val RESOURCES_PATH = "/assets/romm/resources"

    /**
     * Full URL of a cover path as RomM returns it: RomM 4+ sends `/assets/romm/resources/…?ts=…`
     * (`path_cover_small`), older servers the bare `roms/3/15/cover/small.png` (`path_cover_s`).
     * Absolute URLs (an IGDB `url_cover`) pass through. Null when [path] is blank.
     */
    fun url(baseUrl: String, path: String): String? {
        val p = path.trim().takeIf { it.isNotEmpty() } ?: return null
        if (p.startsWith("http://", ignoreCase = true) || p.startsWith("https://", ignoreCase = true)) return p
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val relative = if (p.trimStart('/').startsWith(RESOURCES_PATH.trimStart('/'))) "/" + p.trimStart('/') else "$RESOURCES_PATH/" + p.trimStart('/')
        return base + relative.replace(" ", "%20")
    }

    /** File extension of a cover path (`png` when it has none worth trusting). */
    fun extension(path: String): String =
        path.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
            .lowercase(Locale.ROOT).takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) } ?: "png"

    /** Formats ES-DE reads as they are; anything else is converted to PNG. */
    fun esdeReadable(extension: String): Boolean = extension in setOf("png", "jpg", "jpeg")

    /** A game found in a console folder on disk: its ES-DE [system] and its file (or `.m3u` folder) [stem]. */
    data class DiskGame(val system: String, val consoleIds: List<String>, val stem: String)

    data class Job(val system: String, val stem: String, val coverPath: String)

    /** [jobs] to write; [kept] games have a server cover but ES-DE already shows one. */
    data class EsdePlan(val jobs: List<Job>, val kept: Int)

    /**
     * The covers to write: one per game on disk that the server has a cover for, skipping the
     * stems [existing] already has a cover for (system → lower-cased stems) unless [overwrite].
     * [coverFor] answers the server cover path of a game, null when the server has none.
     */
    fun planEsde(
        games: List<DiskGame>,
        existing: Map<String, Set<String>>,
        overwrite: Boolean,
        coverFor: (consoleId: String, stem: String) -> String?
    ): EsdePlan {
        val seen = HashSet<String>()
        val jobs = ArrayList<Job>()
        var kept = 0
        for (game in games) {
            val stem = game.stem.lowercase(Locale.ROOT)
            if (!seen.add(game.system + "|" + stem)) continue
            val cover = game.consoleIds.firstNotNullOfOrNull { coverFor(it, game.stem) } ?: continue
            if (!overwrite && stem in existing[game.system].orEmpty()) kept++ else jobs += Job(game.system, game.stem, cover)
        }
        return EsdePlan(jobs, kept)
    }

    /**
     * The stem ES-DE names a game's media after: the file name without extension; a
     * "directory interpreted as file" (`Game.m3u/`) loses its extension the same way.
     * Null for what is not a game: our own shortcut, hidden files, plain folders.
     */
    fun stemOf(name: String, isDirectory: Boolean): String? {
        // Hidden names (the trash) and the half-copied files of a library move are not games.
        if (name.startsWith('.') || LibraryMoveRules.isPart(name)) return null
        if (isDirectory) return if (name.lowercase(Locale.ROOT).endsWith(".m3u")) name.dropLast(4) else null
        if (name.lowercase(Locale.ROOT).endsWith(".${DgmtxFile.EXTENSION}")) return null
        return MultiDisc.stripExtension(name).takeIf { it.isNotBlank() }
    }
}
