package com.cortinadev.dogmatix.util

import java.net.URL

/**
 * Following a redirect by hand with the archive.org session: `archive.org/download/…` sends the
 * client to a data node (`ia601900.us.archive.org`, `dn721601.ca.archive.org`) that checks the
 * session too, and which headers each HTTP client keeps across hosts is not something to rely on.
 * The credentials only go on to another archive.org host over HTTPS. Pure, for the tests.
 */
object AuthRedirects {

    const val MAX_REDIRECTS = 5
    val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    private val credentialHeaders = setOf("cookie", "authorization")

    fun isArchiveHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase()?.trimEnd('.') ?: return false
        return h == IaSource.HOST || h.endsWith("." + IaSource.HOST)
    }

    /** An http(s) address on archive.org or one of its hosts. */
    fun isArchiveUrl(url: String): Boolean = isArchiveHost(MirrorUrls.hostOf(url))

    private fun isHttps(url: String): Boolean = url.trim().startsWith("https://", ignoreCase = true)

    /** The session may follow a redirect from [from] to [location] (both resolved). */
    fun keepAuth(from: String, location: String): Boolean =
        isArchiveUrl(from) && isArchiveUrl(location) && isHttps(location)

    /** [location] of a redirect, resolved against the address that sent it; null when it cannot be read. */
    fun resolve(base: String, location: String): String? =
        runCatching { URL(URL(base), location.trim()).toString() }.getOrNull()

    fun carriesCredentials(headers: Map<String, String>): Boolean = headers.keys.any { it.lowercase() in credentialHeaders }

    /** [headers] without the session, for a redirect that leaves archive.org. */
    fun withoutCredentials(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { it.lowercase() !in credentialHeaders }
}
