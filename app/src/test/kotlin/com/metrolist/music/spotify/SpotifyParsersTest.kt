package com.metrolist.music.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Pathfinder answer readers. The playlist answer is a real capture (BitChord's fixture fetch-playlist.json, "This Is Kanye
 * West", 25 of 50 tracks); the library, Liked Songs and profile answers are small synthetic ones with the shapes BitChord's
 * parsers read.
 */
class SpotifyParsersTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/spotify/$name")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    // ------------------------------------------------------------ a real playlist answer

    @Test
    fun `a real playlist page gives its header and every track`() {
        val (header, page) = parseTrackPage(obj(resource("fetch-playlist.json")))
        assertEquals("This Is Kanye West", header.name)
        assertEquals("Spotify", header.owner)
        assertEquals(50, header.total)
        assertEquals(50, page.total)
        assertEquals(25, page.raw)
        assertEquals(25, page.items.size)
        // the cover has no width in this capture: the one url is used
        assertEquals("https://pickasso.spotifycdn.com/image/ab67c0de0000deef/dt/v1/img/thisisv3/5K4W6rqBFWDnAN6FQUkS6x/en", header.imageUrl)
    }

    @Test
    fun `the first real track is read in full`() {
        val track = parseTrackPage(obj(resource("fetch-playlist.json"))).second.items.first()
        assertEquals("5TRPicyLGbAF2LGBFbHGvO", track.id)
        assertEquals("Flashing Lights", track.title)
        assertEquals(listOf("Kanye West", "Dwele"), track.artists)
        assertEquals("Graduation", track.album)
        // a playlist answer calls the length trackDuration
        assertEquals(237506L, track.durationMs)
        // sources of 300, 64 and 640 px in no fixed order: the smallest one that is at least 300 wide
        assertEquals("https://i.scdn.co/image/ab67616d00001e0226f7f19c7f0381e56156c94a", track.imageUrl)
    }

    @Test
    fun `every real track has a title, an artist and a length`() {
        val items = parseTrackPage(obj(resource("fetch-playlist.json"))).second.items
        assertTrue(items.all { it.title.isNotBlank() && it.artists.isNotEmpty() && it.durationMs > 0 && it.id.length == 22 })
        assertEquals(items.size, items.map { it.id }.toSet().size)
    }

    // ------------------------------------------------------------ playlist page, edge cases

    private fun playlistPage(items: String, total: Int = 3) =
        obj(
            """{"data":{"playlistV2":{"__typename":"Playlist","name":" Mix ","ownerV2":{"data":{"name":"Rao"}},
            "images":{"items":[{"sources":[{"url":"https://i.scdn.co/a","width":64},{"url":"https://i.scdn.co/b","width":640}]}]},
            "content":{"totalCount":$total,"items":[$items]}}}}""",
        )

    private fun trackItem(uri: String, name: String, typename: String = "Track", extra: String = "") =
        """{"itemV2":{"data":{"__typename":"$typename","uri":"$uri","name":"$name","artists":{"items":[{"profile":{"name":"A"}}]},
        "albumOfTrack":{"name":"Al","coverArt":{"sources":[{"url":"https://i.scdn.co/c","width":300}]}},
        "trackDuration":{"totalMilliseconds":1000}$extra}}}"""

    @Test
    fun `podcast episodes, local files and rows without a name are left out`() {
        val answer =
            playlistPage(
                listOf(
                    trackItem("spotify:track:aaaaaaaaaaaaaaaaaaaaaa", "Song"),
                    trackItem("spotify:episode:bbbbbbbbbbbbbbbbbbbbbb", "Episode", typename = "Episode"),
                    trackItem("spotify:local:x:y:z:1", "Local file"),
                    trackItem("spotify:track:cccccccccccccccccccccc", "  "),
                    """{"itemV2":{"data":{"__typename":"NotFound"}}}""",
                    """{"itemV2":null}""",
                ).joinToString(","),
                total = 6,
            )
        val (header, page) = parseTrackPage(answer)
        assertEquals(listOf("Song"), page.items.map { it.title })
        assertEquals(6, page.raw)
        assertEquals("Mix", header.name)
        // the widest cover for the header
        assertEquals("https://i.scdn.co/b", header.imageUrl)
    }

    @Test
    fun `an older answer that calls the length duration is read too`() {
        val answer =
            playlistPage(
                """{"itemV2":{"data":{"uri":"spotify:track:aaaaaaaaaaaaaaaaaaaaaa","name":"Old","duration":{"totalMilliseconds":4242}}}}""",
                total = 1,
            )
        assertEquals(4242L, parseTrackPage(answer).second.items.single().durationMs)
    }

    @Test
    fun `a playlist that is not there is a plain error and an unknown shape means the query changed`() {
        try {
            parseTrackPage(obj("""{"data":{"playlistV2":{"__typename":"NotFound"}}}"""))
            fail()
        } catch (e: SpotifyException) {
            assertEquals(SpotifyException.Kind.OTHER, e.kind)
        }
        try {
            parseTrackPage(obj("""{"data":{}}"""))
            fail()
        } catch (e: SpotifyException) {
            assertEquals(SpotifyException.Kind.API_CHANGED, e.kind)
        }
    }

    // ------------------------------------------------------------ library

    @Test
    fun `the library keeps playlists and drops folders, episodes and unnamed ones`() {
        val answer =
            obj(
                """{"data":{"me":{"libraryV3":{"totalCount":4,"items":[
                {"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:37i9dQZF1DXcBWIGoYBM5M","data":{"__typename":"Playlist","name":"Today's Top Hits",
                 "ownerV2":{"data":{"name":"Spotify"}},"images":{"items":[{"sources":[{"url":"https://i.scdn.co/small","width":64},{"url":"https://i.scdn.co/big","width":640}]}]}}}},
                {"item":{"__typename":"PseudoPlaylistResponseWrapper","_uri":"spotify:collection:tracks","data":{"__typename":"PseudoPlaylist","name":"Liked Songs"}}},
                {"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:bbbbbbbbbbbbbbbbbbbbbb","data":{"__typename":"Playlist","name":""}}},
                {"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:cccccccccccccccccccccc","data":{"__typename":"Playlist","name":"Mine"}}}
                ]}}}}""",
            )
        val page = parsePlaylistPage(answer)
        assertEquals(4, page.raw)
        assertEquals(4, page.total)
        assertEquals(listOf("Today's Top Hits", "Mine"), page.items.map { it.name })
        val first = page.items.first()
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", first.id)
        assertEquals("Spotify", first.owner)
        assertEquals("https://i.scdn.co/big", first.imageUrl)
        assertNull(page.items.last().owner)
        assertNull(page.items.last().imageUrl)
    }

    @Test
    fun `an empty library is an empty page, a missing one means the query changed`() {
        val empty = parsePlaylistPage(obj("""{"data":{"me":{"libraryV3":{"totalCount":0,"items":[]}}}}"""))
        assertEquals(0, empty.raw)
        assertTrue(empty.items.isEmpty())
        try {
            parsePlaylistPage(obj("""{"data":{"me":null}}"""))
            fail()
        } catch (e: SpotifyException) {
            assertEquals(SpotifyException.Kind.API_CHANGED, e.kind)
        }
    }

    // ------------------------------------------------------------ Liked Songs

    @Test
    fun `liked songs are read from the track wrappers`() {
        val answer =
            obj(
                """{"data":{"me":{"library":{"tracks":{"totalCount":120,"items":[
                {"track":{"_uri":"spotify:track:dddddddddddddddddddddd","data":{"__typename":"Track","name":"Liked one",
                 "artists":{"items":[{"profile":{"name":"X"}},{"profile":{"name":"Y"}}]},
                 "albumOfTrack":{"name":"Rec","coverArt":{"sources":[{"url":"https://i.scdn.co/l","width":300}]}},
                 "trackDuration":{"totalMilliseconds":180000}}}},
                {"track":{"_uri":"spotify:track:eeeeeeeeeeeeeeeeeeeeee","data":null}}
                ]}}}}}""",
            )
        val page = parseLikedPage(answer)
        assertEquals(120, page.total)
        assertEquals(2, page.raw)
        val track = page.items.single()
        assertEquals("dddddddddddddddddddddd", track.id)
        assertEquals("Liked one", track.title)
        assertEquals(listOf("X", "Y"), track.artists)
        assertEquals("Rec", track.album)
        assertEquals(180000L, track.durationMs)
        assertEquals("https://i.scdn.co/l", track.imageUrl)
    }

    @Test
    fun `a liked songs answer without the library means the query changed`() {
        try {
            parseLikedPage(obj("""{"data":{"me":{"library":null}}}"""))
            fail()
        } catch (e: SpotifyException) {
            assertEquals(SpotifyException.Kind.API_CHANGED, e.kind)
        }
    }

    // ------------------------------------------------------------ profile and errors

    @Test
    fun `the profile gives a name and the avatar nearest to 64 px`() {
        val profile =
            parseProfile(
                obj("""{"data":{"me":{"profile":{"name":"Test User","username":"testuser","avatar":{"sources":[{"url":"https://i.scdn.co/p300","width":300},{"url":"https://i.scdn.co/p64","width":64}]}}}}}"""),
            )
        assertNotNull(profile)
        assertEquals("Test User", profile!!.name)
        assertEquals("https://i.scdn.co/p64", profile.avatarUrl)
        // no display name: the username
        assertEquals("testuser", parseProfile(obj("""{"data":{"me":{"profile":{"username":"testuser"}}}}"""))!!.name)
        assertNull(parseProfile(obj("""{"data":{"me":{"profile":{}}}}""")))
        assertNull(parseProfile(obj("""{"data":null}""")))
    }

    @Test
    fun `graphql errors are told apart from a stale hash`() {
        assertNull(graphQlProblem(obj("""{"data":{"a":1}}""")))
        val stale = graphQlProblem(obj("""{"errors":[{"message":"PersistedQueryNotFound"}]}"""))!!
        assertTrue(stale.stale)
        assertFalse(stale.hasData)
        val partial = graphQlProblem(obj("""{"data":{"a":1},"errors":[{"message":"one track failed"}]}"""))!!
        assertFalse(partial.stale)
        assertTrue(partial.hasData)
        assertEquals("one track failed", partial.message)
    }

    @Test
    fun `source choice never fails on odd input`() {
        assertNull(pickSource(null, 300))
        assertNull(pickSource(Json.parseToJsonElement("""[{"width":300},{"url":""}]""") as kotlinx.serialization.json.JsonArray, 300))
        // only smaller ones: the widest
        assertEquals("https://x/b", pickSource(Json.parseToJsonElement("""[{"url":"https://x/a","width":50},{"url":"https://x/b","width":200}]""") as kotlinx.serialization.json.JsonArray, 300))
    }
}
