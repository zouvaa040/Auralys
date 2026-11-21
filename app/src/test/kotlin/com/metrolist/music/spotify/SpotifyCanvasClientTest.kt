/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.spotify

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client against a fake Spotify (an OkHttp interceptor that answers from the real captured search answer and
 * hand-written canvas answers): what it asks, with which headers, where the tokens go, and every way it can end.
 * Nothing here talks to Spotify; the live behaviour is NOT verified by these tests.
 */
class SpotifyCanvasClientTest {
    private class Sent(val url: String, val operation: String, val hash: String, val variables: String, val headers: Map<String, String>)

    private class MemoryStore(var saved: Map<String, String> = emptyMap()) : SpotifyHashStore {
        override fun load() = saved

        override fun save(hashes: Map<String, String>) {
            saved = hashes
        }
    }

    private val canvasUrl = "https://canvaz.scdn.co/upload/artist/example-clip.cnvs.mp4"
    private val noCanvas = SpotifyFixtures.text("canvas-anonymous.json")
    private val foundCanvas = SpotifyFixtures.text("canvas-found-example.json")
    private val searchAnswer = SpotifyFixtures.text("search-tracks.json")

    private val sent = mutableListOf<Sent>()
    private var clock = 1_700_000_000_000L
    private var tokens: SpotifyTokens? = SpotifyTokens("BEARER-TOKEN-1", "CLIENT-TOKEN-1")

    /** Routes: operation name -> (status, body). Canvas answers are chosen by track uri. */
    private var onSearch: (Sent) -> Pair<Int, String> = { 200 to searchAnswer }
    private var onCanvas: (Sent) -> Pair<Int, String> = { 200 to foundCanvas }

    private val fetched = mutableListOf<String>()
    private var pages: (String) -> String? = { null }
    private val store = MemoryStore()

