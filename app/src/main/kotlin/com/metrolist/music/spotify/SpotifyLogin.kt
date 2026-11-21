/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The in-app Spotify sign-in: a full-screen web view on Spotify's own login page. When the page lands on open.spotify.com the
 * `sp_dc` cookie is read from the web view's cookie jar and handed to [SpotifyAccount.connect]; the view is hidden first so the
 * web player does not flash up while the screen closes. Adapted from BitChord's SpotifyLoginScreen
 * (https://github.com/kushagrasinghx/BitChord, GPL-3.0); see NOTICE.
 *
 * This must be hosted outside the page content (a dialog route, see NavigationBuilder): the page content is recorded into a
 * layer that the glass backdrop draws a second time, and a web view drawn twice flickers, drops parts of the page and on
 * some phones takes the app down in Chromium's draw functor. No script bridge is added to this view: the cookie is read
 * through the cookie manager, on this device, and goes nowhere but into the app's own private store.
 */

package com.metrolist.music.spotify

import android.annotation.SuppressLint
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.metrolist.music.R
import com.metrolist.music.ui.component.AccountProvider
import com.metrolist.music.ui.component.LoginFrame
import com.metrolist.music.ui.component.LoginPhase
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

/** Spotify's own pages and the identity providers its sign-in hands off to; anything else is not part of signing in. */
private val LOGIN_HOST_SUFFIXES = listOf("spotify.com", "scdn.co", "google.com", "gstatic.com", "facebook.com", "apple.com")

internal fun isLoginHost(scheme: String?, host: String?): Boolean {
    if (scheme != "https") return false
    val h = host?.lowercase() ?: return false
    return LOGIN_HOST_SUFFIXES.any { h == it || h.endsWith(".$it") }
}

/**
 * True when [url] is the web player, where a finished sign-in lands: https and the PARSED host is exactly open.spotify.com.
 * Compared by host, not by the start of the text: `https://open.spotify.com.example/` starts the same way and is not Spotify.
 * Not "any spotify.com page": the sign-in page itself (accounts.spotify.com) must not count, because the web view's jar may
 * still hold the cookie of an expired session, and that page would hand it over again at once.
 */
internal fun isSpotifyLanding(url: String?): Boolean {
    val parsed = url?.toHttpUrlOrNull() ?: return false
    return parsed.isHttps && parsed.host == "open.spotify.com"
}

/** The `sp_dc` value in a `Cookie`-style header string (`a=1; sp_dc=xyz; b=2`), or null. */
internal fun spDcFrom(cookieHeader: String?): String? =
    cookieHeader
        ?.split(";")
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("sp_dc=") }
        ?.substringAfter("=")
        ?.takeIf { it.isNotBlank() }

private const val FALLBACK_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

/**
 * The device's own mobile Chrome, minus the WebView markers. The default WebView user agent made Spotify (and Google) serve
 * pages for an embedded browser; a desktop one served the desktop layout scaled into a phone.
 */
internal fun browserUserAgent(platform: String?): String {
    val stripped =
        platform
            ?.replace("; wv", "")
            ?.replace(" Version/4.0", "")
            ?.replace("  ", " ")
            ?.trim()
    return stripped?.takeIf { it.contains("Chrome/") } ?: FALLBACK_USER_AGENT
}

/**
 * The whole sign-in screen: the frame it shares with the YouTube Music sign-in (a close button, the Spotify mark and title, a thin
 * progress line, a branded "opening" state, an offline state with a retry, one plain privacy line), the web view in it, and the
 * back button walking the web view's own history first. [onConnected] gets the cookie once; [onClose] is for the close button.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginContent(
    onConnected: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    // wrapper state only: the cover over the page (opening / painted / offline), how far it has loaded, and "the sign-in was read"
    var pagePhase by remember { mutableStateOf(LoginPhase.Loading) }
    var pageProgress by remember { mutableFloatStateOf(0f) }
    var connecting by remember { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        // the same frame as the YouTube Music sign-in (header, progress line, branded states, the privacy line)
        LoginFrame(
            provider = AccountProvider.SPOTIFY,
            title = stringResource(R.string.spotify_login_title),
            privacy = stringResource(R.string.login_privacy_spotify),
            phase = if (connecting) LoginPhase.Loading else pagePhase,
            progress = if (connecting) 1f else pageProgress,
            loadingText = stringResource(if (connecting) R.string.login_connecting else R.string.login_loading),
            onBack = onClose,
            onBackLongClick = onClose,
            backIcon = R.drawable.close,
            onRetry = {
                pagePhase = LoginPhase.Loading
                webView?.reload()
            },
            modifier = Modifier.safeDrawingPadding(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        // MATCH_PARENT, not the WRAP_CONTENT Compose gives a bare view: a zero layout viewport collapses
                        // any page sized off `height: 100%`, and Spotify's is
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.userAgentString = browserUserAgent(settings.userAgentString)
                        setBackgroundColor(0xFF121212.toInt())
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        var sent = false
                        // The cookie shows up once the sign-in is done. Looked for when a page finishes AND when the page
                        // changes its address without loading (the web player is a single-page app).
                        fun handOverIfSignedIn(view: WebView, url: String?) {
                            if (sent || !isSpotifyLanding(url)) return
                            val cookie = spDcFrom(CookieManager.getInstance().getCookie("https://open.spotify.com")) ?: return
                            sent = true
                            connecting = true
                            // hide before handing over, so the web player this landed on does not flash up
                            view.visibility = View.GONE
                            onConnected(cookie)
                        }
                        webViewClient =
                            object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val url: Uri = request.url
                                    return !isLoginHost(url.scheme, url.host)
                                }

                                // only the cover over the page follows the page: it goes when the page has painted, and shows the
                                // offline state when the main page cannot be reached
                                override fun onPageCommitVisible(view: WebView, url: String?) {
                                    if (pagePhase == LoginPhase.Loading) pagePhase = LoginPhase.Ready
                                }

                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) pagePhase = LoginPhase.Offline
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    if (pagePhase == LoginPhase.Loading) pagePhase = LoginPhase.Ready
                                    canGoBack = view.canGoBack()
                                    handOverIfSignedIn(view, url)
                                }

                                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                    canGoBack = view.canGoBack()
                                    handOverIfSignedIn(view, url)
                                }
                            }
                        webChromeClient =
                            object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    pageProgress = newProgress / 100f
                                }
                            }
                        webView = this
                        loadUrl(LOGIN_URL)
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }

    BackHandler(enabled = canGoBack) { webView?.goBack() }
}
