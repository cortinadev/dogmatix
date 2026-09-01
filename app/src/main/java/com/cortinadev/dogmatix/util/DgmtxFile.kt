package com.cortinadev.dogmatix.util

import java.net.URLEncoder

/**
 * `.dgmtx` shortcut files: a tiny text file dropped into a platform folder so frontends
 * (ES-DE, Daijishō…) can list Dogmatix as an "emulator" entry. The file holds a
 * `dogmatix://library?…` deep link; lines starting with `#` are comments.
 *
 * Pure JVM (no Android types) so it can be unit-tested.
 */
object DgmtxFile {

    const val EXTENSION = "dgmtx"

    /**
     * Base display name of the shortcut files. Frontends show the file name as the game title,
     * so it reads as an action; the star groups it at one end of an alphabetical game list
     * instead of mixing with the games.
     */
    const val SHORTCUT_NAME = "★ Search for more games..."

    /** Cap when reading a shortcut handed over by another app: anything bigger is not ours. */
    const val MAX_BYTES = 64 * 1024

    /**
     * Fully qualified launch activity, the same in every build (`applicationIdSuffix` moves the
     * application id, not the class). Frontends need the component spelled out — ES-DE's find
     * rule wants `package/class`, and iiSU rebuilds the class from the package when given a
     * `%PACKAGE%` pattern — so the name lives here once instead of in one literal per frontend.
     */
    const val LAUNCH_ACTIVITY = "com.cortinadev.dogmatix.MainActivity"

    /** The `package/activity` component string frontends launch. */
    fun launchComponent(packageName: String): String = "$packageName/$LAUNCH_ACTIVITY"

    /** First `dogmatix://` link in [text], or null. Comments (`#`), blanks and a BOM are skipped. */
    fun extractLink(text: String): String? =
        text.removePrefix("\uFEFF")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .firstOrNull { it.startsWith("${DeepLinkParser.SCHEME}://", ignoreCase = true) }

    /** Deep link that opens the library filtered to [consoleId]. */
    fun libraryLink(consoleId: String): String =
        "${DeepLinkParser.SCHEME}://${DeepLinkParser.HOST_LIBRARY}?console=" + encode(consoleId)

    /** Body of the shortcut written into a platform folder. */
    fun contentForConsole(consoleId: String, consoleName: String): String = buildString {
        append("# Dogmatix shortcut — ").append(consoleName).append('\n')
        append("# Opening this file shows this platform's games in Dogmatix.\n")
        append(libraryLink(consoleId)).append('\n')
    }

    private fun encode(s: String): String = runCatching { URLEncoder.encode(s, "UTF-8") }.getOrDefault(s)
}
