package com.metrolist.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Response parsers of the keyless providers. Small fixtures, no network. */
class NewProvidersParseTest {
    // ---- SimpMusic ----

    private val simpAnswer =
        """
        {"success":true,"data":[
          {"duration":224,
           "richSyncLyrics":"[00:12.34]<00:12.34>Ra <00:12.80>sa\n[00:15.00]<00:15.00>Lagu &#x27;ku",
           "syncedLyrics":"[00:12.34]Rasa\n[00:15.00]Lagu",
           "plainLyrics":"Rasa\nLagu"},
          {"duration":251,"syncedLyrics":"[00:20.00]Edit lain"}
        ]}
        """.trimIndent()

    @Test
    fun `simpmusic prefers word timing of the entry with the right length`() {
        val lyrics = SimpMusicLyricsProvider.parse(simpAnswer, 225)
        assertNotNull(lyrics)
        assertTrue(lyrics!!.startsWith("[00:12.34]<00:12.34>Ra"))
        assertFalse(lyrics.contains("Edit lain"))
    }

    @Test
    fun `simpmusic picks the closest of two entries and ignores one that is too far`() {
        assertEquals("[00:20.00]Edit lain", SimpMusicLyricsProvider.parse(simpAnswer, 250))
        // 224 and 251 are both more than 10 seconds away from 300
        assertNull(SimpMusicLyricsProvider.parse(simpAnswer, 300))
    }

    @Test
    fun `simpmusic falls back to line timing and then plain text`() {
        val noRich = """{"success":true,"data":[{"duration":200,"syncedLyrics":"[00:01.00]Satu","plainLyrics":"Satu"}]}"""
        assertEquals("[00:01.00]Satu", SimpMusicLyricsProvider.parse(noRich, 200))
        val plainOnly = """{"success":true,"data":[{"duration":200,"plainLyrics":"Satu\nDua"}]}"""
        assertEquals("Satu\nDua", SimpMusicLyricsProvider.parse(plainOnly, 205))
    }

    @Test
    fun `simpmusic treats refusals and non json as a miss`() {
        assertNull(SimpMusicLyricsProvider.parse("""{"success":false,"data":[]}""", 200))
        assertNull(SimpMusicLyricsProvider.parse("Access denied from your region", 200))
        assertNull(SimpMusicLyricsProvider.parse("""{"success":true,"data":[]}""", 200))
    }

    // ---- Unison ----

    private val ttml =
        """
        <tt xmlns="http://www.w3.org/ns/ttml">
          <body><div>
            <p begin="00:12.000"><span>Rasa sayang</span></p>
            <p begin="00:15.000"><span>Hey</span></p>
          </div></body>
        </tt>
        """.trimIndent()

    @Test
    fun `unison converts ttml entries to lrc`() {
        val body = """{"success":true,"data":{"song":"Rasa Sayang","artist":"Anak Negeri","lyrics":${quoted(ttml)},"format":"ttml","syncType":"linesync"}}"""
        val lrc = UnisonLyricsProvider.parse(body, "Rasa Sayang", "Anak Negeri")
        assertNotNull(lrc)
        assertTrue(lrc!!.contains("[00:12.00]Rasa sayang"))
        assertTrue(lrc.contains("[00:15.00]Hey"))
    }

    @Test
    fun `unison returns lrc and plain entries as they are`() {
        val lrc = """{"success":true,"data":{"song":"Rasa Sayang","artist":"Anak Negeri","lyrics":"[00:12.00]Rasa\n[00:15.00]Hey","format":"lrc","syncType":"linesync"}}"""
        assertEquals("[00:12.00]Rasa\n[00:15.00]Hey", UnisonLyricsProvider.parse(lrc, "Rasa Sayang", "Anak Negeri"))
        val plain = """{"success":true,"data":{"song":"Rasa Sayang","artist":"Anak Negeri","lyrics":"Rasa\nHey","format":"lrc","syncType":"plain"}}"""
        assertEquals("Rasa\nHey", UnisonLyricsProvider.parse(plain, "Rasa Sayang", "Anak Negeri"))
    }

    @Test
    fun `unison rejects an entry for another song or artist`() {
        val other = """{"success":true,"data":{"song":"Lagu Lain","artist":"Anak Negeri","lyrics":"[00:12.00]x","format":"lrc"}}"""
        assertNull(UnisonLyricsProvider.parse(other, "Rasa Sayang", "Anak Negeri"))
        val otherArtist = """{"success":true,"data":{"song":"Rasa Sayang","artist":"Orang Lain","lyrics":"[00:12.00]x","format":"lrc"}}"""
        assertNull(UnisonLyricsProvider.parse(otherArtist, "Rasa Sayang", "Anak Negeri"))
        assertNull(UnisonLyricsProvider.parse("""{"success":false}""", "Rasa Sayang", "Anak Negeri"))
        assertNull(UnisonLyricsProvider.parse("<html>bad gateway</html>", "Rasa Sayang", "Anak Negeri"))
    }

    // ---- lrc.red ----