    private fun fakeApi(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                val buffer = Buffer().also { request.body?.writeTo(it) }
                val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                val record = Sent(
                    url = request.url.toString(),
                    operation = body["operationName"]!!.jsonPrimitive.content,
                    hash = body["extensions"]!!.jsonObject["persistedQuery"]!!.jsonObject["sha256Hash"]!!.jsonPrimitive.content,
                    variables = body["variables"].toString(),
                    headers = request.headers.toMultimap().mapValues { it.value.first() },
                )
                sent += record
                val (code, text) = if (record.operation == "searchTracks") onSearch(record) else onCanvas(record)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_2)
                    .code(code)
                    .message("fake")
                    .body(text.toResponseBody("application/json".toMediaType()))
                    .build()
            },
        )
        .build()

    private fun client(): SpotifyCanvasClient {
        val hashes = SpotifyCanvasHashes(
            fetch = { url -> fetched += url; pages(url) },
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            now = { clock },
        )
        return SpotifyCanvasClient(fakeApi(), { tokens }, hashes, now = { clock })
    }

    private fun find(client: SpotifyCanvasClient, title: String = "Let Down", artist: String = "Radiohead", album: String? = "OK Computer", sec: Int? = 299) =
        runBlocking { client.find(title, artist, album, sec, isVideo = false) }

    // The happy path

    @Test
    fun `finds the track, asks its canvas and gives the mp4`() {
        val result = find(client())
        assertEquals(SpotifyCanvasResult.Found(canvasUrl), result)
        assertEquals(listOf("searchTracks", "canvas"), sent.map { it.operation })
        // the canvas is asked for the matching track: the OK Computer one, first of the real answer
        assertTrue(sent[1].variables.contains("spotify:track:2fuYa3Lx06QQJAm0MjztKr"))
        assertTrue(sent[0].variables.contains("let down radiohead"))
    }

    @Test
    fun `every call is a POST to Pathfinder v2 with the web player's headers and the account's tokens`() {
        find(client())
        for (call in sent) {
            assertEquals("https://api-partner.spotify.com/pathfinder/v2/query", call.url)
            assertEquals("Bearer BEARER-TOKEN-1", call.headers["authorization"])
            assertEquals("CLIENT-TOKEN-1", call.headers["client-token"])
            assertEquals("WebPlayer", call.headers["app-platform"])
            assertEquals("https://open.spotify.com", call.headers["origin"])
            assertEquals("https://open.spotify.com/", call.headers["referer"])
            assertEquals("application/json", call.headers["accept"])
        }
        assertEquals(SpotifyCanvasQuery.KNOWN_SEARCH_HASH, sent[0].hash)
        assertEquals(SpotifyCanvasQuery.KNOWN_CANVAS_HASH, sent[1].hash)
    }

    @Test
    fun `without a client token the request goes without that header`() {
        tokens = SpotifyTokens("BEARER-TOKEN-2", null)
        find(client())
        assertTrue(sent.all { it.headers["authorization"] == "Bearer BEARER-TOKEN-2" && "client-token" !in it.headers })
    }

    // Tokens go to spotify.com only

    @Test
    fun `the tokens are refused for any host that is not spotify com`() {
        val client = client()
        val session = SpotifyTokens("B", "C")
        for (host in listOf("https://evil.example/x", "https://spotify.com.evil.example/x", "https://notspotify.com/x", "http://api-partner.spotify.com/x", "https://open.spotifycdn.com/x")) {
            assertThrows(host, IllegalArgumentException::class.java) { client.authorised(host.toHttpUrl(), session) }
        }
        for (host in listOf("https://api-partner.spotify.com/x", "https://open.spotify.com/", "https://spotify.com/")) {
            val request = client.authorised(host.toHttpUrl(), session).build()
            assertEquals("Bearer B", request.header("Authorization"))
        }
    }

    @Test
    fun `reading Spotify's scripts for new hashes never sees a token`() {
        // the fetch function the hash reader gets has no access to the tokens at all; it is asked for these hosts only
        val bundle = "x" + "\"searchTracks\",\"query\",\"" + "e".repeat(64) + "\"," + "{9:\"xpui-routes-search\"}{9:\"abcd0123\"}"
        pages = { url ->
            when (url) {
                "https://open.spotify.com/" -> """<script src="https://open.spotifycdn.com/cdn/build/web-player/web-player.0a1b2c.js"></script>"""
                "https://open.spotifycdn.com/cdn/build/web-player/web-player.0a1b2c.js" -> bundle
                else -> null
            }
        }
        var calls = 0
        onSearch = { if (calls++ == 0) 200 to """{"errors":[{"message":"PersistedQueryNotFound"}]}""" else 200 to searchAnswer }
        find(client())
        assertTrue(fetched.isNotEmpty())
        assertTrue(fetched.all { it.startsWith("https://open.spotify.com/") || it.startsWith("https://open.spotifycdn.com/") })
    }

    // The ways it can end

    @Test
    fun `signed out means unavailable and nothing is sent`() {
        tokens = null
        assertEquals(SpotifyCanvasResult.Unavailable, find(client()))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `no matching track is a missing, with no canvas call`() {
        val result = find(client(), title = "A Song Nobody Sang", artist = "Nobody")
        assertEquals(SpotifyCanvasResult.Missing, result)
        assertEquals(listOf("searchTracks"), sent.map { it.operation })
    }

    @Test
    fun `a track without a canvas is a missing`() {
        onCanvas = { 200 to noCanvas }
        assertEquals(SpotifyCanvasResult.Missing, find(client()))
    }

    @Test
    fun `when the best release has no canvas the same song on another release is tried, at most three`() {
        val asked = mutableListOf<String>()
        onCanvas = { call ->
            asked += call.variables
            if (call.variables.contains("2yzLpXCWjksJRAqD9sR7it")) 200 to foundCanvas else 200 to noCanvas
        }
        val result = find(client())
        assertTrue(result is SpotifyCanvasResult.Found)
        assertEquals(3, asked.size) // OK Computer, Remastered, Paranoid Android single
        assertTrue(asked.last().contains("2yzLpXCWjksJRAqD9sR7it"))

        sent.clear()
        onCanvas = { 200 to noCanvas }
        assertEquals(SpotifyCanvasResult.Missing, find(client()))
        assertEquals(3, sent.count { it.operation == "canvas" })
    }

    @Test
    fun `server errors and unreadable answers are failures, not a statement about the song`() {
        onSearch = { 500 to "oops" }
        assertEquals(SpotifyCanvasResult.Failed, find(client()))
        onSearch = { 200 to "<html>" }
        assertEquals(SpotifyCanvasResult.Failed, find(client()))
        onSearch = { 200 to searchAnswer }
        onCanvas = { 502 to "" }
        assertEquals(SpotifyCanvasResult.Failed, find(client()))
        onCanvas = { 200 to """{"data":{"unexpected":true}}""" }
        assertEquals(SpotifyCanvasResult.Failed, find(client()))
    }

    @Test
    fun `a 429 cools Spotify down for ten minutes without asking again`() {
        onSearch = { 429 to "" }
        val client = client()
        assertEquals(SpotifyCanvasResult.Failed, find(client))
        assertEquals(1, sent.size)
        assertEquals(SpotifyCanvasResult.Unavailable, find(client))
        assertEquals(1, sent.size)
        clock += 9 * 60_000L
        assertEquals(SpotifyCanvasResult.Unavailable, find(client))
        assertEquals(1, sent.size)
        clock += 2 * 60_000L
        onSearch = { 200 to searchAnswer }
        assertTrue(find(client) is SpotifyCanvasResult.Found)
    }

    // Retired hashes

    private fun webPlayerWith(searchHash: String?, canvasHash: String?): (String) -> String? {
        val bundle = buildString {
            append("{9:\"xpui-routes-search\"}{9:\"abcd0123\"}")
            canvasHash?.let { append("""n("canvas","query","$it"),""") }
        }
        val chunk = searchHash?.let { """n("searchTracks","query","$it"),""" }.orEmpty()
        return { url ->
            when (url) {
                "https://open.spotify.com/" -> """<script src="https://open.spotifycdn.com/cdn/build/web-player/web-player.0a1b2c.js"></script>"""
                "https://open.spotifycdn.com/cdn/build/web-player/web-player.0a1b2c.js" -> bundle
                "https://open.spotifycdn.com/cdn/build/web-player/xpui-routes-search.abcd0123.js" -> chunk
                else -> null
            }
        }
    }

    @Test
    fun `a retired search hash is read again from the search chunk once and the call is repeated`() {
        val fresh = "f".repeat(64)
        pages = webPlayerWith(searchHash = fresh, canvasHash = null)
        onSearch = { call ->
            if (call.hash == fresh) 200 to searchAnswer else 200 to """{"errors":[{"message":"PersistedQueryNotFound"}],"data":null}"""
        }
        val result = find(client())
        assertTrue(result is SpotifyCanvasResult.Found)
        assertEquals(listOf(SpotifyCanvasQuery.KNOWN_SEARCH_HASH, fresh, SpotifyCanvasQuery.KNOWN_CANVAS_HASH), sent.map { it.hash })
        // learned for next time, and kept on disk
        assertEquals(fresh, store.saved["searchTracks"])
        assertEquals(
            listOf("https://open.spotify.com/", "https://open.spotifycdn.com/cdn/build/web-player/web-player.0a1b2c.js", "https://open.spotifycdn.com/cdn/build/web-player/xpui-routes-search.abcd0123.js"),
            fetched,
        )
    }

    @Test
    fun `a retired canvas hash is read from the bundle`() {
        val fresh = "c".repeat(64)
        pages = webPlayerWith(searchHash = null, canvasHash = fresh)
        onCanvas = { call ->
            if (call.hash == fresh) 200 to foundCanvas else 400 to """{"errors":[{"message":"PersistedQueryNotFound"}]}"""
        }
        assertTrue(find(client()) is SpotifyCanvasResult.Found)
        assertEquals(fresh, store.saved["canvas"])
    }

    @Test
    fun `when the scripts cannot be read a retired hash is a failure, and they are not read again for half an hour`() {
        pages = { null }
        onSearch = { 200 to """{"errors":[{"message":"PersistedQueryNotFound"}]}""" }
        val client = client()
        assertEquals(SpotifyCanvasResult.Failed, find(client))
        val readsAfterFirst = fetched.size
        assertEquals(1, readsAfterFirst)
        assertEquals(SpotifyCanvasResult.Failed, find(client))
        assertEquals(readsAfterFirst, fetched.size)
        clock += SpotifyCanvasHashes.MIN_REFRESH_INTERVAL_MS + 1
        assertEquals(SpotifyCanvasResult.Failed, find(client))
        assertTrue(fetched.size > readsAfterFirst)
    }

    @Test
    fun `a hash that did not change after a re-read is not retried forever`() {
        pages = webPlayerWith(searchHash = SpotifyCanvasQuery.KNOWN_SEARCH_HASH, canvasHash = null)
        onSearch = { 200 to """{"errors":[{"message":"PersistedQueryNotFound"}]}""" }
        assertEquals(SpotifyCanvasResult.Failed, find(client()))
        assertEquals(1, sent.size)
    }

    @Test
    fun `hashes learned earlier are used from the first call`() {
        val learned = "9".repeat(64)
        store.saved = mapOf("searchTracks" to learned, "junk" to "not a hash")
        find(client())
        assertEquals(learned, sent[0].hash)
        assertEquals(SpotifyCanvasQuery.KNOWN_CANVAS_HASH, sent[1].hash)
    }

    @Test
    fun `a blank title or artist is asked nothing`() {
        assertEquals(SpotifyCanvasResult.Missing, find(client(), title = "(Official Video)", artist = ""))
        assertTrue(sent.isEmpty())
        assertNull(SpotifyCanvasMatch.cacheKey("(Official Video)", ""))
        assertFalse(SpotifyCanvasQuery.isHash("xyz"))
    }
}
