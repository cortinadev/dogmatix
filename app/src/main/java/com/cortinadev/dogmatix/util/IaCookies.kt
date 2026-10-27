package com.cortinadev.dogmatix.util

import java.net.URLDecoder

/** The two archive.org cookies that make a session, as Dogmatix keeps them (raw values). */
data class IaCookiePair(val user: String, val sig: String) {
    /** The account's email: `logged-in-user` is it, URL-encoded. */
    val email: String get() = IaCookies.decode(user)
}

/**
 * Reading the session out of the sign-in page's cookies and writing it back on requests to
 * archive.org. Only `logged-in-user` and `logged-in-sig` are kept: nothing else of the page, and
 * never the password. Pure, for the tests.
 */
object IaCookies {

    const val USER = "logged-in-user"
    const val SIG = "logged-in-sig"

    /** A `Cookie` header (`a=b; c=d`, what `CookieManager.getCookie` gives) with both cookies; null otherwise. */
    fun parse(header: String?): IaCookiePair? {
        if (header.isNullOrBlank()) return null
        val values = header.split(';').mapNotNull { part ->
            val name = part.substringBefore('=', "").trim()
            val value = part.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty()) null else name to value
        }.toMap()
        val user = values[USER] ?: return null
        val sig = values[SIG] ?: return null
        return IaCookiePair(user, sig)
    }

    /** The stored values, when both are there. */
    fun of(user: String?, sig: String?): IaCookiePair? =
        if (user.isNullOrBlank() || sig.isNullOrBlank()) null else IaCookiePair(user.trim(), sig.trim())

    /** The `Cookie` header that carries the session. */
    fun header(pair: IaCookiePair): String = "$USER=${pair.user}; $SIG=${pair.sig}"

    internal fun decode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
}
