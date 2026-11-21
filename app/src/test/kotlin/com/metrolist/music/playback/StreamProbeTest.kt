/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/**
 * A stream address that serves only its first MiB (what the live harness saw for a forced ANDROID_VR client: 206 up to
 * byte 1 048 575, 403 after) is told apart from a good one by asking for its last byte. Pure JVM, a local HTTP server.
 */
class StreamProbeTest {
    private val size = 4_000_000L
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/good") { ex ->
                ex.sendResponseHeaders(206, 1)
                ex.responseBody.use { it.write(1) }
            }
            createContext("/firstmib") { ex ->
                val from = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: -1L
                if (from in 0 until 1_048_576L) {
                    ex.sendResponseHeaders(206, 1)
                    ex.responseBody.use { it.write(1) }
                } else {
                    ex.sendResponseHeaders(403, -1)
                    ex.close()
                }
            }
            start()
        }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val probe = StreamProbe(OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build())

    @After
    fun tearDown() = server.stop(0)

    @Test
    fun `the last byte is asked for, short or unknown streams are not checked`() {
        assertEquals(size - 1, StreamProbe.positionFor(size))
        assertNull(StreamProbe.positionFor(900_000L))
        assertNull(StreamProbe.positionFor(null))
    }

    @Test
    fun `a stream that serves only its first MiB is refused at its end, a good one is not`() {
        val at = StreamProbe.positionFor(size)!!
        val bad = probe.statusAt("$base/firstmib", emptyMap(), at)
        assertEquals(403, bad)
        assertTrue(StreamLoadErrorPolicy.isRefusal(bad!!))
        assertEquals(206, probe.statusAt("$base/firstmib", emptyMap(), 1_000L))
        assertEquals(206, probe.statusAt("$base/good", mapOf("User-Agent" to "x"), at))
    }

    @Test
    fun `a probe that cannot be made says nothing (the stream is used as before)`() {
        assertNull(probe.statusAt("http://127.0.0.1:1/none", emptyMap(), 5L))
        assertNull(probe.statusAt("not a url", emptyMap(), 5L))
    }
}
