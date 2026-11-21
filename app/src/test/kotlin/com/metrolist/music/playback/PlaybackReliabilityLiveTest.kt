/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.EOFException
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * LIVE HARNESS of the audio stream path (round 4.3.4, reports 2-4). Skipped unless `LIVE_PLAYBACK=1`, so it is never part
 * of a normal run. For each song it does what the app does, as far as the JVM can:
 *
 *  1. search the song (`YouTube.search(..., FILTER_SONG)`);
 *  2. the audio extraction with the app's hints (`allowBoundedRange = true`, no HLS, no SABR) and a PO-token provider that
 *     is declared but gives nothing (a WebView is not available on the JVM: on a phone the WebView token usually works, so
 *     the client choice here can differ from a phone);
 *  3. a second extraction with the first client excluded (what a retry after `markStreamClientFailed` gets);
 *  4. HTTP reads of the chosen URL: the first MiB (bounded), the last 256 KiB, 256 KiB at the middle, and an unbounded read
 *     (no Range header) of 256 KiB with its throughput;
 *  5. for streams of the bounded-range clients: the WHOLE song read the way the app read it before this round (every open
 *     cut to one chunk, a clean end of input inside a chunk ends the song, an EOFException re-opens at the input position),
 *     and the same song read as ONE continuous stream through [RangeChunkDataSource]: the last sample time of both is
 *     compared with the song length.
 *
 * Env: `LIVE_PLAYBACK_OUT` report file, `LIVE_PLAYBACK_COUNT` (songs), `LIVE_PLAYBACK_FULL` (how many bounded songs are
 * read whole, default 6), `LIVE_PLAYBACK_GAP_MS` (default 1200), `LIVE_PLAYBACK_GL`/`_HL` (default ID/id). It stops when
 * three extractions in a row meet a bot check (it never tries to get around one).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaybackReliabilityLiveTest {
    private val lines = ArrayList<String>()

    private fun say(text: String) {
        println(text)
        lines += text
    }

    private val warnings = ArrayList<String>()

    private val logger =
        InnerTubeLogger { event ->
            if (event.level == InnerTubeLogLevel.WARN || event.level == InnerTubeLogLevel.ERROR) {
                synchronized(warnings) { warnings += "${event.tag}:${event.message}" }
            }
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

    /** What the app declares (a WebView BotGuard provider), but the WebView gives nothing. */
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

    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

    private class Pick(
        val label: String,
        val query: String,
    )

    private val picks =
        listOf(
            // the songs of the reports
            Pick("report", "Tame Impala Dracula JENNIE"),
            Pick("report", "Tame Impala Dracula"),
            Pick("report", "Tame Impala The Less I Know The Better"),
            Pick("report", "Tame Impala Loser"),
            Pick("report", "Flower In A Hurricane"),
            Pick("report", "SZA Snooze"),
            Pick("report", "Tems Free Mind"),
            Pick("report", "Cup of Joe Multo"),
            Pick("report", "The 1975 About You"),
            Pick("report", "Piche Kota Bahagia Lagi"),
            Pick("report", "Nadhif Basalamah penjaga hati"),
            Pick("report", "Pamungkas Monolog"),
            Pick("report", "Nadhif Basalamah kota ini tak sama tanpamu"),
            Pick("report", "Aziz Hedra Somebody's Pleasure"),
            // the rest of the user's 15-song playlist in video A
            Pick("playlist", "Nadhif Basalamah bergema sampai selamanya"),
            Pick("playlist", "For Revenge Stereo Wall Jakarta Hari Ini"),
            Pick("playlist", "Andmesh Anugerah Terindah"),
            Pick("playlist", "Naykilla SO ASU"),
            Pick("playlist", "Aku Jeje Lihat Kebunku Taman Bunga"),
            Pick("playlist", "Hindia everything u are"),
            // popular ID / EN
            Pick("popular", "Tulus Hati-Hati di Jalan"),
            Pick("popular", "Bernadya Satu Bulan"),
            Pick("popular", "Sal Priadi Gala Bunga Matahari"),
            Pick("popular", "Mahalini Sial"),
            Pick("popular", "Juicy Luicy Lantas"),
            Pick("popular", "Hindia Rumah ke Rumah"),
            Pick("popular", "Billie Eilish Birds of a Feather"),
            Pick("popular", "Sabrina Carpenter Espresso"),
            Pick("popular", "ROSE Bruno Mars APT"),
            Pick("popular", "Lady Gaga Bruno Mars Die With A Smile"),
            Pick("popular", "The Weeknd Blinding Lights"),
            Pick("popular", "Taylor Swift Cruel Summer"),
            Pick("popular", "Rex Orange County Untitled"),
            Pick("popular", "NIKI La La Lost You Acoustic"),
        )

    private class Extraction(
        val ok: Boolean,
        val ms: Long,
        val stream: ExtractedStream? = null,
        val why: String = "",
        val attempts: String = "",
    )

    private class ReadResult(
        val status: Int,
        val bytes: Long,
        val ms: Long,
        val note: String = "",
    ) {
        override fun toString(): String = if (status < 0) "ERR($note)" else "$status/${bytes / 1024}KiB/${ms}ms${if (note.isNotEmpty()) "/$note" else ""}"
    }

    private suspend fun buildExtractor(): InnerTubeExtractor {
        YouTube.locale = YouTubeLocale(gl = System.getenv("LIVE_PLAYBACK_GL") ?: "ID", hl = System.getenv("LIVE_PLAYBACK_HL") ?: "id")
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
        return InnerTubeExtractor(
            configParser = withFallbackConfig,
            cipherService = cipher,
            innerTube = t.innerTube,
            tokenProvider = appLikeFailingWebView,
            logger = logger,
        )
    }

    private suspend fun extract(
        extractor: InnerTubeExtractor,
        videoId: String,
        explicit: Boolean?,
        excluded: Set<String>,
        clientOverride: String? = null,
    ): Extraction {
        val t0 = System.nanoTime()

        fun ms() = (System.nanoTime() - t0) / 1_000_000L
        return try {
            // exactly the hints of InnerTubeXPlayer.extractForPlayback for a catalogue song
            val hints =
                ContentHints(isExplicit = explicit, isUploaded = false, playbackClientOverrideId = clientOverride)
                    .withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true)
            val stream =
                withTimeoutOrNull(180_000) {
                    extractor.extract(
                        videoId = videoId,
                        hints = hints,
                        excludedClients = excluded,
                        audioQuality = AudioQuality.AUTO,
                        clientPlaybackNonce = generateClientPlaybackNonce(),
                    )
                } ?: return Extraction(false, ms(), why = "TIMEOUT 180 s")
            val attempts = stream.streamDiagnostics?.attempts?.joinToString(" ") { "${it.clientName}:${it.outcome.take(24)}" }.orEmpty()
            Extraction(true, ms(), stream, attempts = attempts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StreamResolveException) {
            val attempts = e.diagnostics?.attempts?.joinToString(" ") { "${it.clientName}:${it.outcome.take(24)}" }.orEmpty()
            Extraction(false, ms(), why = "${e.reason} ${e.message?.take(80)} cause=${e.cause?.javaClass?.simpleName}:${e.cause?.message?.take(80)}", attempts = attempts)
        } catch (e: Throwable) {
            Extraction(false, ms(), why = "${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }
    }

    private fun read(
        stream: ExtractedStream,
        from: Long,
        toInclusive: Long?,
        limit: Long,
        openEnded: Boolean = false,
    ): ReadResult {
        val t0 = System.nanoTime()
        return try {
            val request =
                Request
                    .Builder()
                    .url(stream.audioUrl)
                    .apply {
                        stream.headers.forEach { (k, v) -> header(k, v) }
                        if (toInclusive != null) header("Range", "bytes=$from-$toInclusive")
                        if (toInclusive == null && openEnded) header("Range", "bytes=$from-")
                    }.build()
            http.newCall(request).execute().use { response ->
                var total = 0L
                val body = response.body
                if (response.isSuccessful) {
                    val source = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    while (total < limit) {
                        val n = source.read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt())
                        if (n < 0) break
                        total += n
                    }
                }
                val ms = (System.nanoTime() - t0) / 1_000_000L
                val rate = if (ms > 0) " ${(total * 8 / ms)}kbps" else ""
                ReadResult(response.code, total, ms, (response.header("Content-Range") ?: "") + rate)
            }
        } catch (e: Exception) {
            ReadResult(-1, 0, (System.nanoTime() - t0) / 1_000_000L, "${e.javaClass.simpleName}: ${e.message?.take(60)}")
        }
    }

    // ------------------------------------------------------------------------------------------ whole-song parse

    private class Recorder : ExtractorOutput {
        private val scratch = ByteArray(64 * 1024)
        var lastUs = 0L
        var samples = 0
        var durationUs = C.TIME_UNSET

        override fun track(
            id: Int,
            type: Int,
        ): TrackOutput =
            object : TrackOutput {
                override fun format(format: Format) = Unit

                override fun sampleData(
                    input: DataReader,
                    length: Int,
                    allowEndOfInput: Boolean,
                    sampleDataPart: Int,
                ): Int {
                    // what media3's SampleQueue does: an end inside a sample is an EOFException, not -1 bytes
                    val n = input.read(scratch, 0, minOf(length, scratch.size))
                    if (n == C.RESULT_END_OF_INPUT) {
                        if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                        throw EOFException()
                    }
                    return n
                }

                override fun sampleData(
                    data: ParsableByteArray,
                    length: Int,
                    sampleDataPart: Int,
                ) {
                    data.skipBytes(length)
                }

                override fun sampleMetadata(
                    timeUs: Long,
                    flags: Int,
                    size: Int,
                    offset: Int,
                    cryptoData: TrackOutput.CryptoData?,
                ) {
                    samples++
                    if (timeUs > lastUs) lastUs = timeUs
                }
            }

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) {
            durationUs = seekMap.durationUs
        }
    }

    private fun newExtractor(stream: ExtractedStream): Extractor =
        when {
            stream.mimeType?.contains("webm") == true -> MatroskaExtractor()
            else -> FragmentedMp4Extractor()
        }

    private fun httpSource(stream: ExtractedStream) =
        OkHttpDataSource
            .Factory(http)
            .setDefaultRequestProperties(stream.headers)
            .createDataSource()

    /**
     * The read the app did before this round: every open is cut to one chunk ([withResolvedStream] subrange); a clean
     * end of input ends the song; an EOFException (end inside an element) re-opens at the input position.
     */
    private fun readLikeOldApp(stream: ExtractedStream): String {
        val chunk = stream.rangeChunkSizeBytes.takeIf { it > 0 } ?: (1L shl 20)
        val extractor = newExtractor(stream)
        val rec = Recorder()
        extractor.init(rec)
        var position = 0L
        var opens = 0
        var eofRetries = 0
        val holder = PositionHolder()
        val t0 = System.nanoTime()
        val deadline = System.nanoTime() + 60_000_000_000L
        while (opens < 400) {
            if (System.nanoTime() > deadline) return "TIME LIMIT (60 s) at ${position / 1024} KiB, last=${rec.lastUs / 1000} ms, opens=$opens"
            val ds = httpSource(stream)
            opens++
            val spec = DataSpec.Builder().setUri(stream.audioUrl).setPosition(position).setLength(chunk).build()
            val length = try {
                ds.open(spec)
            } catch (e: Exception) {
                ds.close()
                return "open failed at ${position / 1024} KiB after $opens opens: ${e.javaClass.simpleName} ${e.message?.take(60)}"
            }
            val input = DefaultExtractorInput(ds, position, if (length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else position + length)
            try {
                while (true) {
                    when (extractor.read(input, holder)) {
                        Extractor.RESULT_CONTINUE -> if (System.nanoTime() > deadline) throw IllegalStateException("time limit 60 s")
                        Extractor.RESULT_SEEK -> {
                            position = holder.position
                            break
                        }
                        else -> {
                            ds.close()
                            val secs = (System.nanoTime() - t0) / 1_000_000_000L
                            return "ENDED at ${rec.lastUs / 1000} ms (opens=$opens eofRetries=$eofRetries, ${secs}s)"
                        }
                    }
                }
            } catch (e: EOFException) {
                eofRetries++
                position = input.position
            } catch (e: Exception) {
                ds.close()
                return "error at ${position / 1024} KiB: ${e.javaClass.simpleName} ${e.message?.take(60)} last=${rec.lastUs / 1000} ms"
            }
            ds.close()
        }
        return "gave up after $opens opens, last=${rec.lastUs / 1000} ms"
    }

    /** The same song through [RangeChunkDataSource] as one continuous stream, with the chunk policy the resolver sets. */
    private fun readContinuous(
        stream: ExtractedStream,
        chunk: Long,
    ): String {
        val extractor = newExtractor(stream)
        val rec = Recorder()
        extractor.init(rec)
        var position = 0L
        var opens = 0
        val holder = PositionHolder()
        val t0 = System.nanoTime()
        val source = RangeChunkDataSource(httpSource(stream))
        val deadline = System.nanoTime() + 60_000_000_000L
        while (opens < 50) {
            if (System.nanoTime() > deadline) return "TIME LIMIT (60 s) at ${position / 1024} KiB, last=${rec.lastUs / 1000} ms"
            opens++
            val spec =
                DataSpec
                    .Builder()
                    .setUri(stream.audioUrl)
                    .setPosition(position)
                    .setCustomData(StreamChunking(chunk))
                    .build()
            val length = try {
                source.open(spec)
            } catch (e: Exception) {
                source.close()
                return "open failed at ${position / 1024} KiB: ${e.javaClass.simpleName} ${e.message?.take(60)}"
            }
            val input = DefaultExtractorInput(source, position, if (length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else position + length)
            try {
                while (true) {
                    when (extractor.read(input, holder)) {
                        Extractor.RESULT_CONTINUE -> if (System.nanoTime() > deadline) throw IllegalStateException("time limit 60 s")
                        Extractor.RESULT_SEEK -> {
                            position = holder.position
                            break
                        }
                        else -> {
                            source.close()
                            val secs = (System.nanoTime() - t0) / 1_000_000_000L
                            return "ENDED at ${rec.lastUs / 1000} ms (opens=$opens, requests=${source.requestCount}, ${secs}s)"
                        }
                    }
                }
            } catch (e: Exception) {
                source.close()
                return "error at ${input.position / 1024} KiB: ${e.javaClass.simpleName} ${e.message?.take(60)} last=${rec.lastUs / 1000} ms"
            }
            source.close()
        }
        return "gave up after $opens opens, last=${rec.lastUs / 1000} ms"
    }

    // ------------------------------------------------------------------------------------------ the run

    @Test
    fun liveReliabilityOfTheAudioPath() {
        val on = System.getProperty("live.playback") == "1" || System.getenv("LIVE_PLAYBACK") == "1"
        assumeTrue("LIVE_PLAYBACK not set: the network test is skipped", on)
        val count = System.getenv("LIVE_PLAYBACK_COUNT")?.toIntOrNull() ?: picks.size
        val fullReads = System.getenv("LIVE_PLAYBACK_FULL")?.toIntOrNull() ?: 6
        val gapMs = System.getenv("LIVE_PLAYBACK_GAP_MS")?.toLongOrNull() ?: 1200L
        val started = System.currentTimeMillis()
        var fullDone = 0
        var boundedProbes = 0
        var botRun = 0
        var ok = 0
        var failed = 0
        val clients = HashMap<String, Int>()
        val slow = ArrayList<String>()
        try {
            runBlocking {
                val extractor = buildExtractor()
                say("LIVE audio path, ${java.time.LocalDateTime.now()}, songs=${picks.take(count).size}, PO token: WebView declared but empty (JVM)")
                for ((index, pick) in picks.take(count).withIndex()) {
                    if (botRun >= 3) {
                        say("STOP: three bot checks in a row")
                        break
                    }
                    if (index > 0) delay(gapMs)
                    val song =
                        try {
                            YouTube
                                .search(pick.query, YouTube.SearchFilter.FILTER_SONG, recordSearchHistory = false)
                                .getOrThrow()
                                .items
                                .filterIsInstance<SongItem>()
                                .firstOrNull { !it.isEpisode }
                        } catch (e: Exception) {
                            say("#${index + 1} ${pick.query}: search failed ${e.javaClass.simpleName} ${e.message?.take(60)}")
                            null
                        } ?: continue
                    synchronized(warnings) { warnings.clear() }
                    val first = extract(extractor, song.id, song.explicit, emptySet())
                    val head = "#${index + 1} [${pick.label}] ${song.title} - ${song.artists.joinToString { it.name }} (${song.id}, ${song.duration}s${if (song.explicit) ", E" else ""})"
                    if (!first.ok) {
                        failed++
                        if (first.why.contains("bot", ignoreCase = true) || first.why.contains("LOGIN", ignoreCase = true)) botRun++ else botRun = 0
                        say("$head\n   FAIL ${first.ms} ms: ${first.why}\n   attempts: ${first.attempts}\n   warnings: ${synchronized(warnings) { warnings.take(6).joinToString(" | ") }}")
                        continue
                    }
                    botRun = 0
                    ok++
                    val s = first.stream!!
                    clients.merge(s.clientName, 1, Int::plus)
                    if (first.ms > 6000) slow += "${song.title} ${first.ms} ms"
                    val len = s.contentLengthBytes
                    say(
                        "$head\n   OK ${first.ms} ms client=${s.clientName} profile=${s.profileId} itag=${s.itag} ${s.mimeType};${s.codecs} " +
                            "br=${s.bitrate} len=${len?.div(1024)}KiB bounded=${s.requireBoundedRange} chunks=${s.useRangeChunks} chunk=${s.rangeChunkSizeBytes / 1024}KiB " +
                            "expires=${s.expiresAt} headers=${s.headers.keys}\n   attempts: ${first.attempts}",
                    )
                    val firstMib = read(s, 0, (1L shl 20) - 1, 1L shl 20)
                    val tail = if (len != null && len > 300_000) read(s, len - 262_144, len - 1, 262_144) else null
                    val middle = if (len != null && len > 600_000) read(s, len / 2, len / 2 + 262_143, 262_144) else null
                    val unbounded = read(s, 0, null, 262_144)
                    val openEnded = read(s, 0, null, 262_144, openEnded = true)
                    say("   reads: first1MiB=$firstMib tail256K=$tail mid256K=$middle unbounded256K=$unbounded openEnded256K=$openEnded")
                    // the chunk the app now uses for this stream (StreamUrlCache.chunkBytesOrNull)
                    val appChunk =
                        CachedStreamUrl(s.audioUrl, s.headers, s.clientName, s.requireBoundedRange, s.rangeChunkSizeBytes, s.useRangeChunks)
                            .chunkBytesOrNull()
                    if (appChunk != null && fullDone < fullReads) {
                        fullDone++
                        if (s.requireBoundedRange || s.useRangeChunks) say("   whole song, old app read: ${readLikeOldApp(s)}")
                        say("   whole song, new app read (ranges of ${appChunk / 1024} KiB): ${readContinuous(s, appChunk)}")
                    }
                    if (boundedProbes < 2) {
                        // a bounded-range client (what a phone gets when its preferred clients fail): old read vs new read
                        boundedProbes++
                        val vr = extract(extractor, song.id, song.explicit, emptySet(), clientOverride = "ANDROID_VR_1_65_10")
                        val v = vr.stream
                        if (v == null) {
                            say("   ANDROID_VR forced: FAIL ${vr.ms} ms ${vr.why.take(120)}")
                        } else {
                            say("   ANDROID_VR forced: OK ${vr.ms} ms itag=${v.itag} bounded=${v.requireBoundedRange} chunks=${v.useRangeChunks} chunk=${v.rangeChunkSizeBytes / 1024}KiB len=${v.contentLengthBytes?.div(1024)}KiB")
                            say("      unbounded256K=${read(v, 0, null, 262_144)} first1MiB=${read(v, 0, (1L shl 20) - 1, 1L shl 20)}")
                            say("      whole song, old app read: ${readLikeOldApp(v)}")
                            val c = CachedStreamUrl(v.audioUrl, v.headers, v.clientName, v.requireBoundedRange, v.rangeChunkSizeBytes, v.useRangeChunks).chunkBytesOrNull() ?: (1L shl 20)
                            say("      whole song, new app read (ranges of ${c / 1024} KiB): ${readContinuous(v, c)}")
                        }
                    }
                    val second = extract(extractor, song.id, song.explicit, setOf(s.clientName))
                    say(
                        if (second.ok) {
                            "   retry without ${s.clientName}: OK ${second.ms} ms client=${second.stream!!.clientName} itag=${second.stream.itag} bounded=${second.stream.requireBoundedRange}"
                        } else {
                            "   retry without ${s.clientName}: FAIL ${second.ms} ms ${second.why.take(120)}"
                        },
                    )
                }
            }
        } finally {
            val seconds = (System.currentTimeMillis() - started) / 1000
            say("")
            say("SUMMARY: ok=$ok failed=$failed clients=$clients slow(>6s)=$slow time=${seconds}s")
            System.getenv("LIVE_PLAYBACK_OUT")?.let { File(it).writeText(lines.joinToString("\n")) }
        }
    }
}
