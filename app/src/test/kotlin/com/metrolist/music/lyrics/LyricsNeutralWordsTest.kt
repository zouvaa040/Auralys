package com.metrolist.music.lyrics

import com.metrolist.lrclib.models.Track
import com.metrolist.music.lyrics.LyricsSelection.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 2026-11-12, findings 9, 10 and 12: labels of the release or the upload are ignored, versions must match, the
 * search title loses the labels, music video uploads may be further off in length.
 */
class LyricsNeutralWordsTest {
    private fun same(
        hit: String,
        wanted: String,
    ) = LyricsHitMatch.sameTitle(hit, wanted)

    @Test
    fun `labels of the release or the upload are ignored`() {
        // the review's cases that the stricter check rejected
        assertTrue(same("Song (Remastered Version)", "Song"))
        assertTrue(same("Song", "Song (Remastered Version)"))
        assertTrue(same("Song - Remaster 2011", "Song"))
        assertTrue(same("Song (Remaster 2011)", "Song"))
        assertTrue(same("Song (2009 Remastered Version)", "Song"))
        assertTrue(same("Song (Explicit Version)", "Song"))
        assertTrue(same("Song (Album Version)", "Song"))
        assertTrue(same("Song (Single Version)", "Song"))
        assertTrue(same("Song (Original Mix)", "Song"))
        assertTrue(same("Song (Bonus Track)", "Song"))
        assertTrue(same("Song (Deluxe Edition)", "Song"))
        assertTrue(same("Song [Explicit]", "Song (Clean)"))
        assertTrue(same("Song (Official Audio)", "Song"))
        assertTrue(same("Song (Official Music Video)", "Song"))
        assertTrue(same("Song [Lyric Video]", "Song"))
        assertTrue(same("Song (Visualizer)", "Song"))
        assertTrue(same("Song (Video Lirik)", "Song"))
        assertTrue(same("Song (From \"Barbie The Album\")", "Song"))
        assertTrue(same("Song [Prod. Metro Boomin]", "Song"))
        assertTrue(same("Song - Official Video", "Song"))
    }

    @Test
    fun `guests without brackets are handled by the guest rule, not by the title`() {
        assertTrue(same("Song ft. X", "Song"))
        assertTrue(same("Song feat. Ana & Budi", "Song"))
        assertTrue(same("Song featuring Ana", "Song (feat. Ana)"))
        assertEquals(setOf("ana", "budi"), LyricsHitMatch.featuredNames("Song feat. Ana & Budi"))
        // a guest inside a version bracket
        assertEquals(setOf("justin bieber"), LyricsHitMatch.featuredNames("Despacito (Remix feat. Justin Bieber)"))
        assertEquals("Despacito (Remix)", LyricsQuery.title("Despacito (Remix feat. Justin Bieber)"))
        // a guest tail stops at the next bracket: the version after it still counts
        assertEquals(setOf("justin bieber"), LyricsHitMatch.featuredNames("Despacito ft. Justin Bieber (Remix)"))
        assertTrue(same("Despacito ft. Justin Bieber (Remix)", "Despacito (Remix)"))
        assertFalse(same("Despacito ft. Justin Bieber (Remix)", "Despacito"))
        assertEquals("Despacito (Remix)", LyricsQuery.title("Despacito ft. Justin Bieber (Remix)"))
    }

    @Test
    fun `full-width brackets and bracket-only titles`() {
        assertTrue(same("光年之外", "光年之外（電影《太空旅客》中文主題曲）"))
        assertTrue(same("光年之外【Live】", "光年之外 (Live)"))
        assertFalse(same("光年之外", "光年之外（Live版）"))
        assertFalse(same("光年之外 (伴奏)", "光年之外"))
        assertTrue(same("カナタハルカ (映画「すずめの戸締まり」主題歌)", "カナタハルカ"))
        // a title that is only a bracket is read by its text
        assertTrue(same("(Interlude)", "(Interlude)"))
        assertTrue(same("Interlude", "(Interlude)"))
        assertFalse(same("(Interlude)", "(Outro)"))
    }

