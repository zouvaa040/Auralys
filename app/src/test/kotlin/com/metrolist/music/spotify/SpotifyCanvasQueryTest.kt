/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.spotify

import com.metrolist.music.spotify.SpotifyCanvasQuery.CanvasAnswer
import com.metrolist.music.spotify.SpotifyCanvasQuery.SearchAnswer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parsers and request bodies, on the real answers BitChord captured from Spotify's web player (spotify-canvas/). */
class SpotifyCanvasQueryTest {
    @Test
    fun `the real search answer gives its ten tracks with uri, title, artists, album and length`() {
        val hits = SpotifyFixtures.letDownSearch
        assertEquals(10, hits.size)
        val first = hits.first()
        assertEquals("spotify:track:2fuYa3Lx06QQJAm0MjztKr", first.uri)
        assertEquals("Let Down", first.title)
        assertEquals(listOf("Radiohead"), first.artists)
        assertEquals("OK Computer", first.album)
        assertEquals(299, first.durationSec) // 299560 ms
        // a track with several credited artists keeps every name
        assertEquals(listOf("Eibell", "Odd Era.", "fedo DJ"), hits.first { it.title == "Let Down - Choir" }.artists)
        assertTrue(hits.all { SpotifyCanvasMatch.isTrackUri(it.uri) })
    }

    @Test
    fun `a retired hash is recognised in the error answer, in any case`() {
        val body = """{"errors":[{"message":"PersistedQueryNotFound"}]}"""
        val search = SpotifyCanvasQuery.parseSearch(body) as SearchAnswer.Failed
        assertTrue(search.staleHash)
        val canvas = SpotifyCanvasQuery.parseCanvas("""{"errors":[{"message":"persistedquerynotfound: x"}],"data":null}""") as CanvasAnswer.Failed
        assertTrue(canvas.staleHash)
    }

    @Test
    fun `answers that are not the known shape fail instead of reading as no result`() {
        assertTrue(SpotifyCanvasQuery.parseSearch("<html>") is SearchAnswer.Failed)
        assertTrue(SpotifyCanvasQuery.parseSearch("""{"data":{"searchV2":{}}}""") is SearchAnswer.Failed)
        assertTrue(SpotifyCanvasQuery.parseSearch("""{"data":{}}""") is SearchAnswer.Failed)
        assertTrue(SpotifyCanvasQuery.parseCanvas("""{"data":{"other":1}}""") is CanvasAnswer.Failed)
        assertFalse((SpotifyCanvasQuery.parseSearch("""{"data":{"searchV2":{"tracksV2":{"items":[]}}}}""") as SearchAnswer.Tracks).hits.isNotEmpty())
    }

    @Test
    fun `non track rows and rows without a name are skipped`() {
        val body = """
            {"data":{"searchV2":{"tracksV2":{"items":[
              {"item":{"data":{"__typename":"Episode","uri":"spotify:episode:abc","name":"Talk"}}},
              {"item":{"data":{"__typename":"Track","uri":"spotify:track:AAA","name":" "}}},
              {"item":{"data":{"__typename":"Track","id":"BBB","name":"Song","artists":{"items":[{"profile":{"name":"Band"}}]},"trackDuration":{"totalMilliseconds":181000}}}}
            ]}}}}
        """.trimIndent()
        val hits = (SpotifyCanvasQuery.parseSearch(body) as SearchAnswer.Tracks).hits
        assertEquals(1, hits.size)
        assertEquals("spotify:track:BBB", hits.single().uri)
        assertEquals(181, hits.single().durationSec)
    }

    @Test
    fun `the anonymous canvas answer (canvas null) is a real no`() {
        val answer = SpotifyCanvasQuery.parseCanvas(SpotifyFixtures.text("canvas-anonymous.json"))
        assertTrue(answer is CanvasAnswer.NoCanvas)
    }

    @Test
    fun `a canvas with a plain https mp4 is found`() {
        val answer = SpotifyCanvasQuery.parseCanvas(SpotifyFixtures.text("canvas-found-example.json"))
        answer as CanvasAnswer.Found
        assertEquals("https://canvaz.scdn.co/upload/artist/example-clip.cnvs.mp4", answer.url)
        assertEquals("VIDEO_LOOPING", answer.type)
    }

