/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * Report 3 ("lagu belum selesai sudah di-skip"): a stream of a bounded-range client used to be cut to one chunk per open,
 * and a chunk that ended between two elements was taken as the end of the song. [RangeChunkDataSource] must give the WHOLE
 * stream as one read, end only at its real end, and fail (not end) when the server drops a range early.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RangeChunkDataSourceTest {
    /** A googlevideo-like server: answers ranges, optionally with a Content-Range header, optionally cuts one range short. */
    private class FakeRangeServer(
        private val data: ByteArray,
        private val sendContentRange: Boolean = true,
        private val cutRangeAt: Long? = null,
    ) : DataSource {
        val opened = ArrayList<Pair<Long, Long>>()
        private var position = 0L
        private var end = 0L
        private var headers: Map<String, List<String>> = emptyMap()
        private var uri: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            opened += dataSpec.position to dataSpec.length
            if (dataSpec.position >= data.size) {
                throw HttpDataSource.InvalidResponseCodeException(416, "Range Not Satisfiable", null, emptyMap(), dataSpec, ByteArray(0))
            }
            position = dataSpec.position
            val wantedEnd = if (dataSpec.length == C.LENGTH_UNSET.toLong()) data.size.toLong() else minOf(data.size.toLong(), position + dataSpec.length)
            end = cutRangeAt?.takeIf { it in (position + 1) until wantedEnd } ?: wantedEnd
            headers =
                if (sendContentRange) mapOf("Content-Range" to listOf("bytes $position-${wantedEnd - 1}/${data.size}")) else emptyMap()
            return wantedEnd - position
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (position >= end) return C.RESULT_END_OF_INPUT
            val n = minOf(length.toLong(), end - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            position += n
            return n
        }

        override fun getUri(): Uri? = uri

        override fun getResponseHeaders(): Map<String, List<String>> = headers

        override fun close() = Unit
    }

    private val song = ByteArray(1_300_000) { (it * 31 + 7).toByte() }

    private fun spec(
        position: Long = 0,
        chunk: Long? = 512L * 1024,
        length: Long = C.LENGTH_UNSET.toLong(),
    ) = DataSpec
        .Builder()
        .setUri("https://rr1---sn.googlevideo.com/videoplayback?c=ANDROID_VR")
        .setPosition(position)
        .setLength(length)
        .setCustomData(chunk?.let(::StreamChunking))
        .build()

    private fun readAll(
        source: DataSource,
        readSize: Int = 8192,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(readSize)
        while (true) {
            val n = source.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun `a stream without chunking passes through in one request`() {
        val server = FakeRangeServer(song)
        val source = RangeChunkDataSource(server)
        assertEquals(song.size.toLong(), source.open(spec(chunk = null)))
        assertArrayEquals(song, readAll(source))
        source.close()
        assertEquals(listOf(0L to C.LENGTH_UNSET.toLong()), server.opened)
    }

    @Test
    fun `the whole song comes through across every chunk boundary and its length is known at once`() {
        val server = FakeRangeServer(song)
        val source = RangeChunkDataSource(server)
        assertEquals(song.size.toLong(), source.open(spec()))
        assertArrayEquals(song, readAll(source))
        source.close()
        // 1 300 000 bytes in 512 KiB ranges: 3 requests, every one bounded
        assertEquals(3, source.requestCount)
        assertTrue(server.opened.all { it.second in 1..(512L * 1024) })
    }

    @Test
    fun `without a Content-Range header the stream still ends at its real end`() {
        val server = FakeRangeServer(song, sendContentRange = false)
        val source = RangeChunkDataSource(server)
        assertEquals(C.LENGTH_UNSET.toLong(), source.open(spec()))
        assertArrayEquals(song, readAll(source, readSize = 100_000))
        source.close()
    }

    @Test
    fun `a stream whose size is a whole number of chunks ends on the 416 of the next range`() {
        val exact = ByteArray(2 * 512 * 1024) { it.toByte() }
        val server = FakeRangeServer(exact, sendContentRange = false)
        val source = RangeChunkDataSource(server)
        source.open(spec())
        assertArrayEquals(exact, readAll(source))
        source.close()
        assertEquals(3, source.requestCount)
    }

    @Test
    fun `a read that starts in the middle (a seek) goes on to the end`() {
        val server = FakeRangeServer(song)
        val source = RangeChunkDataSource(server)
        val from = 700_000L
        assertEquals(song.size - from, source.open(spec(position = from)))
        assertArrayEquals(song.copyOfRange(from.toInt(), song.size), readAll(source))
        source.close()
    }

    @Test
    fun `a bounded request of the cache is honoured exactly`() {
        val server = FakeRangeServer(song)
        val source = RangeChunkDataSource(server)
        assertEquals(600_000L, source.open(spec(position = 100_000, length = 600_000)))
        assertArrayEquals(song.copyOfRange(100_000, 700_000), readAll(source))
        source.close()
    }

    @Test
    fun `a range the server drops early is an error the loader retries, never a silent end`() {
        val server = FakeRangeServer(song, cutRangeAt = 900_000)
        val source = RangeChunkDataSource(server)
        source.open(spec())
        try {
            readAll(source)
            fail("a short range must not end the song")
        } catch (e: RangeChunkDataSource.RangeEndedEarlyException) {
            assertTrue(e.message!!.contains("900000"))
        }
        source.close()
    }

    @Test
    fun `content range total is parsed`() {
        assertEquals(4567890L, RangeChunkDataSource.totalLength(mapOf("content-range" to listOf("bytes 0-524287/4567890"))))
        assertNull(RangeChunkDataSource.totalLength(mapOf("Content-Range" to listOf("bytes 0-524287/*"))))
        assertNull(RangeChunkDataSource.totalLength(emptyMap()))
    }

    @Test
    fun `the resolver marks a bounded stream instead of cutting it`() {
        val bounded =
            CachedStreamUrl(
                url = "https://rr1---sn.googlevideo.com/videoplayback?c=IOS",
                requestHeaders = mapOf("User-Agent" to "x"),
                clientName = "IOS",
                requireBoundedRange = true,
                rangeChunkSizeBytes = 1_048_576,
            )
        val resolved = spec(chunk = null).withResolvedStream(bounded)
        assertEquals(C.LENGTH_UNSET.toLong(), resolved.length)
        assertEquals(StreamChunking(1_048_576), resolved.customData)
        assertEquals("x", resolved.httpRequestHeaders["User-Agent"])

        // every googlevideo stream is read in ranges: without an end googlevideo sends it at about 2x playback speed
        val plain = bounded.copy(requireBoundedRange = false, useRangeChunks = false, rangeChunkSizeBytes = 0L)
        assertEquals(StreamChunking(DEFAULT_RANGE_CHUNK_BYTES), spec(chunk = null).withResolvedStream(plain).customData)
        // another host (a proxy, a test server) is read as before
        val other = plain.copy(url = "https://example.org/audio.webm")
        assertNull(spec(chunk = null).withResolvedStream(other).customData)
        assertTrue(isGoogleVideoUrl("https://rr3---sn-a5mekn6s.googlevideo.com/videoplayback?expire=1"))
        assertTrue(!isGoogleVideoUrl("http://rr3---sn-a5mekn6s.googlevideo.com/videoplayback"))
        assertTrue(!isGoogleVideoUrl("https://googlevideo.com.evil.example/videoplayback"))
    }
}
