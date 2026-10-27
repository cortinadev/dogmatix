package com.cortinadev.dogmatix.data.service

import android.util.Log
import android.webkit.CookieManager
import com.cortinadev.dogmatix.BuildConfig
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.AuthRedirects
import com.cortinadev.dogmatix.util.IaAuthJson
import com.cortinadev.dogmatix.util.IaCookiePair
import com.cortinadev.dogmatix.util.IaCookies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A saved archive.org session: the account it belongs to. */
data class IaSession(val email: String)

/** What archive.org said about the saved session. */
sealed interface IaSessionCheck {
    data class Valid(val name: String) : IaSessionCheck
    /** No session, or archive.org no longer accepts it. */
    data object Expired : IaSessionCheck
    /** archive.org could not be asked (no network, an error page). */
    data object Failed : IaSessionCheck
}

/**
 * The Internet Archive session, for the items that need an account. The user signs in on the
 * real archive.org page (Settings → Internet Archive, a WebView); Dogmatix keeps only the two
 * cookies that make the session (`logged-in-user`, `logged-in-sig`), never the password, and sends
 * them only to archive.org over HTTPS. Every request to archive.org also says who is asking
 * ([userAgent]), as its guidance for automated access asks. Nothing of the session is logged.
 */
@Singleton
class IaAuth @Inject constructor(private val settingsRepository: SettingsRepository) {

    private val cookies: Flow<IaCookiePair?> =
        settingsRepository.iaCookies.map { (user, sig) -> IaCookies.of(user, sig) }.distinctUntilChanged()

    val session: Flow<IaSession?> = cookies.map { pair -> pair?.let { IaSession(it.email) } }

    suspend fun hasSession(): Boolean = cookies.first() != null

    /** Requests to [url] go with [headers]: archive.org and its data nodes. */
    fun appliesTo(url: String): Boolean = AuthRedirects.isArchiveUrl(url)

    fun userAgent(): String = userAgent(BuildConfig.VERSION_NAME)

    /** For a request to [url]: the `User-Agent` always, the session cookies when signed in and over HTTPS. */
    suspend fun headers(url: String): Map<String, String> = headersFor(BuildConfig.VERSION_NAME, url, cookies.first())

    /**
     * Keeps the session out of the sign-in page's cookies ([header], what `CookieManager.getCookie`
     * gives for archive.org); false while it does not hold both cookies yet.
     */
    suspend fun saveFromCookieHeader(header: String?): Boolean {
        val pair = IaCookies.parse(header) ?: return false
        settingsRepository.setIaCookies(pair.user, pair.sig)
        return true
    }

    /** Asks archive.org who the saved session belongs to. */
    suspend fun check(): IaSessionCheck {
        val pair = cookies.first() ?: return IaSessionCheck.Expired
        return withContext(Dispatchers.IO) {
            val response = runCatching { JsonHttp.request("GET", WHOAMI_URL, headersFor(BuildConfig.VERSION_NAME, WHOAMI_URL, pair)) }
                .onFailure { Log.w(TAG, "whoami failed: ${it.javaClass.simpleName}") }
                .getOrNull() ?: return@withContext IaSessionCheck.Failed
            when {
                response.code == 401 || response.code == 403 -> IaSessionCheck.Expired
                response.code !in 200..299 -> IaSessionCheck.Failed
                else -> IaAuthJson.whoami(response.code, response.body)?.let { IaSessionCheck.Valid(it.name ?: pair.email) }
                    ?: IaSessionCheck.Expired
            }
        }
    }

    /** Forgets the session here and in the WebView's cookie jar (the sign-in page is the only WebView). */
    suspend fun logout() {
        settingsRepository.setIaCookies("", "")
        clearWebCookies()
    }

    /** Empties the WebView's cookie jar: after sign-in the only copy of the session is the saved one. */
    suspend fun clearWebCookies() = withContext(Dispatchers.Main) {
        runCatching {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        }.onFailure { Log.w(TAG, "Could not clear the WebView cookies: ${it.javaClass.simpleName}") }
        Unit
    }

    companion object {
        private const val TAG = "IaAuth"
        const val PROJECT_URL = "https://github.com/cortinadev/dogmatix"
        const val LOGIN_URL = "https://archive.org/account/login"
        /** Where the sign-in page sets the cookies. */
        const val COOKIE_URL = "https://archive.org"
        const val WHOAMI_URL = "https://archive.org/services/user.php?op=whoami"

        /** Tool name, version and where to find it, as archive.org asks of automated clients. */
        fun userAgent(versionName: String): String = "Dogmatix/$versionName (+$PROJECT_URL)"

        /** Headers for a request to [url]: none off archive.org; the session only over HTTPS. */
        fun headersFor(versionName: String, url: String, cookies: IaCookiePair?): Map<String, String> {
            if (!AuthRedirects.isArchiveUrl(url)) return emptyMap()
            return buildMap {
                put("User-Agent", userAgent(versionName))
                if (cookies != null && url.trim().startsWith("https://", ignoreCase = true)) put("Cookie", IaCookies.header(cookies))
            }
        }
    }
}
