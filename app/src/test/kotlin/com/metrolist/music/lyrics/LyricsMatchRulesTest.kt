package com.metrolist.music.lyrics

import com.metrolist.kugou.KuGou
import com.metrolist.lrclib.models.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matching rules added after the live probe of 2026-11-12 (hit lists below are the metadata LRCLIB returned for the
 * reported songs; the lyric texts are replaced by stamps only).
 */
class LyricsMatchRulesTest {
    private fun synced(lastSeconds: Int) = "[00:07.50]x\n[%02d:%02d.00]y".format(lastSeconds / 60, lastSeconds % 60)

    private fun track(
        title: String,
        artist: String,
        seconds: Double,
        lastSeconds: Int = 188,
    ) = Track(id = 0, trackName = title, artistName = artist, duration = seconds, plainLyrics = "x", syncedLyrics = synced(lastSeconds))

    @Test
    fun `guests are read from the title`() {
        assertEquals(setOf("jennie"), LyricsHitMatch.featuredNames("Dracula (feat. JENNIE)"))
        assertEquals(setOf("justin bieber"), LyricsHitMatch.featuredNames("Despacito (Remix) [feat. Justin Bieber]"))
        assertEquals(setOf("a", "b"), LyricsHitMatch.featuredNames("Song ft. A & B"))
        assertEquals(setOf("dababy"), LyricsHitMatch.featuredNames("Levitating (with DaBaby)"))
        assertTrue(LyricsHitMatch.featuredNames("Dracula").isEmpty())
    }

    @Test
    fun `a guest the song does not have is a conflict, a missing guest is not`() {
        assertTrue(LyricsHitMatch.guestConflict("Dracula (feat. JENNIE)", "Tame Impala", "Dracula", "Tame Impala"))
        assertFalse(LyricsHitMatch.guestConflict("Dracula (feat. JENNIE)", "Tame Impala", "Dracula (feat. JENNIE)", "Tame Impala"))
        assertFalse(LyricsHitMatch.guestConflict("Dracula (feat. JENNIE)", "Tame Impala", "Dracula", "Tame Impala, JENNIE"))
        assertFalse(LyricsHitMatch.guestConflict("Dracula", "Tame Impala", "Dracula (feat. JENNIE)", "Tame Impala"))
        assertTrue(LyricsHitMatch.guestMissing("Dracula", "Tame Impala", "Dracula (feat. JENNIE)"))
        assertFalse(LyricsHitMatch.guestMissing("Dracula", "Tame Impala & JENNIE", "Dracula (feat. JENNIE)"))
        assertFalse(LyricsHitMatch.guestMissing("Dracula", "Tame Impala", "Dracula"))
    }

    @Test
    fun `the length of a ttml document is read from its body`() {
        assertEquals(208.511, LyricsConvert.ttmlDurationSeconds("""<tt><head/><body dur="3:28.511"><div/></body></tt>""")!!, 0.001)
        assertEquals(209.721, LyricsConvert.ttmlDurationSeconds("""<tt><body dur="3:29.721" ttm:agent="v2">""")!!, 0.001)
        assertEquals(3808.5, LyricsConvert.ttmlDurationSeconds("""<body xml:lang="en" dur="01:03:28.5">""")!!, 0.001)
        assertEquals(208.5, LyricsConvert.clockSeconds("208.5s")!!, 0.001)
        assertNull(LyricsConvert.ttmlDurationSeconds("<tt><body><p begin=\"1.0\">x</p></body></tt>"))
        assertNull(LyricsConvert.clockSeconds("soon"))
    }

    @Test
    fun `kugou title lines and singer labels are taken off`() {
        val lrc =
            listOf(
                "[00:00.00]Untitled (Explicit) - Rex Orange County",
                "[00:02.00]I make enough mistakes",
                "[00:40.00]DaBaby：",
                "[00:41.00]Next line: with a colon inside",
                "[01:22.00]last",
            ).joinToString("\n")
        val out = KuGou.stripHeaderAndLabels(lrc, "Untitled", "Rex Orange County")
        assertFalse(out.contains("Explicit"))
        assertFalse(out.contains("DaBaby"))
        assertTrue(out.contains("I make enough mistakes"))
        assertTrue(out.contains("with a colon inside"))
        assertTrue(out.contains("last"))
        // a line in the middle that happens to have " - " stays
        val middle = "[00:01.00]a\n[00:02.00]b\n[00:03.00]c\n[00:30.00]Untitled - Rex Orange County"
        assertTrue(KuGou.stripHeaderAndLabels(middle, "Untitled", "Rex Orange County").contains("[00:30.00]"))
    }

    @Test
    fun `lrclib - the acoustic song takes the acoustic entry, not the original of the same length`() {
        // LRCLIB answer for "La La Lost You" (live probe): the original is 209 s too
        val hits =
            listOf(
                track("La La Lost You", "NIKI", 209.0, lastSeconds = 188),
                track("La La Lost You", "88rising & NIKI", 200.0, lastSeconds = 184),
                track("NIKI - La La Lost You (Live) | Acoustic Sessions", "NIKI", 212.0, lastSeconds = 173),
                track("La La Lost You (Acoustic Version)", "NIKI", 209.0, lastSeconds = 193),
                track("La La Lost You Version", "NIKI", 208.51, lastSeconds = 188),
                track("La La Lost You (Acoustic Version)", "NIKI", 3.0, lastSeconds = 193),
                track("La La Lost You - Acoustic Version", "NIKI", 329.0, lastSeconds = 193),
            )
        val acoustic = LyricsRequest.of("3s4bzVIAHEU", "La La Lost You (Acoustic Version)", listOf("NIKI", "88rising"), 209, null)
        val picked = LrcLibLyricsProvider.pick(hits, acoustic)
        assertEquals("La La Lost You (Acoustic Version)", picked?.title)
        assertEquals(209.0, picked?.durationSeconds!!, 0.01)

        val original = LyricsRequest.of("x", "La La Lost You", listOf("NIKI"), 209, null)
        assertEquals("La La Lost You", LrcLibLyricsProvider.pick(hits, original)?.title)
    }