    private val redAnswer =
        """
        {"hits":[
          {"isrc":"USRC17607839","title":"Bohemian Rhapsody (Operatic Section / 2011 A Cappella Mix)","artist":"Queen","duration":150.0},
          {"isrc":"GBUM71029604","title":"Bohemian Rhapsody (Live)","artist":"Queen","duration":352.0},
          {"isrc":"GBUM71029605","title":"Bohemian Rhapsody - 2011 Remaster","artist":"Queen","duration":354.1},
          {"isrc":"not-an-isrc","title":"Bohemian Rhapsody","artist":"Queen","duration":354.0}
        ],"next":"abc"}
        """.trimIndent()

    @Test
    fun `lrcred takes the studio track and not the live cut or the medley`() {
        val hits = LrcRedLyricsProvider.parseHits(redAnswer)
        assertNotNull(hits)
        val best = LrcRedLyricsProvider.bestHit(hits!!, "Bohemian Rhapsody", "Queen", 354)
        assertEquals("GBUM71029605", best?.isrc)
        assertEquals("https://lrc.red/s/GBUM71029605.ttml", LrcRedLyricsProvider.documentUrl(best?.isrc))
    }

    @Test
    fun `lrcred asks for the live version only when the song is the live version`() {
        val hits = LrcRedLyricsProvider.parseHits(redAnswer)!!
        assertEquals("GBUM71029604", LrcRedLyricsProvider.bestHit(hits, "Bohemian Rhapsody (Live)", "Queen", 352)?.isrc)
    }

    @Test
    fun `lrcred rejects a wrong length a wrong artist and bad isrc values`() {
        val hits = LrcRedLyricsProvider.parseHits(redAnswer)!!
        assertNull(LrcRedLyricsProvider.bestHit(hits, "Bohemian Rhapsody", "Queen", 300))
        assertNull(LrcRedLyricsProvider.bestHit(hits, "Bohemian Rhapsody", "Someone Else", 354))
        assertNull(LrcRedLyricsProvider.documentUrl("not-an-isrc"))
        assertNull(LrcRedLyricsProvider.documentUrl("../etc/passwd"))
        assertNull(LrcRedLyricsProvider.parseHits("<html>"))
    }

    // ---- BiniLyrics ----

    private val biniAnswer =
        """
        {"total":2,"source":"HIT-FUZZY","results":[
          {"track_name":"Lagu Yang Mirip","artist_name":"Anak Negeri","duration":223,"isrc":"IDA010900001","timing_type":"line","lyricsUrl":"https://lrc.red/s/IDA010900001.ttml"},
          {"track_name":"Rasa Sayang","artist_name":"Anak Negeri","duration":221,"isrc":"IDA010900002","timing_type":"word","lyricsUrl":"https://lrc.red/s/IDA010900002.ttml"},
          {"track_name":"Rasa Sayang","artist_name":"Anak Negeri","duration":300,"isrc":"IDA010900003","timing_type":"word","lyricsUrl":"https://lrc.red/s/IDA010900003.ttml"},
          {"track_name":"Rasa Sayang","artist_name":"Anak Negeri","duration":222,"isrc":"IDA010900004","timing_type":"word"}
        ]}
        """.trimIndent()

    @Test
    fun `bini uses only the hit that is the playing song and has a document`() {
        val hit = BiniLyricsProvider.bestHit(biniAnswer, "Rasa Sayang", "Anak Negeri", 222)
        assertEquals("IDA010900002", hit?.isrc)
        assertEquals("word", hit?.timingType)
    }

    @Test
    fun `bini returns nothing when no hit agrees`() {
        assertNull(BiniLyricsProvider.bestHit(biniAnswer, "Judul Lain", "Anak Negeri", 222))
        assertNull(BiniLyricsProvider.bestHit(biniAnswer, "Rasa Sayang", "Anak Negeri", 400))
        assertNull(BiniLyricsProvider.bestHit("not json", "Rasa Sayang", "Anak Negeri", 222))
        assertNull(BiniLyricsProvider.bestHit("""{"total":0,"results":[]}""", "Rasa Sayang", "Anak Negeri", 222))
    }

    // ---- shared pieces ----

    @Test
    fun `title cleanup drops credits and keeps version words`() {
        assertEquals("Dracula", LyricsQuery.title("Dracula (feat. JENNIE)"))
        assertEquals("Song", LyricsQuery.title("Song [Official Video]"))
        assertEquals("Song (Live)", LyricsQuery.title("Song (Live) (Official Audio)"))
        assertEquals("Song (Sped Up)", LyricsQuery.title("Song (Sped Up)"))
        assertEquals("Artis", LyricsQuery.artist("Artis - Topic"))
    }

    @Test
    fun `hit match is strict about version words and tolerant about brackets that are only labels`() {
        assertTrue(LyricsHitMatch.sameTitle("Song - 2011 Remaster", "Song"))
        assertTrue(LyricsHitMatch.sameTitle("Song (From \"Movie\")", "Song"))
        assertFalse(LyricsHitMatch.sameTitle("Song (Live)", "Song"))
        assertFalse(LyricsHitMatch.sameTitle("Song (Remix)", "Song"))
        assertFalse(LyricsHitMatch.sameTitle("Song Two", "Song"))
        // unknown length passes, a known one must agree
        assertTrue(LyricsHitMatch.isSameRecording("Song", "Artis", null, "Song", "Artis", 200, 4.0))
        assertFalse(LyricsHitMatch.isSameRecording("Song", "Artis", 190.0, "Song", "Artis", 200, 4.0))
    }

    private fun quoted(text: String) =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
