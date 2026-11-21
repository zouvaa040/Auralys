/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Small GET helper shared by the keyless lyrics providers (SimpMusic, Unison, lrc.red, BiniLyrics).
 * An answer of the server that holds nothing (HTTP 4xx, an oversized body) is a null: the source answered "no".
 * A failure of the connection (offline, DNS, timeout, TLS) and a busy server (HTTP 5xx, 429) throw an [IOException]
 * instead, so the lookup can tell "the source has nothing" from "the source could not be reached" and never stores
 * "no lyrics" for a song because the phone was offline (see LyricsLookup). The call is cancelled as soon as the
 * coroutine is.
 */
internal object LyricsHttp {
    private const val AGENT = "Auralys (Android)"
    private const val MAX_BODY_BYTES = 2L * 1024 * 1024

    /** The server answered, but could not serve the request now (HTTP 5xx or 429). */
    class ServerBusy(val code: Int) : IOException("HTTP $code")

    private val client by lazy {
        OkHttpClient
            .Builder()
            .callTimeout(6, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    /** Builds `base?name=value&...` with proper escaping; blank values are left out. */
    fun url(
        base: String,
        vararg params: Pair<String, String?>,
    ): String? {
        val builder = base.toHttpUrlOrNull()?.newBuilder() ?: return null
        params.forEach { (name, value) ->
            if (!value.isNullOrBlank()) builder.addQueryParameter(name, value)
        }
        return builder.build().toString()
    }

    /** True for an https URL; a server-supplied link is never followed over plain http. */
    fun isHttps(url: String?): Boolean = url != null && url.toHttpUrlOrNull()?.isHttps == true

    suspend fun get(
        url: String,
        accept: String = "application/json",
    ): String? {
        val request =
            runCatching {
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", AGENT)
                    .header("Accept", accept)
                    .build()
            }.getOrNull() ?: return null

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val body =
                            runCatching {
                                response.use { r ->
                                    val source = r.body
                                    when {
                                        r.code >= 500 || r.code == 429 -> throw ServerBusy(r.code)
                                        !r.isSuccessful || source == null || source.contentLength() > MAX_BODY_BYTES -> null
                                        else -> source.string()
                                    }
                                }
                            }
                        if (!continuation.isActive) return
                        body.fold(
                            onSuccess = { continuation.resume(it) },
                            // a body cut off half way is a connection failure too
                            onFailure = { e -> if (e is IOException) continuation.resumeWithException(e) else continuation.resume(null) },
                        )
                    }
                },
            )
        }
    }
}
