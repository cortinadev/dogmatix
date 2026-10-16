package com.cortinadev.dogmatix.util

import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Trust on first use for a RomM server with a self-signed (or private CA) certificate: the user
 * confirms the certificate's SHA-256 fingerprint once and, from then on, connections to that
 * origin (scheme, host and port) accept the system's certificates or exactly that one, also when
 * its name does not match the host (a server reached by IP). Pure, for the tests; the TLS side
 * is `RommTls`.
 */
object CertPin {

    /** The certificate trusted by hand for one server. */
    data class Pin(val origin: String, val fingerprint: String)

    /** What a server's certificate means for a connection to it. */
    enum class Verdict {
        /** The system trusts it and its name matches: nothing to ask. */
        SYSTEM,
        /** The certificate the user trusted for this origin. */
        PINNED,
        /** Not trusted, never asked about: ask. */
        UNTRUSTED,
        /** The origin has a trusted certificate, but this is another one: ask again. */
        CHANGED
    }

    fun sha256Hex(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    /** Lower-case hex without separators, so `AB:CD` and `abcd` compare equal. */
    fun normalize(fingerprint: String): String = fingerprint.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }

    fun isValid(fingerprint: String): Boolean = normalize(fingerprint).length == 64

    fun matches(expected: String, actual: String?): Boolean {
        if (actual == null) return false
        val a = normalize(expected)
        return a.length == 64 && a == normalize(actual)
    }

    /** `AB:CD:…` (32 pairs) for display. */
    fun format(fingerprint: String): String = normalize(fingerprint).uppercase().chunked(2).joinToString(":")

    /** The first pairs only, for a one-line hint. */
    fun short(fingerprint: String, pairs: Int = 8): String = format(fingerprint).split(':').take(pairs).joinToString(":") + "…"

    /** `https://host:port`, host in lower case and the port spelt out; null for anything but an https URL with a host. */
    fun originOf(url: String): String? = runCatching {
        val uri = URI(url.trim())
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        origin(host, if (uri.port > 0) uri.port else 443)
    }.getOrNull()

    fun origin(host: String, port: Int): String {
        val bare = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        return "https://" + (if (':' in bare) "[$bare]" else bare) + ":" + port
    }

    /** `host:port` of an origin, for the user. */
    fun hostOf(origin: String): String = origin.removePrefix("https://")

    /** True when [pin] lets a certificate with [leafFingerprint] through on [origin]. */
    fun accepts(pin: Pin?, origin: String?, leafFingerprint: String?): Boolean =
        pin != null && origin != null && origin == pin.origin && matches(pin.fingerprint, leafFingerprint)

    fun verdict(pin: Pin?, origin: String, leafFingerprint: String, systemTrusted: Boolean, hostnameMatches: Boolean): Verdict = when {
        systemTrusted && hostnameMatches -> Verdict.SYSTEM
        accepts(pin, origin, leafFingerprint) -> Verdict.PINNED
        pin != null && pin.origin == origin -> Verdict.CHANGED
        else -> Verdict.UNTRUSTED
    }

    /** Settings keep the pin as `origin fingerprint`. */
    fun encode(pin: Pin?): String = pin?.let { "${it.origin} ${normalize(it.fingerprint)}" }.orEmpty()

    /** "" or anything malformed = no pin. */
    fun decode(value: String?): Pin? {
        val parts = value?.trim()?.split(' ')?.filter { it.isNotEmpty() } ?: return null
        if (parts.size != 2 || !isValid(parts[1])) return null
        val origin = originOf(parts[0]) ?: return null
        return Pin(origin, normalize(parts[1]))
    }

    /**
     * The connection failed because the server's certificate was refused (unknown issuer, expired,
     * name that does not match), not because of the network.
     */
    fun isCertificateFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(8).any {
            it is SSLPeerUnverifiedException || it is CertificateException || it is CertPathValidatorException
        }

    fun isExpired(notAfter: Long, now: Long): Boolean = now > notAfter

    /**
     * A distinguished name (`CN=romm.lan,O=Home`) as `romm.lan · Home`: its common name and
     * organisation, or the whole name when it has neither.
     */
    fun displayName(dn: String): String {
        val parts = Regex("""(?<!\\),""").split(dn).map { it.trim() }
        fun value(key: String) = parts.firstOrNull { it.startsWith("$key=", ignoreCase = true) }
            ?.substringAfter('=')?.replace("\\,", ",")?.trim()?.takeIf { it.isNotEmpty() }
        return listOfNotNull(value("CN"), value("O")).distinct().joinToString(" · ").ifEmpty { dn }
    }
}
