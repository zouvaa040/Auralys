package com.metrolist.music.spotify

import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which OkHttp client carries a request: anything that can carry a credential must not follow a redirect. */
class SpotifyTransportTest {
    @Test
    fun `the client for authenticated calls follows no redirect, neither to another host nor from https to http`() {
        val auth = OkHttpSpotifyTransport.defaultAuthClient
        assertFalse(auth.followRedirects)
        assertFalse(auth.followSslRedirects)
    }

    @Test
    fun `the plain client for the web player's scripts may follow redirects`() {
        assertTrue(OkHttpSpotifyTransport.defaultClient.followRedirects)
    }

    @Test
    fun `both clients keep the same timeouts`() {
        assertTrue(OkHttpSpotifyTransport.defaultAuthClient.callTimeoutMillis == OkHttpSpotifyTransport.defaultClient.callTimeoutMillis)
        assertTrue(OkHttpSpotifyTransport.defaultAuthClient.readTimeoutMillis == OkHttpSpotifyTransport.defaultClient.readTimeoutMillis)
    }

    @Test
    fun `every POST and every call with a token or cookie uses the no-redirect client`() {
        val plain = OkHttpClient()
        val auth = OkHttpClient.Builder().followRedirects(false).build()
        val t = OkHttpSpotifyTransport(plain, auth)
        // POSTs: the Pathfinder queries and the client-token request
        assertSame(auth, t.clientFor(isPost = true, emptyMap()))
        assertSame(auth, t.clientFor(isPost = true, mapOf("Authorization" to "Bearer x")))
        // a GET that carries a credential header, in any spelling
        assertSame(auth, t.clientFor(isPost = false, mapOf("Authorization" to "Bearer x")))
        assertSame(auth, t.clientFor(isPost = false, mapOf("client-token" to "t")))
        assertSame(auth, t.clientFor(isPost = false, mapOf("COOKIE" to "sp_dc=1")))
        // the web player's page and scripts carry nothing
        assertSame(plain, t.clientFor(isPost = false, mapOf("User-Agent" to "x", "Accept" to "text/html")))
        assertSame(plain, t.clientFor(isPost = false, emptyMap()))
    }
}
