/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Spotify Canvas (the short looping video behind a track) as a source of animated covers: finds the
 * Spotify track that is the playing song, asks Pathfinder for its canvas and returns the video's URL.
 * Used by canvas/AnimatedArtworkRepository, in front of Apple Music's motion artwork.
 *
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord, GPL-3.0): data/canvas/SpotifyCanvas.kt
 * (search, then canvas by track uri) and web/extension/lib/spotify.ts (Pathfinder v2 requests, hashes read
 * from the web player, one re-read on PersistedQueryNotFound). Rewritten for Auralys on OkHttp.
 *
 * Privacy: the account's tokens come from [SpotifySession.tokens] for each call and are sent only to
 * *.spotify.com (the request builder refuses any other host); they are never stored here and never logged.
 * Scripts are read without any token. Redirects are not followed on authenticated calls.
 */

package com.metrolist.music.spotify

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What a Spotify Canvas lookup came to. */
sealed interface SpotifyCanvasResult {
    /** A plain https mp4 of the track's canvas. */
    data class Found(val url: String) : SpotifyCanvasResult

    /** Spotify answered: no matching track, or the track has no (playable) canvas. A real answer, worth remembering. */
    data object Missing : SpotifyCanvasResult

    /** It did not work this time (network, rate limit, unexpected answer). Not a statement about the track. */
    data object Failed : SpotifyCanvasResult

    /** Not asked at all: no Spotify account tokens, or Spotify asked us to slow down. Nothing to remember. */
    data object Unavailable : SpotifyCanvasResult
}

/** The seam the animated-artwork repository uses (and its tests replace). */
fun interface SpotifyCanvasSource {
    suspend fun find(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int?,
        isVideo: Boolean,
    ): SpotifyCanvasResult
}

/** Where the hashes read from Spotify's scripts are kept between runs. */
internal interface SpotifyHashStore {
    fun load(): Map<String, String>

    fun save(hashes: Map<String, String>)
}

internal class SpotifyFileHashStore(private val file: File) : SpotifyHashStore {
    override fun load(): Map<String, String> = runCatching {
        if (!file.isFile) return emptyMap()
        file.readLines().mapNotNull { line ->
            val op = line.substringBefore('=', "")
            val hash = line.substringAfter('=', "").trim()
            if (op in SpotifyCanvasQuery.KNOWN_HASHES && SpotifyCanvasQuery.isHash(hash)) op to hash else null
        }.toMap()
    }.getOrDefault(emptyMap())

    override fun save(hashes: Map<String, String>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(hashes.entries.joinToString("\n") { "${it.key}=${it.value}" })
        }
    }
}

/**
 * The hash of each operation. Starts with the constants that last worked (or what an earlier read of
 * Spotify's scripts found), so a lookup costs no extra download; the 4 MB web-player script is read only
 * after Spotify said a hash is retired, at most once per [MIN_REFRESH_INTERVAL_MS].
 */
