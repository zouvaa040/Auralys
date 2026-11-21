/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The bearer token of Spotify's own web player, minted from the user's sign-in cookie. There is no public API for the
 * library, so this walks the same door the web player uses. Spotify refuses a token this code would sign itself (it
 * answers 429 downstream), so the token is read from the real page: an offscreen WebView loads open.spotify.com with the
 * cookie applied and a small script reads the answer of the page's own /api/token request. Adapted from BitChord's
 * SpotifyToken (https://github.com/kushagrasinghx/BitChord, GPL-3.0); see NOTICE.
 *
 * The cookie is the user's own, read on his device and sent only to *.spotify.com. Nothing here logs a token, a cookie or
 * a payload.
 *
 * A mint takes seconds (a WebView loads the whole web player), so it never runs inside the coroutine of whoever asked: it
 * runs in this class's own scope, one at a time, and the callers wait for it ([tokens]). A caller that gives up waiting (a
 * lookup with a time budget, a screen that was closed) leaves the mint running, and the next caller finds the token.
 */

package com.metrolist.music.spotify

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** An access token read from the web player. [expiresAtMs] is epoch milliseconds. */
class MintedToken(
    val accessToken: String,
    val expiresAtMs: Long,
    val clientId: String?,
)

/** How one attempt to read the web player's token ended. */
sealed interface HarvestResult {
    class Token(val token: MintedToken) : HarvestResult

    /**
     * The page proved the cookie is no longer accepted: it sent the player to the login page, or the page finished loading
     * and had only ever minted an anonymous token. A slow page or a slow network is NOT this, it is [Failed].
     */
    object Expired : HarvestResult

    /** No connection. */
    object Offline : HarvestResult

    /** Nothing arrived in time, or the page could not run. Says nothing about the cookie. */
    object Failed : HarvestResult
}

/** One attempt to read the web player's own token with [cookie] applied. Never throws except for cancellation. */
fun interface SpotifyTokenHarvester {
    suspend fun harvest(cookie: String, wipeAllWebStorage: Boolean): HarvestResult
}

/** Why the last mint gave no token, so a screen can tell "sign in again" from "try again later". */
enum class SpotifyMintFailure { EXPIRED, OFFLINE, FAILED }

