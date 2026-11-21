/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * An ExoPlayer DataSource over the library's SABR video stream (`SabrVideoStream`, innertubex).
 *
 * SABR is not a file download: it is a conversation in which the player says "I am at time T" and the server sends the
 * next segments. The segments are cut from the progressive file, and every segment says at which byte of that file it
 * starts (`startRange`), so writing them at their `startRange` gives the progressive file (webm: EBML header + Tracks +
 * Cues, then clusters; mp4: ftyp + moov + sidx, then fragments). ExoPlayer's own extractors read that layout. What is
 * missing is seeking: ExoPlayer asks for "byte X", SABR wants "time T".
 *
 * - open(0): a new SABR stream from the start (or from the start hint, see below). The init segment comes first.
 * - open(X), X > 0 (a seek or a retry): the extractor's SeekMap says which cue (segment) begins at or before byte X;
 *   its time is T. A new SABR stream is started at T. It repeats the init segment (skipped here, it lies before X) and
 *   then sends the segment whose `startRange` is that cue's byte. A chunk that begins before X is cut, a gap is an
 *   error (the player retries).
 * - Start hint: ExoPlayer always opens byte 0 first, even when playback starts at 90 s, reads the header, and only then
 *   seeks. With a hint the stream at open(0) is started at the hint time: the init segment is served, and when the next
 *   segment lies far behind it (a gap) the read ends there ("end of input" for the extractor, which has all it needs).
 *   The stream is kept for a few seconds, and the open(X) that follows adopts it when X is the segment it was sent, so
 *   the start costs one SABR request instead of two.
 * - Backpressure: the stream is read into a bounded queue by its own coroutine; SABR itself does not send more than
 *   about 25 s ahead of the clock it is given (the video player's position, see [VideoClock]).
 * - close() cancels (or parks) the stream; interrupting the loading thread (ExoPlayer does that on cancel) ends a
 *   blocked read.
 *
 * Tested with a fake SABR source and a synthetic WebM (SabrVideoDataSourceTest), and once live (SabrVideoLiveTest).
 */

package com.metrolist.music.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.innertubex.sabr.SabrVideoStream
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Where the SABR chunks come from. The real one is [LibrarySabrChunkSource]; tests use a fake. */
fun interface SabrChunkSource {
    /**
     * The chunks of the video stream started at media time [startMs]: first the init segment, then the segment that
     * contains [startMs] and the ones after it. [clock] is the playback position the server is told about.
     */
    fun chunks(
        startMs: Long,
        clock: () -> Long,
    ): Flow<SabrChunk>
}

/** The video player's position, written by the controller and read by the SABR stream's coroutine. */
class VideoClock {
    private val value = AtomicLong(0L)

    var positionMs: Long
        get() = value.get()
        set(v) = value.set(v.coerceAtLeast(0L))
}

/** The real chunk source: the library's [SabrVideoStream] started at the time asked for. */
@OptIn(ExperimentalSabrApi::class)
class LibrarySabrChunkSource(
    private val httpClient: () -> HttpClient,
    private val bootstrap: SabrBootstrap,
    private val logger: InnerTubeLogger = InnerTubeLogger.NONE,
) : SabrChunkSource {
    override fun chunks(
        startMs: Long,
        clock: () -> Long,
    ): Flow<SabrChunk> =
        SabrVideoStream(
            httpClient = httpClient(),
            bootstrap = bootstrap,
            playbackPositionMs = clock,
            initialPlayerTimeMs = startMs,
            logger = logger,
        ).chunks()
}

/**
 * Byte position to media time, from the SeekMap the extractor builds out of the Cues (webm) or the sidx (mp4). The cue
 * positions are the same numbers as SABR's `startRange`, so a cue is exactly a segment.
 */
class SabrTimeMap(
    private val durationHintMs: Long,
) {
    @Volatile
    var seekMap: SeekMap? = null

    /**
     * The start time (ms) of the segment that begins at or before [position] (0 inside the header). Null while there is
     * no SeekMap or when the stream is not seekable.
     */
    fun startMsForByte(position: Long): Long? {
        val map = seekMap ?: return null
        if (!map.isSeekable) return null
        val durationUs = map.durationUs.takeIf { it != C.TIME_UNSET && it > 0 } ?: (durationHintMs * 1000L)
        // before the first cue (inside the header): the stream starts at the beginning, the header is cut from its init chunk
        if (map.getSeekPoints(0L).first.position > position) return 0L
        var lo = 0L
        var hi = durationUs.coerceAtLeast(0L)
        // the largest time whose seek point is at or before the position (the seek point is monotone in time)
        while (lo < hi) {
            val mid = lo + (hi - lo + 1) / 2
            if (map.getSeekPoints(mid).first.position <= position) lo = mid else hi = mid - 1
        }
        return map.getSeekPoints(lo).first.timeUs / 1000L
    }

    /** The start time (ms) of the segment that contains media time [timeMs]. Null without a usable SeekMap. */
    fun segmentStartMsAt(timeMs: Long): Long? {
        val map = seekMap ?: return null
        if (!map.isSeekable) return null
        return map.getSeekPoints(timeMs.coerceAtLeast(0L) * 1000L).first.timeUs / 1000L
    }
}

/** Passes everything to [inner] and hands its SeekMap to [onSeekMap] on the way to the player. */
class SeekMapTapExtractor(
    private val inner: Extractor,
    private val onSeekMap: (SeekMap) -> Unit,
) : Extractor {
    override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)

    override fun init(output: ExtractorOutput) {
        inner.init(
            object : ExtractorOutput {
                override fun track(
                    id: Int,
                    type: Int,
                ) = output.track(id, type)

                override fun endTracks() = output.endTracks()

                override fun seekMap(seekMap: SeekMap) {
                    onSeekMap(seekMap)
                    output.seekMap(seekMap)
                }
            },
        )
    }

    override fun read(
        input: ExtractorInput,
        seekPosition: PositionHolder,
    ): Int = inner.read(input, seekPosition)

    override fun seek(
        position: Long,
        timeUs: Long,
    ) = inner.seek(position, timeUs)

    override fun release() = inner.release()

    override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
}

