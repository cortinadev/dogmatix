package com.cortinadev.dogmatix.ui.screens.settings.ia

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.view.ViewGroup
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.IaAuth
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.SidePanelWidth
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.util.AuthRedirects
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class IaLoginViewModel @Inject constructor(private val iaAuth: IaAuth) : ViewModel() {
    private val _done = MutableStateFlow(false)
    /** The session is saved: the screen goes back to Settings. */
    val done: StateFlow<Boolean> = _done.asStateFlow()
    private var saving = false

    /**
     * The cookies the page has for archive.org right now; once the session is among them it is
     * saved and the WebView's jar emptied, so the only copy is the saved one.
     */
    fun onCookies(header: String?) {
        // Called on the main thread (page callbacks and the screen's poll), so a flag is enough.
        if (saving || _done.value || header.isNullOrBlank()) return
        saving = true
        viewModelScope.launch {
            try {
                if (iaAuth.saveFromCookieHeader(header)) {
                    // Before leaving: once the screen is gone this scope is cancelled.
                    iaAuth.clearWebCookies()
                    _done.value = true
                }
            } finally {
                saving = false
            }
        }
    }
}

/**
 * Settings → Internet Archive: the real archive.org sign-in page in a WebView. Dogmatix never
 * sees the password; when the page has set its session cookies they are kept and this screen
 * closes. Cancel (focused first) or B go back to Settings without saving anything; the page
 * takes the D-pad and A as a web page does. The page never leaves archive.org: Google's sign-in
 * refuses embedded WebViews (a blank page or "disallowed_useragent"), so a navigation or a
 * pop-up towards another host is dropped, the login page comes back and the panel says to use
 * email and password instead. In landscape the title, the note and Cancel sit in a
 * side panel ([SidePanelWidth], like the Library filters) so the page keeps the full height; in
 * portrait they are a bar above it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun IaLoginScreen(navController: NavController, viewModel: IaLoginViewModel = hiltViewModel()) {
    val done by viewModel.done.collectAsState()
    val context = LocalContext.current
    val cancelFocus = rememberInitialFocus()

    // Only while this screen is on top: a second press must not take Settings away too.
    val leave = { if (navController.currentBackStackEntry?.destination?.route == NavRoutes.IaLogin.route) navController.popBackStack() }
    BackHandler { leave() }
    val doneMessage = stringResource(R.string.ia_login_done)
    LaunchedEffect(done) {
        if (!done) return@LaunchedEffect
        ToastUtil.showSuccess(context, doneMessage)
        leave()
    }
    // The page signs in by script, often without a new page load: look at the jar every second too.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            viewModel.onCookies(runCatching { CookieManager.getInstance().getCookie(IaAuth.COOKIE_URL) }.getOrNull())
        }
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var outsideBlocked by remember { mutableStateOf(false) }

    val heading: @Composable () -> Unit = {
        Text(
            stringResource(R.string.settings_ia_session),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.ia_login_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (outsideBlocked) {
            Text(
                stringResource(R.string.ia_login_outside_blocked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
    val cancel: @Composable () -> Unit = {
        DialogButton(stringResource(R.string.dialog_cancel), onClick = { leave() }, initialFocus = cancelFocus)
    }
    val page: @Composable (Modifier) -> Unit = { modifier ->
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                WebView(ctx).apply webView@{
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(this@webView, true)
                    }
                    // Anything outside archive.org (Google's sign-in) is not loaded: back to the login page.
                    fun stayOnArchive(view: WebView, request: WebResourceRequest?): Boolean {
                        val url = request?.url ?: return false
                        if (AuthRedirects.isArchiveHost(url.host)) return false
                        outsideBlocked = true
                        if (view.url == null || !AuthRedirects.isArchiveUrl(view.url.orEmpty())) view.loadUrl(IaAuth.LOGIN_URL)
                        return true
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest?): Boolean =
                            stayOnArchive(view, request)
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url != null && AuthRedirects.isArchiveUrl(url)) outsideBlocked = false
                            viewModel.onCookies(CookieManager.getInstance().getCookie(IaAuth.COOKIE_URL))
                        }
                    }
                    // Pop-ups (window.open) land in a throwaway view whose only job is to route the URL.
                    settings.setSupportMultipleWindows(true)
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                            val popup = WebView(view.context)
                            popup.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(popupView: WebView, request: WebResourceRequest?): Boolean {
                                    val url = request?.url
                                    if (url != null && AuthRedirects.isArchiveHost(url.host)) view.loadUrl(url.toString())
                                    else { outsideBlocked = true }
                                    // Not inside its own callback: Chromium logs a call on a destroyed view.
                                    popupView.post { popupView.destroy() }
                                    return true
                                }
                            }
                            (resultMsg.obj as WebView.WebViewTransport).webView = popup
                            resultMsg.sendToTarget()
                            return true
                        }
                    }
                    isFocusable = true
                    isFocusableInTouchMode = true
                    loadUrl(IaAuth.LOGIN_URL)
                }
            },
            onRelease = { it.stopLoading(); it.destroy() }
        )
    }

    if (isLandscape) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
            Column(
                modifier = Modifier.width(SidePanelWidth).fillMaxHeight().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                heading()
                cancel()
            }
            page(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp))
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) { heading() }
                cancel()
            }
            page(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp))
        }
    }
}
