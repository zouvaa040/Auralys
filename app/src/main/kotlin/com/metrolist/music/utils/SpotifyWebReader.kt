/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Spotify's embed page only lists the first 100 songs of a playlist, while the public web page lists all of
 * them. For longer playlists this opens that page in a hidden WebView, scrolls it and reads the rows the way a
 * person scrolling the page would see them. It never signs in and never calls a Spotify API; when anything
 * fails the caller keeps the songs it already has.
 */

package com.metrolist.music.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.metrolist.music.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import kotlin.coroutines.resume

/** Songs read from the web page. [total] is the size the page announces (0 when it could not be read). */
data class SpotifyWebPlaylist(
    val tracks: List<ExternalTrack>,
    val total: Int,
    val complete: Boolean,
)

/** Why the whole playlist could not be read; each value has its own plain-language message in the import dialog. */
enum class SpotifyWebFailure {
    /** The page was too slow to load or to scroll through. */
    TIMEOUT,

    /** The page could not be opened (no connection, HTTP error). */
    NETWORK,

    /** The WebView's render process died, usually because the device ran out of memory. */
    MEMORY,

    /** The page opened but did not give a (complete) song list, e.g. because Spotify changed its layout. */
    PAGE,

    /** No usable WebView on this device. */
    UNAVAILABLE,
}

/**
 * [playlist] is null when nothing could be read. [failure] is null only when the whole playlist was read;
 * [diag] is a technical note, shown in the import dialog only when the user opens "technical details".
 */
data class SpotifyWebResult(
    val playlist: SpotifyWebPlaylist?,
    val diag: String,
    val failure: SpotifyWebFailure? = null,
)

object SpotifyWebReader {
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    // The desktop layout is what lists every row; the narrow mobile layout asks to open the app instead.
    private const val VIEW_WIDTH_DP = 900
    private const val VIEW_HEIGHT_DP = 700
    private const val TIMEOUT_MS = 75_000L
    private const val POLL_MS = 400L
    private const val EVALUATE_TIMEOUT_MS = 4_000L
    private const val LOG_TAG = "SpotifyWebReader"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val STATE_JS =
        "(function(){var s=window.__aur;return s?JSON.stringify({stage:s.stage,count:s.count,total:s.total}):null;})()"
    private const val ROWS_JS =
        "(function(){var s=window.__aur;if(!s)return null;var a=[];for(var k in s.rows){a.push(s.rows[k]);}" +
            "a.sort(function(x,y){return x.n-y.n;});return JSON.stringify(a);})()"

    private const val DIAG_JS =
        "(function(){var r=document.querySelector('[data-testid=\"tracklist-row\"]');var el=r,sc=null;" +
            "while(el){var ov=getComputedStyle(el).overflowY;if((ov==='scroll'||ov==='auto')&&el.scrollHeight>el.clientHeight+100){sc=el;break;}el=el.parentElement;}" +
            "var s=window.__aur||{};return JSON.stringify({href:location.href,title:document.title,vis:document.visibilityState," +
            "iw:innerWidth,ih:innerHeight,dpr:devicePixelRatio,rows:document.querySelectorAll('[data-testid=\"tracklist-row\"]').length," +
            "grids:document.querySelectorAll('[role=\"grid\"]').length,stage:s.stage||null,count:s.count||0,total:s.total||0," +
            "err:s.error||null,sc:sc?[Math.round(sc.scrollTop),sc.clientHeight,sc.scrollHeight]:null," +
            "r0:r?{c:r.querySelectorAll('[role=\"gridcell\"]').length,l:!!r.querySelector('a[data-testid=\"internal-track-link\"]')," +
            "t:((r.querySelector('[role=\"gridcell\"]')||{}).textContent||'').slice(0,12)}:null," +
            "txt:(document.body?document.body.innerText:'').replace(/\\s+/g,' ').slice(0,100)});})()"

    private class Snapshot(val stage: String, val count: Int, val total: Int)

