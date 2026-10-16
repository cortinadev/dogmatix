package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class CertPinTest {

    /** Self-signed test certificate (public part only): CN=romm.test, O=Dogmatix Test. */
    private val pem = """
        -----BEGIN CERTIFICATE-----
        MIIBrjCCAVOgAwIBAgIULNS9R/438/Ylja85iF6kVyUlMm0wCgYIKoZIzj0EAwIw
        LDESMBAGA1UEAwwJcm9tbS50ZXN0MRYwFAYDVQQKDA1Eb2dtYXRpeCBUZXN0MB4X
        DTI2MTAxNDE3MjM1NFoXDTM2MTAxMTE3MjM1NFowLDESMBAGA1UEAwwJcm9tbS50
        ZXN0MRYwFAYDVQQKDA1Eb2dtYXRpeCBUZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0D
        AQcDQgAEevg2pBZzPzsJm1xB6+NTGi7N0Yv9UK5PoaiHgJavFA/rAD/L7E5EF8ag
        1jjl5fbpRoRPfD6jH/YTxkfgS9Ts/qNTMFEwHQYDVR0OBBYEFPwQZ20H3pr4vgQU
        9zHzzw5fmzzaMB8GA1UdIwQYMBaAFPwQZ20H3pr4vgQU9zHzzw5fmzzaMA8GA1Ud
        EwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSQAwRgIhAOO2IAdW+4KbBNMBrt0LIluc
        8Hzmq/fuuDCUGBEvccfyAiEA0ZltkHPen2AK+530aPzNfmKmxd4bsPTDXgdFaVoM
        sn8=
        -----END CERTIFICATE-----
    """.trimIndent()

    /** What `openssl x509 -fingerprint -sha256` prints for it. */
    private val opensslFingerprint = "63:01:3A:F9:29:C9:BE:F9:69:A8:AD:40:30:BA:E9:86:93:A7:D2:3D:5C:D8:FC:D5:33:47:67:5D:1D:BC:8C:0C"

    private val certificate: X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate

    private val fp = CertPin.sha256Hex(certificate.encoded)

    @Test
    fun `fingerprint is the SHA-256 of the DER certificate`() {
        assertEquals(CertPin.normalize(opensslFingerprint), fp)
        assertEquals(opensslFingerprint, CertPin.format(fp))
        assertTrue(CertPin.matches(opensslFingerprint, fp))
        assertTrue(CertPin.matches(fp, opensslFingerprint.lowercase()))
        assertFalse(CertPin.matches(fp, fp.replaceFirst('6', '7')))
        assertFalse(CertPin.matches("abcd", "abcd"))
        assertFalse(CertPin.matches(fp, null))
        assertEquals("63:01:3A:F9…", CertPin.short(fp, 4))
    }

    @Test
    fun `origin is scheme host and port`() {
        assertEquals("https://romm.lan:443", CertPin.originOf("https://Romm.LAN"))
        assertEquals("https://romm.lan:443", CertPin.originOf("https://romm.lan/api/roms"))
        assertEquals("https://192.168.1.10:8443", CertPin.originOf(" https://192.168.1.10:8443/ "))
        assertEquals("https://[::1]:8443", CertPin.originOf("https://[::1]:8443"))
        assertEquals("https://[::1]:8443", CertPin.origin("::1", 8443))
        assertEquals("https://192.168.1.10:8443", CertPin.origin("192.168.1.10", 8443))
        assertNull(CertPin.originOf("http://romm.lan"))
        assertNull(CertPin.originOf("romm.lan"))
        assertNull(CertPin.originOf(""))
        assertEquals("192.168.1.10:8443", CertPin.hostOf("https://192.168.1.10:8443"))
    }

    @Test
    fun `a pin only lets its certificate through on its origin`() {
        val pin = CertPin.Pin("https://192.168.1.10:8443", fp)
        assertTrue(CertPin.accepts(pin, "https://192.168.1.10:8443", fp))
        assertFalse(CertPin.accepts(pin, "https://192.168.1.10:443", fp))
        assertFalse(CertPin.accepts(pin, "https://192.168.1.11:8443", fp))
        assertFalse(CertPin.accepts(pin, "https://192.168.1.10:8443", "00".repeat(32)))
        assertFalse(CertPin.accepts(null, "https://192.168.1.10:8443", fp))
        assertFalse(CertPin.accepts(pin, null, fp))
    }

    @Test
    fun `verdict tells what to ask`() {
        val origin = "https://romm.lan:443"
        val pin = CertPin.Pin(origin, fp)
        assertEquals(CertPin.Verdict.SYSTEM, CertPin.verdict(null, origin, fp, systemTrusted = true, hostnameMatches = true))
        assertEquals(CertPin.Verdict.UNTRUSTED, CertPin.verdict(null, origin, fp, systemTrusted = false, hostnameMatches = true))
        // A public certificate reached by IP fails on the name: still a question.
        assertEquals(CertPin.Verdict.UNTRUSTED, CertPin.verdict(null, origin, fp, systemTrusted = true, hostnameMatches = false))
        assertEquals(CertPin.Verdict.PINNED, CertPin.verdict(pin, origin, fp, systemTrusted = false, hostnameMatches = false))
        assertEquals(CertPin.Verdict.CHANGED, CertPin.verdict(pin, origin, "11".repeat(32), systemTrusted = false, hostnameMatches = true))
        // A pin for another server does not make this one "changed".
        assertEquals(CertPin.Verdict.UNTRUSTED, CertPin.verdict(CertPin.Pin("https://other:443", fp), origin, "11".repeat(32), false, true))
    }

    @Test
    fun `pin survives settings round trip and junk reads as none`() {
        val pin = CertPin.Pin("https://romm.lan:8443", fp)
        assertEquals(pin, CertPin.decode(CertPin.encode(pin)))
        assertEquals("", CertPin.encode(null))
        assertNull(CertPin.decode(""))
        assertNull(CertPin.decode(null))
        assertNull(CertPin.decode("https://romm.lan:8443"))
        assertNull(CertPin.decode("https://romm.lan:8443 abcd"))
        assertNull(CertPin.decode("http://romm.lan $fp"))
        assertEquals(pin, CertPin.decode("https://ROMM.lan:8443 ${CertPin.format(fp)}"))
    }

    @Test
    fun `certificate failures are told from network ones`() {
        assertTrue(CertPin.isCertificateFailure(SSLHandshakeException("Chain validation failed").apply {
            initCause(CertificateException("Trust anchor for certification path not found.", CertPathValidatorException("no anchor")))
        }))
        assertTrue(CertPin.isCertificateFailure(SSLPeerUnverifiedException("Hostname 192.168.1.10 not verified")))
        assertTrue(CertPin.isCertificateFailure(IOException("wrapped", CertPathValidatorException("expired"))))
        assertFalse(CertPin.isCertificateFailure(SocketTimeoutException("timeout")))
        assertFalse(CertPin.isCertificateFailure(SSLHandshakeException("Connection reset")))
        assertFalse(CertPin.isCertificateFailure(IOException("HTTP 401")))
    }

    @Test
    fun `distinguished names read as common name and organisation`() {
        assertEquals("romm.test · Dogmatix Test", CertPin.displayName(certificate.subjectX500Principal.name))
        assertEquals("Doe, John · Acme", CertPin.displayName("CN=Doe\\, John,O=Acme,C=ES"))
        assertEquals("Caddy Local Authority", CertPin.displayName("CN=Caddy Local Authority"))
        assertEquals("OU=Lab,C=ES", CertPin.displayName("OU=Lab,C=ES"))
        assertTrue(CertPin.isExpired(certificate.notAfter.time, certificate.notAfter.time + 1))
        assertFalse(CertPin.isExpired(certificate.notAfter.time, certificate.notBefore.time))
    }
}
