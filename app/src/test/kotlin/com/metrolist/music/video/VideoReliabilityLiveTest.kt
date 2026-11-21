package com.metrolist.music.video

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.music.utils.InnerTubeXPlayer
import com.metrolist.music.version.OfficialCounterpart
import com.metrolist.music.version.OfficialCounterpartParser
import com.metrolist.music.version.TrackMatcher
import com.metrolist.music.version.VersionResolver
import com.metrolist.music.version.toTrackInfo
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * LIVE RELIABILITY HARNESS of the video mode. Skipped unless `LIVE_VIDEO=1` (or `-Dlive.video=1`), so it is never part of a
 * normal run. For a list of real songs it walks the same steps as the app, as far as the JVM can:
 *
 *  1. search the song with the app's innertube (`YouTube.search(..., FILTER_SONG)`), the top catalogue song is the "audio id";
 *  2. the OFFICIAL counterpart link (`next` panel wrapper, [OfficialCounterpartParser]) and the title-search counterpart
 *     ([VersionResolver] without the official step), with the old (15 s) and the new (45 s longer) window side by side;
 *  3. the extraction the app does ([InnerTubeXPlayer.videoHints], tokenless profile first, then the fallback order with a
 *     PO-token provider that is present but gives nothing = a WebView that fails), pass A at 720 p, pass B "mobile-like" at
 *     360 p in the library's own order;
 *  4. the first seconds of the picture through [SabrVideoDataSource] and media3's extractor (frames, key frames, timing).
 *
 * Env: `LIVE_VIDEO_OUT` report file, `LIVE_VIDEO_COUNT` (songs, default all), `LIVE_VIDEO_BYTES_EVERY` (default 3: every
 * 3rd song reads picture bytes), `LIVE_VIDEO_PASSB_EVERY` (default 2), `LIVE_VIDEO_GAP_MS` (default 1500), `LIVE_VIDEO_MAXREQ`
 * (hard cap of HTTP requests, default 260), `LIVE_VIDEO_DUMP` (a folder: the raw `next` answer of the first 3 songs),
 * `LIVE_VIDEO_GL` / `LIVE_VIDEO_HL` (default ID / id). It stops at once when three extractions in a row meet a bot check or a
 * 403 (it never tries to get around that).
 */