/** The extractor that reads the container of the chosen format, wrapped so the data source can see its SeekMap. */
fun sabrExtractorsFactory(
    container: VideoContainer,
    timeMap: SabrTimeMap,
): ExtractorsFactory =
    ExtractorsFactory {
        val inner: Extractor =
            when (container) {
                VideoContainer.WEBM -> MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED)
                VideoContainer.MP4 -> FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED)
            }
        arrayOf(SeekMapTapExtractor(inner) { timeMap.seekMap = it })
    }

/** Knobs of the data source; the defaults are what the app uses, tests shrink the times. */
class SabrDataSourceSettings(
    /** Chunks (segments, a few seconds each) the queue holds before the stream waits. */
    val maxQueuedChunks: Int = 12,
    /** A start hint below this is ignored: the first segment covers it anyway. */
    val minHintMs: Long = 6_000L,
    /** How long a stream kept for the seek that follows the header read waits for it. */
    val parkMs: Long = 8_000L,
    /** The time of the first sample the player will want, or 0. Read at open(0). */
    val startHintMs: () -> Long = { 0L },
)

class SabrVideoDataSource(
    private val source: SabrChunkSource,
    private val timeMap: SabrTimeMap,
    private val clock: VideoClock,
    private val scope: CoroutineScope,
    private val settings: SabrDataSourceSettings = SabrDataSourceSettings(),
    private val parked: AtomicReference<SabrVideoDataSource.Stream?> = AtomicReference(null),
) : BaseDataSource(/* isNetwork = */ true) {
    private sealed interface Item {
        class Data(
            val chunk: SabrChunk,
        ) : Item

        object Done : Item

        class Failure(
            val error: Throwable,
        ) : Item
    }

    /** One running SABR stream with its bounded queue. */
    class Stream internal constructor(
        val startMs: Long,
        internal val queue: LinkedBlockingQueue<Any>,
        /** Opened at byte 0 with a start hint: reading stops at the gap after the init segment. */
        @Volatile var stopsAtGap: Boolean,
    ) {
        /** The start of the segment that contains [startMs], set when the stream is parked. */
        @Volatile internal var cueMs: Long? = null

        internal var job: Job? = null

        /** First media chunk that was read but not delivered (the gap), kept for the open() that adopts the stream. */
        @Volatile internal var heldChunk: SabrChunk? = null

        @Volatile internal var finished = false

        /** The stream ended with an error: it is never kept. */
        @Volatile internal var failed = false

        internal fun cancel() {
            job?.cancel()
            queue.clear()
            queue.offer(Item.Failure(IOException("SABR stream closed")))
        }
    }

    private var uri: Uri? = null
    private var stream: Stream? = null
    private var current: ByteArray? = null
    private var currentOffset = 0
    private var filePosition = 0L
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        endStream(park = false)
        val position = dataSpec.position
        stream = obtainStream(position)
        current = null
        currentOffset = 0
        filePosition = position
        bytesRemaining = dataSpec.length
        opened = true
        transferStarted(dataSpec)
        return dataSpec.length
    }

    private fun obtainStream(position: Long): Stream {
        val waiting = parked.getAndSet(null)
        if (position == 0L) {
            waiting?.cancel()
            val hint = settings.startHintMs()
            return if (hint >= settings.minHintMs) startStream(hint, stopsAtGap = true) else startStream(0L, stopsAtGap = false)
        }
        val ms = timeMap.startMsForByte(position) ?: run {
            waiting?.cancel()
            throw IOException("SABR: no media time for byte $position")
        }
        if (waiting != null) {
            val held = waiting.heldChunk
            val sameSegment = if (held != null) held.startRange == position else waiting.cueMs == ms
            if (sameSegment) {
                // the stream that read the header was sent exactly this segment: carry on with it
                waiting.stopsAtGap = false
                waiting.finished = false
                return waiting
            }
            waiting.cancel()
        }
        return startStream(ms, stopsAtGap = false)
    }

    private fun startStream(
        startMs: Long,
        stopsAtGap: Boolean,
    ): Stream {
        val queue = LinkedBlockingQueue<Any>(settings.maxQueuedChunks.coerceAtLeast(2))
        val s = Stream(startMs, queue, stopsAtGap)
        // The server must never think the viewer is behind the point the stream was started at.
        val streamClock: () -> Long = { maxOf(clock.positionMs, startMs) }
        s.job =
            scope.launch(Dispatchers.IO) {
                try {
                    source.chunks(startMs, streamClock).collect { chunk ->
                        runInterruptible { queue.put(Item.Data(chunk)) }
                    }
                    runInterruptible { queue.put(Item.Done) }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // after the chunks that were already read, so the player can still play what it has
                    runInterruptible { queue.put(Item.Failure(t)) }
                }
            }
        s.job?.invokeOnCompletion { cause ->
            // the factory scope was cancelled while the reader was waiting: wake the reader
            if (cause != null && !s.finished) {
                queue.clear()
                queue.offer(Item.Failure(IOException("SABR stream closed", cause)))
            }
        }
        return s
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        val s = stream ?: throw IOException("SABR data source is not open")
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        var data = current
        while (data == null || currentOffset >= data.size) {
            if (s.finished) return C.RESULT_END_OF_INPUT
            val chunk = nextChunk(s) ?: return C.RESULT_END_OF_INPUT
            val start = chunk.startRange
            val end = chunk.endRangeExclusive
            when {
                end <= filePosition -> continue // already delivered, or before the seek position (the repeated init segment)
                start <= filePosition -> {
                    data = chunk.data
                    currentOffset = (filePosition - start).toInt()
                }
                s.stopsAtGap && filePosition > 0L -> {
                    // header read for a later start: the next segment is far behind the init segment, stop here
                    s.heldChunk = chunk
                    s.finished = true
                    return C.RESULT_END_OF_INPUT
                }
                else -> throw IOException("SABR gap: expected byte $filePosition, the next chunk starts at $start")
            }
            current = data
        }
        var count = minOf(length, data.size - currentOffset)
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) count = minOf(count.toLong(), bytesRemaining).toInt()
        System.arraycopy(data, currentOffset, buffer, offset, count)
        currentOffset += count
        filePosition += count
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    /** The next chunk of [s], null at the end. Waits (interruptibly) for the producer. */
    private fun nextChunk(s: Stream): SabrChunk? {
        s.heldChunk?.let {
            s.heldChunk = null
            return it
        }
        while (true) {
            val item =
                try {
                    s.queue.poll(250, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("SABR read interrupted")
                } ?: continue
            return when (item) {
                is Item.Data -> item.chunk
                Item.Done -> {
                    s.finished = true
                    null
                }
                is Item.Failure -> {
                    s.finished = true
                    s.failed = true
                    throw IOException("SABR stream failed: ${item.error.message}", item.error)
                }
                else -> null
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            endStream(park = true)
        } finally {
            uri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun endStream(park: Boolean) {
        val s = stream ?: return
        stream = null
        current = null
        if (park && s.stopsAtGap && !s.failed && (s.heldChunk != null || !s.finished)) {
            // the seek that follows the header read will most likely ask for exactly this stream
            s.cueMs = timeMap.segmentStartMsAt(s.startMs)
            parked.getAndSet(s)?.cancel()
            scope.launch {
                delay(settings.parkMs)
                if (parked.compareAndSet(s, null)) s.cancel()
            }
        } else {
            s.cancel()
        }
    }
}

/**
 * Creates the data source of one video player. All its streams live in one scope; [shutdown] ends every stream (the
 * controller calls it when it lets the player go).
 */
class SabrVideoDataSourceFactory(
    private val source: SabrChunkSource,
    private val timeMap: SabrTimeMap,
    private val clock: VideoClock,
    private val settings: SabrDataSourceSettings = SabrDataSourceSettings(),
) : DataSource.Factory {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val parked = AtomicReference<SabrVideoDataSource.Stream?>(null)

    override fun createDataSource(): DataSource = SabrVideoDataSource(source, timeMap, clock, scope, settings, parked)

    fun shutdown() {
        parked.getAndSet(null)?.cancel()
        scope.cancel()
    }
}