    @Test
    fun `versions must match`() {
        assertFalse(same("Dynamite (Japanese Ver.)", "Dynamite"))
        assertTrue(same("Dynamite (Japanese Ver.)", "Dynamite (Japanese Version)"))
        assertFalse(same("Song (Acoustic)", "Song"))
        assertTrue(same("Song (Acoustic Version)", "Song (Acoustic)"))
        assertTrue(same("Song (Versi Akustik)", "Song (Acoustic Version)"))
        assertFalse(same("Song (Live)", "Song"))
        assertFalse(same("Song (Live at Wembley)", "Song (Live)"))
        assertFalse(same("Song (Remix)", "Song"))
        assertFalse(same("Song (DNMO Remix)", "Song (Remix)"))
        assertTrue(same("Song (Remixed)", "Song (Remix)"))
        assertTrue(same("Song (Sped Up)", "Song (Speed Up)"))
        assertTrue(same("Song (sped up version)", "Song - Sped Up"))
        assertFalse(same("Song (Sped Up)", "Song"))
        assertTrue(same("Song (Slowed + Reverb)", "Song (Slowed)"))
        assertFalse(same("Song (Slowed + Reverb)", "Song"))
        assertFalse(same("Song (Super Slowed)", "Song (Slowed)"))
        assertFalse(same("Song (Nightcore)", "Song"))
        assertFalse(same("Song (Instrumental)", "Song"))
        assertFalse(same("Song (Karaoke Version)", "Song"))
        assertFalse(same("Song (Extended Mix)", "Song"))
        assertFalse(same("Song (Radio Edit)", "Song"))
        assertFalse(same("Song (Demo)", "Song"))
        assertFalse(same("Song (Cover)", "Song"))
        assertFalse(same("Song (Piano Version)", "Song"))
        assertFalse(same("Song (Orchestral Version)", "Song"))
        assertFalse(same("Love Story (Taylor's Version)", "Love Story"))
        assertTrue(same("Love Story (Taylor's Version)", "Love Story (Taylor’s Version)"))
        assertEquals(setOf("japanese"), LyricsHitMatch.versionWords("Dynamite (Japanese Ver.)"))
        assertEquals(setOf("acoustic"), LyricsHitMatch.versionWords("La La Lost You (Acoustic Version)"))
        assertTrue(LyricsHitMatch.versionWords("Hotel California (2013 Remaster)").isEmpty())
    }

    @Test
    fun `the search title loses labels and keeps versions`() {
        assertEquals("Blinding Lights", LyricsQuery.title("Blinding Lights (Bonus Track)"))
        assertEquals("Bohemian Rhapsody", LyricsQuery.title("Bohemian Rhapsody - Remastered 2011"))
        assertEquals("Song", LyricsQuery.title("Song (Official Video) [4K]"))
        assertEquals("Song", LyricsQuery.title("Song (From \"Frozen\")"))
        assertEquals("Song", LyricsQuery.title("Song [Prod. X]"))
        assertEquals("La La Lost You (Acoustic Version)", LyricsQuery.title("La La Lost You (Acoustic Version)"))
        assertEquals("Dynamite (Japanese Ver.)", LyricsQuery.title("Dynamite (Japanese Ver.)"))
        assertEquals("Song (Live)", LyricsQuery.title("Song (Live) (Remastered 2011)"))
        assertEquals("Song - Live", LyricsQuery.title("Song - Live"))
        // unknown words stay (they may be part of the title)
        assertEquals("(I Can't Get No) Satisfaction", LyricsQuery.title("(I Can't Get No) Satisfaction"))
        assertEquals("Song (TV Size)", LyricsQuery.title("Song (TV Size)"))
        // a video name keeps its artist part
        assertEquals("Denny Caknan - Kartonyono Medot Janji", LyricsQuery.title("Denny Caknan - Kartonyono Medot Janji (Official Music Video)"))
        assertEquals("Blinding Lights", LyricsRequest.of("id", "Blinding Lights (Bonus Track)", listOf("The Weeknd"), 200, null).title)
    }

    private fun synced(lastSeconds: Int) = "[00:07.50]x\n[%02d:%02d.00]y".format(lastSeconds / 60, lastSeconds % 60)