@OptIn(ExperimentalSabrApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VideoReliabilityLiveTest {
    private val lines = ArrayList<String>()

    private fun say(text: String) {
        println(text)
        lines += text
    }

    private val libWarnings = AtomicInteger()

    private val logger =
        InnerTubeLogger { event ->
            if (event.level == InnerTubeLogLevel.WARN || event.level == InnerTubeLogLevel.ERROR) libWarnings.incrementAndGet()
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

    /** No provider at all (what the earlier live run and the PC probe used). */
    private val noTokens =
        object : TokenProvider {
            override val capabilities = TokenProviderCapabilities(providers = emptySet(), usesWebView = false)

            override suspend fun getPoToken(
                videoId: String,
                visitorData: String,
                cookie: String?,
            ): PoTokenResult? = null

            override suspend fun close() = Unit
        }

    /** What the app declares (WebView BotGuard provider), but the WebView gives nothing: the failing-WebView case. */
    private val appLikeFailingWebView =
        object : TokenProvider {
            override val capabilities = TokenProviderCapabilities(providers = setOf(PoTokenProviderKind.WEB_BOTGUARD), usesWebView = true)

            override suspend fun getPoToken(
                videoId: String,
                visitorData: String,
                cookie: String?,
            ): PoTokenResult? = null

            override suspend fun close() = Unit
        }

    private class Extractors(
        val tokenless: InnerTubeExtractor,
        val appLike: InnerTubeExtractor,
    )

    private suspend fun buildExtractors(): Extractors {
        YouTube.locale = YouTubeLocale(gl = System.getenv("LIVE_VIDEO_GL") ?: "ID", hl = System.getenv("LIVE_VIDEO_HL") ?: "id")
        YouTube.visitorData().getOrNull()?.let { YouTube.visitorData = it }
        val t = YouTube.extractionTransport()
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

        fun make(tokens: TokenProvider) =
            InnerTubeExtractor(
                configParser = withFallbackConfig,
                cipherService = cipher,
                innerTube = t.innerTube,
                tokenProvider = tokens,
                logger = logger,
            )
        return Extractors(make(noTokens), make(appLikeFailingWebView))
    }

    // ------------------------------------------------------------------------------------------------ the song list

    private class Pick(
        val label: String,
        val query: String,
    )

    private val picks =
        listOf(
            // Indonesian pop, dangdut, campursari
            Pick("id-dangdut", "Denny Caknan Negoro Angin"),
            Pick("id-dangdut", "Denny Caknan Kartonyono Medot Janji"),
            Pick("id-dangdut", "Denny Caknan Los Dol"),
            Pick("id-dangdut", "Denny Caknan Sugeng Dalu"),
            Pick("id-dangdut", "Happy Asmara Tak Kiro Kowe Setia"),
            Pick("id-dangdut", "Guyon Waton Pelangi di Matamu"),
            Pick("id-pop", "Tulus Hati-Hati di Jalan"),
            Pick("id-pop", "Tulus Monokrom"),
            Pick("id-pop", "Hindia Everybody Lie"),
            Pick("id-pop", "Hindia Secukupnya"),
            Pick("id-pop", "JKT48 Heavy Rotation"),
            Pick("id-pop", "JKT48 Aitakatta"),
            Pick("id-pop", "Juicy Luicy Lantas"),
            Pick("id-pop", "Juicy Luicy Tanpa Tergesa"),
            Pick("id-pop", "Nadhif Basalamah Kita Usahakan Rumah Itu"),
            Pick("id-pop", "Sheila On 7 Dan"),
            Pick("id-pop", "Fourtwnty Zona Nyaman"),
            Pick("id-pop", "Sal Priadi Gala Bunga Matahari"),
            // older classics, probably only a live or uploaded video
            Pick("old", "Panbers Gereja Tua"),
            Pick("old", "Koes Plus Kolam Susu"),
            Pick("old", "John Denver Take Me Home Country Roads"),
            Pick("old", "Queen Bohemian Rhapsody"),
            // English pop and rock
            Pick("en", "Justin Bieber Baby"),
            Pick("en", "Harry Styles As It Was"),
            Pick("en", "Ed Sheeran Shape of You"),
            Pick("en", "Coldplay Yellow"),
            Pick("en", "Imagine Dragons Believer"),
            Pick("en", "Billie Eilish bad guy"),
            Pick("en", "The Weeknd Blinding Lights"),
            Pick("en", "Adele Hello"),
            // K-pop
            Pick("kpop", "BTS Dynamite"),
            Pick("kpop", "BLACKPINK How You Like That"),
            Pick("kpop", "NewJeans Hype Boy"),
            Pick("kpop", "TWICE Cheer Up"),
        )

    // ------------------------------------------------------------------------------------------------ records

    private class Outcome(
        val ok: Boolean,
        val ms: Long,
        val client: String? = null,
        val profile: String? = null,
        val itag: Int? = null,
        val width: Int? = null,
        val height: Int? = null,
        val codec: String? = null,
        val kind: VideoFailureKind? = null,
        val why: String? = null,
        val boot: SabrBootstrap? = null,
        val format: VideoFormatInfo? = null,
        val stage: String = "",
    ) {
        fun short(): String =
            if (ok) "ok ${ms}ms $stage ${client ?: "?"}/${profile ?: "?"} itag=$itag ${width}x$height $codec" else "FAIL ${ms}ms $stage kind=${kind?.key} $why"
    }

    private class Bytes(
        val ok: Boolean,
        val frames: Int,
        val keyFrames: Int,
        val firstFrameS: Double,
        val totalS: Double,
        val kib: Long,
        val why: String? = null,
    ) {
        fun short(): String = if (ok) "ok $frames frames/$keyFrames key, first ${"%.2f".format(firstFrameS)}s, 15s media after ${"%.2f".format(totalS)}s, $kib KiB" else "FAIL $why"
    }

    private class Row(
        val pick: Pick,
    ) {
        var searchMs = 0L
        var searchWhy: String? = null
        var audio: SongItem? = null
        var officialMs = 0L
        var officialState = "-"
        var wrapperSeen = false
        var official: OfficialCounterpart? = null
        var heurMs = 0L
        var heurNew: SongItem? = null
        var heurOld: SongItem? = null
        var heurNoUgc: SongItem? = null
        var queriesRun = 0
        var droppedByOldWindow: SongItem? = null
        var searchRows: List<List<SongItem>?> = emptyList()
        var variantNotes: String? = null
        var extA: Outcome? = null
        var extB: Outcome? = null
        var bytesA: Bytes? = null
        var bytesB: Bytes? = null

        val audioSec: Int? get() = audio?.duration
        val chosen: SongItem? get() = official?.song ?: heurNew
        val chosenOffsetMs: Long?
            get() {
                official?.offsetMs?.let { return it }
                val a = audioSec ?: return null
                return VideoEligibility.counterpartOffsetMs(a, chosen?.duration)
            }
    }

    // ------------------------------------------------------------------------------------------------ the test

    @Test
    fun liveReliabilityOfTheVideoPath() {
        val on = System.getProperty("live.video") == "1" || System.getenv("LIVE_VIDEO") == "1"
        assumeTrue("live.video / LIVE_VIDEO not set: the network test is skipped", on)
        val maxReq = System.getenv("LIVE_VIDEO_MAXREQ")?.toIntOrNull() ?: 260
        val count = System.getenv("LIVE_VIDEO_COUNT")?.toIntOrNull() ?: picks.size
        val bytesEvery = (System.getenv("LIVE_VIDEO_BYTES_EVERY")?.toIntOrNull() ?: 3).coerceAtLeast(1)
        val passBEvery = (System.getenv("LIVE_VIDEO_PASSB_EVERY")?.toIntOrNull() ?: 2).coerceAtLeast(1)
        val gapMs = System.getenv("LIVE_VIDEO_GAP_MS")?.toLongOrNull() ?: 1500L
        val officialN = System.getenv("LIVE_VIDEO_OFFICIAL_N")?.toIntOrNull() ?: 5
        val dumpDir = System.getenv("LIVE_VIDEO_DUMP")?.let { File(it).also(File::mkdirs) }
        val requests = AtomicInteger()
        val rows = ArrayList<Row>()
        var aborted: String? = null
        val started = System.currentTimeMillis()
        try {
            runBlocking {
                val ex = buildExtractors()
                YouTube.extractionTransport().httpClient.plugin(HttpSend).intercept { request ->
                    requests.incrementAndGet()
                    execute(request)
                }
                fun overBudget() = requests.get() >= maxReq

                var wallStreak = 0

                fun noteWall(o: Outcome) {
                    val wall = !o.ok && (o.kind == VideoFailureKind.BOT_CHECK || o.kind == VideoFailureKind.FORBIDDEN || o.why?.contains("429") == true)
                    wallStreak = if (wall) wallStreak + 1 else 0
                    if (wallStreak >= 3 && aborted == null) aborted = "three extractions in a row met a bot check or a 403/429: stopped, nothing was tried to get around it"
                }

                // ---- pass A: per song
                for ((index, pick) in picks.take(count).withIndex()) {
                    if (aborted != null) break
                    if (overBudget()) {
                        aborted = "request budget of $maxReq reached after ${rows.size} songs"
                        break
                    }
                    val row = Row(pick)
                    rows += row
                    searchStep(row)
                    val audio = row.audio
                    if (audio != null) {
                        // measured on 2026-11-07: a signed-out `next` answer never carries the counterpart (the app asks only when signed in)
                        if (index < officialN) officialStep(row, dumpDir?.takeIf { index < 3 })
                        heuristicStep(row)
                        val video = row.chosen
                        if (video != null && !overBudget()) {
                            row.extA = extractTwoStages(ex, video.id, 720)
                            row.extA?.let(::noteWall)
                            val a = row.extA
                            if (a != null && a.ok && index % bytesEvery == 0 && !overBudget()) row.bytesA = readFirst(a, 15_000L)
                        }
                    }
                    say(row.render(index + 1))
                    delay(gapMs)
                }

                // ---- pass B (mobile-like): 360 p, the library's own order, the PO-token provider present but silent
                if (aborted == null) {
                    say("")
                    say("---- pass B: cap 360 p (mobile-like), library order, WebView provider present and silent ----")
                    delay(10_000)
                    var n = 0
                    for ((index, row) in rows.withIndex()) {
                        if (aborted != null || overBudget()) break
                        val video = row.chosen ?: continue
                        if (n++ % passBEvery != 0) continue
                        row.extB = extractDefaultOrder(ex, video.id, 360)
                        row.extB?.let(::noteWall)
                        val b = row.extB
                        if (b != null && b.ok && n % (bytesEvery + 1) == 0 && !overBudget()) row.bytesB = readFirst(b, 15_000L)
                        say("B ${index + 1}. ${row.pick.query}: ${b?.short()} ${row.bytesB?.let { "| bytes " + it.short() } ?: ""}")
                        delay(gapMs)
                    }
                    if (overBudget() && aborted == null) aborted = "request budget of $maxReq reached during pass B"
                }
            }
        } finally {
            val seconds = (System.currentTimeMillis() - started) / 1000
            say("")
            say(summary(rows, requests.get(), seconds, aborted))
            System.getenv("LIVE_VIDEO_OUT")?.let { File(it).writeText(lines.joinToString("\n")) }
        }
    }

    // ------------------------------------------------------------------------------------------------ steps

    private suspend fun searchStep(row: Row) {
        val t0 = System.nanoTime()
        try {
            val items = YouTube.search(row.pick.query, YouTube.SearchFilter.FILTER_SONG, recordSearchHistory = false).getOrThrow().items.filterIsInstance<SongItem>()
            row.audio = items.firstOrNull { !it.looksLikeVideo && !it.isEpisode } ?: items.firstOrNull { !it.isEpisode }
            if (row.audio == null) row.searchWhy = "no song in the result"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            row.searchWhy = VideoFailures.describe(e)
        }
        row.searchMs = (System.nanoTime() - t0) / 1_000_000L
    }

    private suspend fun officialStep(
        row: Row,
        dumpDir: File?,
    ) {
        val audio = row.audio ?: return
        val t0 = System.nanoTime()
        try {
            val body = YouTube.extractionTransport().innerTube.next(YouTubeClient.WEB_REMIX, videoId = audio.id).bodyAsText()
            row.wrapperSeen = "playlistPanelVideoWrapperRenderer" in body
            dumpDir?.let { File(it, "next-${audio.id}.json").writeText(body) }
            val link = OfficialCounterpartParser.parse(body, audio.id)
            row.official = link
            row.officialState = if (link != null) "found" else if (row.wrapperSeen) "wrapper-but-no-match" else "none"
            if (System.getenv("LIVE_VIDEO_NEXTVARIANTS") == "1" && link == null) {
                // does any other shape of the same request carry the Song | Video wrapper?
                val notes = StringBuilder()
                val variants: List<Pair<String, suspend () -> io.ktor.client.statement.HttpResponse>> =
                    listOf(
                        "radio" to { YouTube.extractionTransport().innerTube.next(YouTubeClient.WEB_REMIX, videoId = audio.id, playlistId = "RDAMVM${audio.id}") },
                        "radio+wAEB" to { YouTube.extractionTransport().innerTube.next(YouTubeClient.WEB_REMIX, videoId = audio.id, playlistId = "RDAMVM${audio.id}", params = "wAEB") },
                    )
                for ((name, call) in variants) {
                    val text = call().bodyAsText()
                    dumpDir?.let { File(it, "next-$name-${audio.id}.json").writeText(text) }
                    notes.append("$name: wrapper=${"playlistPanelVideoWrapperRenderer" in text} counterpart=${"\"counterpart\"" in text} parsed=${OfficialCounterpartParser.parse(text, audio.id) != null}; ")
                }
                row.variantNotes = notes.toString()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            row.officialState = "error ${VideoFailures.classify(e).key}"
        }
        row.officialMs = (System.nanoTime() - t0) / 1_000_000L
    }

    private suspend fun heuristicStep(row: Row) {
        val audio = row.audio ?: return
        val info = audio.toTrackInfo()
        val log = CopyOnWriteArrayList<List<SongItem>?>()
        val resolver =
            VersionResolver(
                search = { query, filter ->
                    VersionResolver.searchSongs(query, filter).also { log += it }
                },
                searchTimeoutMs = 8_000,
            )
        val t0 = System.nanoTime()
        val found = resolver.counterpartOf(info)
        row.heurMs = (System.nanoTime() - t0) / 1_000_000L
        row.queriesRun = log.size
        row.searchRows = log.toList()
        row.heurNew = found?.item
        // the same rows without the rule for lyric videos of fan channels (a video that names the artist in its title)
        for (rows in log) {
            val best = rows?.let { r -> TrackMatcher.best(info, r.map { it.toTrackInfo() }, wantVideo = true, maxLongerSec = VideoEligibility.COUNTERPART_MAX_INTRO_SEC, titleNamesArtist = false) }
            if (best != null) {
                row.heurNoUgc = rows.first { it.id == best.id }
                break
            }
        }
        // the same rows under the old window (a video longer by more than 15 s was thrown away)
        for (rows in log) {
            val best = rows?.let { r -> TrackMatcher.best(info, r.map { it.toTrackInfo() }, wantVideo = true, maxLongerSec = TrackMatcher.MAX_DURATION_DIFF_SEC) }
            if (best != null) {
                row.heurOld = rows.first { it.id == best.id }
                break
            }
        }
        if (row.heurNew != null && row.heurOld == null) row.droppedByOldWindow = row.heurNew
    }

    /** Pass A: the tokenless profile alone, then (if it failed) the library's order without it, like InnerTubeVideoExtractor. */
    private suspend fun extractTwoStages(
        ex: Extractors,
        id: String,
        cap: Int,
    ): Outcome {
        val t0 = System.nanoTime()
        val first = extractOnce(ex.tokenless, id, cap, InnerTubeVideoExtractor.TOKENLESS_PROFILE, emptySet(), InnerTubeVideoExtractor.STAGE1_MS, "tokenless")
        if (first.ok || first.kind == VideoFailureKind.OFFLINE) return first
        val second = extractOnce(ex.appLike, id, cap, null, setOf(InnerTubeVideoExtractor.TOKENLESS_PROFILE), InnerTubeVideoExtractor.STAGE2_MS, "fallback")
        val total = (System.nanoTime() - t0) / 1_000_000L
        return if (second.ok) {
            Outcome(true, total, second.client, second.profile, second.itag, second.width, second.height, second.codec, boot = second.boot, format = second.format, stage = "fallback(after ${first.kind?.key})")
        } else {
            Outcome(false, total, kind = first.kind, why = "stage1 ${first.kind?.key}: ${first.why} | stage2 ${second.kind?.key}: ${second.why}", stage = "both")
        }
    }

    /** Pass B: no pinning, the library decides, the failing-WebView provider is present. */
    private suspend fun extractDefaultOrder(
        ex: Extractors,
        id: String,
        cap: Int,
    ): Outcome = extractOnce(ex.appLike, id, cap, null, emptySet(), 30_000L, "default-order")

    private suspend fun extractOnce(
        extractor: InnerTubeExtractor,
        id: String,
        cap: Int,
        override: String?,
        excluded: Set<String>,
        limitMs: Long,
        stage: String,
    ): Outcome {
        val t0 = System.nanoTime()

        fun ms() = (System.nanoTime() - t0) / 1_000_000L
        return try {
            val stream =
                withTimeoutOrNull(limitMs) {
                    extractor.extract(
                        videoId = id,
                        hints = InnerTubeXPlayer.videoHints(cap, override),
                        excludedClients = excluded,
                        audioQuality = AudioQuality.AUTO,
                        clientPlaybackNonce = generateClientPlaybackNonce(),
                    )
                } ?: return Outcome(false, ms(), kind = VideoFailureKind.TIMEOUT, why = "timeout after $limitMs ms", stage = stage)
            val boot = stream.sabrVideoBootstrap ?: return Outcome(false, ms(), client = stream.clientName, kind = VideoFailureKind.NO_FORMAT, why = "no SABR video from ${stream.clientName}", stage = stage)
            val format = VideoFormats.parse(stream.videoMimeType, stream.videoCodecs, stream.videoWidth, stream.videoHeight, stream.videoBitrate)
            Outcome(true, ms(), stream.clientName, stream.profileId, stream.videoItag, stream.videoWidth, stream.videoHeight, format?.codec?.name, boot = boot, format = format, stage = stage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Outcome(false, ms(), kind = VideoFailures.classify(e), why = VideoFailures.describe(e), stage = stage)
        }
    }

    // ------------------------------------------------------------------------------------------------ the picture bytes

    private suspend fun readFirst(
        o: Outcome,
        mediaMs: Long,
    ): Bytes {
        val boot = o.boot
        val format = o.format
        if (boot == null || format == null) return Bytes(false, 0, 0, 0.0, 0.0, 0, "no bootstrap or unreadable format")
        val rig = Rig(boot, format)
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val rec = Recorder()
                rig.extractor.init(rec)
                rig.clock.start(0L)
                val begin = System.nanoTime()
                rig.ds.open(DataSpec(rig.uri))
                val input = DefaultExtractorInput(rig.ds, 0, C.LENGTH_UNSET.toLong())
                val holder = PositionHolder()
                var firstAt = -1.0
                while (rec.lastUs < mediaMs * 1000L) {
                    if (rig.extractor.read(input, holder) == Extractor.RESULT_END_OF_INPUT) {
                        return@withContext Bytes(false, rec.samples, rec.keyframes, firstAt, 0.0, rig.bytes.get() / 1024, "the stream ended before ${mediaMs / 1000} s (last ${rec.lastUs / 1000} ms)")
                    }
                    if ((System.nanoTime() - begin) / 1e9 > 60.0) {
                        return@withContext Bytes(false, rec.samples, rec.keyframes, firstAt, 0.0, rig.bytes.get() / 1024, "no ${mediaMs / 1000} s of frames within 60 s")
                    }
                    if (firstAt < 0 && rec.samples > 0) firstAt = (System.nanoTime() - begin) / 1e9
                }
                val total = (System.nanoTime() - begin) / 1e9
                rig.ds.close()
                Bytes(rec.samples > 50 && rec.keyframes > 0, rec.samples, rec.keyframes, firstAt, total, rig.bytes.get() / 1024)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Bytes(false, 0, 0, 0.0, 0.0, rig.bytes.get() / 1024, VideoFailures.describe(e))
        } finally {
            rig.shutdown()
        }
    }

    private inner class Rig(
        boot: SabrBootstrap,
        format: VideoFormatInfo,
    ) {
        val uri: android.net.Uri = android.net.Uri.parse("sabr-video://live")
        val clock = RunningClock()
        val bytes = AtomicLong()
        val timeMap = SabrTimeMap(boot.durationMs)
        val source = LibrarySabrChunkSource(httpClient = { YouTube.extractionTransport().httpClient }, bootstrap = boot, logger = logger)
        val factory = SabrVideoDataSourceFactory(source, timeMap, clock.videoClock, SabrDataSourceSettings())
        val ds = factory.createDataSource().also { it.addTransferListener(CountingListener(bytes)) }
        val extractor: Extractor = sabrExtractorsFactory(format.container, timeMap).createExtractors()[0]

        fun shutdown() {
            factory.shutdown()
            clock.stop()
        }
    }

    private class CountingListener(
        private val total: AtomicLong,
    ) : androidx.media3.datasource.TransferListener {
        override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            total.addAndGet(bytesTransferred.toLong())
        }

        override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }

    private class RunningClock {
        val videoClock = VideoClock()

        @Volatile private var running = true
        private var thread: Thread? = null

        fun start(fromMs: Long) {
            stop()
            running = true
            val t0 = System.nanoTime()
            videoClock.positionMs = fromMs
            thread =
                Thread {
                    while (running) {
                        videoClock.positionMs = fromMs + (System.nanoTime() - t0) / 1_000_000L
                        try {
                            Thread.sleep(100)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                    }
                }.also {
                    it.isDaemon = true
                    it.start()
                }
        }

        fun stop() {
            running = false
            thread?.interrupt()
            thread = null
        }
    }

    private class Recorder : ExtractorOutput {
        var samples = 0
        var keyframes = 0
        var lastUs = Long.MIN_VALUE

        private val trackOut =
            object : TrackOutput {
                override fun format(format: Format) = Unit

                override fun sampleData(
                    input: DataReader,
                    length: Int,
                    allowEndOfInput: Boolean,
                    sampleDataPart: Int,
                ): Int = input.read(ByteArray(length), 0, length)

                override fun sampleData(
                    data: ParsableByteArray,
                    length: Int,
                    sampleDataPart: Int,
                ) = data.skipBytes(length)

                override fun sampleMetadata(
                    timeUs: Long,
                    flags: Int,
                    size: Int,
                    offset: Int,
                    cryptoData: TrackOutput.CryptoData?,
                ) {
                    samples++
                    if (flags and C.BUFFER_FLAG_KEY_FRAME != 0) keyframes++
                    if (timeUs > lastUs) lastUs = timeUs
                }
            }

        override fun track(
            id: Int,
            type: Int,
        ): TrackOutput = trackOut

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) = Unit
    }

    // ------------------------------------------------------------------------------------------------ the report

    private fun Row.render(n: Int): String =
        buildString {
            val a = audio
            append("$n. [${pick.label}] ${pick.query}\n")
            if (a == null) {
                append("   search: FAIL ${searchWhy} (${searchMs} ms)")
                return@buildString
            }
            append("   audio ${a.id} \"${a.title}\" by ${a.artists.joinToString { it.name }} ${a.duration}s type=${a.musicVideoType} (search ${searchMs} ms)\n")
            val o = official
            append(
                "   official: $officialState (${officialMs} ms)" +
                    if (o != null) {
                        " -> ${o.song.id} \"${o.song.title}\" ${o.song.duration}s type=${o.song.musicVideoType} segmentOffset=${o.offsetMs} segments=${o.segments} lengthDiff=${o.song.duration?.let { d -> a.duration?.let { d - it } }}s\n"
                    } else {
                        "\n"
                    },
            )
            append(
                "   title search: ${heurNew?.let { "NEW ${it.id} \"${it.title}\" ${it.duration}s diff=${it.duration?.let { d -> a.duration?.let { x -> d - x } }}s" } ?: "NEW none"}" +
                    " | ${heurOld?.let { "OLD ${it.id}" } ?: "OLD none"} (${queriesRun} queries, ${heurMs} ms)" +
                    (if (droppedByOldWindow != null) " | RECOVERED by the 45 s window" else "") + "\n",
            )
            variantNotes?.let { append("   next variants: $it").append('\n') }
            if (chosen == null) {
                searchRows.forEachIndexed { qi, rows ->
                    append("   candidates of query ${qi + 1} (${rows?.size ?: "failed"}): ")
                    append(rows.orEmpty().take(6).joinToString(" | ") { r -> "${r.id} \"${r.title}\" ${r.artists.joinToString { it.name }} ${r.duration}s ${r.musicVideoType ?: "-"}${if (r.looksLikeVideo) " VIDEO" else ""}" })
                    append('\n')
                }
                append("   wanted core: ${TrackMatcher.parseTitle(a.title, a.artists.map { it.name })} artists=${a.artists.joinToString { it.name }}").append('\n')
            }
            val verdict =
                when {
                    o != null && heurNew != null && o.song.id == heurNew!!.id -> "official = title search"
                    o != null && heurNew != null -> "DIFFERENT: official ${o.song.id} vs search ${heurNew!!.id}"
                    o != null -> "official only"
                    heurNew != null -> "title search only"
                    else -> "no counterpart"
                }
            append("   counterpart: $verdict; offset used=${chosenOffsetMs}ms\n")
            extA?.let { append("   extract A (720 p): ${it.short()}\n") }
            bytesA?.let { append("   picture A: ${it.short()}\n") }
        }.trimEnd()

    private fun median(values: List<Long>): Long = if (values.isEmpty()) 0L else values.sorted()[values.size / 2]

    private fun p95(values: List<Long>): Long = if (values.isEmpty()) 0L else values.sorted()[((values.size - 1) * 0.95).toInt().coerceAtLeast(0)]

    private fun pct(
        part: Int,
        whole: Int,
    ): String = if (whole == 0) "n/a" else "${part * 100 / whole}% ($part/$whole)"

    private fun summary(
        rows: List<Row>,
        requests: Int,
        seconds: Long,
        aborted: String?,
    ): String =
        buildString {
            val searched = rows.count { it.audio != null }
            append("==== SUMMARY: ${rows.size} songs, $searched found in the catalogue search, $requests HTTP requests, ${seconds}s, library warnings ${libWarnings.get()}\n")
            aborted?.let { append("ABORTED: $it\n") }
            val withAudio = rows.filter { it.audio != null }
            val officialFound = withAudio.count { it.official != null }
            append("official link found:        ${pct(officialFound, withAudio.size)}; with a segment map: ${pct(withAudio.count { it.official?.offsetMs != null }, officialFound)}\n")
            val oldFound = withAudio.count { it.heurOld != null }
            val newFound = withAudio.count { it.heurNew != null }
            append("title search, old 15 s window: ${pct(oldFound, withAudio.size)}   new +45 s window (+ lyric videos of fan channels): ${pct(newFound, withAudio.size)}   recovered by the window: ${withAudio.count { it.droppedByOldWindow != null }}\n")
            val noUgc = withAudio.count { it.heurNoUgc != null }
            append("   without the lyric-video rule: ${pct(noUgc, withAudio.size)}; the lyric-video rule alone added ${withAudio.count { it.heurNew != null && it.heurNoUgc == null }}\n")
            withAudio.filter { it.heurNew != null && it.heurNoUgc == null }.forEach { append("      lyric rule: ${it.pick.query} -> ${it.heurNew!!.id} \"${it.heurNew!!.title}\" by ${it.heurNew!!.artists.joinToString { a -> a.name }} ${it.heurNew!!.duration}s (song ${it.audioSec}s)\n") }
            append("   chosen videos by kind: ${withAudio.mapNotNull { it.chosen }.groupingBy { c -> if (c.musicVideoType == "MUSIC_VIDEO_TYPE_OMV") "OMV" else if (c.musicVideoType == "MUSIC_VIDEO_TYPE_UGC") "UGC" else c.musicVideoType ?: "?" }.eachCount()}\n")
            val lengthDiffs = withAudio.mapNotNull { r -> r.chosen?.duration?.let { d -> r.audioSec?.let { a -> d - a } } }
            append("   length difference chosen video - song (s): ${lengthDiffs.sorted()}\n")
            val any = withAudio.count { it.chosen != null }
            append("counterpart found (official, else title search): ${pct(any, withAudio.size)}   by the OLD pipeline (title search, 15 s): ${pct(oldFound, withAudio.size)}\n")
            val both = withAudio.filter { it.official != null && it.heurNew != null }
            val differ = both.filter { it.official!!.song.id != it.heurNew!!.id }
            append("both ways found: ${both.size}, same video: ${both.size - differ.size}, DIFFERENT (title search wrong or another upload): ${differ.size}\n")
            differ.forEach { append("   differs: ${it.pick.query}: official ${it.official!!.song.id} \"${it.official!!.song.title}\" ${it.official!!.song.duration}s vs search ${it.heurNew!!.id} \"${it.heurNew!!.title}\" ${it.heurNew!!.duration}s\n") }
            val offsets = withAudio.mapNotNull { r -> r.official?.let { o -> o.offsetMs?.let { off -> Triple(r.pick.query, off, o.song.duration?.let { d -> r.audioSec?.let { d - it } }) } } }
            if (offsets.isNotEmpty()) {
                append("measured offset (segment map) vs the length-difference guess:\n")
                offsets.forEach { (q, off, diff) -> append("   $q: measured ${off} ms, length difference ${diff?.times(1000)} ms\n") }
            }
            append("search latency ms: median ${median(rows.filter { it.audio != null }.map { it.searchMs })}, p95 ${p95(rows.map { it.searchMs })}\n")
            append("official link latency ms: median ${median(withAudio.map { it.officialMs })}, p95 ${p95(withAudio.map { it.officialMs })}\n")
            append("title search latency ms: median ${median(withAudio.map { it.heurMs })}, p95 ${p95(withAudio.map { it.heurMs })}\n")
            val a = rows.mapNotNull { it.extA }
            append("extraction A (720 p, tokenless first): ${pct(a.count { it.ok }, a.size)}; latency ms median ${median(a.filter { it.ok }.map { it.ms })}, p95 ${p95(a.filter { it.ok }.map { it.ms })}\n")
            append("   by stage/profile: ${a.filter { it.ok }.groupingBy { "${it.stage}/${it.profile}" }.eachCount()}\n")
            append("   failures: ${a.filter { !it.ok }.groupingBy { it.kind?.key }.eachCount()}\n")
            a.filter { !it.ok }.forEach { append("   fail detail: ${it.why}\n") }
            val b = rows.mapNotNull { it.extB }
            append("extraction B (360 p, library order, silent WebView): ${pct(b.count { it.ok }, b.size)}; latency ms median ${median(b.filter { it.ok }.map { it.ms })}, p95 ${p95(b.filter { it.ok }.map { it.ms })}\n")
            append("   by client/profile: ${b.filter { it.ok }.groupingBy { "${it.client}/${it.profile}" }.eachCount()}\n")
            append("   failures: ${b.filter { !it.ok }.groupingBy { it.kind?.key }.eachCount()}\n")
            b.filter { !it.ok }.forEach { append("   fail detail: ${it.why}\n") }
            val bytes = rows.flatMap { listOfNotNull(it.bytesA, it.bytesB) }
            append("first picture bytes: ${pct(bytes.count { it.ok }, bytes.size)}; first frame s median ${"%.2f".format(median(bytes.filter { it.ok }.map { (it.firstFrameS * 1000).toLong() }) / 1000.0)}, 15 s of media after s median ${"%.2f".format(median(bytes.filter { it.ok }.map { (it.totalS * 1000).toLong() }) / 1000.0)}\n")
            bytes.filter { !it.ok }.forEach { append("   bytes fail: ${it.why}\n") }
        }.trimEnd()
}
