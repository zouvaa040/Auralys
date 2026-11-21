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
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The ONE test that uses the network. Skipped unless the system property `live.video=1` or the environment variable
 * `LIVE_VIDEO=1` is set (so it never runs in a normal build). It does what the phone does, minus the screen:
 *
 * 1. asks the library for the SABR video (no PO token, like the feasibility probe),
 * 2. reads the first 20 s through [SabrVideoDataSource] with media3's own extractor and counts the frames,
 * 3. seeks (the SeekMap names the byte, the data source restarts SABR at that segment) and reads 10 s more,
 * 4. repeats the start with a start hint at 45 s (header read, then the seek that adopts the parked stream).
 *
 * `LIVE_VIDEO_IDS` = comma separated video ids (default: one VP9 and one H.264 sample), `LIVE_VIDEO_CAP` = height cap
 * (default 360), `LIVE_VIDEO_OUT` = a file the numbers are also written to.
 */
@OptIn(ExperimentalSabrApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SabrVideoLiveTest {
    private val lines = ArrayList<String>()

    private fun say(text: String) {
        println(text)
        lines += text
    }

    private val logger =
        InnerTubeLogger { event ->
            if (event.level == InnerTubeLogLevel.WARN || event.level == InnerTubeLogLevel.ERROR) {
                say("   [lib ${event.level}] ${event.tag}: ${event.message} ${event.details}")
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

    private suspend fun buildExtractor(): InnerTubeExtractor {
        YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
        YouTube.visitorData().getOrNull()?.let { YouTube.visitorData = it }
        val t = YouTube.extractionTransport()
        val store = RemotePlayerConfigStore(t.httpClient, memoryConfig, logger)
        val cipher = YouTubeCipherService(t.httpClient, store, logger)
        val parser: YtConfigParser = YtConfigParserImpl(t.httpClient, t.innerTube, store, logger)
        return InnerTubeExtractor(
            configParser =
                object : YtConfigParser by parser {
                    override suspend fun fetchConfig(
                        videoId: String,
                        useLoginCookies: Boolean,
                    ) = try {
                        parser.fetchConfig(videoId, useLoginCookies)
                    } catch (_: IllegalStateException) {
                        parser.fetchEmbeddedConfig(videoId, useLoginCookies = false)
                    }
                },
            cipherService = cipher,
            innerTube = t.innerTube,
            tokenProvider = noTokens,
            logger = logger,
        )
    }

    @Test
    fun liveVideoThroughTheDataSource() {
        val on = System.getProperty("live.video") == "1" || System.getenv("LIVE_VIDEO") == "1"
        assumeTrue("live.video / LIVE_VIDEO not set: the network test is skipped", on)
        val ids = (System.getenv("LIVE_VIDEO_IDS") ?: "dQw4w9WgXcQ,9bZkp7q19f0").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val cap = System.getenv("LIVE_VIDEO_CAP")?.toIntOrNull() ?: 360
        val failures = ArrayList<String>()
        try {
            runBlocking {
                val extractor = buildExtractor()
                for (id in ids) {
                    try {
                        runOne(extractor, id, cap)
                    } catch (e: Throwable) {
                        say("   FAILED $id: ${e.javaClass.simpleName}: ${e.message}")
                        e.printStackTrace()
                        failures += "$id: ${e.message}"
                    }
                }
            }
        } finally {
            System.getenv("LIVE_VIDEO_OUT")?.let { File(it).writeText(lines.joinToString("\n")) }
        }
        assertTrue("live failures: $failures", failures.isEmpty())
    }

    private suspend fun runOne(
        extractor: InnerTubeExtractor,
        id: String,
        cap: Int,
    ) {
        say("=== $id cap=${cap}p")
        val t0 = System.nanoTime()
        val stream =
            extractor.extract(
                videoId = id,
                hints = ContentHints(wantVideo = true, maxVideoHeight = cap, sabrFirst = true).withStreamCapabilities(allowHls = false, allowSabr = true, allowBoundedRange = true),
                excludedClients = emptySet(),
                audioQuality = AudioQuality.AUTO,
                clientPlaybackNonce = generateClientPlaybackNonce(),
            )
        val boot = requireNotNull(stream?.sabrVideoBootstrap) { "no SABR video bootstrap" }
        say("   extract ${"%.1f".format((System.nanoTime() - t0) / 1e9)} s: client=${stream.clientName} profile=${stream.profileId} video itag=${stream.videoItag} ${stream.videoWidth}x${stream.videoHeight} ${stream.videoMimeType} codecs=${stream.videoCodecs} len=${stream.videoContentLengthBytes} duration=${boot.durationMs} ms")
        val format = requireNotNull(VideoFormats.parse(stream.videoMimeType, stream.videoCodecs, stream.videoWidth, stream.videoHeight, stream.videoBitrate)) { "unreadable format" }
        say("   format: ${format.container} ${format.codec} (decoder mime ${format.codec.decoderMime})")

        // 1. from the start: the first 20 s
        run {
            val rig = Rig(boot, format, startHintMs = 0L)
            val rec = Recorder()
            rig.extractor.init(rec)
            rig.clock.start(0L)
            val begin = System.nanoTime()
            rig.ds.open(DataSpec(rig.uri))
            val input = DefaultExtractorInput(rig.ds, 0, C.LENGTH_UNSET.toLong())
            val holder = PositionHolder()
            var firstFrameAt = -1.0
            while (rec.lastUs < 20_000_000L) {
                check(rig.extractor.read(input, holder) != Extractor.RESULT_END_OF_INPUT) { "the stream ended before 20 s (last ${rec.lastUs} us)" }
                check((System.nanoTime() - begin) / 1e9 < 90.0) { "no 20 s of frames within 90 s" }
                if (firstFrameAt < 0 && rec.samples > 0) firstFrameAt = (System.nanoTime() - begin) / 1e9
            }
            val sec = (System.nanoTime() - begin) / 1e9
            val bytesRead = rig.bytes.get()
            rig.ds.close()
            val track = rec.video
            say(
                "   [start] ${rec.samples} frames, ${rec.keyframes} key frames, first ${rec.firstUs / 1000} ms, last ${rec.lastUs / 1000} ms, " +
                    "${track?.sampleMimeType} ${track?.width}x${track?.height}; first frame after ${"%.2f".format(firstFrameAt)} s, " +
                    "20 s of media after ${"%.2f".format(sec)} s; ${bytesRead / 1024} KiB read; SABR starts=${rig.source.starts}",
            )
            val sm = requireNotNull(rec.seekMap) { "no seek map" }
            say("   [start] seekMap seekable=${sm.isSeekable} duration=${sm.durationUs / 1000} ms; seek point for 60 s = ${sm.getSeekPoints(60_000_000L).first.timeUs / 1000} ms at byte ${sm.getSeekPoints(60_000_000L).first.position}")
            assertTrue("seekable", sm.isSeekable)
            assertTrue("frames", rec.samples > 100)
            assertNotNull("a video track", track)
            assertEquals("the track is the codec the library announced", format.codec.decoderMime, track?.sampleMimeType)
            rig.shutdown()
        }

        // 2. a seek to 60 s (or the middle of a short video): SABR restarts at the segment of that byte
        run {
            val rig = Rig(boot, format, startHintMs = 0L)
            val rec = Recorder()
            rig.extractor.init(rec)
            rig.clock.start(0L)
            rig.ds.open(DataSpec(rig.uri))
            var input = DefaultExtractorInput(rig.ds, 0, C.LENGTH_UNSET.toLong())
            val holder = PositionHolder()
            while (rec.seekMap == null || rec.samples < 10) {
                check(rig.extractor.read(input, holder) != Extractor.RESULT_END_OF_INPUT) { "ended before the seek map" }
            }
            val seekMs = minOf(60_000L, boot.durationMs / 2)
            val point = rec.seekMap!!.getSeekPoints(seekMs * 1000L).first
            rig.ds.close()
            rig.clock.start(seekMs)
            val begin = System.nanoTime()
            rig.ds.open(DataSpec.Builder().setUri(rig.uri).setPosition(point.position).build())
            rig.extractor.seek(point.position, seekMs * 1000L)
            input = DefaultExtractorInput(rig.ds, point.position, C.LENGTH_UNSET.toLong())
            rec.reset()
            var firstAt = -1.0
            while (rec.lastUs < (point.timeUs + 10_000_000L)) {
                check(rig.extractor.read(input, holder) != Extractor.RESULT_END_OF_INPUT) { "ended after the seek" }
                check((System.nanoTime() - begin) / 1e9 < 90.0) { "no 10 s of frames after the seek within 90 s" }
                if (firstAt < 0 && rec.samples > 0) firstAt = (System.nanoTime() - begin) / 1e9
            }
            rig.ds.close()
            say(
                "   [seek] to $seekMs ms: seek point ${point.timeUs / 1000} ms at byte ${point.position}; first frame ${rec.firstUs / 1000} ms after ${"%.2f".format(firstAt)} s, " +
                    "10 s of media after ${"%.2f".format((System.nanoTime() - begin) / 1e9)} s; ${rec.samples} frames, ${rec.keyframes} key frames; SABR starts=${rig.source.starts}",
            )
            assertEquals("the first frame is the key frame of the segment that was asked for", point.timeUs, rec.firstUs)
            assertTrue("frames after the seek", rec.samples > 100)
            rig.shutdown()
        }

        // 3. a start hint: header read, then the seek to the hint adopts the parked stream (one SABR request)
        run {
            val hintMs = minOf(45_000L, boot.durationMs / 2)
            val rig = Rig(boot, format, startHintMs = hintMs)
            val rec = Recorder()
            rig.extractor.init(rec)
            rig.clock.start(hintMs)
            val begin = System.nanoTime()
            rig.ds.open(DataSpec(rig.uri))
            var input = DefaultExtractorInput(rig.ds, 0, C.LENGTH_UNSET.toLong())
            val holder = PositionHolder()
            var result: Int
            do {
                result = rig.extractor.read(input, holder)
            } while (result == Extractor.RESULT_CONTINUE)
            val headerAt = (System.nanoTime() - begin) / 1e9
            assertEquals("the header read ends at the gap", Extractor.RESULT_END_OF_INPUT, result)
            val sm = requireNotNull(rec.seekMap) { "no seek map after the header" }
            rig.ds.close()
            val point = sm.getSeekPoints(hintMs * 1000L).first
            rig.ds.open(DataSpec.Builder().setUri(rig.uri).setPosition(point.position).build())
            rig.extractor.seek(point.position, hintMs * 1000L)
            input = DefaultExtractorInput(rig.ds, point.position, C.LENGTH_UNSET.toLong())
            rec.reset()
            var firstAt = -1.0
            while (rec.lastUs < point.timeUs + 10_000_000L) {
                check(rig.extractor.read(input, holder) != Extractor.RESULT_END_OF_INPUT) { "ended after the hint seek" }
                check((System.nanoTime() - begin) / 1e9 < 90.0) { "no 10 s of frames within 90 s" }
                if (firstAt < 0 && rec.samples > 0) firstAt = (System.nanoTime() - begin) / 1e9
            }
            rig.ds.close()
            say(
                "   [hint] start at $hintMs ms: header after ${"%.2f".format(headerAt)} s, first frame ${rec.firstUs / 1000} ms after ${"%.2f".format(firstAt)} s, " +
                    "10 s of media after ${"%.2f".format((System.nanoTime() - begin) / 1e9)} s; SABR starts=${rig.source.starts}",
            )
            assertEquals("one SABR request for the header and the seek", 1, rig.source.starts.size)
            assertEquals(point.timeUs, rec.firstUs)
            rig.shutdown()
        }
    }

    /** The real data source, the real extractor of the format and the library's SABR stream, with a clock that runs in real time. */
    private inner class Rig(
        boot: com.metrolist.innertubex.sabr.SabrBootstrap,
        format: VideoFormatInfo,
        startHintMs: Long,
    ) {
        val uri: android.net.Uri = android.net.Uri.parse("sabr-video://live")
        val clock = RunningClock()
        val bytes = java.util.concurrent.atomic.AtomicLong()
        val timeMap = SabrTimeMap(boot.durationMs)
        val real = LibrarySabrChunkSource(httpClient = { YouTube.extractionTransport().httpClient }, bootstrap = boot, logger = logger)
        val source = CountingSource(real)
        val factory = SabrVideoDataSourceFactory(source, timeMap, clock.videoClock, SabrDataSourceSettings(startHintMs = { startHintMs }))
        val ds = factory.createDataSource().also { it.addTransferListener(CountingListener(bytes)) }
        val extractor: Extractor = sabrExtractorsFactory(format.container, timeMap).createExtractors()[0]

        fun shutdown() {
            factory.shutdown()
            clock.stop()
        }
    }

    private class CountingListener(
        private val total: java.util.concurrent.atomic.AtomicLong,
    ) : androidx.media3.datasource.TransferListener {
        override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            total.addAndGet(bytesTransferred.toLong())
        }

        override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }

    private class CountingSource(
        private val inner: SabrChunkSource,
    ) : SabrChunkSource {
        val starts = CopyOnWriteArrayList<Long>()

        override fun chunks(
            startMs: Long,
            clock: () -> Long,
        ) = inner.chunks(startMs.also { starts += it }, clock)
    }

    /** A playback clock that runs in real time from [start], what a playing video player would report. */
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
        var video: Format? = null
        var samples = 0
        var keyframes = 0
        var firstUs = Long.MIN_VALUE
        var lastUs = Long.MIN_VALUE
        var seekMap: SeekMap? = null

        private val trackOut =
            object : TrackOutput {
                override fun format(format: Format) {
                    if (format.sampleMimeType?.startsWith("video/") == true) video = format
                }

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
                    if (firstUs == Long.MIN_VALUE) firstUs = timeUs
                    if (timeUs > lastUs) lastUs = timeUs
                }
            }

        override fun track(
            id: Int,
            type: Int,
        ): TrackOutput = trackOut

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }

        fun reset() {
            samples = 0
            keyframes = 0
            firstUs = Long.MIN_VALUE
            lastUs = Long.MIN_VALUE
        }
    }
}
