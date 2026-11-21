/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Finds Apple Music "motion artwork" (the animated album covers) for a song and keeps a small
 * local copy of the video so the full player can loop it silently.
 *
 * Adapted from Convx (https://github.com/cosmictaserdev-creator/Convx, GPL-3.0):
 * canvas/AppleMusicCatalog.kt (web-player token scrape, normalisation and artist matching) and
 * applecanvas/AppleMusicCanvasProvider.kt (catalog search with extend=editorialVideo).
 * Rewritten for Auralys on OkHttp + org.json, with a disk cache that also remembers misses, and
 * HLS-free playback: Apple's motion artwork variants are single fragmented MP4 files described by an
 * HLS playlist, so the playlist is resolved here and the MP4 is played as a plain local file.
 *
 * Second source (when a Spotify account is connected and "Sampul animasi dari Spotify" is on): Spotify Canvas,
 * the short looping video Spotify shows behind a track (spotify/SpotifyCanvasClient.kt, adapted from BitChord,
 * GPL-3.0). It is asked first, under a hard time budget, and any miss, failure, time-out or missing token falls
 * through to Apple at once. Canvases belong to tracks, so they are cached per track under their own keys; with
 * no Spotify account everything below behaves exactly as it did before.
 */

package com.metrolist.music.canvas

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Base64
import com.metrolist.music.constants.DataSaverKey
import com.metrolist.music.constants.DataSaverMobileOnlyKey
import com.metrolist.music.constants.SpotifyCanvasKey
import com.metrolist.music.spotify.SpotifyAccount
import com.metrolist.music.spotify.SpotifyCanvasClient
import com.metrolist.music.spotify.SpotifyCanvasMatch
import com.metrolist.music.spotify.SpotifyCanvasResult
import com.metrolist.music.spotify.SpotifyCanvasSource
import com.metrolist.music.spotify.SpotifySession
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

object AnimatedArtworkRepository {
    private const val TAG = "AnimatedArtwork"

    private const val AMP = "https://amp-api.music.apple.com"
    private const val STOREFRONT = "us"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private const val HIT_TTL_MS = 14L * 24 * 60 * 60 * 1000
    private const val MISS_TTL_MS = 3L * 24 * 60 * 60 * 1000
    private const val FAILURE_BACKOFF_MS = 10L * 60 * 1000
    private const val TOKEN_SCRAPE_BACKOFF_MS = 30L * 60 * 1000
    private const val LOOKUP_TIMEOUT_MS = 20_000L
    private const val DOWNLOAD_TIMEOUT_MS = 45_000L

    /** The whole Spotify attempt (token, search, canvas) may take this long; then Apple goes on. */
    internal const val SPOTIFY_BUDGET_MS = 6_000L
    private const val SPOTIFY_PAUSE_MS = 60_000L
    private const val MAX_VIDEO_BYTES = 12L * 1024 * 1024
    private const val MAX_CACHE_BYTES = 60L * 1024 * 1024
    private const val MAX_INDEX_ENTRIES = 600

    /** Below this bitrate the 486px variants still look clean on a phone-sized cover. */
    private const val PREFERRED_MAX_BANDWIDTH = 1_600_000L

    /**
     * Apple's public web-player token (issuer AMPWebPlay), as served in music.apple.com's own
     * JS bundle; valid until the end of 2026. When it expires or is rejected a fresh one is
     * read from the bundle again and kept in the cache dir.
     */
    private const val SEED_TOKEN =
        "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6IldlYlBsYXlLaWQifQ" +
            ".eyJpc3MiOiJBTVBXZWJQbGF5IiwiaWF0IjoxNzkyNTM1Njc2LCJleHAiOjE3OTg1ODM2NzYsInJvb3RfaHR0cHNfb3JpZ2luIjpbImFwcGxlLmNvbSJdfQ" +
            ".YpBDqePdK0r_5lxtByqto20zc60FhO1eLoYJ5mrayZ73IYsW4yguaJAFMzud_5FSZ7cjTCdM5ST_MecdbHDA2Q"

    /** Square variants first: the player slot is square. Tall ones are centre-cropped. */
    private val EDITORIAL_VIDEO_KEYS = listOf(
        "motionDetailSquare",
        "motionSquareVideo1x1",
        "motionDetailTall",
        "motionTallVideo3x4",
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** For the web-player bundle (~3 MB, fetched rarely) and video files. */
    private val slowClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    private val lookupLock = Mutex()
    private val downloadLock = Mutex()

    private data class Entry(val url: String?, val expiresAt: Long)

    private val memory = LinkedHashMap<String, Entry>()
    private val failedUntil = HashMap<String, Long>()
    private var indexLoaded = false

    @Volatile private var token: String? = null
    @Volatile private var rejectedToken: String? = null
    @Volatile private var nextScrapeAt = 0L

    internal sealed interface Outcome {
        data class Found(val url: String) : Outcome
        data object Missing : Outcome
        data object Failed : Outcome

        /** Not asked (no Spotify token, data saver): nothing is remembered about the song. */
        data object Unavailable : Outcome
    }

    /** A video URL and, when it came from Spotify, the cache key of that track. */
    private class Hit(val url: String, val spotifyKey: String?)

    /** Replacements for the real network and settings, for tests only; all null in the app. */
    internal class TestHooks {
        @Volatile var spotifySource: SpotifyCanvasSource? = null
        @Volatile var spotifyEnabled: (suspend () -> Boolean)? = null
        @Volatile var dataSaver: (suspend () -> Boolean)? = null
        @Volatile var apple: (suspend (title: String, artist: String, album: String?) -> Outcome)? = null
        @Volatile var download: (suspend (url: String) -> File?)? = null
        @Volatile var spotifyBudgetMs: Long? = null

        /** Whether a Spotify token is being minted in the background (account A's minter); null = ask the real one. */
        @Volatile var spotifyMintRunning: (() -> Boolean)? = null
    }

    internal val testHooks = TestHooks()

    @Volatile private var spotifyClient: SpotifyCanvasSource? = null

    /** After a Spotify time-out or no token, Spotify is not asked again for a while, so the cover never waits twice. */
    @Volatile private var spotifyPausedUntil = 0L

    private class LookupFailed(message: String) : Exception(message)

    /** Whether the device is comfortable decoding a second video stream next to playback. */
    fun isRecommendedForDevice(context: Context): Boolean = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            am?.isLowRamDevice != true &&
            info.totalMem >= 3L * 1000 * 1000 * 1000
    } catch (_: Throwable) {
        false
    }

    /**
     * A local MP4 of the animated cover for this song (Spotify Canvas when connected and on, else Apple's
     * motion artwork), or null (no artwork, offline, data saver, any error). Never throws except for
     * coroutine cancellation; bounded by strict timeouts. [durationSec] and [isVideo] only help to
     * pick the right Spotify track.
     */
    suspend fun videoFile(
        context: Context,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int? = null,
        isVideo: Boolean = false,
    ): File? = withContext(Dispatchers.IO) {
        try {
            val appContext = context.applicationContext
            var spotifyOn = spotifyActive(appContext)
            // A second round happens only when Spotify's file would not download: Apple then gets its turn.
            repeat(2) {
                val hit = withTimeoutOrNull(LOOKUP_TIMEOUT_MS + if (spotifyOn) SPOTIFY_BUDGET_MS else 0L) {
                    findHit(appContext, title, artist, album, durationSec, isVideo, spotifyOn)
                } ?: return@withContext null
                cachedFileFor(appContext, hit.url)?.let { return@withContext it }
                if (dataSaverActive(appContext)) return@withContext null
                val file = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) { downloadFile(appContext, hit.url) }
                if (file != null || hit.spotifyKey == null) return@withContext file
                dropSpotifyHit(appContext, hit.spotifyKey)
                spotifyOn = false
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.tag(TAG).w("videoFile failed: ${t.javaClass.simpleName}")
            null
        }
    }

    /** Drops a local file that failed to play so it is fetched again next time. */
    fun forget(file: File) {
        runCatching { file.delete() }
    }

    /** The remote MP4 URL of the animated cover, or null. Cached in memory and on disk, misses included. */
    suspend fun findVideoUrl(
        context: Context,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int? = null,
        isVideo: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        findHit(context, title, artist, album, durationSec, isVideo, spotifyActive(context))?.url
    }

    private suspend fun findHit(
        context: Context,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int?,
        isVideo: Boolean,
        spotifyOn: Boolean,
    ): Hit? = withContext(Dispatchers.IO) {
        try {
            val albumKey = album?.let(::normalize).orEmpty()
            val key = normalize(artist.primaryArtist()) + "|" +
                (if (albumKey.isNotEmpty()) "a:$albumKey" else "s:" + normalize(title))
            lookupLock.withLock {
                loadIndex(context)
                if (spotifyOn) {
                    spotifyHit(context, title, artist, album, durationSec, isVideo)?.let { return@withLock it }
                }
                cachedOrLookup(context, key) { lookup(context, title, artist, album) }?.let { Hit(it, null) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.tag(TAG).w("findVideoUrl failed: ${t.javaClass.simpleName}")
            null
        }
    }

    /** The memory/disk cache around one provider's lookup: hit, remembered miss, failure back-off. Call with [lookupLock] held. */
    private suspend fun cachedOrLookup(context: Context, key: String, lookup: suspend () -> Outcome): String? {
        val now = System.currentTimeMillis()
        memory[key]?.takeIf { it.expiresAt > now }?.let { return it.url }
        failedUntil[key]?.takeIf { it > now }?.let { return null }
        return when (val outcome = lookup()) {
            is Outcome.Found -> {
                memory[key] = Entry(outcome.url, now + HIT_TTL_MS)
                saveIndex(context)
                outcome.url
            }
            Outcome.Missing -> {
                memory[key] = Entry(null, now + MISS_TTL_MS)
                saveIndex(context)
                null
            }
            Outcome.Failed -> {
                failedUntil[key] = now + FAILURE_BACKOFF_MS
                null
            }
            Outcome.Unavailable -> null
        }
    }

    // Spotify Canvas (first choice when an account is connected)

    /** Spotify is asked only with a connected account and the switch on; otherwise nothing of this code runs. */
    private suspend fun spotifyActive(context: Context): Boolean {
        val hook = testHooks.spotifyEnabled
        if (hook != null) return hook()
        if (!SpotifySession.connected.value) return false
        return try {
            context.dataStore.data.first()[SpotifyCanvasKey] ?: true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Spotify's canvas for this track, from the cache or one budgeted lookup; null on a miss, a failure, a
     * time-out, no token or an unknown song, and Apple goes next. Keys belong to the track, not the album.
     */
    private suspend fun spotifyHit(
        context: Context,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int?,
        isVideo: Boolean,
    ): Hit? {
        val key = SpotifyCanvasMatch.cacheKey(title, artist) ?: return null
        val url = cachedOrLookup(context, key) {
            spotifyOutcome(context, title, artist, album, durationSec, isVideo)
        }
        return url?.let { Hit(it, key) }
    }

    private suspend fun spotifyOutcome(
        context: Context,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int?,
        isVideo: Boolean,
    ): Outcome {
        return try {
            // Lookups answer with ~70 KB of JSON: with data saver on only what is cached is used.
            if (System.currentTimeMillis() < spotifyPausedUntil || dataSaverActive(context)) return Outcome.Unavailable
            // A token mint (a WebView loading the web player) is still running in the background, started by an earlier
            // lookup that ran out of time: Apple goes on at once and the mint finishes by itself, so a later track finds a
            // warm token. Waiting for it again here would cost every track the full budget.
            if (mintRunning()) return Outcome.Unavailable
            val source = testHooks.spotifySource ?: spotifyClient ?: SpotifyCanvasClient.create(context).also { spotifyClient = it }
            val result = withTimeoutOrNull(testHooks.spotifyBudgetMs ?: SPOTIFY_BUDGET_MS) {
                source.find(title, artist, album, durationSec, isVideo)
            }
            when (result) {
                is SpotifyCanvasResult.Found -> Outcome.Found(result.url)
                SpotifyCanvasResult.Missing -> Outcome.Missing
                SpotifyCanvasResult.Unavailable -> {
                    spotifyPausedUntil = System.currentTimeMillis() + SPOTIFY_PAUSE_MS
                    Outcome.Unavailable
                }
                SpotifyCanvasResult.Failed -> Outcome.Failed
                null ->
                    if (mintRunning()) {
                        // the budget ended while the token was still being minted (it keeps running): nothing is held
                        // against the song, and Spotify is asked again as soon as the token is warm
                        Outcome.Unavailable
                    } else {
                        spotifyPausedUntil = System.currentTimeMillis() + SPOTIFY_PAUSE_MS
                        Outcome.Failed
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.tag(TAG).d("spotify lookup error: ${t.javaClass.simpleName}")
            Outcome.Failed
        }
    }

    private fun mintRunning(): Boolean = testHooks.spotifyMintRunning?.invoke() ?: SpotifyAccount.isMintRunning()

    /** Spotify's file could not be downloaded: forget that answer for a while so Apple's can be used. */
    private suspend fun dropSpotifyHit(context: Context, key: String) {
        lookupLock.withLock {
            memory.remove(key)
            failedUntil[key] = System.currentTimeMillis() + FAILURE_BACKOFF_MS
            saveIndex(context)
        }
    }

    private suspend fun dataSaverActive(context: Context): Boolean = testHooks.dataSaver?.invoke() ?: isDataSaverActive(context)

    private suspend fun downloadFile(context: Context, url: String): File? {
        val hook = testHooks.download
        return if (hook != null) hook(url) else download(context, url)
    }

    /** Clears what is remembered in memory (tests only; the real index file is not touched). */
    internal fun resetForTest() {
        memory.clear()
        failedUntil.clear()
        indexLoaded = false
        spotifyClient = null
        spotifyPausedUntil = 0L
    }

    // Lookup

    private suspend fun lookup(context: Context, title: String, artist: String, album: String?): Outcome = try {
        val hook = testHooks.apple
        if (hook != null) hook(title, artist, album) else lookupOrThrow(context, title, artist, album)
    } catch (e: CancellationException) {
        throw e
    } catch (e: LookupFailed) {
        Timber.tag(TAG).d("lookup not possible: ${e.message}")
        Outcome.Failed
    } catch (t: Throwable) {
        Timber.tag(TAG).d("lookup error: ${t.javaClass.simpleName}")
        Outcome.Failed
    }

    private suspend fun lookupOrThrow(context: Context, title: String, artist: String, album: String?): Outcome {
        val primary = artist.primaryArtist()

        // 1. The album itself: motion artwork belongs to albums.
        if (!album.isNullOrBlank()) {
            val root = amp(
                context,
                "/v1/catalog/$STOREFRONT/search",
                "term" to "$primary $album".take(200),
                "types" to "albums",
                "limit" to "10",
                "extend" to "editorialVideo",
            ) ?: return Outcome.Missing
            val items = root.optJSONObject("results")?.optJSONObject("albums")?.optJSONArray("data")
            var matchedWithoutVideo = false
            items?.forEachObject { item ->
                val attrs = item.optJSONObject("attributes") ?: return@forEachObject
                if (!titleMatches(album, attrs.optString("name")) || !artistMatches(primary, attrs.optString("artistName"))) {
                    return@forEachObject
                }
                val master = editorialVideoUrl(attrs.optJSONObject("editorialVideo"))
                if (master == null) {
                    matchedWithoutVideo = true
                    return@forEachObject
                }
                return resolveProgressiveMp4(master)?.let { Outcome.Found(it) } ?: Outcome.Missing
            }
            if (matchedWithoutVideo) return Outcome.Missing
        }

        // 2. Through the song, for singles or album titles that differ between catalogs.
        val root = amp(
            context,
            "/v1/catalog/$STOREFRONT/search",
            "term" to "$primary $title".take(200),
            "types" to "songs",
            "limit" to "10",
        ) ?: return Outcome.Missing
        val songs = root.optJSONObject("results")?.optJSONObject("songs")?.optJSONArray("data")
        var albumId: String? = null
        songs?.forEachObject { item ->
            val attrs = item.optJSONObject("attributes") ?: return@forEachObject
            if (albumId == null &&
                titleMatches(title, attrs.optString("name")) &&
                artistMatches(primary, attrs.optString("artistName"))
            ) {
                albumId = albumIdFromUrl(attrs.optString("url"))
            }
        }
        val id = albumId ?: return Outcome.Missing
        val detail = amp(context, "/v1/catalog/$STOREFRONT/albums/$id", "extend" to "editorialVideo")
            ?: return Outcome.Missing
        val attrs = detail.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes")
            ?: return Outcome.Missing
        val master = editorialVideoUrl(attrs.optJSONObject("editorialVideo")) ?: return Outcome.Missing
        return resolveProgressiveMp4(master)?.let { Outcome.Found(it) } ?: Outcome.Missing
    }

    private fun editorialVideoUrl(editorialVideo: JSONObject?): String? {
        editorialVideo ?: return null
        for (key in EDITORIAL_VIDEO_KEYS) {
            val asset = editorialVideo.optJSONObject(key) ?: continue
            val url = asset.optString("video").takeIf { it.startsWith("https://") }
            if (url != null) return url
        }
        return null
    }

    private fun albumIdFromUrl(url: String): String? {
        val path = url.substringAfter("/album/", "").substringBefore("?")
        return path.substringAfterLast("/", "").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    // HLS playlist -> single progressive MP4

    private data class Variant(val uri: String, val width: Int, val bandwidth: Long)

    private val resolutionRegex = Regex("""RESOLUTION=(\d+)x(\d+)""")
    private val bandwidthRegex = Regex("""[,:]BANDWIDTH=(\d+)""")
    private val codecsRegex = Regex("""CODECS="([^"]+)"""")
    private val mapRegex = Regex("""#EXT-X-MAP:URI="([^"]+)"""")

    /** Picks an H.264 variant of the motion artwork and returns its single MP4 file, or null. */
    private fun resolveProgressiveMp4(masterUrl: String): String? {
        val master = getText(client, masterUrl) ?: throw LookupFailed("master playlist")
        val lines = master.lines().map { it.trim() }
        if (lines.none { it.startsWith("#EXT-X-STREAM-INF") }) {
            return singleFileOf(masterUrl, master)
        }
        val variants = mutableListOf<Variant>()
        lines.forEachIndexed { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF:")) return@forEachIndexed
            val codecs = codecsRegex.find(line)?.groupValues?.get(1).orEmpty()
            if (!codecs.startsWith("avc1")) return@forEachIndexed
            val uri = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@forEachIndexed
            val width = resolutionRegex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val bandwidth = bandwidthRegex.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE
            variants += Variant(resolve(masterUrl, uri), width, bandwidth)
        }
        val chosen = variants.filter { it.width >= 480 && it.bandwidth <= PREFERRED_MAX_BANDWIDTH }.maxByOrNull { it.bandwidth }
            ?: variants.filter { it.width >= 480 }.minByOrNull { it.bandwidth }
            ?: variants.maxByOrNull { it.width }
            ?: return null
        val media = getText(client, chosen.uri) ?: throw LookupFailed("media playlist")
        return singleFileOf(chosen.uri, media)
    }

    /** The one MP4 a byte-range media playlist points into, or null when segments are separate files. */
    private fun singleFileOf(playlistUrl: String, playlist: String): String? {
        val files = mutableSetOf<String>()
        mapRegex.find(playlist)?.groupValues?.get(1)?.let { files += it }
        playlist.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { files += it }
        val only = files.singleOrNull() ?: return null
        if (!only.substringBefore("?").endsWith(".mp4", ignoreCase = true)) return null
        return resolve(playlistUrl, only)
    }

    private fun resolve(base: String, relative: String): String = runCatching { URI(base).resolve(relative).toString() }.getOrDefault(relative)

    // Transport

    private suspend fun amp(context: Context, path: String, vararg params: Pair<String, String>): JSONObject? {
        var bearer = currentToken(context, forceRefresh = false) ?: throw LookupFailed("no token")
        var response = ampGet(path, params, bearer)
        if (response.first == 401 || response.first == 403) {
            rejectedToken = bearer
            token = null
            bearer = currentToken(context, forceRefresh = true) ?: throw LookupFailed("token rejected")
            response = ampGet(path, params, bearer)
        }
        return when (response.first) {
            200 -> runCatching { JSONObject(response.second.orEmpty()) }.getOrNull()
            404 -> null
            else -> throw LookupFailed("$path -> ${response.first}")
        }
    }

    private fun ampGet(path: String, params: Array<out Pair<String, String>>, bearer: String): Pair<Int, String?> {
        val url = (AMP + path).toHttpUrlOrNull()?.newBuilder()?.apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }?.build() ?: throw LookupFailed("bad url")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearer")
            .header("Origin", "https://music.apple.com")
            .header("Referer", "https://music.apple.com/")
            .header("User-Agent", USER_AGENT)
            .build()
        return try {
            client.newCall(request).execute().use { it.code to it.body?.string() }
        } catch (e: java.io.IOException) {
            throw LookupFailed("network: ${e.javaClass.simpleName}")
        }
    }

    private fun getText(httpClient: OkHttpClient, url: String): String? = try {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        httpClient.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (e: java.io.IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    // Token

    private val jwtRegex = Regex("""eyJ[\w-]+\.eyJ[\w-]+\.[\w-]+""")
    private val bundleRegex = Regex("""src="(/assets/index[^"]*\.js)"""")

    private fun tokenFile(context: Context) = File(context.cacheDir, "animated_artwork/webplay.token")

    private fun currentToken(context: Context, forceRefresh: Boolean): String? {
        if (!forceRefresh) {
            token?.takeIf { usable(it) }?.let { return it }
            runCatching { tokenFile(context).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
                ?.takeIf { usable(it) }
                ?.let { token = it; return it }
            SEED_TOKEN.takeIf { usable(it) }?.let { token = it; return it }
        }
        val now = System.currentTimeMillis()
        if (now < nextScrapeAt) return null
        val fresh = scrapeToken()
        if (fresh == null) {
            nextScrapeAt = now + TOKEN_SCRAPE_BACKOFF_MS
            return null
        }
        token = fresh
        runCatching { tokenFile(context).apply { parentFile?.mkdirs() }.writeText(fresh) }
        return fresh
    }

    private fun usable(jwt: String): Boolean {
        if (jwt == rejectedToken) return false
        val payload = jwtPayload(jwt) ?: return false
        if (payload.optString("iss") != "AMPWebPlay") return false
        val exp = payload.optLong("exp", 0L)
        return exp - 86_400 > System.currentTimeMillis() / 1000
    }

    private fun jwtPayload(jwt: String): JSONObject? = runCatching {
        val part = jwt.split('.').getOrNull(1) ?: return null
        JSONObject(String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
    }.getOrNull()

    /** Reads the current web-player token from music.apple.com's JS bundle. */
    private fun scrapeToken(): String? {
        val html = getText(slowClient, "https://music.apple.com/us/new") ?: return null
        val bundles = bundleRegex.findAll(html).map { it.groupValues[1] }.distinct()
            .sortedBy { if ("legacy" in it) 1 else 0 }.toList()
        for (bundle in bundles) {
            val js = getText(slowClient, "https://music.apple.com$bundle") ?: continue
            jwtRegex.findAll(js).map { it.value }.firstOrNull { usable(it) }?.let { return it }
        }
        Timber.tag(TAG).d("token scrape found nothing (${bundles.size} bundles)")
        return null
    }

    // Video files

    private fun videoDir(context: Context) = File(context.cacheDir, "animated_artwork/videos")

    private fun fileName(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + ".mp4"

    private fun cachedFileFor(context: Context, url: String): File? {
        val file = File(videoDir(context), fileName(url))
        if (!file.isFile || file.length() == 0L) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    private suspend fun download(context: Context, url: String): File? = downloadLock.withLock {
        cachedFileFor(context, url)?.let { return@withLock it }
        val dir = videoDir(context).apply { mkdirs() }
        val target = File(dir, fileName(url))
        val part = File(dir, target.name + ".part")
        try {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            slowClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withLock null
                val body = response.body ?: return@withLock null
                if (body.contentLength() > MAX_VIDEO_BYTES) return@withLock null
                var total = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_VIDEO_BYTES) return@withLock null
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            if (!part.renameTo(target)) return@withLock null
            trimVideos(dir)
            target
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        } finally {
            if (part.exists()) part.delete()
        }
    }

    private fun trimVideos(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") } ?: return
        var kept = 0L
        files.sortedByDescending { it.lastModified() }.forEach { file ->
            kept += file.length()
            if (kept > MAX_CACHE_BYTES) file.delete()
        }
    }

    /** The phone's own Data Saver on a metered network, or Auralys' data saver (settings) when it applies. */
    private suspend fun isDataSaverActive(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val metered = cm != null && cm.isActiveNetworkMetered
        val prefs = context.dataStore.data.first()
        val appSaver = (prefs[DataSaverKey] ?: false) && (!(prefs[DataSaverMobileOnlyKey] ?: true) || metered)
        appSaver || (cm != null && metered &&
            cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        false
    }

    // Index (small JSON in the cache dir; misses are remembered too)

    private fun indexFile(context: Context) = File(context.cacheDir, "animated_artwork/index.json")

    private fun loadIndex(context: Context) {
        if (indexLoaded) return
        indexLoaded = true
        runCatching {
            val file = indexFile(context)
            if (!file.isFile) return
            val root = JSONObject(file.readText())
            val now = System.currentTimeMillis()
            root.keys().forEach { key ->
                val o = root.optJSONObject(key) ?: return@forEach
                val expiresAt = o.optLong("e", 0L)
                if (expiresAt <= now) return@forEach
                val url = if (o.isNull("u")) null else o.optString("u").takeIf { it.isNotEmpty() }
                memory[key] = Entry(url, expiresAt)
            }
        }
    }

    private fun saveIndex(context: Context) {
        runCatching {
            val now = System.currentTimeMillis()
            memory.entries.removeAll { it.value.expiresAt <= now }
            while (memory.size > MAX_INDEX_ENTRIES) {
                val eldest = memory.keys.firstOrNull() ?: break
                memory.remove(eldest)
            }
            val root = JSONObject()
            memory.forEach { (key, entry) ->
                root.put(key, JSONObject().put("u", entry.url ?: JSONObject.NULL).put("e", entry.expiresAt))
            }
            val file = indexFile(context)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        }
    }

    // Matching (after Convx's AppleMusicCatalog.normalize / artistMatches)

    private val bracketed = Regex("""\s*[(\[][^)\]]*(feat|ft\.|with |remaster|version|edit|deluxe|explicit|clean|single|bonus)[^)\]]*[)\]]""")
    private val trailingKind = Regex("""\s+-\s+(single|ep)$""")
    private val marks = Regex("""\p{Mn}+""")
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")
    private val artistSplit = Regex("""\s*,\s*|\s+&\s+|\s+x\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+featuring\s+""", RegexOption.IGNORE_CASE)

    internal fun normalize(s: String): String {
        val base = s.lowercase(Locale.ROOT).replace(bracketed, "").replace(trailingKind, "").replace("&", " and ")
        return Normalizer.normalize(base, Normalizer.Form.NFD).replace(marks, "").replace(nonWord, " ").trim()
    }

    private fun String.primaryArtist(): String = split(artistSplit).firstOrNull { it.isNotBlank() }?.trim() ?: this

    private fun titleMatches(expected: String, candidate: String): Boolean {
        val e = normalize(expected)
        val c = normalize(candidate)
        if (e.isEmpty() || c.isEmpty()) return false
        if (e == c) return true
        val shorter = minOf(e.length, c.length)
        return shorter >= 4 && (c.startsWith("$e ") || e.startsWith("$c "))
    }

    private fun artistMatches(expected: String, candidate: String): Boolean {
        val e = normalize(expected)
        val c = normalize(candidate)
        val cFirst = normalize(candidate.primaryArtist())
        // Whole-word containment: "Drake" must not match "Drakeo the Ruler".
        return e.isNotEmpty() && (" $c ".contains(" $e ") || (cFirst.isNotEmpty() && " $e ".contains(" $cFirst ")))
    }

    private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
        for (i in 0 until length()) optJSONObject(i)?.let(block)
    }
}
