package com.metrolist.music.video

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.test.core.app.ApplicationProvider
import com.metrolist.innertubex.sabr.SabrChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A REAL ExoPlayer (its ProgressiveMediaPeriod, loader thread, SeekMap and cancel by interrupt) on the synthetic WebM
 * through the data source, with a renderer that only counts samples. This is the closest the PC gets to the phone
 * without MediaCodec: the start hint with the adopted stream, a seek past the buffer, and a failing stream.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SabrExoPlayerPipelineTest {
    /** ExoPlayer adds this to the time of every sample the renderers get (MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US). */
    private val RENDERER_OFFSET_US = 1_000_000_000_000L

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val webm = SyntheticWebm(clusterCount = 40) // 160 s, 40 clusters of 4 s
    private var player: ExoPlayer? = null
    private var factory: SabrVideoDataSourceFactory? = null

    @After
    fun tearDown() {
        player?.release()
        factory?.shutdown()
    }

    private class FakeSabr(
        private val file: SyntheticWebm,
        private val failAfterChunks: Int = -1,
    ) : SabrChunkSource {
        val starts = CopyOnWriteArrayList<Long>()
        val emitted = java.util.concurrent.atomic.AtomicInteger()

        override fun chunks(
            startMs: Long,
            clock: () -> Long,
        ): Flow<SabrChunk> =
            flow {
                starts += startMs
                var sent = 0
                for (chunk in file.chunksFrom(startMs)) {
                    if (failAfterChunks >= 0 && sent >= failAfterChunks) throw IOException("network down")
                    emit(chunk)
                    emitted.incrementAndGet()
                    sent++
                }
            }
    }

    /** Reads every sample of the stream, per "epoch" (a new one after every seek/reset). */
    private class SampleCounter : BaseRenderer(C.TRACK_TYPE_VIDEO) {
        class Epoch {
            @Volatile var samples = 0

            @Volatile var firstUs = Long.MIN_VALUE

            @Volatile var lastUs = Long.MIN_VALUE
        }

        private val holder = FormatHolder()
        private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)

        @Volatile var ended = false
        val epochs = CopyOnWriteArrayList<Epoch>()

        override fun getName() = "SampleCounter"

        override fun supportsFormat(format: Format): Int =
            RendererCapabilities.create(if (MimeTypes.isVideo(format.sampleMimeType)) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE)

        override fun onPositionReset(
            positionUs: Long,
            joining: Boolean,
            sampleStreamIsResetToKeyFrame: Boolean,
        ) {
            epochs += Epoch()
            ended = false
        }

        override fun render(
            positionUs: Long,
            elapsedRealtimeUs: Long,
        ) {
            if (ended) return
            if (epochs.isEmpty()) epochs += Epoch()
            val epoch = epochs.last()
            while (true) {
                buffer.clear()
                when (readSource(holder, buffer, SampleStream.FLAG_OMIT_SAMPLE_DATA)) {
                    C.RESULT_NOTHING_READ -> return
                    C.RESULT_FORMAT_READ -> continue
                    else -> {
                        if (buffer.isEndOfStream) {
                            ended = true
                            return
                        }
                        epoch.samples++
                        if (epoch.firstUs == Long.MIN_VALUE) epoch.firstUs = buffer.timeUs
                        epoch.lastUs = buffer.timeUs
                    }
                }
            }
        }

        override fun isReady() = true

        override fun isEnded() = ended
    }

    private fun build(
        fake: SabrChunkSource,
        hintMs: Long,
        startMs: Long,
        retries: Int = 1,
        file: SyntheticWebm = webm,
        smallBuffer: Boolean = false,
    ): Pair<ExoPlayer, SampleCounter> {
        val counter = SampleCounter()
        val timeMap = SabrTimeMap(file.durationMs)
        val f = SabrVideoDataSourceFactory(fake, timeMap, VideoClock(), SabrDataSourceSettings(startHintMs = { hintMs }))
        factory = f
        val builder =
            ExoPlayer
                .Builder(context, RenderersFactory { _, _, _, _, _ -> arrayOf<Renderer>(counter) })
                .setLooper(Looper.getMainLooper())
        if (smallBuffer) {
            // like the video player of the app: a few seconds ahead of the position, then the loading waits
            builder.setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl
                    .Builder()
                    .setBufferDurationsMs(1_000, 2_000, 500, 500)
                    .setBackBuffer(0, false)
                    .build(),
            )
        }
        val exo = builder.build()
        player = exo
        val source =
            ProgressiveMediaSource
                .Factory(f, sabrExtractorsFactory(VideoContainer.WEBM, timeMap))
                .setContinueLoadingCheckIntervalBytes(if (smallBuffer) 64 * 1024 else 1024 * 1024)
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(retries))
                .createMediaSource(MediaItem.fromUri("sabr-video://pipeline"))
        exo.setMediaSource(source, startMs)
        exo.playWhenReady = true
        exo.prepare()
        return exo to counter
    }

    /**
     * Runs the main looper until [condition] holds. Robolectric's clock is frozen between calls, and ExoPlayer's playback
     * thread schedules its work against that clock, so the clock is moved forward by the real time that passed.
     */
    private fun pump(
        what: String,
        timeoutMs: Long = 15_000,
        condition: () -> Boolean,
    ) {
        val looper = shadowOf(Looper.getMainLooper())
        val end = System.currentTimeMillis() + timeoutMs
        var last = System.nanoTime()
        while (System.currentTimeMillis() < end) {
            val now = System.nanoTime()
            val passed = (now - last) / 1_000_000L
            if (passed > 0) {
                looper.idleFor(Duration.ofMillis(passed))
                last = now
            } else {
                looper.idle()
            }
            if (condition()) return
            Thread.sleep(5)
        }
        looper.idle()
        assertTrue("timeout: $what", condition())
    }

    @Test
    fun `playing from the start reads every frame once`() {
        val fake = FakeSabr(webm)
        val (_, counter) = build(fake, hintMs = 0L, startMs = 0L)
        pump("the stream is read to the end") { counter.ended }
        val epoch = counter.epochs.first()
        assertEquals(40 * webm.framesPerCluster, epoch.samples)
        assertEquals(0L, epoch.firstUs - RENDERER_OFFSET_US)
        assertEquals(listOf(0L), fake.starts.toList())
    }

    @Test
    fun `a start in the middle - header read, seek to the start position, one SABR request, the right frames`() {
        val fake = FakeSabr(webm)
        val (exo, counter) = build(fake, hintMs = 90_500L, startMs = 90_500L)
        pump("the stream is read to the end") { counter.ended }
        // frames start at the key frame of the segment that contains 90.5 s (88 s) and run to the end
        val last = counter.epochs.last { it.samples > 0 }
        assertEquals(88_000_000L, last.firstUs - RENDERER_OFFSET_US)
        assertEquals((40 - 22) * webm.framesPerCluster, last.samples)
        assertEquals("one SABR request for the header and the seek", listOf(90_500L), fake.starts.toList())
        assertEquals(160_000L, exo.duration)
        assertEquals(null, exo.playerError)
    }

    @Test
    fun `with a small buffer the stream is not read ahead, a seek outside the buffer restarts SABR at the target segment`() {
        val big = SyntheticWebm(clusterCount = 40, frameBytes = 5_000) // 8 MB, 160 s
        val fake = FakeSabr(big)
        val (exo, counter) = build(fake, hintMs = 0L, startMs = 0L, file = big, smallBuffer = true)
        pump("the first seconds") { (counter.epochs.firstOrNull()?.samples ?: 0) >= 40 }
        Thread.sleep(500)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("not read to the end: ${fake.emitted.get()} chunks", fake.emitted.get() < 41)
        val before = counter.epochs.size
        exo.seekTo(100_500L)
        pump("the frames after the seek") { counter.epochs.size > before && counter.epochs.last().samples >= 40 }
        assertEquals(100_000_000L, counter.epochs.last().firstUs - RENDERER_OFFSET_US)
        assertEquals(listOf(0L, 100_000L), fake.starts.toList())
        assertEquals(null, exo.playerError)
    }

    @Test
    fun `a seek back and forth keeps working`() {
        val fake = FakeSabr(webm)
        val (exo, counter) = build(fake, hintMs = 0L, startMs = 0L)
        pump("end") { counter.ended }
        for (target in listOf(100_000L, 12_500L, 150_000L)) {
            val before = counter.epochs.size
            exo.seekTo(target)
            pump("frames after the seek to $target") { counter.epochs.size > before && counter.ended && counter.epochs.last().samples > 0 }
            val cue = target / 4_000 * 4_000
            assertEquals("first frame after the seek to $target", cue * 1000L, counter.epochs.last().firstUs - RENDERER_OFFSET_US)
        }
        assertEquals(null, exo.playerError)
    }

    @Test
    fun `a stream that fails ends in a player error, not a hang`() {
        val fake = FakeSabr(webm, failAfterChunks = 1) // the init chunk, then the network goes: no progress, so the retries run out
        val (exo, _) = build(fake, hintMs = 0L, startMs = 0L, retries = 1)
        var error: PlaybackException? = null
        exo.addListener(
            object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    error = e
                }
            },
        )
        pump("the player error", timeoutMs = 20_000) { error != null }
        assertNotNull(error)
        val e = error!!
        assertTrue(e.message.orEmpty() + " / " + e.cause, e.cause is IOException || e.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    }

    @Test
    fun `releasing the player while the stream is open ends the stream`() {
        val fake = FakeSabr(webm)
        val (exo, counter) = build(fake, hintMs = 0L, startMs = 0L)
        pump("some frames") { counter.epochs.firstOrNull()?.samples ?: 0 > 10 }
        exo.release()
        player = null
        factory?.shutdown()
        // nothing left to wait for, but the test must not hang or throw: the loader thread has been interrupted
        shadowOf(Looper.getMainLooper()).idle()
    }
}
