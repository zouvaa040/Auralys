/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.spotify

import com.metrolist.music.spotify.SpotifyFixtures.hit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which Spotify result is the song that is playing: right track, wrong track, guests, remixes, other takes. */
class SpotifyCanvasMatchTest {
    private fun titles(
        candidates: List<SpotifyTrackHit>,
        title: String,
        artist: String,
        album: String? = null,
        sec: Int? = null,
        video: Boolean = false,
    ) = SpotifyCanvasMatch.rank(candidates, title, artist, album, sec, video).map { it.title }

    // The real answer for "Let Down Radiohead"

    @Test
    fun `Let Down by Radiohead on OK Computer takes that track first, then the same song on other releases`() {
        val ranked = SpotifyCanvasMatch.rank(SpotifyFixtures.letDownSearch, "Let Down", "Radiohead", "OK Computer", 299)
        assertEquals(
            listOf("spotify:track:2fuYa3Lx06QQJAm0MjztKr", "spotify:track:1CeC8kOYxUTCC2wHGtWROD", "spotify:track:2yzLpXCWjksJRAqD9sR7it"),
            ranked.map { it.uri },
        )
    }

    @Test
    fun `covers, choirs, orchestral versions, other artists and other songs of the real answer are never taken`() {
        val ranked = SpotifyCanvasMatch.rank(SpotifyFixtures.letDownSearch, "Let Down", "Radiohead", null, null)
        val names = ranked.flatMap { it.artists }.toSet()
        assertEquals(setOf("Radiohead"), names)
        assertTrue(ranked.none { it.title.contains("Choir") || it.title.contains("Orchestral") || it.title.contains("Cover") })
    }

    @Test
    fun `the same title by another artist is the other song`() {
        val ranked = SpotifyCanvasMatch.rank(SpotifyFixtures.letDownSearch, "Let Down", "Hot Chelle Rae", "Tonight Tonight EP", 164)
        assertEquals(listOf("spotify:track:1WkyKvixQ6gPL65hF6R8D9"), ranked.map { it.uri })
    }

    @Test
    fun `a length that disagrees by more than 30 s rejects the track (90 s for a music video)`() {
        assertTrue(SpotifyCanvasMatch.rank(SpotifyFixtures.letDownSearch, "Let Down", "Radiohead", null, 163).isEmpty())
        val song = listOf(hit("Song", "Band", sec = 200))
        assertEquals(1, SpotifyCanvasMatch.rank(song, "Song", "Band", null, 225).size)
        assertTrue(SpotifyCanvasMatch.rank(song, "Song", "Band", null, 250).isEmpty())
        assertEquals(1, SpotifyCanvasMatch.rank(song, "Song", "Band", null, 250, isVideo = true).size)
        assertTrue(SpotifyCanvasMatch.rank(song, "Song", "Band", null, 330, isVideo = true).isEmpty())
        // an unknown length on either side does not reject
        assertEquals(1, SpotifyCanvasMatch.rank(song, "Song", "Band", null, null).size)
        assertEquals(1, SpotifyCanvasMatch.rank(listOf(hit("Song", "Band")), "Song", "Band", null, 200).size)
    }

    @Test
    fun `the closest length comes first when the album does not decide`() {
        val a = hit("Song", "Band", album = "X", sec = 215, id = "0AAAAAAAAAAAAAAAAAAAA1")
        val b = hit("Song", "Band", album = "Y", sec = 201, id = "0AAAAAAAAAAAAAAAAAAAA2")
        assertEquals(listOf(b.uri, a.uri), SpotifyCanvasMatch.rank(listOf(a, b), "Song", "Band", null, 200).map { it.uri })
        // the album outranks the length
        assertEquals(listOf(a.uri, b.uri), SpotifyCanvasMatch.rank(listOf(a, b), "Song", "Band", "X", 200).map { it.uri })
    }

    // Title: packaging is ignored, other takes are not

    @Test
    fun `YouTube packaging and the artist in front of the title are ignored`() {
        val song = listOf(hit("Let Down", "Radiohead", sec = 299))
        for (title in listOf("Let Down (Official Video)", "Radiohead - Let Down (Official Music Video)", "LET DOWN [HD]", "Let Down (Audio)", "Let Down | Radiohead")) {
            assertEquals(title, 1, SpotifyCanvasMatch.rank(song, title, "Radiohead", null, null).size)
        }
    }