    @Test
    fun `lrclib - a song with a common title only takes the entry of its own artist`() {
        // LRCLIB answer for "Untitled" (live probe): twelve other artists, none of them Rex Orange County
        val hits =
            listOf(
                track("Untitled", "Suede", 225.0, 198),
                track("Untitled", "Silverchair", 210.0, 197),
                track("Untitled", "1/N", 187.0, 175),
                track("Untitled", "Mickey Woods Jr.", 161.0, 120),
                // another script cannot be compared as a name, so the length has to decide: 201 s is not 136 s
                Track(id = 0, trackName = "Untitled", artistName = "Скрябін", duration = 201.0, plainLyrics = "x", syncedLyrics = null),
            )
        val song = LyricsRequest.of("yIPpLTRlMDw", "Untitled", listOf("Rex Orange County"), 136, "Apricot Princess")
        assertNull(LrcLibLyricsProvider.pick(hits, song))
    }

    @Test
    fun `lrclib - the solo song does not take the duet, the duet takes a close entry`() {
        val hits =
            listOf(
                track("Dracula", "Tame Impala", 244.0, 198),
                track("Dracula", "Tame Impala", 205.0, 198),
                track("Dracula (feat. JENNIE)", "Tame Impala", 210.0, 198),
            )
        val solo = LyricsRequest.of("a", "Dracula", listOf("Tame Impala"), 206, null)
        val pickedSolo = LrcLibLyricsProvider.pick(hits, solo)
        assertEquals("Dracula", pickedSolo?.title)
        assertEquals(205.0, pickedSolo?.durationSeconds!!, 0.01)
        val duet = LyricsRequest.of("6KjVYeQ9SRw", "Dracula (feat. JENNIE)", listOf("Tame Impala"), 210, null)
        assertEquals("Dracula (feat. JENNIE)", LrcLibLyricsProvider.pick(hits, duet)?.title)
    }

    @Test
    fun `lrclib - lyrics of the original are not taken for the sped up song`() {
        // "Die For You (Sped Up)" is 224 s; LRCLIB's synced text ran to 4:03 (the original)
        val hits = listOf(track("Die For You", "The Weeknd", 260.0, lastSeconds = 243))
        val spedUp = LyricsRequest.of("RWZsu_38-x8", "Die For You (Sped Up)", listOf("The Weeknd", "xxtristanxo", "Speed Radio"), 224, null)
        assertNull(LrcLibLyricsProvider.pick(hits, spedUp))
    }

    @Test
    fun `every word of a version part counts, filler and a bare version do not`() {
        assertFalse(LyricsHitMatch.sameTitle("Despacito (DNMO Remix)", "Despacito (Remix)"))
        assertTrue(LyricsHitMatch.sameTitle("Despacito (feat. Justin Bieber) [Remix]", "Despacito (Remix)"))
        assertTrue(LyricsHitMatch.sameTitle("Despacito (Remix feat. Justin Bieber)", "Despacito (Remix)"))
        assertTrue(LyricsHitMatch.sameTitle("La La Lost You (Acoustic Version|Explicit)", "La La Lost You (Acoustic Version)"))
        assertTrue(LyricsHitMatch.sameTitle("La La Lost You (Acoustic)", "La La Lost You (Acoustic Version)"))
        assertTrue(LyricsHitMatch.sameTitle("La La Lost You - Acoustic Version", "La La Lost You (Acoustic Version)"))
        assertFalse(LyricsHitMatch.sameTitle("NIKI - La La Lost You (Live) | Acoustic Sessions", "La La Lost You (Acoustic Version)"))
        assertFalse(LyricsHitMatch.sameTitle("Blinding Lights (Mixed)", "Blinding Lights"))
        assertTrue(LyricsHitMatch.sameTitle("Hotel California (2013 Remaster)", "Hotel California"))
        assertTrue(LyricsHitMatch.sameTitle("Die For You (Sped Up)", "Die For You (Sped Up)"))
        assertFalse(LyricsHitMatch.sameTitle("Die For You", "Die For You (Sped Up)"))
        assertTrue(LyricsHitMatch.sameTitle("Untitled (Explicit)", "Untitled"))
    }

    @Test
    fun `the request keeps every form of the title`() {
        val r = LyricsRequest.of("id", "Despacito (Remix) (feat. Justin Bieber)", listOf("Luis Fonsi", "Daddy Yankee - Topic"), 231, "VIDA")
        assertEquals("Despacito (Remix)", r.title)
        assertEquals("Despacito", r.searchTitle)
        assertEquals("Luis Fonsi, Daddy Yankee", r.artist)
        assertEquals(setOf("remix"), LyricsHitMatch.versionWords(r.title))
    }
}
