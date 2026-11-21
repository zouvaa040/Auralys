/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.ParserException
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
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
import org.junit.Assert.assertFalse
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

/**
 * Round 4.3.5, report "About You" (a song that would not play right after its download: "Skipping atom with length >
 * 2147483647 (unsupported)"). Drives the download chain the way DownloadManager does (a CacheWriter over a CacheDataSource
 * that writes the download cache, retried after an IOException) against a local server with TWO files of one song (like
 * opus 251 and AAC 140, or the DRC variant of an itag).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StreamFileIntegrityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fileA = ByteArray(2_600_000) { (it * 13 + 5).toByte() }
    private val fileB = ByteArray(2_400_000) { (it * 7 + 101).toByte() }

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            for ((path, data) in listOf("/a" to fileA, "/b" to fileB)) {
                createContext(path) { ex ->
                    val range = ex.requestHeaders.getFirst("Range").orEmpty().removePrefix("bytes=")
                    val from = range.substringBefore('-').toLongOrNull() ?: 0L
                    val to = range.substringAfter('-', "").toLongOrNull()?.coerceAtMost(data.size - 1L) ?: (data.size - 1L)
                    if (from >= data.size) {
                        ex.responseHeaders.add("Content-Range", "bytes */${data.size}")
                        ex.sendResponseHeaders(416, -1)
                        ex.close()
                        return@createContext
                    }
                    ex.responseHeaders.add("Content-Type", "audio/webm")
                    ex.responseHeaders.add("Content-Range", "bytes $from-$to/${data.size}")
                    ex.sendResponseHeaders(206, to - from + 1)
                    ex.responseBody.use { it.write(data, from.toInt(), (to - from + 1).toInt()) }
                }
            }
            start()
        }

    private fun url(file: String) = "http://127.0.0.1:${server.address.port}/$file?itag=${if (file == "a") 140 else 251}&lmt=17${file.length}"

    @After
    fun stop() = server.stop(0)

    private fun cache(name: String) =
        SimpleCache(tmp.newFolder(name), NoOpCacheEvictor(), StandaloneDatabaseProvider(ApplicationProvider.getApplicationContext()))

    private fun http() = RangeChunkDataSource.Factory(OkHttpDataSource.Factory(OkHttpClient()))

    private fun spec() =
        DataSpec
            .Builder()
            .setUri(Uri.parse("song1"))
            .setKey("song1")
            .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
            .build()

    /** What DownloadManager does: CacheWriter over a CacheDataSource writing [download], retried after an IOException. */
    private fun downloadWithRetries(
        download: Cache,
        upstream: androidx.media3.datasource.DataSource.Factory,
    ): Int {
        var errors = 0
        while (true) {
            try {
                CacheWriter(CacheDataSource.Factory().setCache(download).setUpstreamDataSourceFactory(upstream).createDataSource(), spec(), null, null).cache()
                return errors
            } catch (e: IOException) {
                errors++
                if (errors > 5) throw e
            }
        }
    }

    private fun readAll(
        cache: Cache,
        length: Int,
    ): ByteArray {
        val out = ByteArray(length)
        val reader = CacheDataSource.Factory().setCache(cache).setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE).createDataSource()
        reader.open(spec())
        var at = 0
        while (at < out.size) {
            val n = reader.read(out, at, out.size - at)
            if (n < 0) break
            at += n
        }
        reader.close()
        return out
    }

    /** The player had cached the first MiB of file A (e.g. AAC on mobile data) before the user tapped Download. */
    private fun playerCacheWithHeadOfA(): Cache {
        val player = cache("player")
        CacheWriter(
            CacheDataSource.Factory().setCache(player).setUpstreamDataSourceFactory(http()).createDataSource(),
            DataSpec.Builder().setUri(url("a")).setKey("song1").setPosition(0).setLength(1_048_576).setCustomData(StreamChunking(1_048_576)).build(),
            null,
            null,
        ).cache()
        return player
    }

    @Test
    fun `the 4_3_3 download chain glues the player cache to a newly resolved file`() {
        val player = playerCacheWithHeadOfA()
        val download = cache("download")
        // DownloadUtil before 4.3.5: player cache first, unresolved spec while it has the byte; else a NEW address (file B)
        val old =
            ResolvingDataSource.Factory(CacheDataSource.Factory().setCache(player).setCacheWriteDataSinkFactory(null).setUpstreamDataSourceFactory(http())) { s ->
                if (player.isCached("song1", s.position, 1)) {
                    s
                } else {
                    s.withResolvedStream(CachedStreamUrl(url("b"), emptyMap(), "VISIONOS"))
                }
            }
        val errors = downloadWithRetries(download, old)
        val saved = readAll(download, fileA.size)
        // the hole after the player's bytes failed once (no address), the retry resolved file B and glued it on
        assertTrue("errors=$errors", errors >= 1)
        assertArrayEquals(fileA.copyOfRange(0, 1_048_576), saved.copyOfRange(0, 1_048_576))
        assertArrayEquals(fileB.copyOfRange(1_048_576, 1_048_576 + 4096), saved.copyOfRange(1_048_576, 1_048_576 + 4096))
        player.release()
        download.release()
    }

    @Test
    fun `a download resumed with another file starts again and keeps one file`() {
        val download = cache("download")
        var address = url("a")
        // first run: file A, cut after 1 MiB (the connection dropped / the app was killed)
        val firstRun =
            ResolvingDataSource.Factory(http()) { s ->
                CacheFormatPin.keepOneFile(download, "song1", CacheFormatPin.signature(-1, address))
                if (s.position >= 1_048_576) throw IOException("connection lost")
                s.withResolvedStream(CachedStreamUrl(address, emptyMap(), "VISIONOS"))
            }
        try {
            CacheWriter(CacheDataSource.Factory().setCache(download).setUpstreamDataSourceFactory(firstRun).createDataSource(), spec().buildUpon().setLength(1_048_576).build(), null, null).cache()
        } catch (_: IOException) {
        }
        assertTrue(download.isCached("song1", 0, 1_048_576))
        // the resumed download resolves another file (Wi-Fi now: opus instead of AAC)
        address = url("b")
        val resumed =
            ResolvingDataSource.Factory(http()) { s ->
                CacheFormatPin.keepOneFile(download, "song1", CacheFormatPin.signature(-1, address))
                s.withResolvedStream(CachedStreamUrl(address, emptyMap(), "VISIONOS"))
            }
        val errors = downloadWithRetries(download, resumed)
        assertEquals("one restart from byte 0", 1, errors)
        assertTrue(CacheFormatPin.isComplete(download, "song1"))
        assertEquals(fileB.size.toLong(), CacheFormatPin.storedLength(download, "song1"))
        assertArrayEquals(fileB, readAll(download, fileB.size))
        assertEquals(CacheFormatPin.signature(-1, url("b")), CacheFormatPin.pinned(download, "song1"))
        download.release()
    }

    @Test
    fun `the player never mixes a partial download into a stream`() {
        val download = cache("download")
        // a partial download of file A (failed or still running)
        CacheWriter(
            CacheDataSource.Factory().setCache(download).setUpstreamDataSourceFactory(http()).createDataSource(),
            DataSpec.Builder().setUri(url("a")).setKey("song1").setLength(1_048_576).setCustomData(StreamChunking(1_048_576)).build(),
            null,
            null,
        ).cache()
        assertFalse(CacheFormatPin.isComplete(download, "song1"))
        val layer =
            CacheDataSource
                .Factory()
                .setCache(download)
                .setCacheWriteDataSinkFactory(null)
                .setCacheKeyFactory(CacheFormatPin.downloadLayerKeys)
                .setUpstreamDataSourceFactory { ByteArrayDataSource(fileB) }
                .createDataSource()
        val buf = ByteArray(64)
        // a stream read (not marked): the downloaded bytes are not used
        layer.open(DataSpec.Builder().setUri(Uri.parse("song1")).setKey("song1").setPosition(1000).build())
        layer.read(buf, 0, buf.size)
        layer.close()
        assertArrayEquals(fileB.copyOfRange(1000, 1064), buf)
        // a read the resolver marked as a complete download: the downloaded bytes are used
        layer.open(DataSpec.Builder().setUri(Uri.parse("song1")).setKey("song1").setPosition(1000).setLength(64).setCustomData(CacheFormatPin.FromDownload).build())
        layer.read(buf, 0, buf.size)
        layer.close()
        assertArrayEquals(fileA.copyOfRange(1000, 1064), buf)
        assertEquals("song1", CacheFormatPin.playerLayerKeys.buildCacheKey(DataSpec.Builder().setUri(Uri.parse("x")).setKey(CacheFormatPin.NOT_FROM_DOWNLOAD + "song1").build()))
        download.release()
    }

    @Test
    fun `the file signature tells itags, DRC variants and versions apart`() {
        val base = "https://rr1.googlevideo.com/videoplayback?expire=1&itag=251&lmt=1700000000000001&mime=audio%2Fwebm"
        assertEquals("251|1700000000000001|", CacheFormatPin.signature(140, base))
        assertFalse(CacheFormatPin.signature(-1, base) == CacheFormatPin.signature(-1, "$base&xtags=CggKA2RyYxIBMQ"))
        assertFalse(CacheFormatPin.signature(-1, base) == CacheFormatPin.signature(-1, base.replace("lmt=1700000000000001", "lmt=1700000000000002")))
        assertEquals("140||", CacheFormatPin.signature(140, "https://x/stream"))
        // drop: bytes of another pinned file; bytes from before 4.3.5 only when the database itag differs
        assertTrue(CacheFormatPin.mustDrop(true, "140|1|", "251|2|", null))
        assertFalse(CacheFormatPin.mustDrop(true, "251|2|", "251|2|", 140))
        assertFalse(CacheFormatPin.mustDrop(false, "140|1|", "251|2|", 140))
        assertTrue(CacheFormatPin.mustDrop(true, null, "251|2|", 140))
        assertFalse(CacheFormatPin.mustDrop(true, null, "251|2|", 251))
        assertFalse(CacheFormatPin.mustDrop(true, null, "251|2|", null))
    }

    @Test
    fun `range answers that are not the asked media bytes are refused`() {
        val piece = DataSpec.Builder().setUri("https://rr1.googlevideo.com/v").setPosition(1_048_576).setLength(1_048_576).build()
        RangeChunkDataSource.checkPiece(piece, mapOf("Content-Type" to listOf("audio/webm"), "Content-Range" to listOf("bytes 1048576-2097151/3000000")))
        RangeChunkDataSource.checkPiece(piece, mapOf("content-type" to listOf("application/octet-stream")))
        try {
            RangeChunkDataSource.checkPiece(piece, mapOf("Content-Type" to listOf("text/html; charset=utf-8")))
            fail("a web page is not media")
        } catch (_: HttpDataSource.InvalidContentTypeException) {
        }
        try {
            RangeChunkDataSource.checkPiece(piece, mapOf("Content-Range" to listOf("bytes 0-1048575/3000000")))
            fail("a range from another byte would land at the wrong place")
        } catch (_: RangeChunkDataSource.RangeMismatchException) {
        }
        assertEquals(5L, RangeChunkDataSource.rangeStart("bytes 5-9/10"))
        assertEquals(null, RangeChunkDataSource.rangeStart("bytes */10"))
    }

    @Test
    fun `a parse error is told apart from other cache errors`() {
        val parse =
            PlaybackException(
                "Source error",
                ParserException.createForUnsupportedContainerFeature("Skipping atom with length > 2147483647 (unsupported)."),
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            )
        assertTrue(PlaybackFailures.isParseError(parse))
        assertEquals(PlaybackFailureKind.CACHE_MISMATCH, PlaybackFailures.classify(parse))
        assertFalse(PlaybackFailures.isParseError(PlaybackException("x", IOException("y"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
        assertEquals(C.LENGTH_UNSET.toLong(), C.LENGTH_UNSET.toLong())
    }
}
