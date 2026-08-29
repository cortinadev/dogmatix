package com.cortinadev.dogmatix.util

import java.net.URLEncoder

/**
 * A RomM platform as a library source: `romm://<platform slug>` (the server comes from
 * Settings → RomM). Pure helpers shared by the scraper, the downloader and the Sources UI.
 */
object RommSource {

    const val SCHEME = "romm://"

    fun isSource(url: String): Boolean = url.trim().startsWith(SCHEME, ignoreCase = true)

    /** `romm://psp` → `psp`; null when [url] is not a RomM source or has no slug. */
    fun slugOf(url: String): String? =
        url.trim().takeIf { isSource(it) }?.substring(SCHEME.length)?.trim('/', ' ')?.lowercase()?.takeIf { it.isNotEmpty() }

    fun sourceFor(slug: String): String = SCHEME + slug.trim().lowercase()

    /** Where RomM serves a ROM file. */
    fun downloadUrl(baseUrl: String, romId: Int, fileName: String): String =
        "${baseUrl.trimEnd('/')}/api/roms/$romId/content/${URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")}"

    /** True when [downloadUrl] points at the configured RomM server (so auth headers apply). */
    fun isDownloadFrom(baseUrl: String, downloadUrl: String): Boolean {
        val base = baseUrl.trim().trimEnd('/')
        return base.isNotEmpty() && downloadUrl.startsWith("$base/api/roms/", ignoreCase = true)
    }
}
