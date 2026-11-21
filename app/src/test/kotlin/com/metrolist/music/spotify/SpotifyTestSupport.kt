package com.metrolist.music.spotify

import java.io.IOException
import java.util.Base64

/** A transport that records every call and answers from [handler]. */
class FakeSpotifyTransport(
    var handler: (Call) -> SpotifyHttpResult = { SpotifyHttpResult(404, "") },
) : SpotifyTransport {
    class Call(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String?,
        val contentType: String?,
    )

    val calls = ArrayList<Call>()

    override suspend fun get(url: String, headers: Map<String, String>): SpotifyHttpResult {
        val call = Call("GET", url, headers, null, null)
        calls += call
        return handler(call)
    }

    override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray, contentType: String): SpotifyHttpResult {
        val call = Call("POST", url, headers, String(body, Charsets.UTF_8), contentType)
        calls += call
        return handler(call)
    }

    fun posts(): List<Call> = calls.filter { it.method == "POST" }

    fun gets(): List<Call> = calls.filter { it.method == "GET" }

    companion object {
        fun failing(): FakeSpotifyTransport = FakeSpotifyTransport { throw IOException("offline") }
    }
}

/** 64 hex characters that differ per [n]. */
fun testHash(n: Int): String = "%064x".format(n)

fun ok200(body: String) = SpotifyHttpResult(200, body)

/** The web player's front page with the given script urls and client version. */
fun testWebPlayerHtml(scripts: List<String>, clientVersion: String = "1.3.6.1892.g0a8ca577db87"): String {
    val config = Base64.getEncoder().encodeToString("""{"clientVersion":"$clientVersion","other":1}""".toByteArray())
    return buildString {
        append("<html><head>")
        append("""<script id="appServerConfig" type="text/plain">$config</script>""")
        scripts.forEach { append("""<script src="$it" defer></script>""") }
        append("</head><body></body></html>")
    }
}

const val TEST_MAIN_BUNDLE = "https://open.spotifycdn.com/cdn/build/web-player/web-player.c6932d85.js"
const val TEST_ROUTES_CHUNK = "https://open.spotifycdn.com/cdn/build/web-player/xpui-routes-search.0123abcd.js"

/** A piece of the player's JavaScript that declares operations the way the bundle does. */
fun testBundleDeclaring(vararg operations: Pair<String, String>): String =
    "var a=1;" + operations.joinToString(";") { (name, hash) -> """o=("$name","query","$hash",null)""" } + ";var b=2;"