    /**
     * Reads the whole playlist behind [link]. Must be called from a coroutine; it hops to the main thread itself.
     * Returns null when the page could not be read at all (no WebView, no network, page changed, ...).
     * [onProgress] gets (songs read so far, total announced by the page or 0).
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun read(
        activity: Activity,
        link: SpotifyLink,
        onProgress: (read: Int, total: Int) -> Unit,
    ): SpotifyWebResult = withContext(Dispatchers.Main.immediate) {
        if (link.kind != "playlist") {
            return@withContext SpotifyWebResult(null, "not a playlist", SpotifyWebFailure.UNAVAILABLE)
        }
        val script = runCatching {
            activity.resources.openRawResource(R.raw.auralys_spotify_reader).bufferedReader().use { it.readText() }
        }.getOrNull()
            ?: return@withContext SpotifyWebResult(null, "reader script missing", SpotifyWebFailure.UNAVAILABLE)
        val root = activity.window?.decorView as? ViewGroup
            ?: return@withContext SpotifyWebResult(null, "no window", SpotifyWebFailure.UNAVAILABLE)

        var webView: WebView? = null
        val started = SystemClock.elapsedRealtime()
        try {
            var failed: String? = null
            var gone = false
            var loaded = false
            val view = WebView(activity)
            webView = view
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = DESKTOP_USER_AGENT
                mediaPlaybackRequiresUserGesture = true
                setSupportMultipleWindows(false)
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = WebSettings.LOAD_DEFAULT
                // Cover art is not needed to read the rows; skipping it saves a little data and memory.
                blockNetworkImage = true
            }
            view.setBackgroundColor(Color.TRANSPARENT)
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            view.isFocusable = false
            view.isClickable = false
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    loaded = true
                }

                // Stay on open.spotify.com: never follow app links or sign-in pages out of this hidden view.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val host = request.url.host?.lowercase(Locale.ROOT)
                    return !(request.url.scheme == "https" && host == "open.spotify.com")
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) failed = "net ${error.errorCode} ${error.description}"
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) failed = "http ${errorResponse.statusCode}"
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    gone = true
                    return true
                }
            }

            // Attached to the window (and almost invisible) so the page keeps rendering and lazy-loading rows;
            // the import dialog is a separate window on top of it.
            val density = activity.resources.displayMetrics.density
            root.addView(
                view,
                FrameLayout.LayoutParams((VIEW_WIDTH_DP * density).toInt(), (VIEW_HEIGHT_DP * density).toInt()),
            )
            view.alpha = 0.011f
            view.loadUrl(link.pageUrl, mapOf("Accept-Language" to "en-US,en;q=0.9"))

            val deadline = started + TIMEOUT_MS
            var last: Snapshot? = null
            var timedOut = true
            while (isActive && SystemClock.elapsedRealtime() < deadline) {
                if (failed != null || gone || activity.isFinishing || activity.isDestroyed) {
                    timedOut = false
                    break
                }
                delay(POLL_MS)
                val state = readState(view)
                if (state == null) {
                    if (loaded) view.evaluateJavascript(script, null)
                    continue
                }
                last = state
                onProgress(state.count, state.total)
                if (state.stage == "done" || state.stage == "no-scroller") {
                    timedOut = false
                    break
                }
            }

            val tracks = readTracks(view)
            val total = last?.total ?: 0
            val finished = last?.stage == "done"
            val page = if (gone) "" else evaluate(view, DIAG_JS)?.let { unwrap(it) }.orEmpty()
            val diag = "read ${tracks.size}/${total} in ${(SystemClock.elapsedRealtime() - started) / 1000}s" +
                (if (timedOut) ", timeout" else "") + (if (gone) ", render process gone" else "") +
                (failed?.let { ", $it" } ?: "") + (if (loaded) "" else ", page not finished") +
                (if (page.isNotEmpty()) "; page $page" else "")
            Log.i(LOG_TAG, diag)
            val complete = finished && (total == 0 || tracks.size >= total)
            val failure = when {
                complete -> null
                gone -> SpotifyWebFailure.MEMORY
                failed != null -> SpotifyWebFailure.NETWORK
                last?.stage == "no-scroller" -> SpotifyWebFailure.PAGE
                timedOut && last == null -> if (loaded) SpotifyWebFailure.PAGE else SpotifyWebFailure.TIMEOUT
                timedOut && last?.stage == "wait-grid" -> SpotifyWebFailure.PAGE
                timedOut -> SpotifyWebFailure.TIMEOUT
                else -> SpotifyWebFailure.PAGE
            }
            SpotifyWebResult(
                playlist = tracks.takeIf { it.isNotEmpty() }?.let {
                    SpotifyWebPlaylist(tracks = it, total = total, complete = complete)
                },
                diag = diag,
                failure = failure,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // No WebView installed, the page changed, ...: the caller falls back to the 100 songs it has.
            Log.w(LOG_TAG, "Spotify web read failed", t)
            SpotifyWebResult(
                playlist = null,
                diag = "${t.javaClass.simpleName}: ${t.message?.take(100)}",
                failure = SpotifyWebFailure.UNAVAILABLE,
            )
        } finally {
            webView?.let { view ->
                runCatching {
                    view.stopLoading()
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                }
            }
        }
    }

    /** Null when the page does not answer in time (for example after its render process died). */
    private suspend fun evaluate(view: WebView, script: String): String? =
        withTimeoutOrNull(EVALUATE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                view.evaluateJavascript(script) { result -> if (cont.isActive) cont.resume(result) }
            }
        }

    /** evaluateJavascript hands back a JSON value; a returned JSON string comes back quoted and escaped. */
    private fun unwrap(result: String?): String? {
        if (result.isNullOrEmpty() || result == "null") return null
        return runCatching { json.parseToJsonElement(result).jsonPrimitive.content }.getOrNull()
    }

    private suspend fun readState(view: WebView): Snapshot? {
        val obj = unwrap(evaluate(view, STATE_JS))
            ?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: return null
        return Snapshot(
            stage = obj["stage"]?.jsonPrimitive?.content.orEmpty(),
            count = (obj["count"] as? JsonPrimitive)?.int ?: 0,
            total = (obj["total"] as? JsonPrimitive)?.int ?: 0,
        )
    }

    private suspend fun readTracks(view: WebView): List<ExternalTrack> {
        val array = unwrap(evaluate(view, ROWS_JS))
            ?.let { runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
            ?: return emptyList()
        return array.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val title = row["title"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val artists = (row["artists"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.content.trim().takeIf { name -> name.isNotEmpty() } }
                .orEmpty()
            val ms = (row["ms"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: 0L
            ExternalTrack(title = title, artists = artists, durationMs = ms.takeIf { it > 0 })
        }
    }
}
