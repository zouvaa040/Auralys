package com.metrolist.music.video

import android.net.Uri
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
import com.metrolist.innertubex.sabr.SabrChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The data source against a fake SABR source (no network) and a synthetic WebM laid out like YouTube's: open at 0, the
 * restart at the time that belongs to a byte, the hint that saves a request, bounded buffering, cancellation, and the
 * whole chain with MatroskaExtractor reading through it (including a seek).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SabrVideoDataSourceTest {
    private val webm = SyntheticWebm()
    private val uri: Uri = Uri.parse("sabr-video://test")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** SABR as far as the data source can tell: the init chunk, then the segments from the start time on. */
    private inner class FakeSabr(
        private val file: SyntheticWebm = webm,
        private val perChunkDelayMs: Long = 0,
        private val failAfterChunks: Int = -1,
        private val skipSegment: Int = -1,
        private val hangAfterInit: Boolean = false,
    ) : SabrChunkSource {
        val starts = CopyOnWriteArrayList<Long>()
        val firstClock = CopyOnWriteArrayList<Long>()
        val emitted = AtomicInteger()
        val cancelled = AtomicInteger()
        val active = AtomicInteger()

        override fun chunks(
            startMs: Long,
            clock: () -> Long,
        ): Flow<SabrChunk> =
            flow {
                starts += startMs
                firstClock += clock()
                active.incrementAndGet()
                try {
                    var sent = 0
                    for (chunk in file.chunksFrom(startMs)) {
                        if (chunk.sequenceNumber == skipSegment) continue
                        if (failAfterChunks >= 0 && sent >= failAfterChunks) throw IOException("network down")
                        if (perChunkDelayMs > 0) delay(perChunkDelayMs)
                        emit(chunk)
                        emitted.incrementAndGet()
                        sent++
                        if (hangAfterInit && chunk.isInitialization) awaitCancellation()
                    }
                } finally {
                    active.decrementAndGet()
                    if (!currentCoroutineContext().isActive) cancelled.incrementAndGet()
                }
            }
    }

    private fun source(
        fake: SabrChunkSource,
        clock: VideoClock = VideoClock(),
        settings: SabrDataSourceSettings = SabrDataSourceSettings(),
        timeMap: SabrTimeMap = SabrTimeMap(webm.durationMs),
    ): Triple<SabrVideoDataSource, SabrTimeMap, VideoClock> =
        Triple(SabrVideoDataSource(fake, timeMap, clock, scope, settings), timeMap, clock)

    private fun SabrVideoDataSource.readAll(max: Int = Int.MAX_VALUE): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < max) {
            val n = read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** The SeekMap of the synthetic file, as MatroskaExtractor builds it. */
    private fun seekMapOf(): SeekMap {
        val ds = SabrVideoDataSource(FakeSabr(), SabrTimeMap(webm.durationMs), VideoClock(), scope)
        val extractor = androidx.media3.extractor.mkv.MatroskaExtractor(androidx.media3.extractor.text.SubtitleParser.Factory.UNSUPPORTED)
        val out = Recorder()
        extractor.init(out)
        ds.open(DataSpec(uri))
        val input = DefaultExtractorInput(ds, 0, C.LENGTH_UNSET.toLong())
        val holder = PositionHolder()
        var guard = 0
        while (out.seekMap == null && guard++ < 10_000) {
            if (extractor.read(input, holder) == Extractor.RESULT_END_OF_INPUT) break
        }
        ds.close()
        return requireNotNull(out.seekMap)
    }

    // ------------------------------------------------------------------------------------------------ sequential reading

    @Test
    fun `reading from 0 gives the progressive file byte for byte`() {
        val fake = FakeSabr()
        val (ds, _, _) = source(fake)
        ds.open(DataSpec(uri))
        val bytes = ds.readAll()
        ds.close()
        assertArrayEquals(webm.bytes, bytes)
        assertEquals(listOf(0L), fake.starts.toList())
    }

    @Test
    fun `the length is reported as unknown and the uri is kept while open`() {
        val (ds, _, _) = source(FakeSabr())
        assertEquals(C.LENGTH_UNSET.toLong(), ds.open(DataSpec(uri)))
        assertEquals(uri, ds.uri)
        ds.close()
        assertEquals(null, ds.uri)
    }

    @Test
    fun `a data spec with a length is honoured`() {
        val (ds, _, _) = source(FakeSabr())
        ds.open(DataSpec.Builder().setUri(uri).setPosition(0).setLength(1000).build())
        assertEquals(1000, ds.readAll().size)
        ds.close()
    }

    @Test
    fun `read with a small buffer walks through chunk borders`() {
        val (ds, _, _) = source(FakeSabr())
        ds.open(DataSpec(uri))
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(7)
        while (true) {
            val n = ds.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        ds.close()
        assertArrayEquals(webm.bytes, out.toByteArray())
    }

    // ------------------------------------------------------------------------------------------------ the extractor and the SeekMap

    @Test
    fun `MatroskaExtractor reads the stream through the data source, tap and time map included`() {
        val fake = FakeSabr()
        val timeMap = SabrTimeMap(webm.durationMs)
        val (ds, _, _) = source(fake, timeMap = timeMap)
        val extractor = sabrExtractorsFactory(VideoContainer.WEBM, timeMap).createExtractors()[0]
        val out = Recorder()
        extractor.init(out)
        ds.open(DataSpec(uri))
        val input = DefaultExtractorInput(ds, 0, C.LENGTH_UNSET.toLong())
        val holder = PositionHolder()
        var result: Int
        do {
            result = extractor.read(input, holder)
        } while (result == Extractor.RESULT_CONTINUE)
        ds.close()
        assertEquals(Extractor.RESULT_END_OF_INPUT, result)
        val video = out.tracks.values.single()
        assertEquals("video/x-vnd.on2.vp9", video.format?.sampleMimeType)
        assertEquals(640, video.format?.width)
        assertEquals(webm.clusterCount * webm.framesPerCluster, video.samples)
        assertEquals(webm.clusterCount, video.keyframes)
        val seekMap = out.seekMap
        assertNotNull(seekMap)
        assertTrue(seekMap!!.isSeekable)
        assertEquals(webm.durationMs * 1000L, seekMap.durationUs)
        // the tap handed the same SeekMap to the time map
        assertTrue(timeMap.seekMap === seekMap)
    }

    @Test
    fun `byte to time maps every cluster start to its own time, a byte inside a cluster to the cluster before`() {
        val timeMap = SabrTimeMap(webm.durationMs)
        timeMap.seekMap = seekMapOf()
        for (s in webm.segments) {
            assertEquals("cluster ${s.index} start", s.startMs, timeMap.startMsForByte(s.startRange))
            assertEquals("inside cluster ${s.index}", s.startMs, timeMap.startMsForByte(s.startRange + 17))
            assertEquals("end of cluster ${s.index}", s.startMs, timeMap.startMsForByte(s.endRange - 1))
        }
        // inside the header (before the first cue): from the start
        assertEquals(0L, timeMap.startMsForByte(10))
        // past the end
        assertEquals(webm.segments.last().startMs, timeMap.startMsForByte(webm.bytes.size + 1000L))
    }

    @Test
    fun `without a seek map there is no time for a byte`() {
        assertEquals(null, SabrTimeMap(1000).startMsForByte(1234))
    }

    @Test
    fun `the segment that contains a time`() {
        val timeMap = SabrTimeMap(webm.durationMs)
        timeMap.seekMap = seekMapOf()
        assertEquals(0L, timeMap.segmentStartMsAt(0))
        assertEquals(0L, timeMap.segmentStartMsAt(3_999))
        assertEquals(4_000L, timeMap.segmentStartMsAt(4_000))
        assertEquals(36_000L, timeMap.segmentStartMsAt(37_500))
    }

    // ------------------------------------------------------------------------------------------------ seeking

    @Test
    fun `open at a cluster byte restarts SABR at the time of that cluster and serves the file from there`() {
        val timeMap = SabrTimeMap(webm.durationMs)
        timeMap.seekMap = seekMapOf()
        val fake = FakeSabr()
        val (ds, _, _) = source(fake, timeMap = timeMap)
        val target = webm.segments[7]
        ds.open(DataSpec.Builder().setUri(uri).setPosition(target.startRange).build())
        val bytes = ds.readAll()
        ds.close()
        assertArrayEquals(webm.bytes.copyOfRange(target.startRange.toInt(), webm.bytes.size), bytes)
        assertEquals(listOf(target.startMs), fake.starts.toList())
    }

    @Test
    fun `open in the middle of a cluster cuts the chunk and starts at that cluster`() {
        val timeMap = SabrTimeMap(webm.durationMs)
        timeMap.seekMap = seekMapOf()
        val fake = FakeSabr()
        val (ds, _, _) = source(fake, timeMap = timeMap)
        val target = webm.segments[3]
        val position = target.startRange + 123
        ds.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
        val bytes = ds.readAll()
        ds.close()
        assertArrayEquals(webm.bytes.copyOfRange(position.toInt(), webm.bytes.size), bytes)
        assertEquals(listOf(target.startMs), fake.starts.toList())
    }

    @Test
    fun `open inside the header starts SABR at 0 and cuts the init chunk`() {
        val timeMap = SabrTimeMap(webm.durationMs)
        timeMap.seekMap = seekMapOf()
        val fake = FakeSabr()
        val (ds, _, _) = source(fake, timeMap = timeMap)
        ds.open(DataSpec.Builder().setUri(uri).setPosition(40).build())
        assertArrayEquals(webm.bytes.copyOfRange(40, webm.bytes.size), ds.readAll())
        ds.close()
        assertEquals(listOf(0L), fake.starts.toList())
    }

    @Test
    fun `open at a byte before any seek map exists is an error, not a guess`() {
        val (ds, _, _) = source(FakeSabr())
        try {
            ds.open(DataSpec.Builder().setUri(uri).setPosition(5_000).build())
            fail("expected an IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("no media time"))
        }
    }

    @Test
    fun `a seek closes the old stream and starts a new one`() {
        val timeMap = SabrTimeMap(webm.durationMs)
        timeMap.seekMap = seekMapOf()
        val fake = FakeSabr(perChunkDelayMs = 5)
        val (ds, _, _) = source(fake, timeMap = timeMap)
        ds.open(DataSpec(uri))
        val buf = ByteArray(100)
        assertTrue(ds.read(buf, 0, buf.size) > 0)
        ds.close()
        awaitTrue("the first stream was cancelled") { fake.cancelled.get() >= 1 }
        val target = webm.segments[10]
        ds.open(DataSpec.Builder().setUri(uri).setPosition(target.startRange).build())
        val first = ByteArray(64)
        assertEquals(64, ds.read(first, 0, 64))
        assertArrayEquals(webm.bytes.copyOfRange(target.startRange.toInt(), target.startRange.toInt() + 64), first)
        ds.close()
        assertEquals(listOf(0L, target.startMs), fake.starts.toList())
    }

    @Test
    fun `the whole chain - MatroskaExtractor reads, the player seeks through the SeekMap, the frames after the seek come in`() {
        val fake = FakeSabr()
        val timeMap = SabrTimeMap(webm.durationMs)
        val (ds, _, _) = source(fake, timeMap = timeMap)
        val extractor = sabrExtractorsFactory(VideoContainer.WEBM, timeMap).createExtractors()[0]
        val out = Recorder()
        extractor.init(out)
        ds.open(DataSpec(uri))
        var input = DefaultExtractorInput(ds, 0, C.LENGTH_UNSET.toLong())
        val holder = PositionHolder()
        // read until the player would know the SeekMap and the first frames
        while (out.seekMap == null || out.totalSamples() < 5) {
            check(extractor.read(input, holder) != Extractor.RESULT_END_OF_INPUT)
        }
        // seek to 30 s like ExoPlayer does: the SeekMap names the byte, the data source is reopened there
        val seekTimeUs = 30_000_000L
        val point = out.seekMap!!.getSeekPoints(seekTimeUs).first
        ds.close()
        ds.open(DataSpec.Builder().setUri(uri).setPosition(point.position).build())
        extractor.seek(point.position, seekTimeUs)
        input = DefaultExtractorInput(ds, point.position, C.LENGTH_UNSET.toLong())
        out.reset()
        while (extractor.read(input, holder) == Extractor.RESULT_CONTINUE) {
            // read to the end
        }
        ds.close()
        val video = out.tracks.values.single()
        assertEquals(28_000_000L, video.firstUs) // the keyframe cluster at or before 30 s (7 x 4 s)
        assertEquals((webm.clusterCount - 7) * webm.framesPerCluster, video.samples)
        assertEquals(listOf(0L, 28_000L), fake.starts.toList())
    }

    // ------------------------------------------------------------------------------------------------ errors and cancellation

    @Test
    fun `a gap in the file is an error the player can retry`() {
        val (ds, _, _) = source(FakeSabr(skipSegment = 2))
        ds.open(DataSpec(uri))
        try {
            ds.readAll()
            fail("expected an IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message!!, expected.message!!.contains("gap"))
        }
        ds.close()
    }

    @Test
    fun `a stream that fails after some chunks lets the player read those chunks first`() {
        val (ds, _, _) = source(FakeSabr(failAfterChunks = 3))
        ds.open(DataSpec(uri))
        val expected = webm.bytes.copyOfRange(0, webm.segments[1].endRange.toInt()) // init + two clusters
        val got = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1024)
        var failed: IOException? = null
        try {
            while (true) {
                val n = ds.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                got.write(buf, 0, n)
            }
        } catch (e: IOException) {
            failed = e
        }
        ds.close()
        assertArrayEquals(expected, got.toByteArray())
        assertNotNull(failed)
        assertTrue(failed!!.message!!.contains("network down"))
    }

    @Test
    fun `an interrupt ends a read that waits for the server, and close cancels the stream`() {
        val fake = FakeSabr(hangAfterInit = true)
        val (ds, _, _) = source(fake)
        ds.open(DataSpec(uri))
        val error = AtomicReference<Throwable?>()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val reader =
            Thread {
                try {
                    val buf = ByteArray(1024)
                    started.countDown()
                    while (true) {
                        if (ds.read(buf, 0, buf.size) == C.RESULT_END_OF_INPUT) break
                    }
                } catch (t: Throwable) {
                    error.set(t)
                } finally {
                    ds.close()
                    finished.countDown()
                }
            }
        reader.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        Thread.sleep(300) // it has the init chunk and now waits for a segment that never comes
        reader.interrupt()
        assertTrue("the read ended", finished.await(3, TimeUnit.SECONDS))
        assertTrue(error.get().toString(), error.get() is InterruptedIOException)
        awaitTrue("the stream was cancelled") { fake.cancelled.get() == 1 }
    }

    @Test
    fun `ending the factory scope wakes a reader and stops the streams`() {
        val fake = FakeSabr(hangAfterInit = true)
        val factory = SabrVideoDataSourceFactory(fake, SabrTimeMap(webm.durationMs), VideoClock())
        val ds = factory.createDataSource()
        ds.open(DataSpec(uri))
        val error = AtomicReference<Throwable?>()
        val finished = CountDownLatch(1)
        Thread {
            try {
                val buf = ByteArray(1024)
                while (ds.read(buf, 0, buf.size) != C.RESULT_END_OF_INPUT) {
                    // keep reading
                }
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                finished.countDown()
            }
        }.start()
        Thread.sleep(300)
        factory.shutdown()
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertTrue(error.get() is IOException)
        awaitTrue("stream cancelled") { fake.cancelled.get() == 1 }
        ds.close()
    }

    // ------------------------------------------------------------------------------------------------ buffering and the clock

    @Test
    fun `the queue is bounded, the stream waits until the player reads`() {
        val big = SyntheticWebm(clusterCount = 100)
        val fake = FakeSabr(file = big)
        val settings = SabrDataSourceSettings(maxQueuedChunks = 4)
        val ds = SabrVideoDataSource(fake, SabrTimeMap(big.durationMs), VideoClock(), scope, settings)
        ds.open(DataSpec(uri))
        Thread.sleep(400)
        // the queue holds 4 and one more is in the producer's hand
        assertTrue("emitted ${fake.emitted.get()}", fake.emitted.get() in 4..6)
        val all = ds.readAll()
        ds.close()
        assertArrayEquals(big.bytes, all)
        assertEquals(101, fake.emitted.get())
    }

    @Test
    fun `the clock given to SABR is the player position but never behind the point the stream started at`() {
        val timeMap = SabrTimeMap(webm.durationMs)
        timeMap.seekMap = seekMapOf()
        val clock = VideoClock()
        val clocks = CopyOnWriteArrayList<() -> Long>()
        val spy =
            SabrChunkSource { startMs, c ->
                clocks += c
                FakeSabr().chunks(startMs, c)
            }
        val ds = SabrVideoDataSource(spy, timeMap, clock, scope)
        val target = webm.segments[6] // 24 s
        clock.positionMs = 5_000 // stale, from before the seek
        ds.open(DataSpec.Builder().setUri(uri).setPosition(target.startRange).build())
        awaitTrue("the stream was started") { clocks.size == 1 }
        assertEquals(24_000L, clocks.single()())
        clock.positionMs = 30_000
        assertEquals(30_000L, clocks.single()())
        ds.close()
    }

    // ------------------------------------------------------------------------------------------------ the start hint

    @Test
    fun `a start hint starts SABR at the hint, serves the init chunk and ends before the gap`() {
        val big = SyntheticWebm(clusterCount = 40)
        val fake = FakeSabr(file = big)
        val settings = SabrDataSourceSettings(startHintMs = { 90_000L })
        val ds = SabrVideoDataSource(fake, SabrTimeMap(big.durationMs), VideoClock(), scope, settings)
        ds.open(DataSpec(uri))
        val bytes = ds.readAll()
        ds.close()
        assertArrayEquals(big.bytes.copyOfRange(0, big.initEnd), bytes)
        assertEquals(listOf(90_000L), fake.starts.toList())
    }

    @Test
    fun `a hint below the minimum is ignored`() {
        val fake = FakeSabr()
        val settings = SabrDataSourceSettings(startHintMs = { 5_000L })
        val ds = SabrVideoDataSource(fake, SabrTimeMap(webm.durationMs), VideoClock(), scope, settings)
        ds.open(DataSpec(uri))
        assertArrayEquals(webm.bytes, ds.readAll())
        ds.close()
        assertEquals(listOf(0L), fake.starts.toList())
    }

    @Test
    fun `the seek after the header read adopts the parked stream, one SABR request instead of two`() {
        val big = SyntheticWebm(clusterCount = 40)
        val fake = FakeSabr(file = big)
        val timeMap = SabrTimeMap(big.durationMs)
        val settings = SabrDataSourceSettings(startHintMs = { 90_500L })
        val ds = SabrVideoDataSource(fake, timeMap, VideoClock(), scope, settings)
        val extractor = sabrExtractorsFactory(VideoContainer.WEBM, timeMap).createExtractors()[0]
        val out = Recorder()
        extractor.init(out)
        ds.open(DataSpec(uri))
        var input = DefaultExtractorInput(ds, 0, C.LENGTH_UNSET.toLong())
        val holder = PositionHolder()
        // the header: the extractor ends at the gap
        var result: Int
        do {
            result = extractor.read(input, holder)
        } while (result == Extractor.RESULT_CONTINUE)
        assertEquals(Extractor.RESULT_END_OF_INPUT, result)
        assertNotNull(out.seekMap)
        ds.close()
        // ExoPlayer is prepared and seeks to its start position
        val seekTimeUs = 90_500_000L
        val point = out.seekMap!!.getSeekPoints(seekTimeUs).first
        ds.open(DataSpec.Builder().setUri(uri).setPosition(point.position).build())
        extractor.seek(point.position, seekTimeUs)
        input = DefaultExtractorInput(ds, point.position, C.LENGTH_UNSET.toLong())
        out.reset()
        while (extractor.read(input, holder) == Extractor.RESULT_CONTINUE) {
            // to the end
        }
        ds.close()
        val video = out.tracks.values.single()
        assertEquals(88_000_000L, video.firstUs)
        assertEquals((40 - 22) * big.framesPerCluster, video.samples)
        assertEquals("one SABR request", listOf(90_500L), fake.starts.toList())
    }

    @Test
    fun `a seek to another place than the hint starts a new stream and drops the parked one`() {
        val big = SyntheticWebm(clusterCount = 40)
        val fake = FakeSabr(file = big)
        val timeMap = SabrTimeMap(big.durationMs)
        val settings = SabrDataSourceSettings(startHintMs = { 90_500L })
        val ds = SabrVideoDataSource(fake, timeMap, VideoClock(), scope, settings)
        val extractor = sabrExtractorsFactory(VideoContainer.WEBM, timeMap).createExtractors()[0]
        val out = Recorder()
        extractor.init(out)
        ds.open(DataSpec(uri))
        val input = DefaultExtractorInput(ds, 0, C.LENGTH_UNSET.toLong())
        val holder = PositionHolder()
        while (extractor.read(input, holder) == Extractor.RESULT_CONTINUE) {
            // header
        }
        ds.close()
        val point = out.seekMap!!.getSeekPoints(10_000_000L).first
        ds.open(DataSpec.Builder().setUri(uri).setPosition(point.position).build())
        val buf = ByteArray(32)
        assertEquals(32, ds.read(buf, 0, 32))
        assertArrayEquals(big.bytes.copyOfRange(point.position.toInt(), point.position.toInt() + 32), buf)
        ds.close()
        assertEquals(listOf(90_500L, 8_000L), fake.starts.toList())
        awaitTrue("the parked stream was cancelled") { fake.cancelled.get() >= 2 }
    }

    @Test
    fun `a parked stream that nobody asks for is cancelled after the parking time`() {
        val big = SyntheticWebm(clusterCount = 40)
        val fake = FakeSabr(file = big)
        val settings = SabrDataSourceSettings(startHintMs = { 90_500L }, parkMs = 100)
        val ds = SabrVideoDataSource(fake, SabrTimeMap(big.durationMs), VideoClock(), scope, settings)
        ds.open(DataSpec(uri))
        ds.readAll()
        ds.close()
        assertEquals(0, fake.cancelled.get())
        awaitTrue("the parked stream was cancelled") { fake.cancelled.get() == 1 }
    }

    @Test
    fun `a hint stream that is opened again at 0 drops the parked one`() {
        val big = SyntheticWebm(clusterCount = 40)
        val fake = FakeSabr(file = big)
        val settings = SabrDataSourceSettings(startHintMs = { 90_500L })
        val ds = SabrVideoDataSource(fake, SabrTimeMap(big.durationMs), VideoClock(), scope, settings)
        ds.open(DataSpec(uri))
        ds.readAll()
        ds.close()
        ds.open(DataSpec(uri))
        ds.readAll()
        ds.close()
        assertEquals(listOf(90_500L, 90_500L), fake.starts.toList())
        awaitTrue("the first parked stream was cancelled") { fake.cancelled.get() >= 1 }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun awaitTrue(
        what: String,
        timeoutMs: Long = 3_000,
        check: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return
            Thread.sleep(10)
        }
        assertTrue(what, check())
    }

    /** Counts what the extractor outputs. */
    private class Recorder : ExtractorOutput {
        class Track(
            val type: Int,
        ) : TrackOutput {
            var format: Format? = null
            var samples = 0
            var keyframes = 0
            var firstUs = Long.MIN_VALUE

            override fun format(format: Format) {
                this.format = format
            }

            override fun sampleData(
                input: DataReader,
                length: Int,
                allowEndOfInput: Boolean,
                sampleDataPart: Int,
            ): Int {
                val buf = ByteArray(length)
                return input.read(buf, 0, length)
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
                if (flags and C.BUFFER_FLAG_KEY_FRAME != 0) keyframes++
                if (firstUs == Long.MIN_VALUE) firstUs = timeUs
            }
        }

        val tracks = mutableMapOf<Int, Track>()
        var seekMap: SeekMap? = null

        override fun track(
            id: Int,
            type: Int,
        ): TrackOutput = tracks.getOrPut(id) { Track(type) }

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }

        fun totalSamples() = tracks.values.sumOf { it.samples }

        fun reset() {
            for (t in tracks.values) {
                t.samples = 0
                t.keyframes = 0
                t.firstUs = Long.MIN_VALUE
            }
        }
    }
}
