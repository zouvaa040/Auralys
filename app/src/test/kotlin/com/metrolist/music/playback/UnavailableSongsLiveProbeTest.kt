/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PlayerConfig
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.music.utils.InnerTubeXPlayer
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * LIVE PROBE for the "This track is unavailable" / "No playable stream found" reports of 4.3.3 (Anak Kampung, Brooklyn
 * Baby, Toosie Slide) and for the broken-download report (About You). Skipped unless `LIVE_UNAVAILABLE=1`; never part of a
 * normal run. Output: `LIVE_UNAVAILABLE_OUT` (a text report).
 *
 * Per song it records:
 *  1. the RAW player answer of every client the library knows (playabilityStatus, reason, sub-reason, the audio formats:
 *     itag, DRC, container, `url` / `signatureCipher`, `n` parameter, contentLength, lmt), and a 1 KiB range read of every
 *     direct audio address (status + content type);
 *  2. the app's own extraction (the hints of [InnerTubeXPlayer]), a retry without VISIONOS, and one extraction per
 *     catalogue profile forced with `playbackClientOverrideId`;
 *  3. the search alternatives (videos of the same title + artist) and whether VISIONOS gives them a direct address.
 * For About You also the 4.3.3 download address (`&range=0-N` in the URL) read from the middle with a Range header.
 *
 * The JVM has no WebView (no PO token) and no QuickJS (no cipher): clients that need either cannot give an address here,
 * their playability answer still shows. It never tries to get around a bot check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UnavailableSongsLiveProbeTest {
    private val NL = System.lineSeparator()

    private val lines = ArrayList<String>()

    private fun say(text: String) {
        println(text)
        lines += text
    }

    private val memoryConfig =
        object : PlayerConfigRepository {
            override val enabled = true
            override val sourceUrl = "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
            override val defaultSourceUrl = sourceUrl
            override var cachedJson = ""
            override var cachedAtMs = 0L
            override var cachedSourceUrl = ""
            override var cachedEtag = ""
        }

    private val noWebViewToken =
        object : TokenProvider {
            override val capabilities = TokenProviderCapabilities(providers = setOf(PoTokenProviderKind.WEB_BOTGUARD), usesWebView = true)

            override suspend fun getPoToken(
                videoId: String,
                visitorData: String,
                cookie: String?,
            ): PoTokenResult? = null

            override suspend fun close() = Unit
        }

    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

    private val json = Json { ignoreUnknownKeys = true }

    private class Target(
        val label: String,
        val videoId: String?,
        val query: String,
    )

    private val targets =
        listOf(
            Target("report 1 (Infinix, unavailable)", "vRj31u2UIlU", "Maman Fvndy Anak Kampung"),
            Target("report 2 (TECNO, no playable stream)", "ntZONYzAMCo", "Lana Del Rey Brooklyn Baby"),
            Target("report 3 (TECNO, Toosie Slide)", "eqMj9DTQcAQ", "Drake Toosie Slide"),
            Target("report 4 (TECNO, Not You Too)", "12NrPQ9cxyg", "Drake Not You Too Chris Brown"),
            Target("download report (About You)", null, "The 1975 About You"),
            Target("control", null, "Cup of Joe Multo"),
        )

    private val rawClients: List<Pair<String, YouTubeClient>> =
        listOf(
            "VISIONOS_0_1" to YouTubeClient.VISIONOS_0_1,
            "VISIONOS" to YouTubeClient.VISIONOS,
            "ANDROID_VR_1_65_10" to YouTubeClient.ANDROID_VR_1_65_10,
            "ANDROID_VR_1_61_48" to YouTubeClient.ANDROID_VR_1_61_48,
            "ANDROID_VR_1_43_32" to YouTubeClient.ANDROID_VR_1_43_32,
            "ANDROID_VR_NO_AUTH" to YouTubeClient.ANDROID_VR_NO_AUTH,
            "IOS" to YouTubeClient.IOS,
            "IPADOS" to YouTubeClient.IPADOS,
            "IOS_MUSIC" to YouTubeClient.IOS_MUSIC,
            "ANDROID" to YouTubeClient.ANDROID,
            "ANDROID_MUSIC" to YouTubeClient.ANDROID_MUSIC,
            "ANDROID_CREATOR" to YouTubeClient.ANDROID_CREATOR,
            "ANDROID_KIDS" to YouTubeClient.ANDROID_KIDS,
            "ANDROID_PRODUCER" to YouTubeClient.ANDROID_PRODUCER,
            "TVHTML5" to YouTubeClient.TVHTML5,
            "TVHTML5_DOWNGRADED" to YouTubeClient.TVHTML5_DOWNGRADED,
            "TVHTML5_SIMPLY" to YouTubeClient.TVHTML5_SIMPLY,
            "TVHTML5_SIMPLY_EMBEDDED_PLAYER" to YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
            "WEB_REMIX" to YouTubeClient.WEB_REMIX,
            "WEB" to YouTubeClient.WEB,
            "WEB_SAFARI" to YouTubeClient.WEB_SAFARI,
            "MWEB" to YouTubeClient.MWEB,
            "WEB_EMBEDDED_PLAYER" to YouTubeClient.WEB_EMBEDDED_PLAYER,
            "WEB_KIDS" to YouTubeClient.WEB_KIDS,
            "WEB_CREATOR" to YouTubeClient.WEB_CREATOR,
            "MEDIA_CONNECT_FRONTEND" to YouTubeClient.MEDIA_CONNECT_FRONTEND,
        )

    private val gapMs = (System.getenv("LIVE_UNAVAILABLE_GAP_MS")?.toLongOrNull() ?: 450L)

    private class Bundle(
        val extractor: InnerTubeExtractor,
        val parser: YtConfigParser,
    )

    private suspend fun buildBundle(): Bundle {
        val t = YouTube.extractionTransport()
        val logger = InnerTubeLogger { }
        val store = RemotePlayerConfigStore(t.httpClient, memoryConfig, logger)
        val cipher = YouTubeCipherService(t.httpClient, store, logger)
        val parser: YtConfigParser = YtConfigParserImpl(t.httpClient, t.innerTube, store, logger)
        val withFallbackConfig =
            object : YtConfigParser by parser {
                override suspend fun fetchConfig(
                    videoId: String,
                    useLoginCookies: Boolean,
                ) = try {
                    parser.fetchConfig(videoId, useLoginCookies)
                } catch (_: IllegalStateException) {
                    parser.fetchEmbeddedConfig(videoId, useLoginCookies = false)
                }
            }
        return Bundle(
            InnerTubeExtractor(
                configParser = withFallbackConfig,
                cipherService = cipher,
                innerTube = t.innerTube,
                tokenProvider = noWebViewToken,
                logger = logger,
            ),
            withFallbackConfig,
        )
    }

    // ------------------------------------------------------------------------------------------ JSON helpers

    private fun JsonElement?.at(vararg path: String): JsonElement? {
        var e: JsonElement? = this
        for (p in path) e = (e as? JsonObject)?.get(p) ?: return null
        return e
    }

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString || it.toString() != "null" }?.content

    private fun JsonElement?.runsText(): String? =
        this.at("simpleText").text()
            ?: (this.at("runs") as? JsonArray)?.joinToString("") { it.at("text").text().orEmpty() }?.takeIf { it.isNotBlank() }

    private class AudioFormat(
        val itag: Int,
        val drc: Boolean,
        val mime: String,
        val url: String?,
        val cipher: Boolean,
        val length: Long?,
        val lmt: String?,
        val track: String?,
        val bitrate: Long?,
    ) {
        val hasN: Boolean get() = url?.contains("&n=") == true || url?.contains("?n=") == true

        override fun toString(): String =
            "$itag${if (drc) "-drc" else ""}/${if (mime.contains("webm")) "webm" else if (mime.contains("mp4")) "m4a" else mime}" +
                "/${if (url != null) "url" else if (cipher) "sigCipher" else "none"}${if (hasN) "+n" else ""}" +
                "/len=${length ?: "?"}/lmt=${lmt ?: "?"}${if (track != null) "/track=$track" else ""}/br=${bitrate ?: "?"}"
    }

    private fun audioFormats(root: JsonElement?): List<AudioFormat> {
        val adaptive = root.at("streamingData", "adaptiveFormats") as? JsonArray ?: return emptyList()
        return adaptive.mapNotNull { f ->
            val mime = f.at("mimeType").text() ?: return@mapNotNull null
            if (!mime.startsWith("audio/")) return@mapNotNull null
            AudioFormat(
                itag = f.at("itag").text()?.toIntOrNull() ?: -1,
                drc = f.at("isDrc").text() == "true",
                mime = mime,
                url = f.at("url").text(),
                cipher = f.at("signatureCipher").text() != null || f.at("cipher").text() != null,
                length = f.at("contentLength").text()?.toLongOrNull(),
                lmt = f.at("lastModified").text(),
                track = f.at("audioTrack", "displayName").text(),
                bitrate = f.at("bitrate").text()?.toLongOrNull(),
            )
        }
    }

    private fun describe(
        code: Int,
        root: JsonElement?,
        requested: String,
    ): String {
        if (root == null) return "HTTP $code (no JSON)"
        val status = root.at("playabilityStatus", "status").text()
        val reason = root.at("playabilityStatus", "reason").text()
        val sub = root.at("playabilityStatus", "errorScreen", "playerErrorMessageRenderer", "subreason").runsText()
        val sd = root.at("streamingData")
        val muxed = (sd.at("formats") as? JsonArray)?.size ?: 0
        val muxedCipher = (sd.at("formats") as? JsonArray)?.count { it.at("signatureCipher").text() != null } ?: 0
        val video = (sd.at("adaptiveFormats") as? JsonArray)?.count { it.at("mimeType").text()?.startsWith("video/") == true } ?: 0
        val audio = audioFormats(root)
        val hls = sd.at("hlsManifestUrl").text() != null
        val sabr = sd.at("serverAbrStreamingUrl").text() != null
        val vid = root.at("videoDetails", "videoId").text()
        val mvt = root.at("videoDetails", "musicVideoType").text()
        val countries = (root.at("microformat", "playerMicroformatRenderer", "availableCountries") as? JsonArray)
        val inId = countries?.any { it.text() == "ID" }
        val drm = sd.at("drmParams").text() != null
        return buildString {
            append("HTTP $code status=$status")
            if (reason != null) append(" reason=\"$reason\"")
            if (sub != null) append(" sub=\"${sub.take(140)}\"")
            if (vid != null && vid != requested) append(" OTHER_VIDEO=$vid")
            if (mvt != null) append(" type=$mvt")
            if (countries != null) append(" countries=${countries.size} ID=$inId")
            if (sd != null) append(" | audio=${audio.size} video=$video muxed=$muxed(sig=$muxedCipher) hls=$hls sabr=$sabr drm=$drm")
            if (audio.isNotEmpty()) append("\n        ").append(audio.joinToString(" "))
        }
    }

    private fun rangeRead(
        url: String,
        headers: Map<String, String>,
        from: Long,
        to: Long,
        extraUrl: String = "",
    ): String =
        try {
            val request =
                Request
                    .Builder()
                    .url(url + extraUrl)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .header("Range", "bytes=$from-$to")
                    .build()
            http.newCall(request).execute().use { r ->
                val body = r.body.byteStream().readNBytes(16)
                "${r.code} ${r.header("Content-Type")} cr=${r.header("Content-Range")} cl=${r.header("Content-Length")} head=${body.joinToString("") { "%02x".format(it) }}"
            }
        } catch (e: Exception) {
            "ERR ${e.javaClass.simpleName}: ${e.message?.take(60)}"
        }

    private suspend fun raw(
        bundle: Bundle,
        name: String,
        client: YouTubeClient,
        videoId: String,
        config: PlayerConfig?,
        embedded: PlayerConfig?,
        reads: Boolean,
    ) {
        delay(gapMs)
        val t = YouTube.extractionTransport()
        val useConfig = if (client.isEmbedded) embedded else config
        val text =
            try {
                val response =
                    t.innerTube.player(
                        client = client,
                        videoId = videoId,
                        signatureTimestamp = useConfig?.signatureTimestamp,
                        requestVisitorData = useConfig?.visitorData,
                        encryptedHostFlags = embedded?.encryptedHostFlags.takeIf { client.isEmbedded },
                    )
                response.status.value to response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("   %-30s REQUEST FAILED %s %s".format(name, e.javaClass.simpleName, e.message?.take(80)))
                return
            }
        val root = runCatching { json.parseToJsonElement(text.second) }.getOrNull()
        say("   %-30s %s".format(name, describe(text.first, root, videoId)))
        if (!reads) return
        audioFormats(root).filter { it.url != null && !it.hasN }.take(3).forEach { f ->
            val url = f.url!!
            val first = rangeRead(url, emptyMap(), 0, 1023)
            val tail = f.length?.takeIf { it > 2_000_000 }?.let { rangeRead(url, emptyMap(), it - 1024, it - 1) }
            val mid = f.length?.takeIf { it > 2_000_000 }?.let { rangeRead(url, emptyMap(), 1_048_576, 1_048_576 + 1023) }
            say("        read ${f.itag}${if (f.drc) "-drc" else ""}: first=$first | 1MiB=$mid | tail=$tail")
        }
    }

    private suspend fun appExtraction(
        bundle: Bundle,
        videoId: String,
        explicit: Boolean?,
        excluded: Set<String> = emptySet(),
        override: String? = null,
        quality: AudioQuality = AudioQuality.AUTO,
    ): String {
        val t0 = System.nanoTime()
        return try {
            val hints =
                ContentHints(
                    isExplicit = InnerTubeXPlayer.explicitFirstPass(explicit),
                    isKidsContent = if (explicit == true) false else null,
                    isUploaded = false,
                    playbackClientOverrideId = override,
                ).withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true)
            val stream =
                withTimeoutOrNull(120_000) {
                    bundle.extractor.extract(videoId, hints, excluded, quality, generateClientPlaybackNonce())
                }
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (stream == null) {
                "null/timeout ${ms}ms"
            } else {
                "OK ${ms}ms client=${stream.clientName} profile=${stream.profileId} itag=${stream.itag} ${stream.mimeType};${stream.codecs} " +
                    "len=${stream.contentLengthBytes} bounded=${stream.requireBoundedRange} lmt=${Regex("[?&]lmt=([0-9]+)").find(stream.audioUrl)?.groupValues?.get(1)}" +
                    " attempts=" + stream.streamDiagnostics?.attempts?.joinToString(" ") { "${it.clientName}:${it.outcome.take(28)}" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StreamResolveException) {
            val ms = (System.nanoTime() - t0) / 1_000_000
            "FAIL ${ms}ms ${e.reason} \"${e.message}\" cause=${e.cause?.javaClass?.simpleName}:${e.cause?.message?.take(60)} attempts=" +
                e.diagnostics?.attempts?.joinToString(" ") { "${it.clientName}:${it.outcome.take(28)}" }
        } catch (e: Throwable) {
            "FAIL ${e.javaClass.simpleName}: ${e.message?.take(100)}"
        }
    }

    @Test
    fun probeUnavailableSongsAndDownloadFormats() {
        val on = System.getProperty("live.unavailable") == "1" || System.getenv("LIVE_UNAVAILABLE") == "1"
        assumeTrue("LIVE_UNAVAILABLE not set: the network probe is skipped", on && System.getenv("LIVE_UNAVAILABLE_MODE") != "verify")
        val regions = (System.getenv("LIVE_UNAVAILABLE_GL") ?: "ID,US").split(',').map(String::trim).filter(String::isNotEmpty)
        try {
            runBlocking {
                for ((regionIndex, gl) in regions.withIndex()) {
                    YouTube.locale = YouTubeLocale(gl = gl, hl = "en")
                    YouTube.visitorData = null
                    YouTube.visitorData().getOrNull()?.let { YouTube.visitorData = it }
                    val bundle = buildBundle()
                    say("=== region gl=$gl hl=en  (${java.time.LocalDateTime.now()}), visitorData=${YouTube.visitorData != null}")
                    for (target in targets) {
                        // the second region only for the three reported songs, and only the clients the app can use
                        if (regionIndex > 0 && !target.label.startsWith("report")) continue
                        delay(gapMs)
                        val found =
                            runCatching {
                                YouTube
                                    .search(target.query, YouTube.SearchFilter.FILTER_SONG, recordSearchHistory = false)
                                    .getOrThrow()
                                    .items
                                    .filterIsInstance<SongItem>()
                            }.getOrElse { emptyList() }
                        val song = target.videoId?.let { id -> found.firstOrNull { it.id == id } } ?: found.firstOrNull { !it.isEpisode }
                        val videoId = target.videoId ?: song?.id
                        if (videoId == null) {
                            say("-- ${target.label}: ${target.query}: not found by search")
                            continue
                        }
                        val explicit = song?.takeIf { it.id == videoId }?.explicit
                        say(
                            "-- ${target.label}: $videoId '${song?.title}' - ${song?.artists?.joinToString { it.name }} " +
                                "dur=${song?.duration}s explicit=$explicit (search hit for the id: ${found.any { it.id == videoId }})",
                        )
                        val config = runCatching { bundle.parser.fetchConfig(videoId, false) }.getOrNull()
                        val embedded = runCatching { bundle.parser.fetchEmbeddedConfig(videoId, false) }.getOrNull()
                        say("   watch config sts=${config?.signatureTimestamp} visitor=${config?.visitorData != null}; embed config flags=${embedded?.encryptedHostFlags != null}")
                        val clients = if (regionIndex == 0) rawClients else rawClients.filter { it.first in setOf("VISIONOS_0_1", "VISIONOS", "IOS", "ANDROID_VR_1_65_10", "TVHTML5", "WEB_REMIX", "WEB_EMBEDDED_PLAYER") }
                        for ((name, client) in clients) {
                            raw(bundle, name, client, videoId, config, embedded, reads = regionIndex == 0 && name in setOf("VISIONOS_0_1", "VISIONOS", "IOS", "ANDROID_VR_1_65_10"))
                        }
                        say("   APP extraction (AUTO): ${appExtraction(bundle, videoId, explicit)}")
                        if (regionIndex > 0) continue
                        delay(gapMs)
                        say("   APP extraction (LOW = AUTO on a metered network): ${appExtraction(bundle, videoId, explicit, quality = AudioQuality.LOW)}")
                        delay(gapMs)
                        say("   APP retry without VISIONOS: ${appExtraction(bundle, videoId, explicit, excluded = setOf("VISIONOS"))}")
                        if (!target.label.startsWith("report")) {
                            if (target.label.startsWith("download")) downloadAddressProbe(bundle, videoId, explicit)
                            continue
                        }
                        PlaybackClientCatalog.manifests
                            .map { it.id }
                            .distinct()
                            .filterNot { it.endsWith("_SABR") }
                            .forEach { id ->
                                delay(gapMs)
                                say("   override %-28s %s".format(id, appExtraction(bundle, videoId, explicit, override = id).take(220)))
                            }
                        // alternatives of the same song: what other open-source clients fall back to
                        delay(gapMs)
                        val title = song?.title ?: target.query
                        val artist = song?.artists?.firstOrNull()?.name.orEmpty()
                        val alternatives =
                            runCatching {
                                YouTube
                                    .search("$title $artist", YouTube.SearchFilter.FILTER_VIDEO, recordSearchHistory = false)
                                    .getOrThrow()
                                    .items
                                    .filterIsInstance<SongItem>()
                                    .filter { it.id != videoId }
                                    .take(4)
                            }.getOrElse { emptyList() }
                        say("   alternatives (video search '$title $artist'): ${alternatives.size}")
                        for (alt in alternatives) {
                            say("     alt ${alt.id} '${alt.title}' - ${alt.artists.joinToString { it.name }} dur=${alt.duration}s type=${alt.musicVideoType}")
                            raw(bundle, "  VISIONOS_0_1", YouTubeClient.VISIONOS_0_1, alt.id, config, embedded, reads = false)
                        }
                    }
                }
            }
        } finally {
            System.getenv("LIVE_UNAVAILABLE_OUT")?.let { File(it).writeText(lines.joinToString("\n")) }
        }
    }

    /**
     * The 4.3.3 download read its address with `&range=0-<len-1>` in the URL. A download that resumes (after a failure, or
     * a hole after bytes it took from the player cache) asks that same address with `Range: bytes=X-`. If the server then
     * answers 206 with the bytes from 0 (the URL range wins) or 200, the bytes at X are not the bytes of X.
     */
    private suspend fun downloadAddressProbe(
        bundle: Bundle,
        videoId: String,
        explicit: Boolean?,
    ) {
        val hints =
            ContentHints(isExplicit = InnerTubeXPlayer.explicitFirstPass(explicit), isUploaded = false)
                .withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = false)
        val stream =
            runCatching { bundle.extractor.extract(videoId, hints, emptySet(), AudioQuality.AUTO, generateClientPlaybackNonce()) }.getOrNull()
        val len = stream?.contentLengthBytes
        if (stream == null || len == null || len < 3_000_000) {
            say("   download address probe: no stream / length ($len)")
            return
        }
        val x = 1_048_576L
        say("   download address probe (4.3.3 URL '&range=0-${len - 1}', client ${stream.clientName}, itag ${stream.itag}):")
        say("     plain URL, Range $x-${x + 1023}:            ${rangeRead(stream.audioUrl, stream.headers, x, x + 1023)}")
        say("     plain URL, Range 0-15:                      ${rangeRead(stream.audioUrl, stream.headers, 0, 15)}")
        say("     URL+&range=0-N, Range $x-${x + 1023}:       ${rangeRead(stream.audioUrl, stream.headers, x, x + 1023, "&range=0-${len - 1}")}")
        say("     URL+&range=0-N, Range $x-${len - 1}:        ${rangeRead(stream.audioUrl, stream.headers, x, len - 1, "&range=0-${len - 1}")}")
    }

    /**
     * `LIVE_UNAVAILABLE_MODE=verify`: (1) the 4.3.3 hints (isExplicit=true as stored in the database) for the reported
     * explicit songs; (2) the app's real entry point [InnerTubeXPlayer.playerResponseForPlayback] with VISIONOS marked as
     * failed for the song: the JVM then has what the TECNO phone had (no WebView token, no QuickJS), so the result shows
     * what the new rescue + alternative path gives.
     */
    @Test
    fun verifyRescueAndAlternative() {
        val on = System.getenv("LIVE_UNAVAILABLE") == "1" && System.getenv("LIVE_UNAVAILABLE_MODE") == "verify"
        assumeTrue("LIVE_UNAVAILABLE_MODE=verify not set", on)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        try {
            runBlocking {
                YouTube.locale = YouTubeLocale(gl = "ID", hl = "en")
                YouTube.visitorData().getOrNull()?.let { YouTube.visitorData = it }
                InnerTubeXPlayer.initialize(context)
                val bundle = buildBundle()
                for (target in targets.filter { it.videoId != null }) {
                    val id = target.videoId!!
                    delay(gapMs)
                    val song =
                        runCatching {
                            YouTube.search(target.query, YouTube.SearchFilter.FILTER_SONG, recordSearchHistory = false).getOrThrow().items
                                .filterIsInstance<SongItem>().firstOrNull { it.id == id }
                        }.getOrNull()
                    say("-- ${target.label} $id '${song?.title}' explicit=${song?.explicit} dur=${song?.duration}")
                    // the hints of 4.3.3 (no explicit first pass): isExplicit as the database stores it
                    val old =
                        try {
                            val hints =
                                ContentHints(isExplicit = song?.explicit, isUploaded = false)
                                    .withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true)
                            val s = bundle.extractor.extract(id, hints, emptySet(), AudioQuality.AUTO, generateClientPlaybackNonce())
                            "OK client=${s?.clientName} itag=${s?.itag}"
                        } catch (e: StreamResolveException) {
                            "FAIL ${e.reason} \"${e.message}\" attempts=" + e.diagnostics?.attempts?.joinToString(" ") { "${it.clientName}:${it.outcome.take(28)}" }
                        } catch (e: Throwable) {
                            "FAIL ${e.javaClass.simpleName}: ${e.message?.take(80)}"
                        }
                    say("   4.3.3 hints (isExplicit=${song?.explicit}): $old")
                    InnerTubeXPlayer.clearStreamClientFailures()
                    InnerTubeXPlayer.markStreamClientFailed(id, "VISIONOS")
                    val info =
                        song?.let {
                            com.metrolist.music.utils.AlternativeMatch.Song(id, it.title, it.artists.map { a -> a.name }, it.duration)
                        }
                    val t0 = System.nanoTime()
                    val r =
                        InnerTubeXPlayer.playerResponseForPlayback(
                            videoId = id,
                            audioQuality = com.metrolist.music.constants.AudioQuality.AUTO,
                            connectivityManager = connectivity,
                            contentHints = ContentHints(isExplicit = song?.explicit, isUploaded = false),
                            song = info,
                        )
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    say(
                        r.fold(
                            { "   NEW path, VISIONOS failed for this song: OK ${ms}ms client=${it.streamClient} itag=${it.format.itag} alternative=${it.alternativeVideoId}" },
                            { "   NEW path, VISIONOS failed for this song: FAIL ${ms}ms ${it.javaClass.simpleName}: ${it.message?.take(100)}" },
                        ),
                    )
                    InnerTubeXPlayer.clearStreamClientFailures()
                    delay(gapMs)
                    val t1 = System.nanoTime()
                    val n =
                        InnerTubeXPlayer.playerResponseForPlayback(
                            videoId = id,
                            audioQuality = com.metrolist.music.constants.AudioQuality.AUTO,
                            connectivityManager = connectivity,
                            contentHints = ContentHints(isExplicit = song?.explicit, isUploaded = false),
                            song = info,
                        )
                    val ms1 = (System.nanoTime() - t1) / 1_000_000
                    say(
                        n.fold(
                            { "   NEW path, normal: OK ${ms1}ms client=${it.streamClient} itag=${it.format.itag} alternative=${it.alternativeVideoId}" },
                            { "   NEW path, normal: FAIL ${ms1}ms ${it.javaClass.simpleName}: ${it.message?.take(100)}" },
                        ),
                    )
                }
            }
        } finally {
            System.getenv("LIVE_UNAVAILABLE_OUT")?.let { File(it).writeText(lines.joinToString(NL)) }
        }
    }
}
