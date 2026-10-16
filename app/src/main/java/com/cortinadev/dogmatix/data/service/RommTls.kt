package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.CertPin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLConnection
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

private const val TAG = "RommTls"

/** What a server presented, for the "trust this certificate?" question. */
data class ServerCertificate(
    /** `https://host:port` the certificate was read from. */
    val origin: String,
    val subject: String,
    val issuer: String,
    val notBefore: Long,
    val notAfter: Long,
    val fingerprint: String,
    val systemTrusted: Boolean,
    val hostnameMatches: Boolean
)

/**
 * TLS trust for the RomM server: the system's certificates, plus the one the user trusted by hand
 * (Settings → RomM → Test connection → "Trust this certificate"), on that origin only. The trust
 * manager reads the peer's host and port from the handshake, so the exception never reaches
 * another server, not even through a redirect. Reusable by anything that talks to RomM:
 * - [secure] for an `HttpURLConnection` (API, downloads, uploads); a no-op for other servers;
 * - [socketFactory] / [hostnameVerifier] / [trustManager] for a client built elsewhere;
 * - [imageClient] for Coil (cover images).
 */
@Singleton
class RommTls @Inject constructor(private val settingsRepository: SettingsRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var pin: CertPin.Pin? = null
    private val loaded = CompletableDeferred<Unit>()

    /** The certificate trusted by hand, if any. */
    val pinned: Flow<CertPin.Pin?> = settingsRepository.rommCertPin.map(CertPin::decode).distinctUntilChanged()

    init {
        scope.launch {
            pinned.collect {
                pin = it
                loaded.complete(Unit)
            }
        }
    }

    /** Waits for the stored pin (the first read of Settings), so the first request after a start is not refused. */
    suspend fun awaitLoaded() {
        withTimeoutOrNull(3_000L) { loaded.await() }
    }

    private val system: X509TrustManager by lazy {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
            init(null as KeyStore?)
            trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }

    private val defaultVerifier: HostnameVerifier by lazy { HttpsURLConnection.getDefaultHostnameVerifier() }

    val trustManager: X509TrustManager by lazy { PinAwareTrustManager() }

    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
    }

    /** The system's check, or the trusted certificate on its own origin whatever name it carries. */
    val hostnameVerifier: HostnameVerifier = HostnameVerifier { host, session ->
        defaultVerifier.verify(host, session) || accepts(host, session.peerPort, leafOf(session))
    }

    /** Lets [connection] accept the trusted certificate when it goes to the trusted origin; nothing otherwise. */
    fun secure(connection: URLConnection) {
        val https = connection as? HttpsURLConnection ?: return
        val current = pin ?: return
        if (CertPin.originOf(https.url.toString()) != current.origin) return
        https.sslSocketFactory = socketFactory
        https.hostnameVerifier = hostnameVerifier
    }

    /** OkHttp client for Coil: system trust everywhere, plus the trusted certificate on its origin. */
    fun imageClient(): OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(socketFactory, trustManager)
        .hostnameVerifier(hostnameVerifier)
        .build()

    /**
     * Reads the certificate [url] presents with a bare TLS handshake: no HTTP request goes out, so
     * no credentials either. Null when the server cannot be reached over HTTPS.
     */
    suspend fun inspect(url: String): ServerCertificate? = withContext(Dispatchers.IO) {
        runCatching { probe(url) }.onFailure { Log.w(TAG, "Could not read the certificate of $url: ${it.message}") }.getOrNull()
    }

    suspend fun trust(certificate: ServerCertificate) {
        val next = CertPin.Pin(certificate.origin, CertPin.normalize(certificate.fingerprint))
        // In memory first: a retest right after, or whoever reacts to the saved pin (the RomM
        // catalogue reads the server again), must not race the settings collector.
        pin = next
        settingsRepository.setRommCertPin(CertPin.encode(next))
    }

    suspend fun forget() {
        pin = null
        settingsRepository.setRommCertPin("")
    }

    private fun accepts(host: String?, port: Int?, leaf: Certificate?): Boolean {
        val current = pin ?: return false
        if (host.isNullOrBlank() || port == null || port <= 0 || leaf == null) return false
        return CertPin.accepts(current, CertPin.origin(host, port), CertPin.sha256Hex(leaf.encoded))
    }

    private fun leafOf(session: SSLSession?): Certificate? = runCatching { session?.peerCertificates?.firstOrNull() }.getOrNull()

    private fun systemCheck(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?, engine: SSLEngine?) {
        val ext = system as? X509ExtendedTrustManager
        when {
            ext != null && socket != null -> ext.checkServerTrusted(chain, authType, socket)
            ext != null && engine != null -> ext.checkServerTrusted(chain, authType, engine)
            else -> system.checkServerTrusted(chain, authType)
        }
    }

    /** Defers to the system and, when it refuses, accepts the trusted certificate on the trusted origin. */
    private inner class PinAwareTrustManager : X509ExtendedTrustManager() {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            try {
                systemCheck(chain, authType, socket, null)
            } catch (e: CertificateException) {
                val ssl = socket as? SSLSocket
                val session = runCatching { ssl?.handshakeSession }.getOrNull()
                val host = session?.peerHost ?: ssl?.inetAddress?.hostAddress
                val port = session?.peerPort?.takeIf { it > 0 } ?: ssl?.port
                if (!accepts(host, port, chain?.firstOrNull())) throw e
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            try {
                systemCheck(chain, authType, null, engine)
            } catch (e: CertificateException) {
                if (!accepts(engine?.peerHost, engine?.peerPort, chain?.firstOrNull())) throw e
            }
        }

        /** Without the peer there is no origin to match: system trust only. */
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            system.checkServerTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
            system.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
            system.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            system.checkClientTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }

    private fun probe(url: String): ServerCertificate? {
        val origin = CertPin.originOf(url) ?: return null
        val uri = URI(url.trim())
        val host = uri.host.removePrefix("[").removeSuffix("]")
        val port = if (uri.port > 0) uri.port else 443
        var chain: Array<out X509Certificate>? = null
        var systemTrusted = false
        // Records what the server shows and whether the system would take it; refuses nothing.
        val capture = object : X509ExtendedTrustManager() {
            override fun checkServerTrusted(c: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
                chain = c
                systemTrusted = runCatching { systemCheck(c, authType, socket, null) }.isSuccess
            }
            override fun checkServerTrusted(c: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
                chain = c
                systemTrusted = runCatching { systemCheck(c, authType, null, engine) }.isSuccess
            }
            override fun checkServerTrusted(c: Array<out X509Certificate>?, authType: String?) {
                chain = c
                systemTrusted = runCatching { system.checkServerTrusted(c, authType) }.isSuccess
            }
            override fun checkClientTrusted(c: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
            override fun checkClientTrusted(c: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
            override fun checkClientTrusted(c: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capture), null) }
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), 10_000)
        raw.soTimeout = 10_000
        (context.socketFactory.createSocket(raw, host, port, true) as SSLSocket).use { ssl ->
            ssl.startHandshake()
            val leaf = chain?.firstOrNull() ?: return null
            val nameMatches = runCatching { defaultVerifier.verify(host, ssl.session) }.getOrDefault(false)
            return ServerCertificate(
                origin = origin,
                subject = leaf.subjectX500Principal.name,
                issuer = leaf.issuerX500Principal.name,
                notBefore = leaf.notBefore.time,
                notAfter = leaf.notAfter.time,
                fingerprint = CertPin.sha256Hex(leaf.encoded),
                systemTrusted = systemTrusted,
                hostnameMatches = nameMatches
            )
        }
    }
}