internal class SpotifyCanvasHashes(
    /** A plain GET of a web-player page or script (no token, no cookie); null on any failure. */
    private val fetch: (String) -> String?,
    private val store: SpotifyHashStore,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var learned: Map<String, String> = store.load()
    private var lastAttemptAt = 0L
    private var inflight: Deferred<Boolean>? = null

    fun hash(operation: String): String = learned[operation] ?: SpotifyCanvasQuery.KNOWN_HASHES.getValue(operation)

    /**
     * Reads the web player's scripts again, once at a time. Runs in its own scope, so a caller that gives up
     * waiting (its time ran out) does not stop it; the next lookup then finds the new hashes. Completes with
     * whether any hash was read.
     */
    @Synchronized
    fun refresh(): Deferred<Boolean> {
        inflight?.takeIf { it.isActive }?.let { return it }
        if (now() - lastAttemptAt < MIN_REFRESH_INTERVAL_MS) return CompletableDeferred(false)
        lastAttemptAt = now()
        return scope.async { readWebPlayer() }.also { inflight = it }
    }

    private fun readWebPlayer(): Boolean = try {
        val html = fetch("https://open.spotify.com/")
        val bundleUrl = html?.let(SpotifyCanvasQuery::bundleUrl)
        val bundle = bundleUrl?.let(fetch)
        if (bundle == null) {
            false
        } else {
            var chunk = ""
            for (url in SpotifyCanvasQuery.searchChunkUrls(bundle)) {
                chunk = fetch(url).orEmpty()
                if (chunk.isNotEmpty()) break
            }
            val found = SpotifyCanvasQuery.KNOWN_HASHES.keys.mapNotNull { op ->
                (SpotifyCanvasQuery.findQueryHash(bundle, op) ?: SpotifyCanvasQuery.findQueryHash(chunk, op))?.let { op to it }
            }.toMap()
            if (found.isNotEmpty()) {
                val merged = learned + found
                learned = merged
                store.save(merged)
            }
            found.isNotEmpty()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        false
    }

    companion object {
        const val MIN_REFRESH_INTERVAL_MS = 30L * 60 * 1000
    }
}

class SpotifyCanvasClient internal constructor(
    /** Authenticated Pathfinder calls: short timeouts, no redirects. */
    private val api: OkHttpClient,
    private val tokens: suspend () -> SpotifyTokens?,
    private val hashes: SpotifyCanvasHashes,
    private val now: () -> Long = System::currentTimeMillis,
) : SpotifyCanvasSource {
    @Volatile private var coolDownUntil = 0L

    override suspend fun find(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int?,
        isVideo: Boolean,
    ): SpotifyCanvasResult {
        if (now() < coolDownUntil) return SpotifyCanvasResult.Unavailable
        val term = SpotifyCanvasMatch.searchTerm(title, artist)
        if (term.isBlank()) return SpotifyCanvasResult.Missing
        val session = tokens() ?: return SpotifyCanvasResult.Unavailable

        val answer = search(term, session) as? SpotifyCanvasQuery.SearchAnswer.Tracks ?: return SpotifyCanvasResult.Failed
        val ranked = SpotifyCanvasMatch.rank(answer.hits, title, artist, album, durationSec, isVideo)
        for (track in ranked.take(MAX_CANVAS_TRIES)) {
            when (val canvas = canvas(track.uri, session)) {
                is SpotifyCanvasQuery.CanvasAnswer.Found -> return SpotifyCanvasResult.Found(canvas.url)
                is SpotifyCanvasQuery.CanvasAnswer.NoCanvas -> Unit // the same song on another release may have one
                is SpotifyCanvasQuery.CanvasAnswer.Failed, null -> return SpotifyCanvasResult.Failed
            }
        }
        return SpotifyCanvasResult.Missing
    }

    /** The search answer, or null when the call did not work. A retired hash is replaced once and the call repeated. */
    private suspend fun search(term: String, session: SpotifyTokens): SpotifyCanvasQuery.SearchAnswer? {
        repeat(2) { attempt ->
            val used = hashes.hash(SpotifyCanvasQuery.OP_SEARCH)
            val body = post(SpotifyCanvasQuery.searchBody(term, used), session) ?: return null
            val answer = SpotifyCanvasQuery.parseSearch(body)
            val stale = answer is SpotifyCanvasQuery.SearchAnswer.Failed && answer.staleHash
            if (!stale || attempt == 1 || !hashChanged(SpotifyCanvasQuery.OP_SEARCH, used)) return answer
        }
        return null
    }

    private suspend fun canvas(trackUri: String, session: SpotifyTokens): SpotifyCanvasQuery.CanvasAnswer? {
        repeat(2) { attempt ->
            val used = hashes.hash(SpotifyCanvasQuery.OP_CANVAS)
            val body = post(SpotifyCanvasQuery.canvasBody(trackUri, used), session) ?: return null
            val answer = SpotifyCanvasQuery.parseCanvas(body)
            val stale = answer is SpotifyCanvasQuery.CanvasAnswer.Failed && answer.staleHash
            if (!stale || attempt == 1 || !hashChanged(SpotifyCanvasQuery.OP_CANVAS, used)) return answer
        }
        return null
    }

    /** Reads Spotify's scripts again (once at a time, rarely) and says whether [operation] now has another hash than [used]. */
    private suspend fun hashChanged(operation: String, used: String): Boolean =
        hashes.refresh().await() && hashes.hash(operation) != used

    /** The answer's body, or null when the call did not work. Sets the cool-down when Spotify says slow down. */
    private suspend fun post(json: String, session: SpotifyTokens): String? {
        val url = SpotifyCanvasQuery.ENDPOINT.toHttpUrlOrNull() ?: return null
        val request = authorised(url, session).post(json.toRequestBody(JSON)).build()
        return try {
            api.newCall(request).await().use { response ->
                val text = readCapped(response.body)
                when {
                    response.code == 429 -> {
                        coolDownUntil = now() + COOL_DOWN_MS
                        Timber.tag(TAG).d("Spotify asked to slow down (429)")
                        null
                    }
                    // a retired hash is answered with an error body that is still worth reading
                    text != null && (response.isSuccessful || text.contains("PersistedQueryNotFound")) -> text
                    else -> {
                        Timber.tag(TAG).d("Spotify answered ${response.code}")
                        null
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.tag(TAG).d("network: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Builds a request that carries the account's tokens; refuses any host that is not spotify.com. */
    internal fun authorised(url: HttpUrl, session: SpotifyTokens): Request.Builder {
        require(isSpotifyHost(url)) { "tokens go to spotify.com only" }
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${session.bearer}")
            .apply { session.clientToken?.let { header("Client-Token", it) } }
            .header("App-Platform", "WebPlayer")
            .header("Accept", "application/json")
            .header("Accept-Language", "en")
            .header("Origin", "https://open.spotify.com")
            .header("Referer", "https://open.spotify.com/")
            .header("User-Agent", USER_AGENT)
    }

    private fun readCapped(body: okhttp3.ResponseBody): String? {
        val source = body.source()
        source.request(MAX_ANSWER_BYTES + 1)
        return if (source.buffer.size > MAX_ANSWER_BYTES) null else source.buffer.readUtf8()
    }

    companion object {
        private const val TAG = "SpotifyCanvas"
        private const val MAX_CANVAS_TRIES = 3
        private const val MAX_ANSWER_BYTES = 2L * 1024 * 1024
        private const val COOL_DOWN_MS = 10L * 60 * 1000
        private val JSON = "application/json;charset=UTF-8".toMediaType()
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        internal fun isSpotifyHost(url: HttpUrl): Boolean =
            url.isHttps && (url.host == "spotify.com" || url.host.endsWith(".spotify.com"))

        /** Hosts whose pages and scripts are read (without any token) to learn the current hashes. */
        private fun isWebPlayerHost(url: HttpUrl): Boolean =
            url.isHttps && (url.host == "open.spotify.com" || url.host == "open.spotifycdn.com")

        /** The client the app uses. Built when first needed; nothing is read or sent until a lookup runs. */
        fun create(context: Context): SpotifyCanvasClient {
            val api = OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val pages = api.newBuilder()
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(40, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val fetch: (String) -> String? = { address ->
                val url = address.toHttpUrlOrNull()
                if (url == null || !isWebPlayerHost(url)) {
                    null
                } else {
                    try {
                        pages.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { response ->
                            if (!response.isSuccessful) {
                                null
                            } else {
                                val source = response.body.source()
                                source.request(MAX_SCRIPT_BYTES + 1)
                                if (source.buffer.size > MAX_SCRIPT_BYTES) null else source.buffer.readUtf8()
                            }
                        }
                    } catch (_: IOException) {
                        null
                    }
                }
            }
            val store = SpotifyFileHashStore(File(context.applicationContext.cacheDir, "spotify_canvas/hashes.txt"))
            val hashes = SpotifyCanvasHashes(fetch, store, CoroutineScope(SupervisorJob() + Dispatchers.IO))
            return SpotifyCanvasClient(api, { SpotifySession.tokens() }, hashes)
        }

        private const val MAX_SCRIPT_BYTES = 12L * 1024 * 1024
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { runCatching { cancel() } }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        },
    )
}