    @Test
    fun `only a plain https mp4 is taken, images, http and file id only answers are skipped`() {
        fun canvas(inner: String) = """{"data":{"trackUnion":{"canvas":$inner}}}"""
        assertTrue(SpotifyCanvasQuery.parseCanvas(canvas("""{"type":"IMAGE","url":"https://i.scdn.co/image/abc.jpg"}""")) is CanvasAnswer.NoCanvas)
        assertTrue(SpotifyCanvasQuery.parseCanvas(canvas("""{"type":"VIDEO","url":"http://canvaz.scdn.co/a.mp4"}""")) is CanvasAnswer.NoCanvas)
        assertTrue(SpotifyCanvasQuery.parseCanvas(canvas("""{"type":"VIDEO","fileId":"abc"}""")) is CanvasAnswer.NoCanvas)
        assertTrue(SpotifyCanvasQuery.parseCanvas(canvas("""{"type":"VIDEO","url":null}""")) is CanvasAnswer.NoCanvas)
        assertTrue(SpotifyCanvasQuery.parseCanvas(canvas("""{"type":"VIDEO","url":"https://canvaz.scdn.co/a.m3u8"}""")) is CanvasAnswer.NoCanvas)
        assertTrue(SpotifyCanvasQuery.parseCanvas(canvas("""{"type":"VIDEO","url":"https://canvaz.scdn.co/a.mp4?token=1"}""")) is CanvasAnswer.Found)
    }

    @Test
    fun `request bodies carry the operation, the persisted query hash and the variables`() {
        val search = Json.parseToJsonElement(SpotifyCanvasQuery.searchBody("let down radiohead", "h".repeat(64))).jsonObject
        assertEquals("searchTracks", search["operationName"]!!.jsonPrimitive.content)
        assertEquals("let down radiohead", search["variables"]!!.jsonObject["searchTerm"]!!.jsonPrimitive.content)
        assertEquals("h".repeat(64), search["extensions"]!!.jsonObject["persistedQuery"]!!.jsonObject["sha256Hash"]!!.jsonPrimitive.content)
        val canvas = Json.parseToJsonElement(SpotifyCanvasQuery.canvasBody("spotify:track:ABC", "c".repeat(64))).jsonObject
        assertEquals("canvas", canvas["operationName"]!!.jsonPrimitive.content)
        assertEquals("spotify:track:ABC", canvas["variables"]!!.jsonObject["trackUri"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the constants are the hashes the captured web player declared`() {
        val hashes = Json.parseToJsonElement(SpotifyFixtures.text("web-player.json")).jsonObject["hashes"]!!.jsonObject
        assertEquals(hashes["searchTracks"]!!.jsonPrimitive.content, SpotifyCanvasQuery.KNOWN_SEARCH_HASH)
        assertEquals(hashes["canvas"]!!.jsonPrimitive.content, SpotifyCanvasQuery.KNOWN_CANVAS_HASH)
        assertTrue(SpotifyCanvasQuery.KNOWN_HASHES.values.all(SpotifyCanvasQuery::isHash))
    }

    @Test
    fun `an operation's hash is read out of a script declaration`() {
        val hash = "a".repeat(63) + "b"
        val script = """x=[...];n("other","query","${"0".repeat(64)}"),n( 'canvas' , 'query' , '$hash' ),y"""
        assertEquals(hash, SpotifyCanvasQuery.findQueryHash(script, "canvas"))
        assertEquals("0".repeat(64), SpotifyCanvasQuery.findQueryHash(script, "other"))
        assertNull(SpotifyCanvasQuery.findQueryHash(script, "searchTracks"))
        assertNull(SpotifyCanvasQuery.findQueryHash("\"canvas\",\"query\",\"short\"", "canvas"))
    }

    @Test
    fun `the web player bundle and the search chunk are found through the page and the chunk table`() {
        val html = """<script src="https://open.spotifycdn.com/cdn/build/web-player/web-player.c6932d85.js" defer></script>"""
        assertEquals("https://open.spotifycdn.com/cdn/build/web-player/web-player.c6932d85.js", SpotifyCanvasQuery.bundleUrl(html))
        assertNull(SpotifyCanvasQuery.bundleUrl("""<script src="https://evil.example/web-player.c6932d85.js">"""))

        val bundle = """a={11:"xpui-routes-album",4521:"xpui-routes-search"};b={11:"11112222",4521:"deadbeef",94521:"cafebabe"}"""
        assertEquals(
            listOf("https://open.spotifycdn.com/cdn/build/web-player/xpui-routes-search.deadbeef.js"),
            SpotifyCanvasQuery.searchChunkUrls(bundle),
        )
        assertTrue(SpotifyCanvasQuery.searchChunkUrls("no table here").isEmpty())
    }
}