    @Test
    fun `remastered, radio edit and album version on Spotify's side are packaging`() {
        val spotify = listOf(
            hit("Let Down - Remastered", "Radiohead", id = "0AAAAAAAAAAAAAAAAAAAA1"),
            hit("Let Down (Radio Edit)", "Radiohead", id = "0AAAAAAAAAAAAAAAAAAAA2"),
            hit("Let Down - 2011 Remaster", "Radiohead", id = "0AAAAAAAAAAAAAAAAAAAA3"),
            hit("Let Down - Album Version", "Radiohead", id = "0AAAAAAAAAAAAAAAAAAAA4"),
        )
        assertEquals(4, SpotifyCanvasMatch.rank(spotify, "Let Down", "Radiohead", null, null).size)
    }

    @Test
    fun `a remix never takes the original's canvas and the original never takes a remix's`() {
        val original = hit("Song", "Band", id = "0AAAAAAAAAAAAAAAAAAAA1")
        val remix = hit("Song - Tiësto Remix", "Band", "Tiësto", id = "0AAAAAAAAAAAAAAAAAAAA2")
        assertEquals(listOf("Song"), titles(listOf(original, remix), "Song", "Band"))
        assertEquals(listOf("Song - Tiësto Remix"), titles(listOf(original, remix), "Song (Tiesto Remix)", "Band"))
        // another remixer's remix is another recording
        assertTrue(titles(listOf(remix), "Song (Kygo Remix)", "Band").isEmpty())
        assertTrue(titles(listOf(remix), "Song (Remix)", "Band").isEmpty())
    }

    @Test
    fun `live, acoustic, instrumental, sped up and part numbers are other takes in both directions`() {
        val studio = hit("Song", "Band")
        assertTrue(titles(listOf(studio), "Song (Live)", "Band").isEmpty())
        assertTrue(titles(listOf(studio), "Song - Acoustic", "Band").isEmpty())
        assertTrue(titles(listOf(studio), "Song Instrumental", "Band").isEmpty())
        assertTrue(titles(listOf(studio), "Song (Sped Up)", "Band").isEmpty())
        assertTrue(titles(listOf(hit("Song - Live at Wembley", "Band")), "Song", "Band").isEmpty())
        assertTrue(titles(listOf(hit("Song - Live at Wembley", "Band")), "Song (Live)", "Band").isEmpty())
        assertEquals(1, titles(listOf(hit("Song - Live at Wembley", "Band")), "Song (Live at Wembley)", "Band").size)
        assertEquals(1, titles(listOf(hit("Song (Acoustic Version)", "Band")), "Song - Acoustic", "Band").size)
        assertTrue(titles(listOf(hit("Song (Part 2)", "Band")), "Song (Part 1)", "Band").isEmpty())
        assertEquals(1, titles(listOf(hit("Song (Part 2)", "Band")), "Song (Part 2)", "Band").size)
    }

    @Test
    fun `a word Spotify adds that is not packaging means another recording, one the video site adds is decoration`() {
        assertTrue(titles(listOf(hit("Song (Piano)", "Band")), "Song", "Band").isEmpty())
        assertEquals(1, titles(listOf(hit("Song", "Band")), "Song (Lirik dan Terjemahan)", "Band").size)
        assertEquals(1, titles(listOf(hit("Song (Piano)", "Band")), "Song (Piano)", "Band").size)
    }

    @Test
    fun `a film credit on Spotify is packaging`() {
        val spotify = listOf(hit("Kesariya (From \"Brahmastra\")", "Arijit Singh", "Pritam"))
        assertEquals(1, titles(spotify, "Kesariya", "Arijit Singh").size)
        assertEquals(1, titles(listOf(hit("Kesariya - From \"Brahmastra\"", "Arijit Singh")), "Kesariya", "Arijit Singh").size)
    }

    @Test
    fun `featured artists in the title do not matter, whole different titles do`() {
        val spotify = listOf(hit("Song (feat. Drake)", "Rihanna", "Drake"))
        assertEquals(1, titles(spotify, "Song", "Rihanna").size)
        assertEquals(1, titles(spotify, "Song (feat. Drake)", "Rihanna, Drake").size)
        assertEquals(1, titles(listOf(hit("Song", "Rihanna", "Drake")), "Song feat. Drake", "Rihanna").size)
        assertTrue(titles(spotify, "Song Two", "Rihanna").isEmpty())
        // a title that merely contains the other one is another song
        assertTrue(titles(listOf(hit("Stay With Me", "Sam Smith")), "Stay", "Sam Smith").isEmpty())
        assertTrue(titles(listOf(hit("Stay", "Sam Smith")), "Stay With Me", "Sam Smith").isEmpty())
    }

    @Test
    fun `accents, case, punctuation and CJK titles compare equal`() {
        assertEquals(1, titles(listOf(hit("CRAZY IN LOVE", "Beyoncé")), "Crazy in Love", "Beyonce").size)
        assertEquals(1, titles(listOf(hit("Don't Stop", "Band")), "Dont Stop", "Band").size)
        assertEquals(1, titles(listOf(hit("夜に駆ける", "YOASOBI")), "夜に駆ける", "YOASOBI").size)
        assertTrue(titles(listOf(hit("夜に駆ける", "YOASOBI")), "怪物", "YOASOBI").isEmpty())
        // voiced kana are not folded into their plain twins
        assertTrue(titles(listOf(hit("が", "A")), "か", "A").isEmpty())
    }

