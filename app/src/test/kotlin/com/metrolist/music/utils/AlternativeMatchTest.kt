/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.innertubex.extraction.StreamResolveException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 4.3.5: the last-resort alternative upload and the "never final before the direct clients" rule. The candidates
 * are the real search answers recorded by the live probe on 2026-11-13 (scratchpad X/probe1.txt).
 */
class AlternativeMatchTest {
    private fun c(
        id: String,
        title: String,
        artist: String,
        seconds: Int,
        type: String,
    ) = AlternativeMatch.Candidate(id, title, listOf(artist), seconds, type)

    private val omv = "MUSIC_VIDEO_TYPE_OMV"
    private val ugc = "MUSIC_VIDEO_TYPE_UGC"

    @Test
    fun `the official audio of the same recording is picked`() {
        val brooklyn = AlternativeMatch.Song("ntZONYzAMCo", "Brooklyn Baby", listOf("Lana Del Rey"), 352)
        val pick =
            AlternativeMatch.pick(
                brooklyn,
                listOf(
                    c("T5xcnjAG8pE", "Brooklyn Baby (Official Audio)", "Lana Del Rey", 354, omv),
                    c("pzCQQPuqapw", "Lana Del Rey - Brooklyn Baby (Lyrics)", "Rebel Rythm", 354, ugc),
                    c("GxNsNSSJV2s", "Lana Del Rey - Brooklyn Baby // Tradução", "sadland", 350, ugc),
                ),
            )
        assertEquals("T5xcnjAG8pE", pick?.videoId)

        val toosie = AlternativeMatch.Song("eqMj9DTQcAQ", "Toosie Slide", listOf("Drake"), 248)
        val toosiePick =
            AlternativeMatch.pick(
                toosie,
                listOf(
                    // the music video has an intro: 312 s, refused
                    c("xWggTb45brM", "Toosie Slide (Official Music Video)", "Drake", 312, omv),
                    c("dGYxT1QReQs", "Toosie Slide (Official Explicit Audio)", "Drake", 249, omv),
                    c("x7IBEq9iOkc", "Drake - Toosie Slide (Lyrics)", "Unique Vibes", 248, ugc),
                ),
            )
        assertEquals("dGYxT1QReQs", toosiePick?.videoId)

        // "(feat. Chris Brown)" is not needed in the upload's title; another song of a guest is refused
        val notYouToo = AlternativeMatch.Song("12NrPQ9cxyg", "Not You Too (feat. Chris Brown)", listOf("Drake"), 270)
        val nytPick =
            AlternativeMatch.pick(
                notYouToo,
                listOf(
                    c("oOni4BMeMp0", "No Guidance (Audio) (feat. Drake)", "Chris Brown", 263, omv),
                    c("ZX_mvoY_Hg0", "Not You Too (Audio)", "Drake", 271, omv),
                ),
            )
        assertEquals("ZX_mvoY_Hg0", nytPick?.videoId)

        val anak = AlternativeMatch.Song("vRj31u2UIlU", "Anak Kampung", listOf("Maman Fvndy"), 237)
        val anakPick =
            AlternativeMatch.pick(
                anak,
                listOf(
                    c("Cxd3jhMkjgk", "Anak Kampung", "MAMAN FVNDY", 237, omv),
                    c("97TxSx2BHlQ", "DJ DAYAK X ANAK KAMPUNG KERJAKU KERJA MOTONG SLOW VIRAL TIKTOK", "Maman Fvndy Lite", 267, ugc),
                ),
            )
        assertEquals("Cxd3jhMkjgk", anakPick?.videoId)
    }

    @Test
    fun `fan uploads, other versions, other lengths and the song itself are never taken`() {
        val song = AlternativeMatch.Song("a", "Monolog", listOf("Pamungkas"), 230)
        assertNull(AlternativeMatch.pick(song, listOf(c("b", "Monolog (Lyrics)", "Pamungkas", 230, ugc))))
        assertNull(AlternativeMatch.pick(song, listOf(c("b", "Monolog (Live at Jakarta)", "Pamungkas", 231, omv))))
        assertNull(AlternativeMatch.pick(song, listOf(c("b", "Monolog (Sped Up)", "Pamungkas", 229, omv))))
        assertNull(AlternativeMatch.pick(song, listOf(c("b", "Monolog", "Pamungkas", 250, omv))))
        assertNull(AlternativeMatch.pick(song, listOf(c("b", "Monolog", "Someone Else", 230, omv))))
        assertNull(AlternativeMatch.pick(song, listOf(c("a", "Monolog", "Pamungkas", 230, omv))))
        // the song's own version word is fine
        val live = AlternativeMatch.Song("a", "Monolog (Live)", listOf("Pamungkas"), 230)
        assertEquals("b", AlternativeMatch.pick(live, listOf(c("b", "Monolog (Live Session)", "Pamungkas", 231, omv)))?.videoId)
        // no length known: nothing is taken
        assertNull(AlternativeMatch.pick(AlternativeMatch.Song("a", "Monolog", listOf("Pamungkas"), null), listOf(c("b", "Monolog", "Pamungkas", 230, omv))))
    }

    @Test
    fun `text helpers work without regex`() {
        assertEquals("not you too", AlternativeMatch.normalize(AlternativeMatch.withoutBrackets("Not You Too (feat. Chris Brown)")))
        assertEquals("光年之外 g e m", AlternativeMatch.normalize("光年之外 - G.E.M."))
    }

    @Test
    fun `a failure is final only for the network, an age gate or a missing music video`() {
        fun e(reason: StreamResolveException.Reason) = StreamResolveException(reason = reason, message = "x")
        assertTrue(InnerTubeXPlayer.isRescuable(e(StreamResolveException.Reason.UNAVAILABLE)))
        assertTrue(InnerTubeXPlayer.isRescuable(e(StreamResolveException.Reason.NO_PLAYABLE_STREAM)))
        assertTrue(InnerTubeXPlayer.isRescuable(e(StreamResolveException.Reason.EXPLICIT_UNSUPPORTED)))
        assertTrue(InnerTubeXPlayer.isRescuable(NoClassDefFoundError("com.dokar.quickjs.QuickJs")))
        assertFalse(InnerTubeXPlayer.isRescuable(e(StreamResolveException.Reason.NETWORK)))
        assertFalse(InnerTubeXPlayer.isRescuable(e(StreamResolveException.Reason.AGE_RESTRICTED)))
        assertFalse(InnerTubeXPlayer.isRescuable(java.io.IOException("x")))
        assertEquals(listOf("VISIONOS_0_1", "VISIONOS"), InnerTubeXPlayer.RESCUE_PROFILES)
    }
}
