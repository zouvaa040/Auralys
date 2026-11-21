package com.metrolist.music.spotify

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SpotifyTokenMinterTest {
    private var clock = 1_000_000L
    private var cookie: String? = "COOKIE-1"
    private val harvests = AtomicInteger(0)
    private val wipes = ArrayList<Boolean>()
    private val results = ArrayDeque<HarvestResult>()

    private fun token(n: Int, lifetimeMs: Long = 3_600_000L, clientId: String? = "client-id") =
        HarvestResult.Token(MintedToken("access-$n", clock + lifetimeMs, clientId))

    private val clientTokenAnswer =
        """{"response_type":"RESPONSE_GRANTED_TOKEN_RESPONSE","granted_token":{"token":"client-tok","expires_after_seconds":3600}}"""

    private fun transport(clientTokenBody: String? = clientTokenAnswer) =
        FakeSpotifyTransport { call ->
            when {
                call.method == "GET" -> ok200(testWebPlayerHtml(emptyList()))
                call.url == SpotifyTokenMinter.CLIENT_TOKEN_URL -> if (clientTokenBody != null) ok200(clientTokenBody) else SpotifyHttpResult(400, "")
                else -> SpotifyHttpResult(404, "")
            }
        }

    private fun minter(
        t: FakeSpotifyTransport = transport(),
        delayMs: Long = 0,
        clientTokenTimeoutMs: Long = SpotifyTokenMinter.CLIENT_TOKEN_TIMEOUT_MS,
    ): SpotifyTokenMinter =
        SpotifyTokenMinter(
            cookie = { cookie },
            harvester =
                SpotifyTokenHarvester { _, wipeAll ->
                    harvests.incrementAndGet()
                    synchronized(wipes) { wipes += wipeAll }
                    if (delayMs > 0) delay(delayMs)
                    results.removeFirstOrNull() ?: HarvestResult.Failed
                },
            webPlayer = SpotifyWebPlayer(t) { clock },
            transport = t,
            osVersion = { "14" },
            now = { clock },
            clientTokenTimeoutMs = clientTokenTimeoutMs,
        )

    /** Waits (really) until [check] is true: a mint runs on its own thread. */
    private suspend fun until(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            delay(5)
        }
    }

    // ------------------------------------------------------------ minting and caching

    @Test
    fun `a signed in account gets the bearer and the client token`() = runBlocking {
        results += token(1)
        val tokens = minter().tokens()!!
        assertEquals("access-1", tokens.bearer)
        assertEquals("client-tok", tokens.clientToken)
    }

    @Test
    fun `without a cookie there is no token and no web view is started`() = runBlocking {
        cookie = null
        assertNull(minter().tokens())
        cookie = "   "
        assertNull(minter().tokens())
        assertEquals(0, harvests.get())
    }

    @Test
    fun `the token is reused until 30 seconds before it expires`() = runBlocking {
        results += token(1, lifetimeMs = 3_600_000L)
        results += token(2)
        val m = minter()
        assertEquals("access-1", m.tokens()!!.bearer)
        clock += 3_600_000L - 31_000L
        assertEquals("access-1", m.tokens()!!.bearer)
        assertEquals(1, harvests.get())
        clock += 2_000L
        assertEquals("access-2", m.tokens()!!.bearer)
        assertEquals(2, harvests.get())
    }

    @Test
    fun `callers at the same time share one mint`() = runBlocking {
        results += token(1)
        val m = minter(delayMs = 30)
        val all = (1..8).map { async { m.tokens()!!.bearer } }.awaitAll()
        assertEquals(List(8) { "access-1" }, all)
        assertEquals(1, harvests.get())
    }

    @Test
    fun `invalidate makes the next call mint again`() = runBlocking {
        results += token(1)
        results += token(2)
        val m = minter()
        assertEquals("access-1", m.tokens()!!.bearer)
        m.invalidate()
        assertEquals("access-2", m.tokens()!!.bearer)
    }

    @Test
    fun `another sign-in never uses the token of the old account`() = runBlocking {
        results += token(1)
        results += token(2)
        val m = minter()
        assertEquals("access-1", m.tokens()!!.bearer)
        cookie = "COOKIE-2"
        assertEquals("access-2", m.tokens()!!.bearer)
        assertEquals(2, harvests.get())
    }

    // ------------------------------------------------------------ failures

    @Test
    fun `a failed attempt is tried again once with all web storage wiped`() = runBlocking {
        results += HarvestResult.Failed
        results += token(1)
        val m = minter()
        assertEquals("access-1", m.tokens()!!.bearer)
        assertEquals(listOf(false, true), wipes)
        assertNull(m.lastFailure)
    }

    @Test
    fun `two failed attempts give nothing and say so`() = runBlocking {
        results += HarvestResult.Failed
        results += HarvestResult.Failed
        val m = minter()
        assertNull(m.tokens())
        assertEquals(SpotifyMintFailure.FAILED, m.lastFailure)
        assertEquals(2, harvests.get())
    }

    @Test
    fun `a cookie Spotify does not accept ends the mint at once`() = runBlocking {
        results += HarvestResult.Expired
        val m = minter()
        assertNull(m.tokens())
        assertEquals(SpotifyMintFailure.EXPIRED, m.lastFailure)
        assertEquals(1, harvests.get())
    }

    @Test
    fun `no connection ends the mint at once and a later success clears the failure`() = runBlocking {
        results += HarvestResult.Offline
        results += token(1)
        val m = minter()
        assertNull(m.tokens())
        assertEquals(SpotifyMintFailure.OFFLINE, m.lastFailure)
        assertEquals("access-1", m.tokens()!!.bearer)
        assertNull(m.lastFailure)
    }

    // ------------------------------------------------------------ the client token

    @Test
    fun `the client token request is the one the web player sends, with the bare content type`() = runBlocking {
        results += token(1)
        val t = transport()
        minter(t).tokens()
        val post = t.posts().single()
        assertEquals(SpotifyTokenMinter.CLIENT_TOKEN_URL, post.url)
        assertEquals("application/json", post.contentType)
        val data = Json.parseToJsonElement(post.body!!).jsonObject["client_data"]!!.jsonObject
        assertEquals("1.3.6.1892.g0a8ca577db87", data["client_version"]!!.jsonPrimitive.content)
        assertEquals("client-id", data["client_id"]!!.jsonPrimitive.content)
        val sdk = data["js_sdk_data"]!!.jsonObject
        assertEquals("android", sdk["os"]!!.jsonPrimitive.content)
        assertEquals("14", sdk["os_version"]!!.jsonPrimitive.content)
        assertTrue(sdk["device_id"]!!.jsonPrimitive.content.isNotBlank())
        // the bearer is not sent to the client token endpoint
        assertTrue(post.headers.keys.none { it.equals("Authorization", ignoreCase = true) })
    }

    @Test
    fun `a refused client token still gives the bearer and is not asked for again at once`() = runBlocking {
        results += token(1)
        val t = transport(clientTokenBody = null)
        val m = minter(t)
        val first = m.tokens()!!
        assertEquals("access-1", first.bearer)
        assertNull(first.clientToken)
        m.tokens()
        m.tokens()
        assertEquals(1, t.posts().size)
        // after the pause it is asked for again
        clock += 6 * 60 * 1000L
        assertNull(m.tokens()!!.clientToken)
        assertEquals(2, t.posts().size)
        assertEquals(1, harvests.get())
    }

    @Test
    fun `no client id means no client token request`() = runBlocking {
        results += token(1, clientId = null)
        val t = transport()
        assertNull(minter(t).tokens()!!.clientToken)
        assertTrue(t.posts().isEmpty())
    }

    @Test
    fun `an unreachable client token endpoint does not fail the mint`() = runBlocking {
        results += token(1)
        val m = minter(FakeSpotifyTransport.failing())
        assertEquals("access-1", m.tokens()!!.bearer)
    }

    // ------------------------------------------------------------ pure readers

    @Test
    fun `the page's token answer is read and an anonymous one is skipped`() {
        val now = 5_000L
        val good = """{"accessToken":"abc","clientId":"cid","accessTokenExpirationTimestampMs":9999999,"isAnonymous":false}"""
        val token = SpotifyTokenMinter.parseTokenPayload(good, now)!!
        assertEquals("abc", token.accessToken)
        assertEquals("cid", token.clientId)
        assertEquals(9_999_999L, token.expiresAtMs)

        val anonymous = """{"accessToken":"abc","isAnonymous":true}"""
        assertNull(SpotifyTokenMinter.parseTokenPayload(anonymous, now))
        assertTrue(SpotifyTokenMinter.isAnonymousPayload(anonymous))
        assertTrue(!SpotifyTokenMinter.isAnonymousPayload(good))

        // no expiry, or one in the past: an hour from now
        assertEquals(now + 3_600_000L, SpotifyTokenMinter.parseTokenPayload("""{"accessToken":"abc","clientId":"cid"}""", now)!!.expiresAtMs)
        assertEquals(
            now + 3_600_000L,
            SpotifyTokenMinter.parseTokenPayload("""{"accessToken":"abc","clientId":"cid","accessTokenExpirationTimestampMs":1}""", now)!!.expiresAtMs,
        )

        assertNull(SpotifyTokenMinter.parseTokenPayload("", now))
        assertNull(SpotifyTokenMinter.parseTokenPayload("not json", now))
        assertNull(SpotifyTokenMinter.parseTokenPayload("""{"accessToken":"","clientId":"cid"}""", now))
        assertNull(SpotifyTokenMinter.parseTokenPayload("""{"other":1}""", now))
        assertNull(SpotifyTokenMinter.parseTokenPayload(null, now))
    }

    @Test
    fun `a token answer needs both the access token and the client id`() {
        val now = 5_000L
        assertNull(SpotifyTokenMinter.parseTokenPayload("""{"accessToken":"abc"}""", now))
        assertNull(SpotifyTokenMinter.parseTokenPayload("""{"accessToken":"abc","clientId":""}""", now))
        assertNull(SpotifyTokenMinter.parseTokenPayload("""{"clientId":"cid"}""", now))
        assertEquals("cid", SpotifyTokenMinter.parseTokenPayload("""{"accessToken":"abc","clientId":"cid"}""", now)!!.clientId)
    }

    // ------------------------------------------------------------ the cookie written into the web view

    @Test
    fun `the cookie is written into the jar as HttpOnly like Spotify's own`() {
        val written = WebViewSpotifyTokenHarvester.spDcCookie("AQBabc")
        assertTrue(written, written.startsWith("sp_dc=AQBabc;"))
        assertTrue(written, written.contains("; HttpOnly"))
        assertTrue(written, written.contains("; Secure"))
        assertTrue(written, written.contains("Domain=.spotify.com"))
        assertTrue(written, written.contains("Path=/"))
    }

    @Test
    fun `only a page that finished with an anonymous token proves the cookie expired, a slow page does not`() {
        assertEquals(HarvestResult.Expired, WebViewSpotifyTokenHarvester.endedWithoutToken(sawAnonymous = true, pageFinished = true))
        assertEquals(HarvestResult.Failed, WebViewSpotifyTokenHarvester.endedWithoutToken(sawAnonymous = true, pageFinished = false))
        assertEquals(HarvestResult.Failed, WebViewSpotifyTokenHarvester.endedWithoutToken(sawAnonymous = false, pageFinished = true))
        assertEquals(HarvestResult.Failed, WebViewSpotifyTokenHarvester.endedWithoutToken(sawAnonymous = false, pageFinished = false))
    }

    // ------------------------------------------------------------ a mint outlives the caller that started it

    @Test
    fun `a caller that gives up does not stop the mint and the next caller finds the token`() = runBlocking {
        results += token(1)
        val m = minter(delayMs = 300)
        // like a lookup with a time budget: it waits 50 ms and leaves
        val first = withTimeoutOrNull(50) { m.tokens() }
        assertNull(first)
        assertTrue("the mint is still running", m.isMinting)
        until { !m.isMinting }
        // the token is warm: no second harvest
        assertEquals("access-1", m.tokens()!!.bearer)
        assertEquals(1, harvests.get())
    }

    @Test
    fun `callers that come while a mint runs share it`() = runBlocking {
        results += token(1)
        val m = minter(delayMs = 200)
        val a = async { m.tokens()?.bearer }
        until { m.isMinting }
        val b = async { m.tokens()?.bearer }
        assertEquals("access-1", a.await())
        assertEquals("access-1", b.await())
        assertEquals(1, harvests.get())
    }

    @Test
    fun `reset stops a mint in progress and its waiters get nothing`() = runBlocking {
        results += token(1)
        val m = minter(delayMs = 5_000)
        val waiter = async { m.tokens() }
        until { m.isMinting }
        m.reset()
        assertNull(waiter.await())
        until { !m.isMinting }
        assertFalse(m.isMinting)
        assertEquals(1, harvests.get())
    }

    @Test
    fun `a token that arrives after the account was disconnected is thrown away`() = runBlocking {
        results += token(1)
        results += token(2)
        val m = minter(delayMs = 150)
        val waiter = async { m.tokens() }
        until { m.isMinting }
        // signed out while the page was still loading
        cookie = null
        assertNull(waiter.await())
        until { !m.isMinting }
        cookie = "COOKIE-1"
        // the first token was not kept: the next call mints again
        assertEquals("access-2", m.tokens()!!.bearer)
        assertEquals(2, harvests.get())
    }

    // ------------------------------------------------------------ a failed mint is remembered for a minute

    @Test
    fun `after a failed mint the same cookie is not tried again for a minute`() = runBlocking {
        results += HarvestResult.Failed
        results += HarvestResult.Failed
        results += token(1)
        val m = minter()
        assertNull(m.tokens())
        assertEquals(2, harvests.get())
        // no call starts another 20 second mint now
        repeat(5) { assertNull(m.tokens()) }
        assertEquals(2, harvests.get())
        clock += SpotifyTokenMinter.NEGATIVE_CACHE_MS + 1
        assertEquals("access-1", m.tokens()!!.bearer)
        assertEquals(3, harvests.get())
    }

    @Test
    fun `an expired cookie is remembered too, and pressing try again forgets it`() = runBlocking {
        results += HarvestResult.Expired
        results += token(1)
        val m = minter()
        assertNull(m.tokens())
        assertNull(m.tokens())
        assertEquals(1, harvests.get())
        assertEquals(SpotifyMintFailure.EXPIRED, m.lastFailure)
        m.forgetFailure()
        assertEquals("access-1", m.tokens()!!.bearer)
        assertEquals(2, harvests.get())
    }

    @Test
    fun `a new cookie is not held back by the failure of the old one`() = runBlocking {
        results += HarvestResult.Expired
        results += token(2)
        val m = minter()
        assertNull(m.tokens())
        cookie = "COOKIE-2"
        assertEquals("access-2", m.tokens()!!.bearer)
    }

    // ------------------------------------------------------------ the client token has its own short budget

    @Test
    fun `a client token endpoint that never answers costs the bearer nothing but its own budget`() = runBlocking {
        results += token(1)
        val never = CompletableDeferred<SpotifyHttpResult>()
        val t =
            FakeSpotifyTransport { call ->
                if (call.method == "GET") ok200(testWebPlayerHtml(emptyList())) else throw IllegalStateException("posts are awaited below")
            }
        val hanging =
            object : SpotifyTransport {
                override suspend fun get(url: String, headers: Map<String, String>) = t.get(url, headers)

                override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray, contentType: String): SpotifyHttpResult =
                    never.await()
            }
        val m =
            SpotifyTokenMinter(
                cookie = { cookie },
                harvester = SpotifyTokenHarvester { _, _ -> results.removeFirst() },
                webPlayer = SpotifyWebPlayer(t) { clock },
                transport = hanging,
                now = { clock },
                clientTokenTimeoutMs = 150,
            )
        val started = System.currentTimeMillis()
        val tokens = m.tokens()!!
        assertEquals("access-1", tokens.bearer)
        assertNull(tokens.clientToken)
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 3_000)
    }

    @Test
    fun `the client token answer is read only when granted`() {
        assertEquals("client-tok" to 3600L, SpotifyTokenMinter.parseClientTokenAnswer(clientTokenAnswer))
        assertEquals(
            "t" to 3600L,
            SpotifyTokenMinter.parseClientTokenAnswer("""{"response_type":"RESPONSE_GRANTED_TOKEN_RESPONSE","granted_token":{"token":"t"}}"""),
        )
        assertNull(SpotifyTokenMinter.parseClientTokenAnswer("""{"response_type":"RESPONSE_CHALLENGES_RESPONSE"}"""))
        assertNull(SpotifyTokenMinter.parseClientTokenAnswer("""{"response_type":"RESPONSE_GRANTED_TOKEN_RESPONSE"}"""))
        assertNull(SpotifyTokenMinter.parseClientTokenAnswer("nope"))
    }

    @Test
    fun `the sign-in page is recognised`() {
        assertTrue(WebViewSpotifyTokenHarvester.isSignInPage("https://accounts.spotify.com/en/login?continue=x"))
        assertTrue(WebViewSpotifyTokenHarvester.isSignInPage("https://accounts.spotify.com/login"))
        assertTrue(!WebViewSpotifyTokenHarvester.isSignInPage("https://open.spotify.com/"))
        assertTrue(!WebViewSpotifyTokenHarvester.isSignInPage("https://accounts.spotify.com/status"))
    }
}