    @Test
    fun `lrclib takes the right entry for a title with labels`() {
        val hits =
            listOf(
                Track(id = 1, trackName = "Blinding Lights", artistName = "The Weeknd", duration = 200.0, plainLyrics = "x", syncedLyrics = synced(190)),
                Track(id = 2, trackName = "Blinding Lights (Remix)", artistName = "The Weeknd", duration = 200.0, plainLyrics = "x", syncedLyrics = synced(190)),
            )
        val request = LyricsRequest.of("id", "Blinding Lights (Bonus Track)", listOf("The Weeknd"), 201, null)
        assertEquals("Blinding Lights", LrcLibLyricsProvider.pick(hits, request)?.title)
        val remastered = LyricsRequest.of("id", "Blinding Lights (Remastered 2020)", listOf("The Weeknd"), 201, null)
        assertEquals("Blinding Lights", LrcLibLyricsProvider.pick(hits, remastered)?.title)
    }

    @Test
    fun `a music video upload may be further off in length, a catalogue song may not`() {
        val text = "[00:12.00]a\n[03:20.00]z"
        val video = LyricsRequest.of("v", "Song (Official Music Video)", listOf("Artist"), 240, null, isVideo = true)
        val audio = LyricsRequest.of("a", "Song", listOf("Artist"), 240, null)
        // the audio is 25 s shorter than the video (an intro): kept for the video, below closer answers
        val hit = LyricsHit(text, "Song", "Artist", 215.0)
        assertEquals(Quality.DISTANT, LyricsSelection.rate("BiniLyrics", video, hit).quality)
        assertEquals(Quality.REJECTED, LyricsSelection.rate("BiniLyrics", audio, hit).quality)
        // too far off even for a video
        assertEquals(Quality.REJECTED, LyricsSelection.rate("BiniLyrics", video, LyricsHit(text, "Song", "Artist", 180.0)).quality)
        // the window grows with the video, between 30 and 45 s
        assertEquals(30.0, LyricsSelection.videoUploadGapSeconds(120), 0.001)
        assertEquals(45.0, LyricsSelection.videoUploadGapSeconds(600), 0.001)
        // lyrics that run past the video are still another cut
        assertEquals(Quality.REJECTED, LyricsSelection.rate("BiniLyrics", video, LyricsHit("[00:12.00]a\n[04:20.00]z", "Song", "Artist", 260.0)).quality)
        // ranked below every closer answer, above plain text
        val distant = LyricsSelection.rate("BiniLyrics", video, hit)
        val close = LyricsSelection.rate("LrcLib", video, LyricsHit(text, "Song", "Artist", 238.0))
        val plain = LyricsSelection.rate("YouTube Music", video, LyricsHit("words\nmore", keyedOnVideo = true))
        assertEquals("LrcLib", LyricsSelection.best(listOf(distant, close, plain))?.provider)
        assertEquals("BiniLyrics", LyricsSelection.best(listOf(plain, distant))?.provider)
        // a guest-less hit for a song with a guest still needs a close length
        val duet = LyricsRequest.of("d", "Song (feat. B)", listOf("Artist"), 240, null, isVideo = true)
        assertEquals(Quality.REJECTED, LyricsSelection.rate("BiniLyrics", duet, hit).quality)
    }

    @Test
    fun `neutral parts are recognised and nothing else`() {
        listOf("(Remastered 2011)", "[Official Video]", "(Bonus Track)", "(Album Version)", "(From \"Barbie\")", "[Prod. X]", "2011 Remaster", "[M/V]", "(Explicit)")
            .forEach { assertTrue(it, LyricsHitMatch.isNeutralPart(it)) }
        listOf("(Live)", "(Japanese Ver.)", "(Remix)", "(feat. X)", "(TV Size)", "(Interlude)", "(Sped Up)", "Kartonyono Medot Janji")
            .forEach { assertFalse(it, LyricsHitMatch.isNeutralPart(it)) }
        assertNull(LyricsQuery.title("").takeIf { it.isNotEmpty() })
    }
}
