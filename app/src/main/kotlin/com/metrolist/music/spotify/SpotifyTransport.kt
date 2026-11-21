/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The only code that talks to Spotify over plain HTTP. Every call goes through [SpotifyTransport] so the callers
 * (token minter, hash reader, Pathfinder client) can be tested with a fake. The real one refuses to send anything that
 * carries a token to a host outside spotify.com, and never logs a URL's headers or a body.
 */

package com.metrolist.music.spotify

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The browser the web player thinks it talks to. The Pathfinder and client-token calls send the same one. */
internal const val SPOTIFY_WEB_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

/** A plain HTTP answer. [setCookies] are the raw `Set-Cookie` header values. */
class SpotifyHttpResult(
    val code: Int,
    val body: String,
    val setCookies: List<String> = emptyList(),
) {
    val ok: Boolean get() = code in 200..299
}

interface SpotifyTransport {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): SpotifyHttpResult

    /** [body] goes out as is, with exactly [contentType] and no charset added. */
    suspend fun post(url: String, headers: Map<String, String>, body: ByteArray, contentType: String): SpotifyHttpResult
}

/** Headers that carry a credential: they may only go to spotify.com. */
private val SENSITIVE_HEADERS = setOf("authorization", "client-token", "cookie")

internal fun isSpotifyHost(host: String?): Boolean {
    val h = host?.lowercase() ?: return false
    return h == "spotify.com" || h.endsWith(".spotify.com")
}

/** Hosts a request without credentials may use: Spotify's own, plus the CDN that serves the web player's scripts. */
internal fun isSpotifyAssetHost(host: String?): Boolean {
    val h = host?.lowercase() ?: return false
    return isSpotifyHost(h) || h == "spotifycdn.com" || h.endsWith(".spotifycdn.com") || h.endsWith(".scdn.co")
}

/** True when [url] may be requested with [headers]: https only, Spotify hosts only, credentials only to spotify.com. */
internal fun isRequestAllowed(url: String, headers: Map<String, String>): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    if (parsed.scheme != "https") return false
    val sensitive = headers.keys.any { it.lowercase() in SENSITIVE_HEADERS }
    return if (sensitive) isSpotifyHost(parsed.host) else isSpotifyAssetHost(parsed.host)
}

class OkHttpSpotifyTransport(
    /** For the web player's page and scripts (no credential): follows redirects. */
    private val client: OkHttpClient = defaultClient,
    /** For every POST and every call that carries a credential: never follows a redirect (see [defaultAuthClient]). */
    private val authClient: OkHttpClient = defaultAuthClient,
) : SpotifyTransport {
    /** The client for a request: a POST, or anything with a credential header, must not follow redirects. */
    internal fun clientFor(isPost: Boolean, headers: Map<String, String>): OkHttpClient =
        if (isPost || headers.keys.any { it.lowercase() in SENSITIVE_HEADERS }) authClient else client

    override suspend fun get(url: String, headers: Map<String, String>): SpotifyHttpResult =
        execute(
            Request.Builder().url(guard(url, headers)).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build(),
            MAX_GET_BYTES,
            clientFor(isPost = false, headers),
        )

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        contentType: String,
    ): SpotifyHttpResult =
        execute(
            Request.Builder()
                .url(guard(url, headers))
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                // a ByteArray, deliberately: the String overload of toRequestBody adds "; charset=utf-8" to a type
                // without one, and Spotify's client-token endpoint answers 400 to that header
                .post(body.toRequestBody(contentType.toMediaType()))
                .build(),
            MAX_POST_BYTES,
            clientFor(isPost = true, headers),
        )

    private fun guard(url: String, headers: Map<String, String>): String {
        if (!isRequestAllowed(url, headers)) throw IOException("Request to a host outside Spotify refused")
        return url
    }

    private suspend fun execute(request: Request, maxBytes: Long, using: OkHttpClient): SpotifyHttpResult =
        suspendCancellableCoroutine { cont ->
            val call = using.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result =
                            try {
                                response.use { r ->
                                    val source = r.body?.source()
                                    val text =
                                        if (source == null) {
                                            ""
                                        } else {
                                            source.request(maxBytes + 1)
                                            if (source.buffer.size > maxBytes) throw IOException("Answer too large")
                                            source.readUtf8()
                                        }
                                    SpotifyHttpResult(r.code, text, r.headers("Set-Cookie"))
                                }
                            } catch (e: IOException) {
                                if (cont.isActive) cont.resumeWithException(e)
                                return
                            }
                        if (cont.isActive) cont.resume(result)
                    }
                },
            )
        }

    companion object {
        private const val MAX_GET_BYTES = 16L * 1024 * 1024
        private const val MAX_POST_BYTES = 8L * 1024 * 1024

        /** Own client: the app's other clients (YouTube, lyrics) carry their own timeouts and interceptors. */
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        /**
         * The client for POSTs and for anything with a token or cookie. OkHttp drops `Authorization` on a redirect to another
         * host but not a custom header like `Client-Token`, and it follows https to http: neither redirect is followed here,
         * so a token can only ever reach the host it was sent to (a 3xx answer is just an error to the caller).
         */
        val defaultAuthClient: OkHttpClient by lazy {
            defaultClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
    }
}
