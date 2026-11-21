package com.metrolist.music.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyLoginTest {
    @Test
    fun `the sign-in may visit Spotify and the identity providers it hands off to, and nothing else`() {
        for (host in listOf("accounts.spotify.com", "open.spotify.com", "spotify.com", "accounts.google.com", "appleid.apple.com", "www.facebook.com", "i.scdn.co")) {
            assertTrue(host, isLoginHost("https", host))
        }
        for (host in listOf("evil.example", "spotify.com.evil.example", "notspotify.com", "google.com.evil.example", "")) {
            assertFalse(host, isLoginHost("https", host))
        }
        assertFalse("a plain http page is never part of the sign-in", isLoginHost("http", "accounts.spotify.com"))
        assertFalse(isLoginHost("intent", "accounts.spotify.com"))
        assertFalse(isLoginHost("https", null))
        assertFalse(isLoginHost(null, "accounts.spotify.com"))
        assertTrue(isLoginHost("https", "ACCOUNTS.SPOTIFY.COM"))
    }

    @Test
    fun `the landing page is the web player, judged by its parsed host`() {
        assertTrue(isSpotifyLanding("https://open.spotify.com/"))
        assertTrue(isSpotifyLanding("https://open.spotify.com/collection/tracks?x=1"))
        assertTrue(isSpotifyLanding("https://OPEN.spotify.com/"))
        // starts like it, is not it
        assertFalse(isSpotifyLanding("https://open.spotify.com.example.com/"))
        assertFalse(isSpotifyLanding("https://open.spotify.com@evil.example/"))
        assertFalse(isSpotifyLanding("https://evilopen.spotify.com/"))
        assertFalse(isSpotifyLanding("https://evil.example/?next=https://open.spotify.com/"))
        assertFalse(isSpotifyLanding("https://accounts.google.com/o/oauth2?redirect=https://open.spotify.com"))
        // the sign-in page itself is not the landing page: a jar may still hold the cookie of an expired session
        assertFalse(isSpotifyLanding("https://accounts.spotify.com/login?continue=x"))
        assertFalse(isSpotifyLanding("http://open.spotify.com/"))
        assertFalse(isSpotifyLanding(null))
        assertFalse(isSpotifyLanding(""))
        assertFalse(isSpotifyLanding("not a url"))
    }

    @Test
    fun `sp_dc is found in a cookie header`() {
        assertEquals("AQBxyz", spDcFrom("sp_t=1; sp_dc=AQBxyz; sp_key=k"))
        assertEquals("AQB=with=equals", spDcFrom("a=1; sp_dc=AQB=with=equals"))
        assertNull(spDcFrom("sp_t=1; sp_key=k"))
        assertNull(spDcFrom("sp_dc="))
        assertNull(spDcFrom("sp_dc=   "))
        assertNull(spDcFrom(""))
        assertNull(spDcFrom(null))
        // another cookie that merely ends in sp_dc is not it
        assertNull(spDcFrom("other_sp_dc=1"))
    }

    @Test
    fun `the user agent is the device's Chrome without the web view markers`() {
        val webView =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UP1A.231105.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Version/4.0 Chrome/124.0.6367.82 Mobile Safari/537.36"
        val ua = browserUserAgent(webView)
        assertFalse(ua.contains("; wv"))
        assertFalse(ua.contains("Version/4.0"))
        assertTrue(ua.contains("Chrome/124.0.6367.82"))
        assertFalse(ua.contains("  "))
        // nothing usable: the fallback, which still looks like Chrome
        assertTrue(browserUserAgent(null).contains("Chrome/"))
        assertTrue(browserUserAgent("Dalvik/2.1.0").contains("Chrome/"))
    }

    @Test
    fun `requests with a credential may only go to spotify dot com`() {
        val auth = mapOf("Authorization" to "Bearer x")
        assertTrue(isRequestAllowed("https://api-partner.spotify.com/pathfinder/v2/query", auth))
        assertTrue(isRequestAllowed("https://clienttoken.spotify.com/v1/clienttoken", mapOf("Client-Token" to "t")))
        assertFalse(isRequestAllowed("https://evil.example/x", auth))
        assertFalse(isRequestAllowed("https://spotify.com.evil.example/x", auth))
        assertFalse(isRequestAllowed("https://open.spotifycdn.com/x.js", auth))
        assertFalse(isRequestAllowed("https://open.spotifycdn.com/x.js", mapOf("cookie" to "sp_dc=1")))
        // without a credential the web player's own script host is fine
        assertTrue(isRequestAllowed("https://open.spotifycdn.com/cdn/build/web-player/a.js", emptyMap()))
        assertTrue(isRequestAllowed("https://open.spotify.com/", mapOf("User-Agent" to "x")))
        assertFalse(isRequestAllowed("https://evil.example/", emptyMap()))
        assertFalse(isRequestAllowed("http://open.spotify.com/", emptyMap()))
        assertFalse(isRequestAllowed("not a url", emptyMap()))
    }
}