class SpotifyTokenMinter(
    private val cookie: () -> String?,
    private val harvester: SpotifyTokenHarvester,
    private val webPlayer: SpotifyWebPlayer,
    private val transport: SpotifyTransport,
    private val osVersion: () -> String = { "unknown" },
    private val now: () -> Long = System::currentTimeMillis,
    /** Where a mint runs. Not the caller's coroutine: see the file header. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clientTokenTimeoutMs: Long = CLIENT_TOKEN_TIMEOUT_MS,
) : SpotifyTokenProvider {
    /** One mint in progress: for which cookie, what the waiters get, and the job that does it. */
    private class Mint(val cookie: String, val result: CompletableDeferred<SpotifyTokens?>) {
        @Volatile var job: Job? = null
    }

    private val lock = Any()
    private var inflight: Mint? = null

    @Volatile private var access: MintedToken? = null

    @Volatile private var mintedFor: String? = null

    @Volatile private var clientToken: String? = null

    @Volatile private var clientTokenExpiresAtMs = 0L

    /** When a client token was last asked for (0 = never for this access token): a refusal is not retried at once. */
    @Volatile private var clientTokenTriedAtMs = 0L

    /** After a failed mint this cookie is not tried again until [failedUntilMs], so no call starts another 20 s mint. */
    @Volatile private var failedFor: String? = null

    @Volatile private var failedUntilMs = 0L

    @Volatile
    var lastFailure: SpotifyMintFailure? = null
        private set

    /** True while a mint is running in the background. */
    val isMinting: Boolean get() = synchronized(lock) { inflight?.job?.isActive == true }

    /** Forgets every cached token: the next call mints again. Used after a 401 and when the account changes. */
    fun invalidate() {
        access = null
        mintedFor = null
        clientToken = null
        clientTokenExpiresAtMs = 0L
        clientTokenTriedAtMs = 0L
    }

    /** Forgets that the last mint failed, so the next call may try at once (a person pressed "try again"). */
    fun forgetFailure() {
        failedFor = null
        failedUntilMs = 0L
    }

    /**
     * Forgets everything and stops a mint that is running: the account was connected, replaced or disconnected. Waiters of
     * the stopped mint get null.
     */
    fun reset() {
        synchronized(lock) {
            inflight?.job?.cancel()
            inflight = null
        }
        invalidate()
        forgetFailure()
        lastFailure = null
    }

    override suspend fun tokens(): SpotifyTokens? {
        val cookieNow = cookie()?.takeIf { it.isNotBlank() } ?: return null
        cached(cookieNow)?.let { return it }
        if (failedFor == cookieNow && now() < failedUntilMs) return null
        // waiting is cancellable, the mint is not: a caller that gives up leaves it running
        return start(cookieNow).result.await()
    }

    private fun start(cookieNow: String): Mint =
        synchronized(lock) {
            val running = inflight
            if (running != null && running.job?.isActive == true) {
                if (running.cookie == cookieNow) return running
                running.job?.cancel() // another account's mint
            }
            val mint = Mint(cookieNow, CompletableDeferred())
            mint.job = scope.launch { runMint(mint) }
            inflight = mint
            mint
        }

    private suspend fun runMint(mint: Mint) {
        try {
            mint.result.complete(mintOnce(mint.cookie))
        } catch (e: CancellationException) {
            mint.result.complete(null)
            throw e
        } catch (e: Exception) {
            lastFailure = SpotifyMintFailure.FAILED
            noteFailure(mint.cookie)
            mint.result.complete(null)
        } finally {
            synchronized(lock) { if (inflight === mint) inflight = null }
        }
    }

    private fun cached(cookieNow: String): SpotifyTokens? {
        val token = access ?: return null
        if (mintedFor != cookieNow) {
            // another sign-in: a token of the old account must never be used
            invalidate()
            return null
        }
        if (now() >= token.expiresAtMs - EXPIRY_MARGIN_MS) return null
        val client = clientToken?.takeIf { now() < clientTokenExpiresAtMs - EXPIRY_MARGIN_MS }
        if (client != null) return SpotifyTokens(token.accessToken, client)
        // no client token yet, or it ran out: it is asked for again after a pause, until then the bearer goes alone
        return if (now() - clientTokenTriedAtMs < CLIENT_TOKEN_RETRY_MS) SpotifyTokens(token.accessToken, null) else null
    }

    private fun noteFailure(cookieNow: String) {
        failedFor = cookieNow
        failedUntilMs = now() + NEGATIVE_CACHE_MS
    }

    private suspend fun mintOnce(cookieNow: String): SpotifyTokens? {
        cached(cookieNow)?.let { return it }
        val existing = access
        val token =
            if (existing != null && mintedFor == cookieNow && now() < existing.expiresAtMs - EXPIRY_MARGIN_MS) {
                existing
            } else {
                harvestToken(cookieNow) ?: return null
            }
        val client = fetchClientToken(token)
        clientTokenTriedAtMs = now()
        return SpotifyTokens(token.accessToken, client)
    }

    private suspend fun harvestToken(cookieNow: String): MintedToken? {
        // the first attempt wipes only Spotify's web storage; if nothing at all came back, the second wipes all of it
        // (BitChord does that every time: the player skips its /api/token request when a live token sits in storage)
        for (wipeAll in listOf(false, true)) {
            when (val result = harvester.harvest(cookieNow, wipeAll)) {
                is HarvestResult.Token -> {
                    // disconnected, or another account signed in, while the page loaded: this token is nobody's now
                    if (cookie() != cookieNow) return null
                    access = result.token
                    mintedFor = cookieNow
                    clientToken = null
                    clientTokenExpiresAtMs = 0L
                    lastFailure = null
                    return result.token
                }
                HarvestResult.Expired -> {
                    lastFailure = SpotifyMintFailure.EXPIRED
                    noteFailure(cookieNow)
                    return null
                }
                HarvestResult.Offline -> {
                    // fails at once and the connection may be back in a moment: no pause
                    lastFailure = SpotifyMintFailure.OFFLINE
                    return null
                }
                HarvestResult.Failed -> lastFailure = SpotifyMintFailure.FAILED
            }
        }
        noteFailure(cookieNow)
        return null
    }

    /** The second header Spotify's endpoints demand next to the bearer. Best effort, at most [clientTokenTimeoutMs]: null when it cannot be had. */
    private suspend fun fetchClientToken(token: MintedToken): String? {
        clientToken?.takeIf { now() < clientTokenExpiresAtMs - EXPIRY_MARGIN_MS }?.let { return it }
        return withTimeoutOrNull(clientTokenTimeoutMs) { requestClientToken(token) }
    }

    private suspend fun requestClientToken(token: MintedToken): String? {
        val clientId = token.clientId ?: return null
        val page = webPlayer.page() ?: return null
        val version = SpotifyWebPlayer.clientVersionIn(page.html) ?: return null
        val body =
            buildClientTokenRequest(
                clientVersion = version,
                clientId = clientId,
                deviceId = page.deviceId ?: UUID.randomUUID().toString(),
                osVersion = osVersion(),
            )
        val answer =
            try {
                transport.post(
                    CLIENT_TOKEN_URL,
                    mapOf("Accept" to "application/json", "User-Agent" to SPOTIFY_WEB_USER_AGENT),
                    body.toByteArray(Charsets.UTF_8),
                    // the bare type: the endpoint answers 400 to "application/json; charset=utf-8"
                    "application/json",
                )
            } catch (e: java.io.IOException) {
                return null
            }
        if (!answer.ok) return null
        val granted = parseClientTokenAnswer(answer.body) ?: return null
        clientToken = granted.first
        clientTokenExpiresAtMs = now() + granted.second * 1000L
        return granted.first
    }

    companion object {
        private const val EXPIRY_MARGIN_MS = 30_000L
        private const val CLIENT_TOKEN_RETRY_MS = 5L * 60 * 1000
        const val CLIENT_TOKEN_TIMEOUT_MS = 5_000L

        /** How long a failed or expired mint is remembered before the same cookie is tried again. */
        const val NEGATIVE_CACHE_MS = 60_000L
        const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"
        private const val DEFAULT_LIFETIME_MS = 3_600_000L
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Reads the answer of the web player's `/api/token` request. Null for an anonymous token (it cannot read a
         * library; the player mints one before the cookie takes effect, so the caller keeps waiting), for an answer without
         * both `accessToken` and `clientId` (the real one always has them), and for junk.
         */
        internal fun parseTokenPayload(payload: String?, now: Long): MintedToken? {
            if (payload.isNullOrBlank()) return null
            val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
            val token = root.text("accessToken")?.takeIf { it.isNotBlank() } ?: return null
            val clientId = root.text("clientId")?.takeIf { it.isNotBlank() } ?: return null
            if (root.text("isAnonymous")?.toBooleanStrictOrNull() == true) return null
            val expiresAt = root.text("accessTokenExpirationTimestampMs")?.toLongOrNull()?.takeIf { it > now } ?: (now + DEFAULT_LIFETIME_MS)
            return MintedToken(token, expiresAt, clientId)
        }

        /** True when [payload] parses as a token answer that is explicitly anonymous. */
        internal fun isAnonymousPayload(payload: String?): Boolean {
            val root = runCatching { json.parseToJsonElement(payload.orEmpty()) as? JsonObject }.getOrNull() ?: return false
            return root.text("accessToken") != null && root.text("isAnonymous")?.toBooleanStrictOrNull() == true
        }

        /** The body of the client-token request, the way the web player sends it. */
        internal fun buildClientTokenRequest(
            clientVersion: String,
            clientId: String,
            deviceId: String,
            osVersion: String,
        ): String =
            buildJsonObject {
                putJsonObject("client_data") {
                    put("client_version", clientVersion)
                    put("client_id", clientId)
                    putJsonObject("js_sdk_data") {
                        put("device_brand", "unknown")
                        put("device_model", "unknown")
                        put("os", "android")
                        put("os_version", osVersion)
                        put("device_id", deviceId)
                        put("device_type", "smartphone")
                    }
                }
            }.toString()

        /** The granted client token and how many seconds it lives, or null when Spotify refused. */
        internal fun parseClientTokenAnswer(body: String): Pair<String, Long>? {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            if (root.text("response_type") != "RESPONSE_GRANTED_TOKEN_RESPONSE") return null
            val granted = root["granted_token"] as? JsonObject ?: return null
            val token = granted.text("token")?.takeIf { it.isNotBlank() } ?: return null
            val seconds = granted.text("expires_after_seconds")?.toLongOrNull()?.takeIf { it > 0 } ?: 3600L
            return token to seconds
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}

/**
 * Receives the raw answers of the page's own `/api/token` request from the script below. Public and kept by name
 * (proguard-rules.pro): the WebView calls it by reflection.
 */
class SpotifyTokenBridge(private val onPayload: (String) -> Unit) {
    @JavascriptInterface
    fun onTokenPayload(payload: String?) {
        if (!payload.isNullOrBlank() && payload.length < MAX_PAYLOAD_CHARS) onPayload(payload)
    }

    private companion object {
        const val MAX_PAYLOAD_CHARS = 20_000
    }
}

/** The real harvester: a WebView that is never shown. All WebView work runs on the main thread. */
class WebViewSpotifyTokenHarvester(
    context: Context,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val now: () -> Long = System::currentTimeMillis,
) : SpotifyTokenHarvester {
    private val appContext = context.applicationContext

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun harvest(cookie: String, wipeAllWebStorage: Boolean): HarvestResult =
        withContext(Dispatchers.Main) {
            val done = CompletableDeferred<HarvestResult>()
            // set on the bridge's and the page's threads, read on the main one
            val sawAnonymous = AtomicBoolean(false)
            val pageFinished = AtomicBoolean(false)
            var webView: WebView? = null
            try {
                val cookies = CookieManager.getInstance()
                cookies.setAcceptCookie(true)
                cookies.setCookie("https://open.spotify.com/", spDcCookie(cookie))
                cookies.setCookie("https://accounts.spotify.com/", spDcCookie(cookie))
                cookies.flush()
                // The player keeps its token in web storage and skips a fresh /api/token request while a live one is
                // there, which would leave the script with nothing to read: wipe it so every mint is a real one.
                val storage = WebStorage.getInstance()
                runCatching { SPOTIFY_WEB_ORIGINS.forEach { storage.deleteOrigin(it) } }
                if (wipeAllWebStorage) runCatching { storage.deleteAllData() }

                val bridge =
                    SpotifyTokenBridge { payload ->
                        val token = SpotifyTokenMinter.parseTokenPayload(payload, now())
                        if (token != null) {
                            done.complete(HarvestResult.Token(token))
                        } else if (SpotifyTokenMinter.isAnonymousPayload(payload)) {
                            sawAnonymous.set(true)
                        }
                    }
                val view = WebView(appContext)
                webView = view
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = SPOTIFY_WEB_USER_AGENT
                    allowFileAccess = false
                    allowContentAccess = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                cookies.setAcceptThirdPartyCookies(view, true)
                view.addJavascriptInterface(bridge, BRIDGE_NAME)
                view.webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            !isSpotifyHost(request.url.host)

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            view.evaluateJavascript(HOOK_SCRIPT, null)
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            // again, in case the player navigated inside the page after the first injection ran
                            view.evaluateJavascript(HOOK_SCRIPT, null)
                            // sent to the login page: the cookie was not accepted
                            if (url != null && isSignInPage(url)) done.complete(HarvestResult.Expired) else pageFinished.set(true)
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) done.complete(HarvestResult.Offline)
                        }
                    }
                view.loadUrl("https://open.spotify.com/")
                withTimeoutOrNull(timeoutMs) { done.await() }
                    ?: endedWithoutToken(sawAnonymous.get(), pageFinished.get())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HarvestResult.Failed
            } finally {
                runCatching {
                    webView?.removeJavascriptInterface(BRIDGE_NAME)
                    webView?.stopLoading()
                    webView?.destroy()
                }
            }
        }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
        private const val BRIDGE_NAME = "AuralysSpotify"

        /**
         * The cookie as it is written into the web view's jar. HttpOnly, like the one Spotify itself sets: without it the
         * page's own scripts (and the third-party ones the web player loads) could read the whole session with
         * `document.cookie`.
         */
        internal fun spDcCookie(value: String): String = "sp_dc=$value; Domain=.spotify.com; Path=/; Secure; HttpOnly"

        /**
         * What a mint that ran out of time means. Only a page that finished loading and still had nothing but an anonymous
         * token proves the cookie is refused; a page that is merely slow (or a slow network) says nothing about it.
         */
        internal fun endedWithoutToken(sawAnonymous: Boolean, pageFinished: Boolean): HarvestResult =
            if (sawAnonymous && pageFinished) HarvestResult.Expired else HarvestResult.Failed

        /** True for the page Spotify sends a signed-out visitor to. */
        internal fun isSignInPage(url: String): Boolean {
            val lower = url.lowercase()
            return lower.startsWith("https://accounts.spotify.com/") && lower.contains("/login")
        }

        /**
         * Watches the page's own fetch and XHR calls and hands the answer of exactly `https://open.spotify.com/api/token` to
         * the bridge (the host and the path are compared, not a piece of the text). It changes nothing the page does and
         * reads nothing else.
         */
        private const val HOOK_SCRIPT = """
            (function () {
              if (window.__auralysTokenHook) return;
              window.__auralysTokenHook = true;
              var report = function (body) { try { AuralysSpotify.onTokenPayload(body); } catch (e) {} };
              var isToken = function (u) {
                try {
                  var a = new URL(String(u), location.href);
                  return a.protocol === 'https:' && a.hostname === 'open.spotify.com' && a.pathname === '/api/token';
                } catch (e) { return false; }
              };
              var origFetch = window.fetch;
              if (origFetch) {
                window.fetch = function (input, init) {
                  var url = (input && input.url) ? input.url : input;
                  var result = origFetch.apply(this, arguments);
                  if (isToken(url)) {
                    try {
                      result.then(function (res) { res.clone().text().then(report).catch(function () {}); }).catch(function () {});
                    } catch (e) {}
                  }
                  return result;
                };
              }
              var origOpen = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function (method, url) {
                this.__auralysUrl = url;
                return origOpen.apply(this, arguments);
              };
              var origSend = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.send = function () {
                var xhr = this;
                try {
                  xhr.addEventListener('load', function () {
                    if (isToken(xhr.__auralysUrl)) { try { report(xhr.responseText); } catch (e) {} }
                  });
                } catch (e) {}
                return origSend.apply(this, arguments);
              };
            })();
        """
    }
}
