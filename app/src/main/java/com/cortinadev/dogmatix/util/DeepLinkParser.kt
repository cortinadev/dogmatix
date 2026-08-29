package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.state.LibraryFilterRequest
import java.net.URI
import java.net.URLDecoder

/**
 * `dogmatix://library?console=nintendo_snes&region=USA,Europe&lang=En&type=GAME&q=mario&fav=1`
 *
 * - `console`: console ids (as in Sources), comma-separated.
 * - `region`, `lang`, `type`, `filetype`, `tag`: library tags, comma-separated. They all end up
 *   in the same tag set; the kind is derived from the value (see `TagCategorizer`).
 * - `q`: search text. `fav`: `1`/`true` shows favourites only, `0`/`false` all games.
 *
 * Pure JVM (no `android.net.Uri`) so it can be unit-tested.
 */
object DeepLinkParser {

    const val SCHEME = "dogmatix"
    const val HOST_LIBRARY = "library"

    private val TAG_PARAMS = setOf("region", "lang", "language", "type", "filetype", "tag")

    fun parse(uri: String?): LibraryFilterRequest? {
        if (uri.isNullOrBlank()) return null
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
        if (!parsed.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!parsed.host.equals(HOST_LIBRARY, ignoreCase = true)) return null
        val params = queryParams(parsed.rawQuery)

        val consoles = params.values("console")
        val tags = TAG_PARAMS.flatMap { params.values(it) }.toSet()
        val query = params["q"]?.trim()?.takeIf { it.isNotEmpty() }
        val fav = params["fav"]?.lowercase()?.let { it == "1" || it == "true" || it == "yes" }
        return LibraryFilterRequest(consoles = consoles, tags = tags, query = query, favouritesOnly = fav)
    }

    private fun Map<String, String>.values(key: String): Set<String> =
        this[key]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    private fun queryParams(rawQuery: String?): Map<String, String> =
        rawQuery.orEmpty().split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val eq = pair.indexOf('=')
                val key = if (eq < 0) pair else pair.substring(0, eq)
                val value = if (eq < 0) "" else pair.substring(eq + 1)
                decode(key).lowercase() to decode(value)
            }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
