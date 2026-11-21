package com.metrolist.music.spotify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpotifyApiTest {
    private var clock = 1_000_000L
    private var invalidated = 0
    private var tokens: SpotifyTokens? = SpotifyTokens("BEARER-SECRET", "CLIENT-SECRET")
    private var mintFailure: SpotifyMintFailure? = null

    private fun api(t: FakeSpotifyTransport): SpotifyApi {
        val hashes = SpotifyHashes(SpotifyWebPlayer(t) { clock }) { clock }
        return SpotifyApi(
            transport = t,
            hashes = hashes,
            tokens = { tokens },
            invalidateTokens = { invalidated++ },
            mintFailure = { mintFailure },
        )
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private fun bodyOf(call: FakeSpotifyTransport.Call): JsonObject = json(call.body!!)

    private fun variables(call: FakeSpotifyTransport.Call): JsonObject = bodyOf(call)["variables"]!!.jsonObject

    private fun hashOf(call: FakeSpotifyTransport.Call): String =
        bodyOf(call)["extensions"]!!.jsonObject["persistedQuery"]!!.jsonObject["sha256Hash"]!!.jsonPrimitive.content

    private fun operationOf(call: FakeSpotifyTransport.Call): String = bodyOf(call)["operationName"]!!.jsonPrimitive.content

    private fun libraryAnswer(vararg ids: String, total: Int = ids.size) =
        ok200(
            """{"data":{"me":{"libraryV3":{"totalCount":$total,"items":[${
                ids.joinToString(",") {
                    """{"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:$it","data":{"__typename":"Playlist","name":"List $it"}}}"""
                }
            }]}}}}""",
        )

    private fun tracksAnswer(from: Int, count: Int, total: Int, name: String = "My list") =
        ok200(
            """{"data":{"playlistV2":{"__typename":"Playlist","name":"$name","content":{"totalCount":$total,"items":[${
                (from until from + count).joinToString(",") {
                    """{"itemV2":{"data":{"uri":"spotify:track:%022d","name":"Song $it","artists":{"items":[{"profile":{"name":"A"}}]},"trackDuration":{"totalMilliseconds":1000}}}}""".format(it)
                }
            }]}}}}""",
        )

    private fun likedAnswer(from: Int, count: Int, total: Int) =
        ok200(
            """{"data":{"me":{"library":{"tracks":{"totalCount":$total,"items":[${
                (from until from + count).joinToString(",") {
                    """{"track":{"_uri":"spotify:track:%022d","data":{"name":"Liked $it","artists":{"items":[{"profile":{"name":"B"}}]}}}}""".format(it)
                }
            }]}}}}}""",
        )

    // ------------------------------------------------------------ playlists

    @Test
    fun `the library is read page by page, Liked Songs first, and the request looks like the web player's`() = runBlocking {
        val t =
            FakeSpotifyTransport { call ->
                when (variables(call)["offset"]!!.jsonPrimitive.content) {
                    "0" -> libraryAnswer("aaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbbbbbbbb", total = 3)
                    else -> libraryAnswer("cccccccccccccccccccccc", total = 3)
                }
            }
        val list = api(t).playlists()
        assertEquals(listOf(SPOTIFY_LIKED_ID, "aaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbbbbbbbb", "cccccccccccccccccccccc"), list.map { it.id })
        assertEquals(SPOTIFY_LIKED_COVER, list.first().imageUrl)
        assertEquals(2, t.posts().size)
        assertEquals(listOf("0", "2"), t.posts().map { variables(it)["offset"]!!.jsonPrimitive.content })

        val first = t.posts().first()
        assertEquals(SpotifyApi.PATHFINDER_URL, first.url)
        assertEquals("libraryV3", operationOf(first))
        assertEquals(SpotifyOperation.LIBRARY.known.first(), hashOf(first))
        assertEquals("50", variables(first)["limit"]!!.jsonPrimitive.content)
        assertEquals("Bearer BEARER-SECRET", first.headers["Authorization"])
        assertEquals("CLIENT-SECRET", first.headers["Client-Token"])
        assertEquals("WebPlayer", first.headers["app-platform"])
        assertEquals("https://open.spotify.com", first.headers["Origin"])
        assertEquals("application/json;charset=UTF-8", first.contentType)
    }

    @Test
    fun `without a client token the header is simply left out`() = runBlocking {
        tokens = SpotifyTokens("B", null)
        val t = FakeSpotifyTransport { libraryAnswer("aaaaaaaaaaaaaaaaaaaaaa") }
        api(t).playlists()
        assertFalse(t.posts().single().headers.containsKey("Client-Token"))
    }

    @Test
    fun `an endless library stops at the page limit`() = runBlocking {
        val t = FakeSpotifyTransport { libraryAnswer("aaaaaaaaaaaaaaaaaaaaaa", total = 1_000_000) }
        val list = api(t).playlists()
        assertEquals(SpotifyApi.MAX_LIBRARY_PAGES, t.posts().size)
        // the same playlist on every page is listed once (plus Liked Songs)
        assertEquals(2, list.size)
    }

    @Test
    fun `an empty page ends the read`() = runBlocking {
        val t = FakeSpotifyTransport { libraryAnswer(total = 10) }
        assertEquals(1, api(t).playlists().size)
        assertEquals(1, t.posts().size)
    }

    // ------------------------------------------------------------ tracks

    @Test
    fun `a playlist's tracks are read page by page with progress after each page`() = runBlocking {
        val t =
            FakeSpotifyTransport { call ->
                val offset = variables(call)["offset"]!!.jsonPrimitive.content.toInt()
                tracksAnswer(from = offset, count = if (offset == 0) 100 else 30, total = 130)
            }
        val progress = ArrayList<Int>()
        val result = api(t).tracks("p".repeat(22)) { progress += it.tracks.size }
        assertEquals(130, result.tracks.size)
        assertEquals(130, result.total)
        assertFalse(result.truncated)
        assertEquals(listOf(100, 130), progress)
        assertEquals("My list", result.header.name)
        assertEquals(listOf("0", "100"), t.posts().map { variables(it)["offset"]!!.jsonPrimitive.content })
        assertEquals("spotify:playlist:" + "p".repeat(22), variables(t.posts().first())["uri"]!!.jsonPrimitive.content)
        assertEquals("100", variables(t.posts().first())["limit"]!!.jsonPrimitive.content)
        assertEquals("fetchPlaylist", operationOf(t.posts().first()))
    }

    @Test
    fun `a huge playlist stops at the page limit and says it is cut`() = runBlocking {
        val t = FakeSpotifyTransport { call -> tracksAnswer(from = variables(call)["offset"]!!.jsonPrimitive.content.toInt(), count = 100, total = 100_000) }
        val result = api(t).tracks("p".repeat(22))
        assertEquals(SpotifyApi.MAX_TRACK_PAGES, t.posts().size)
        assertEquals(SpotifyApi.MAX_TRACK_PAGES * 100, result.tracks.size)
        assertTrue(result.truncated)
        assertEquals(100_000, result.total)
    }

    @Test
    fun `liked songs use their own query and the header has the liked cover`() = runBlocking {
        val t = FakeSpotifyTransport { call -> likedAnswer(from = variables(call)["offset"]!!.jsonPrimitive.content.toInt(), count = 3, total = 3) }
        val result = api(t).tracks(SPOTIFY_LIKED_ID)
        assertEquals(3, result.tracks.size)
        assertEquals("fetchLibraryTracks", operationOf(t.posts().single()))
        assertEquals(SPOTIFY_LIKED_COVER, result.header.imageUrl)
        assertEquals("100", variables(t.posts().single())["limit"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------ tokens

    @Test
    fun `a 401 forgets the tokens and tries once more`() = runBlocking {
        var answered = 0
        val t = FakeSpotifyTransport { if (answered++ == 0) SpotifyHttpResult(401, "") else libraryAnswer("aaaaaaaaaaaaaaaaaaaaaa") }
        assertEquals(2, api(t).playlists().size)
        assertEquals(1, invalidated)
        assertEquals(2, t.posts().size)
    }

    @Test
    fun `two 401s in a row mean the session is over`() = runBlocking {
        val t = FakeSpotifyTransport { SpotifyHttpResult(401, "") }
        try {
            api(t).playlists()
            fail()
        } catch (e: SpotifyException) {
            assertEquals(SpotifyException.Kind.SESSION_EXPIRED, e.kind)
        }
        assertEquals(2, t.posts().size)
    }

    @Test
    fun `no token turns into the right kind of error`() = runBlocking {
        tokens = null
        val cases =
            mapOf(
                null to SpotifyException.Kind.NOT_CONNECTED,
                SpotifyMintFailure.EXPIRED to SpotifyException.Kind.SESSION_EXPIRED,
                SpotifyMintFailure.OFFLINE to SpotifyException.Kind.NETWORK,
                SpotifyMintFailure.FAILED to SpotifyException.Kind.TOKEN_FAILED,
            )
        for ((failure, kind) in cases) {
            mintFailure = failure
            val t = FakeSpotifyTransport()
            try {
                api(t).playlists()
                fail()
            } catch (e: SpotifyException) {
                assertEquals(kind, e.kind)
            }
            assertTrue("nothing is sent without a token", t.calls.isEmpty())
        }
    }

    @Test
    fun `an error never carries a token`() = runBlocking {
        val failures =
            listOf(
                FakeSpotifyTransport.failing(),
                FakeSpotifyTransport { SpotifyHttpResult(500, "BEARER-SECRET") },
                FakeSpotifyTransport { SpotifyHttpResult(400, "BEARER-SECRET CLIENT-SECRET") },
                FakeSpotifyTransport { ok200("""{"errors":[{"message":"BEARER-SECRET"}]}""") },
                FakeSpotifyTransport { ok200("not json BEARER-SECRET") },
            )
        for (t in failures) {
            try {
                api(t).playlists()
                fail()
            } catch (e: SpotifyException) {
                assertFalse("message: ${e.message}", e.message.orEmpty().contains("SECRET"))
            }
        }
    }

    // ------------------------------------------------------------ failures

    @Test
    fun `429, server errors, a broken connection and plain errors are told apart`() = runBlocking {
        fun kindOf(t: FakeSpotifyTransport): SpotifyException.Kind =
            try {
                runBlocking { api(t).playlists() }
                error("no failure")
            } catch (e: SpotifyException) {
                e.kind
            }
        assertEquals(SpotifyException.Kind.RATE_LIMITED, kindOf(FakeSpotifyTransport { SpotifyHttpResult(429, "") }))
        assertEquals(SpotifyException.Kind.NETWORK, kindOf(FakeSpotifyTransport { SpotifyHttpResult(503, "") }))
        assertEquals(SpotifyException.Kind.NETWORK, kindOf(FakeSpotifyTransport.failing()))
        assertEquals(SpotifyException.Kind.OTHER, kindOf(FakeSpotifyTransport { SpotifyHttpResult(400, "") }))
        assertEquals(SpotifyException.Kind.API_CHANGED, kindOf(FakeSpotifyTransport { ok200("<html>") }))
        assertEquals(SpotifyException.Kind.OTHER, kindOf(FakeSpotifyTransport { ok200("""{"errors":[{"message":"boom"}]}""") }))
    }

    @Test
    fun `an answer with data and a partial error is still used`() = runBlocking {
        val t =
            FakeSpotifyTransport {
                ok200(
                    """{"errors":[{"message":"one thing failed"}],"data":{"me":{"libraryV3":{"totalCount":1,"items":[
                    {"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:aaaaaaaaaaaaaaaaaaaaaa","data":{"__typename":"Playlist","name":"X"}}}]}}}}""",
                )
            }
        assertEquals(2, api(t).playlists().size)
    }

    // ------------------------------------------------------------ stale hashes

    private fun staleThenOk(): FakeSpotifyTransport {
        val stale = ok200("""{"errors":[{"message":"PersistedQueryNotFound"}]}""")
        return FakeSpotifyTransport { call ->
            when {
                call.method == "GET" && call.url == SpotifyWebPlayer.WEB_PLAYER_URL -> ok200(testWebPlayerHtml(listOf(TEST_MAIN_BUNDLE)))
                call.method == "GET" && call.url == TEST_MAIN_BUNDLE -> ok200(testBundleDeclaring("libraryV3" to testHash(77)))
                call.method == "POST" && hashOf(call) == testHash(77) -> libraryAnswer("aaaaaaaaaaaaaaaaaaaaaa")
                else -> stale
            }
        }
    }

    @Test
    fun `a stale hash reads the player's current ones once and retries with the new one`() = runBlocking {
        val t = staleThenOk()
        assertEquals(2, api(t).playlists().size)
        val sent = t.posts().map { hashOf(it) }
        assertEquals(listOf(SpotifyOperation.LIBRARY.known.first(), testHash(77)), sent)
        assertEquals(1, t.gets().count { it.url == TEST_MAIN_BUNDLE })
    }

    @Test
    fun `when the player has no newer hash the older known ones are tried, then it gives up`() = runBlocking {
        val t =
            FakeSpotifyTransport { call ->
                if (call.method == "GET") SpotifyHttpResult(404, "") else ok200("""{"errors":[{"message":"PersistedQueryNotFound"}]}""")
            }
        try {
            api(t).playlists()
            fail()
        } catch (e: SpotifyException) {
            assertEquals(SpotifyException.Kind.API_CHANGED, e.kind)
        }
        assertEquals(SpotifyOperation.LIBRARY.known, t.posts().map { hashOf(it) })
    }

    @Test
    fun `the older hash is used when the newer one is refused`() = runBlocking {
        val old = SpotifyOperation.LIBRARY.known.last()
        val t =
            FakeSpotifyTransport { call ->
                when {
                    call.method == "GET" -> SpotifyHttpResult(404, "")
                    hashOf(call) == old -> libraryAnswer("aaaaaaaaaaaaaaaaaaaaaa")
                    else -> ok200("""{"errors":[{"message":"PersistedQueryNotFound"}]}""")
                }
            }
        assertEquals(2, api(t).playlists().size)
    }

    @Test
    fun `an answer with an unknown shape is treated like a stale hash`() = runBlocking {
        val old = SpotifyOperation.LIBRARY.known.last()
        val t =
            FakeSpotifyTransport { call ->
                when {
                    call.method == "GET" -> SpotifyHttpResult(404, "")
                    hashOf(call) == old -> libraryAnswer("aaaaaaaaaaaaaaaaaaaaaa")
                    else -> ok200("""{"data":{"me":{"somethingElse":{}}}}""")
                }
            }
        assertEquals(2, api(t).playlists().size)
    }

    // ------------------------------------------------------------ profile

    @Test
    fun `the profile is read, and an unknown profile query is not an error`() = runBlocking {
        val t = FakeSpotifyTransport { ok200("""{"data":{"me":{"profile":{"name":"Rao","avatar":{"sources":[{"url":"https://i.scdn.co/p","width":64}]}}}}}""") }
        assertEquals("Rao", api(t).profile()!!.name)
        assertEquals("profileAttributes", operationOf(t.posts().single()))

        val unknown = FakeSpotifyTransport { call -> if (call.method == "GET") SpotifyHttpResult(404, "") else ok200("""{"errors":[{"message":"PersistedQueryNotFound"}]}""") }
        assertNull(api(unknown).profile())

        tokens = null
        try {
            api(FakeSpotifyTransport()).profile()
            fail()
        } catch (e: SpotifyException) {
            assertEquals(SpotifyException.Kind.NOT_CONNECTED, e.kind)
        }
    }

    @Test
    fun `a profile query that Spotify does not know is not a stale hash and never reads the web player`() = runBlocking {
        val t = FakeSpotifyTransport { call -> if (call.method == "GET") ok200(testWebPlayerHtml(listOf(TEST_MAIN_BUNDLE))) else ok200("""{"errors":[{"message":"PersistedQueryNotFound"}]}""") }
        assertNull(api(t).profile())
        assertTrue("no page, no script was read", t.gets().isEmpty())
        // and it asked once: no walk through other hashes
        assertEquals(1, t.posts().size)
    }

    @Test
    fun `a profile answer of another shape is not a stale hash either`() = runBlocking {
        val t = FakeSpotifyTransport { call -> if (call.method == "GET") ok200(testWebPlayerHtml(listOf(TEST_MAIN_BUNDLE))) else ok200("""{"data":{"me":{"somethingElse":1}}}""") }
        assertNull(api(t).profile())
        assertTrue(t.gets().isEmpty())
        assertEquals(1, t.posts().size)
    }

    @Test
    fun `a profile that fails with a server error or a lost connection is just no profile`() = runBlocking {
        assertNull(api(FakeSpotifyTransport { SpotifyHttpResult(500, "") }).profile())
        assertNull(api(FakeSpotifyTransport.failing()).profile())
        assertNull(api(FakeSpotifyTransport { SpotifyHttpResult(429, "") }).profile())
    }

    // ------------------------------------------------------------ 401 with and without a client token

    @Test
    fun `a bearer without a client token that is refused means Spotify wants something new, not a lost session`() = runBlocking {
        tokens = SpotifyTokens("B", null)
        val t = FakeSpotifyTransport { SpotifyHttpResult(401, "") }
        try {
            api(t).playlists()
            fail()
        } catch (e: SpotifyException) {
            assertEquals(SpotifyException.Kind.API_CHANGED, e.kind)
        }
        assertEquals(2, t.posts().size)
    }

    @Test
    fun `a refused bearer that came with a client token is a session that ended`() = runBlocking {
        tokens = SpotifyTokens("B", "C")
        val t = FakeSpotifyTransport { SpotifyHttpResult(401, "") }
        try {
            api(t).playlists()
            fail()
        } catch (e: SpotifyException) {
            assertEquals(SpotifyException.Kind.SESSION_EXPIRED, e.kind)
        }
    }

    // ------------------------------------------------------------ cancellation

    @Test
    fun `cancelling the caller stops the read`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<SpotifyHttpResult>()
        val t =
            object : SpotifyTransport {
                override suspend fun get(url: String, headers: Map<String, String>): SpotifyHttpResult = never.await()

                override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray, contentType: String): SpotifyHttpResult {
                    started.complete(Unit)
                    return never.await()
                }
            }
        val hashes = SpotifyHashes(SpotifyWebPlayer(t))
        val job = async { SpotifyApi(t, hashes, { tokens }, { }).playlists() }
        started.await()
        job.cancel()
        try {
            job.await()
            fail()
        } catch (e: CancellationException) {
            // stopped
        }
    }

    @Test
    fun `the request body is the persisted query the web player sends`() {
        val body = json(SpotifyApi.requestBody(SpotifyOperation.PLAYLIST, "H", SpotifyApi.playlistVariables("ID", 100)))
        assertEquals("fetchPlaylist", body["operationName"]!!.jsonPrimitive.content)
        val persisted = body["extensions"]!!.jsonObject["persistedQuery"]!!.jsonObject
        assertEquals("1", persisted["version"]!!.jsonPrimitive.content)
        assertEquals("H", persisted["sha256Hash"]!!.jsonPrimitive.content)
        val v = body["variables"]!!.jsonObject
        assertEquals("spotify:playlist:ID", v["uri"]!!.jsonPrimitive.content)
        assertEquals("100", v["offset"]!!.jsonPrimitive.content)
        assertEquals("false", v["enableWatchFeedEntrypoint"]!!.jsonPrimitive.content)

        val library = SpotifyApi.libraryVariables(50)
        assertEquals("true", library["flatten"]!!.jsonPrimitive.content)
        assertEquals("[\"Playlists\"]", library["filters"].toString())
        assertEquals("null", library["order"].toString())
    }
}