    // Artist

    @Test
    fun `an artist must be the same name, not a part of it`() {
        assertTrue(titles(listOf(hit("Song", "Drake Bell")), "Song", "Drake").isEmpty())
        assertTrue(titles(listOf(hit("Song", "Drakeo the Ruler")), "Song", "Drake").isEmpty())
        assertEquals(1, titles(listOf(hit("Song", "Drake", "21 Savage")), "Song", "Drake").size)
        assertEquals(1, titles(listOf(hit("Song", "Drake")), "Song", "Drake, 21 Savage").size)
        assertEquals(1, titles(listOf(hit("Song", "21 Savage", "Drake")), "Song", "Drake").size)
    }

    @Test
    fun `The Weeknd, JAY-Z, Simon and Garfunkel, Topic and VEVO channels`() {
        assertEquals(1, titles(listOf(hit("Song", "The Weeknd")), "Song", "Weeknd").size)
        assertEquals(1, titles(listOf(hit("Song", "Weeknd")), "Song", "The Weeknd").size)
        assertEquals(1, titles(listOf(hit("Song", "JAY-Z")), "Song", "Jay Z").size)
        assertEquals(1, titles(listOf(hit("Song", "Simon & Garfunkel")), "Song", "Simon & Garfunkel").size)
        assertEquals(1, titles(listOf(hit("Song", "Radiohead")), "Song", "Radiohead - Topic").size)
        assertEquals(1, titles(listOf(hit("Song", "Adele")), "Song", "AdeleVEVO").size)
        assertTrue(titles(listOf(hit("Song", "Muse")), "Song", "Radiohead").isEmpty())
    }

    @Test
    fun `without a usable artist or title nothing is taken`() {
        val spotify = listOf(hit("Song", "Band"))
        assertTrue(titles(spotify, "Song", "").isEmpty())
        assertTrue(titles(spotify, "", "Band").isEmpty())
        assertTrue(titles(spotify, "(Official Video)", "Band").isEmpty())
        assertTrue(titles(listOf(hit("Song", "")), "Song", "Band").isEmpty())
    }

    @Test
    fun `only track uris are accepted`() {
        val notTrack = SpotifyTrackHit("spotify:album:0AAAAAAAAAAAAAAAAAAAA1", "Song", listOf("Band"), null, null)
        val bad = SpotifyTrackHit("spotify:track:", "Song", listOf("Band"), null, null)
        val sneaky = SpotifyTrackHit("spotify:track:abc\"; drop", "Song", listOf("Band"), null, null)
        assertTrue(SpotifyCanvasMatch.rank(listOf(notTrack, bad, sneaky), "Song", "Band", null, null).isEmpty())
    }

    // Cache key and search term

    @Test
    fun `the cache key belongs to the track, keeps the take and ignores packaging and guests`() {
        val plain = SpotifyCanvasMatch.cacheKey("Song", "Band")!!
        assertEquals(plain, SpotifyCanvasMatch.cacheKey("Song (Official Video)", "Band"))
        assertEquals(plain, SpotifyCanvasMatch.cacheKey("Band - Song", "Band"))
        assertEquals(plain, SpotifyCanvasMatch.cacheKey("SONG (feat. Someone)", "Band, Someone"))
        assertNotEquals(plain, SpotifyCanvasMatch.cacheKey("Song (Acoustic)", "Band"))
        assertNotEquals(plain, SpotifyCanvasMatch.cacheKey("Song (Tiësto Remix)", "Band"))
        assertNotEquals(plain, SpotifyCanvasMatch.cacheKey("Song", "Other Band"))
        assertNotEquals(plain, SpotifyCanvasMatch.cacheKey("Other Song", "Band"))
        assertTrue(plain.startsWith("spotify:"))
        assertNull(SpotifyCanvasMatch.cacheKey("", "Band"))
        assertNull(SpotifyCanvasMatch.cacheKey("Song", " "))
    }

    @Test
    fun `the search term is the clean title, its take markers and the main artist`() {
        assertEquals("let down radiohead", SpotifyCanvasMatch.searchTerm("Radiohead - Let Down (Official Video)", "Radiohead"))
        assertEquals("song tiesto remix drake", SpotifyCanvasMatch.searchTerm("Song (Tiësto Remix)", "Drake, 21 Savage"))
        assertEquals("song band", SpotifyCanvasMatch.searchTerm("Song feat. Guest", "Band - Topic"))
    }
}
