package com.cortinadev.dogmatix.util

import androidx.documentfile.provider.DocumentFile

/**
 * Known folder names used by popular frontends (ES-DE, RetroArch, Batocera, EmulationStation…)
 * for each console. When "separate by console" is enabled and the user picks a download
 * directory that already contains e.g. a `gb` or `psx` folder, we reuse it instead of creating
 * a new "Gameboy" / "Playstation" one next to it.
 *
 * Keys are the console part of the console id (e.g. `nintendo_gameboy` → `gameboy`).
 * Aliases are compared after [normalize], so case, spaces, hyphens and underscores are ignored.
 */
object ConsoleFolderAliases {

    private val aliases: Map<String, List<String>> = mapOf(
        "gameboy" to listOf("gb", "gameboy", "game boy"),
        "gameboy_color" to listOf("gbc", "gameboycolor", "game boy color"),
        "gameboy_advance" to listOf("gba", "gameboyadvance", "game boy advance"),
        "nintendo_entertainment_system" to listOf("nes", "famicom", "fc"),
        "super_nintendo_entertainment_system" to listOf("snes", "sfc", "superfamicom", "supernintendo"),
        "nintendo_64" to listOf("n64", "nintendo64"),
        "gamecube" to listOf("gc", "ngc", "gamecube"),
        "nintendo_ds" to listOf("nds", "ds"),
        "nintendo_3ds" to listOf("3ds", "n3ds"),
        "playstation" to listOf("psx", "ps1", "ps", "playstation"),
        "playstation_2" to listOf("ps2", "playstation2"),
        "playstation_portable" to listOf("psp"),
        "master_system" to listOf("mastersystem", "sms", "segamastersystem"),
        "genesis" to listOf("genesis", "megadrive", "md", "segagenesis", "segamegadrive"),
        "dreamcast" to listOf("dreamcast", "dc", "segadreamcast"),
        "cd" to listOf("segacd", "megacd"),
    )

    fun normalize(name: String): String =
        name.lowercase().replace(Regex("[\\s_\\-]"), "")

    /** Console part of a console id: `nintendo_gameboy` → `gameboy`. */
    private fun consoleKey(consoleId: String): String =
        if (consoleId.contains("_")) consoleId.substringAfter("_") else consoleId

    /** Built-in aliases for [consoleId] (without the configured ones), in listing order. */
    fun defaultAliasesFor(consoleId: String): List<String> = aliases[consoleKey(consoleId)].orEmpty()

    /**
     * All normalized names that count as a match for [consoleId]: the default folder name
     * ([ConsoleFormatter.getConsoleFolderName]), the raw key, the short name, the aliases
     * configured on the console ([ConsoleAliasRegistry]) and the built-in ones.
     */
    fun candidatesFor(consoleId: String): Set<String> {
        val key = consoleKey(consoleId)
        val defaults = listOf(ConsoleFormatter.getConsoleFolderName(consoleId), key, ConsoleFormatter.getConsoleShortName(consoleId))
        return (defaults + ConsoleAliasRegistry.aliasesFor(consoleId) + defaultAliasesFor(consoleId)).map(::normalize).toSet()
    }

    fun matches(consoleId: String, folderName: String): Boolean =
        normalize(folderName) in candidatesFor(consoleId)

    /**
     * Returns the display name of an existing sub-directory of [baseDir] that matches
     * [consoleId], or null if none exists (the caller can then create the default one).
     */
    fun findExistingFolder(baseDir: DocumentFile, consoleId: String): String? {
        val existing = try {
            baseDir.listFiles().filter { it.isDirectory }.mapNotNull { it.name }
        } catch (_: Exception) {
            return null
        }
        return pickExistingFolder(existing, consoleId)
    }

    /**
     * Every folder in [folderNames] that matches [consoleId], best first: an exact
     * (case-insensitive) match with the default folder name, then the aliases in listing order.
     */
    fun matchingFolders(folderNames: List<String>, consoleId: String): List<String> {
        val candidates = candidatesFor(consoleId)
        val defaultName = normalize(ConsoleFormatter.getConsoleFolderName(consoleId))
        return folderNames.filter { normalize(it) in candidates }
            .sortedByDescending { normalize(it) == defaultName }
    }

    /** Picks, among [folderNames], the one that matches [consoleId] (or null). See [matchingFolders]. */
    fun pickExistingFolder(folderNames: List<String>, consoleId: String): String? =
        matchingFolders(folderNames, consoleId).firstOrNull()
}
