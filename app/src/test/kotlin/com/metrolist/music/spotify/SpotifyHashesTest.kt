package com.metrolist.music.spotify

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyHashesTest {
    private var clock = 1_000_000L

    /** A transport whose front page links [scripts] and whose scripts answer from [bodies]. */
    private fun transport(scripts: List<String>, bodies: Map<String, String>) =
        FakeSpotifyTransport { call ->
            when {
                call.url == SpotifyWebPlayer.WEB_PLAYER_URL -> ok200(testWebPlayerHtml(scripts))
                bodies.containsKey(call.url) -> ok200(bodies.getValue(call.url))
                else -> SpotifyHttpResult(404, "")
            }
        }

    private fun hashes(t: FakeSpotifyTransport) = SpotifyHashes(SpotifyWebPlayer(t) { clock }) { clock }

    // ------------------------------------------------------------ reading the bundle

    @Test
    fun `operations are read the way the bundle declares them`() {
        val script =
            testBundleDeclaring("libraryV3" to testHash(1), "fetchPlaylist" to testHash(2)) +
                """x("canvas",'query',"${testHash(3)}")""" +
                """y("short","query","abc")""" + // not 64 hex: ignored
                """z("libraryV3","query","${testHash(9)}")""" // the first declaration wins
        val found = SpotifyHashes.queryHashesIn(script)
        assertEquals(testHash(1), found["libraryV3"])
        assertEquals(testHash(2), found["fetchPlaylist"])
        assertEquals(testHash(3), found["canvas"])
        assertNull(found["short"])
        assertEquals(3, found.size)
    }

    @Test
    fun `the player's scripts come from the front page, the main bundle first`() {
        val html = testWebPlayerHtml(listOf(TEST_ROUTES_CHUNK, TEST_MAIN_BUNDLE, TEST_ROUTES_CHUNK, "https://evil.example/web-player.js"))
        assertEquals(listOf(TEST_MAIN_BUNDLE, TEST_ROUTES_CHUNK), SpotifyHashes.webPlayerScripts(html))
    }

    @Test
    fun `the client version is read from the embedded config`() {
        assertEquals("1.3.6.1892.g0a8ca577db87", SpotifyWebPlayer.clientVersionIn(testWebPlayerHtml(emptyList())))
        assertNull(SpotifyWebPlayer.clientVersionIn("<html></html>"))
        assertNull(SpotifyWebPlayer.clientVersionIn("""<script id="appServerConfig" type="text/plain">!!!not base64!!!</script>"""))
    }

    // ------------------------------------------------------------ the provider

    @Test
    fun `before any read the newest known hash is used and nothing is fetched`() {
        val t = transport(listOf(TEST_MAIN_BUNDLE), emptyMap())
        val h = hashes(t)
        for (operation in SpotifyOperation.values()) assertEquals(operation.known.first(), h.current(operation))
        assertTrue(t.calls.isEmpty())
    }

    @Test
    fun `a refresh takes the hashes the player declares and keeps the known ones for the rest`() = runBlocking {
        val t =
            transport(
                listOf(TEST_MAIN_BUNDLE, TEST_ROUTES_CHUNK),
                mapOf(TEST_MAIN_BUNDLE to testBundleDeclaring("libraryV3" to testHash(1), "fetchLibraryTracks" to testHash(2))),
            )
        val h = hashes(t)
        assertTrue(h.refresh())
        assertEquals(testHash(1), h.current(SpotifyOperation.LIBRARY))
        assertEquals(testHash(2), h.current(SpotifyOperation.LIKED))
        assertEquals(SpotifyOperation.PLAYLIST.known.first(), h.current(SpotifyOperation.PLAYLIST))
    }

    @Test
    fun `scripts are read until every operation is found, at most eight`() = runBlocking {
        val scripts = (1..12).map { "https://open.spotifycdn.com/cdn/build/web-player/chunk$it.js" }
        val bodies = scripts.associateWith { testBundleDeclaring("other" to testHash(50)) }
        val t = transport(scripts, bodies)
        assertFalse(hashes(t).refresh())
        assertEquals(8, t.gets().count { it.url != SpotifyWebPlayer.WEB_PLAYER_URL })

        val all =
            SpotifyOperation.values().associate { it.operationName to testHash(it.ordinal + 1) }.toList().toTypedArray()
        val t2 = transport(listOf(TEST_MAIN_BUNDLE, TEST_ROUTES_CHUNK), mapOf(TEST_MAIN_BUNDLE to testBundleDeclaring(*all), TEST_ROUTES_CHUNK to "x"))
        assertTrue(hashes(t2).refresh())
        // the second script is never fetched: the first had everything
        assertFalse(t2.gets().any { it.url == TEST_ROUTES_CHUNK })
    }

    @Test
    fun `the profile's hash is optional and the read stops once the library's operations are found`() = runBlocking {
        val required = SpotifyOperation.values().filter { it != SpotifyOperation.PROFILE }.map { it.operationName to testHash(it.ordinal + 1) }.toTypedArray()
        val t =
            transport(
                listOf(TEST_MAIN_BUNDLE, TEST_ROUTES_CHUNK),
                mapOf(TEST_MAIN_BUNDLE to testBundleDeclaring(*required), TEST_ROUTES_CHUNK to testBundleDeclaring("profileAttributes" to testHash(99))),
            )
        val h = hashes(t)
        assertTrue(h.refresh())
        assertFalse("the second script is not read for the profile", t.gets().any { it.url == TEST_ROUTES_CHUNK })
        assertEquals(SpotifyOperation.PROFILE.known.first(), h.current(SpotifyOperation.PROFILE))
    }

    @Test
    fun `a read that could not reach the player does not start the ten minute pause`() = runBlocking {
        var online = false
        val t =
            FakeSpotifyTransport { call ->
                when {
                    !online -> throw java.io.IOException("offline")
                    call.url == SpotifyWebPlayer.WEB_PLAYER_URL -> ok200(testWebPlayerHtml(listOf(TEST_MAIN_BUNDLE)))
                    call.url == TEST_MAIN_BUNDLE -> ok200(testBundleDeclaring("libraryV3" to testHash(1)))
                    else -> SpotifyHttpResult(404, "")
                }
            }
        val h = hashes(t)
        assertFalse(h.refresh())
        // a minute later the connection is back: the read is allowed, nothing blocks it
        clock += 60_000L
        online = true
        assertTrue(h.refresh())
        assertEquals(testHash(1), h.current(SpotifyOperation.LIBRARY))
    }

    @Test
    fun `a cancelled read does not start the pause either`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<SpotifyHttpResult>()
        var hang = true
        val real = transport(listOf(TEST_MAIN_BUNDLE), mapOf(TEST_MAIN_BUNDLE to testBundleDeclaring("libraryV3" to testHash(1))))
        val slow =
            object : SpotifyTransport {
                override suspend fun get(url: String, headers: Map<String, String>): SpotifyHttpResult =
                    if (hang) gate.await() else real.get(url, headers)

                override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray, contentType: String) =
                    real.post(url, headers, body, contentType)
            }
        val h = SpotifyHashes(SpotifyWebPlayer(slow) { clock }) { clock }
        val job = async { h.refresh() }
        delay(50)
        job.cancel()
        job.join()
        hang = false
        // the next read is not held back by the cancelled one
        assertTrue(h.refresh())
        assertEquals(testHash(1), h.current(SpotifyOperation.LIBRARY))
    }

    @Test
    fun `reset forgets the hashes read from the player and its cached page`() = runBlocking {
        val t = transport(listOf(TEST_MAIN_BUNDLE), mapOf(TEST_MAIN_BUNDLE to testBundleDeclaring("libraryV3" to testHash(1))))
        val h = hashes(t)
        assertTrue(h.refresh())
        assertEquals(testHash(1), h.current(SpotifyOperation.LIBRARY))
        h.reset()
        assertEquals(SpotifyOperation.LIBRARY.known.first(), h.current(SpotifyOperation.LIBRARY))
        // and the pause is gone with it
        assertTrue(h.refresh())
    }

    @Test
    fun `a second refresh within ten minutes does no network work`() = runBlocking {
        val t = transport(listOf(TEST_MAIN_BUNDLE), mapOf(TEST_MAIN_BUNDLE to testBundleDeclaring("libraryV3" to testHash(1))))
        val h = hashes(t)
        assertTrue(h.refresh())
        val calls = t.calls.size
        clock += 9 * 60 * 1000L
        assertFalse(h.refresh())
        assertEquals(calls, t.calls.size)
        clock += 2 * 60 * 1000L
        assertTrue(h.refresh())
        assertTrue(t.calls.size > calls)
    }

    @Test
    fun `a refresh that cannot reach the player returns false and changes nothing`() = runBlocking {
        val h = hashes(FakeSpotifyTransport.failing())
        assertFalse(h.refresh())
        assertEquals(SpotifyOperation.LIBRARY.known.first(), h.current(SpotifyOperation.LIBRARY))
        val notFound = hashes(FakeSpotifyTransport { SpotifyHttpResult(404, "") })
        assertFalse(notFound.refresh())
    }

    @Test
    fun `the older known hashes are offered once the newer ones were tried`() {
        val h = hashes(FakeSpotifyTransport())
        val library = SpotifyOperation.LIBRARY.known
        assertEquals(library[1], h.fallback(SpotifyOperation.LIBRARY, setOf(library[0])))
        assertNull(h.fallback(SpotifyOperation.LIBRARY, library.toSet()))
        // an operation with one known hash has no fallback
        assertNull(h.fallback(SpotifyOperation.LIKED, SpotifyOperation.LIKED.known.toSet()))
    }

    @Test
    fun `every known hash is 64 hex characters and the operations have distinct names`() {
        for (operation in SpotifyOperation.values()) {
            assertTrue(operation.known.isNotEmpty())
            assertTrue(operation.known.all { it.matches(Regex("[0-9a-f]{64}")) })
        }
        assertEquals(SpotifyOperation.values().size, SpotifyOperation.values().map { it.operationName }.toSet().size)
    }

    @Test
    fun `the web player page is cached and its device id comes from the sp_t cookie`() = runBlocking {
        val t =
            FakeSpotifyTransport {
                SpotifyHttpResult(200, testWebPlayerHtml(emptyList()), listOf("sp_t=abc123; Path=/; Secure", "other=1"))
            }
        val player = SpotifyWebPlayer(t) { clock }
        assertEquals("abc123", player.page()!!.deviceId)
        player.page()
        assertEquals(1, t.calls.size)
        clock += 31 * 60 * 1000L
        player.page()
        assertEquals(2, t.calls.size)
    }
}
