package com.cortinadev.dogmatix.util

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** A source URL shortened for one line of the scan report. */
object SourceLabels {
    private val displayName = Regex("""[?&]dn=([^&]+)""", RegexOption.IGNORE_CASE)

    fun short(url: String): String {
        val u = url.trim()
        return when {
            u.startsWith("magnet:", ignoreCase = true) ->
                displayName.find(u)?.groupValues?.get(1)?.let { decode(it, plusIsSpace = true) }?.takeIf { it.isNotBlank() }
                    ?: MagnetLinks.infoHashKey(u)?.let { "btih:${it.take(12)}" }
                    ?: u.take(40)
            u.endsWith(".torrent") && !MirrorUrls.isWeb(u) -> u.substringAfterLast('/')
            MirrorUrls.isWeb(u) -> {
                val host = MirrorUrls.hostOf(u).orEmpty()
                val path = u.substringAfter("://").substringAfter('/', "").substringBefore('?').trimEnd('/')
                val last = decode(path.substringAfterLast('/'))
                when {
                    path.isEmpty() -> host
                    !path.contains('/') -> "$host/$last"
                    else -> "$host/…/$last"
                }
            }
            else -> u
        }
    }

    /** Magnet names are form-encoded (`+` is a space); paths keep a literal `+`. */
    private fun decode(value: String, plusIsSpace: Boolean = false): String = runCatching {
        URLDecoder.decode(if (plusIsSpace) value else value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrDefault(value)
}
