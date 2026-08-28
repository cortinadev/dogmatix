package com.cortinadev.dogmatix.util

/**
 * Keys used by the on-disk library index to decide whether a game is already owned.
 *
 * A key is `scope|name` where `name` is the lower-cased file name (a second key holds the name
 * without extension) and `scope` says *where* the file lives, so the same file name under
 * `snes/` and `gbc/` never marks both consoles:
 *  - `""` — directly in the download root (no per-console subfolders);
 *  - a normalised first-level folder name of the download root (`gba`, `gameboyadvance`…);
 *  - [customScope] — the custom per-console directory picked in Sources;
 *  - [consoleScope] — a download that just finished for that console (before the disk rescan).
 */
object LibraryKeys {

    const val ROOT_SCOPE = ""

    fun folderScope(folderName: String): String = ConsoleFolderAliases.normalize(folderName)
    fun customScope(consoleId: String): String = "custom:$consoleId"
    fun consoleScope(consoleId: String): String = "console:$consoleId"

    /** Both keys (full name and base name) for a file found under [scope]. */
    fun keysFor(scope: String, fileName: String): List<String> {
        val name = FileParsingUtils.decodeUrlEncodedFileName(fileName).lowercase()
        return listOf("$scope|$name", "$scope|${baseName(name)}")
    }

    /** Every scope whose files count as owned for [consoleId]. */
    fun scopesFor(consoleId: String): Set<String> =
        ConsoleFolderAliases.candidatesFor(consoleId) + ROOT_SCOPE + customScope(consoleId) + consoleScope(consoleId)

    fun isOwned(consoleId: String, fileName: String, keys: Set<String>): Boolean {
        if (keys.isEmpty()) return false
        val name = FileParsingUtils.decodeUrlEncodedFileName(fileName).lowercase()
        val base = baseName(name)
        return scopesFor(consoleId).any { scope -> "$scope|$name" in keys || "$scope|$base" in keys }
    }

    fun baseName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }
}
