/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Collections

/**
 * Review 2026-11-12, finding 8: after round 4.3.4 the stream resolver only MARKS a stream for range-by-range reading
 * (StreamChunking) instead of cutting it, but the download chain had no RangeChunkDataSource: it sent one unbounded
 * request, which a bounded-range client refuses (and googlevideo throttles). The download chain is driven here exactly as
 * DownloadManager drives it (a CacheWriter over a CacheDataSource) against a local server that answers bounded ranges
 * only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DownloadRangeChunkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val song = ByteArray(2_600_000) { (it * 13 + 5).toByte() }
    private val requests = Collections.synchronizedList(ArrayList<String>())

    /** Like a bounded-range client's address: a request without an end (or without a Range header) is refused. */
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/stream") { ex ->
                val range = ex.requestHeaders.getFirst("Range")
                requests += range ?: "none"
                val m = range?.let { Regex("""bytes=(\d+)-(\d+)""").matchEntire(it) }
                if (m == null) {
                    ex.sendResponseHeaders(403, -1)
                    ex.close()
                    return@createContext
                }
                val from = m.groupValues[1].toInt()
                val to = minOf(m.groupValues[2].toInt(), song.size - 1)
                if (from >= song.size) {
                    ex.responseHeaders.add("Content-Range", "bytes */${song.size}")
                    ex.sendResponseHeaders(416, -1)
                    ex.close()
                    return@createContext
                }
                ex.responseHeaders.add("Content-Range", "bytes $from-$to/${song.size}")
                ex.sendResponseHeaders(206, (to - from + 1).toLong())
                ex.responseBody.use { it.write(song, from, to - from + 1) }
            }
            start()
        }
    private val url = "http://127.0.0.1:${server.address.port}/stream"

    @After
    fun tearDown() = server.stop(0)

    private fun cache() = SimpleCache(tmp.newFolder(), NoOpCacheEvictor(), StandaloneDatabaseProvider(ApplicationProvider.getApplicationContext()))

    private fun download(
        cache: SimpleCache,
        withRangeChunks: Boolean,
    ) {
        val http = OkHttpDataSource.Factory(OkHttpClient())
        val upstream = if (withRangeChunks) downloadUpstreamFactory(http) else http
        val source =
            CacheDataSource
                .Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                .createDataSource()
        // what the resolver gives the download: the address, marked to be read in ranges
        val spec =
            DataSpec
                .Builder()
                .setUri(url)
                .setKey("song1")
                .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                .setCustomData(StreamChunking(1_048_576L))
                .build()
        CacheWriter(source, spec, null, null).cache()
    }

    @Test
    fun `a download of a bounded-range stream is fetched range by range and complete`() {
        val cache = cache()
        try {
            download(cache, withRangeChunks = true)
            assertTrue(cache.isCached("song1", 0, song.size.toLong()))
            assertEquals(song.size.toLong(), cache.getCachedBytes("song1", 0, Long.MAX_VALUE))
            // every request had an end, and 1 MiB pieces were asked for
            assertTrue("requests: $requests", requests.isNotEmpty() && requests.all { it.matches(Regex("""bytes=\d+-\d+""")) })
            assertEquals(listOf("bytes=0-1048575", "bytes=1048576-2097151", "bytes=2097152-2599999"), requests.toList())
            // the bytes are the song's
            val out = ByteArray(song.size)
            val reader = CacheDataSource.Factory().setCache(cache).setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE).createDataSource()
            reader.open(DataSpec.Builder().setUri(url).setKey("song1").build())
            var at = 0
            while (at < out.size) {
                val n = reader.read(out, at, out.size - at)
                if (n < 0) break
                at += n
            }
            reader.close()
            assertArrayEquals(song, out)
        } finally {
            cache.release()
        }
    }

    @Test
    fun `without the range source (the 4_3_4 download chain) the same download is refused`() {
        val cache = cache()
        try {
            download(cache, withRangeChunks = false)
            fail("an unbounded request should have been refused")
        } catch (_: IOException) {
            assertEquals("none", requests.first())
        } finally {
            cache.release()
        }
    }

    @Test
    fun `the whole-file range parameter is only added when the stream is not read in ranges`() {
        assertEquals("https://x/v?a=1&range=0-99", downloadStreamUrl("https://x/v?a=1", 100L, chunked = false))
        assertEquals("https://x/v?a=1", downloadStreamUrl("https://x/v?a=1", 100L, chunked = true))
        assertEquals("https://x/v?a=1", downloadStreamUrl("https://x/v?a=1", null, chunked = false))
        assertEquals("https://x/v?a=1&range=0-5", downloadStreamUrl("https://x/v?a=1&range=0-5", 100L, chunked = false))
        // and every googlevideo address is read in ranges, the same rule as playback
        assertTrue(CachedStreamUrl("https://rr1.googlevideo.com/videoplayback?x=1", emptyMap(), "WEB").chunkBytesOrNull() != null)
    }
}
