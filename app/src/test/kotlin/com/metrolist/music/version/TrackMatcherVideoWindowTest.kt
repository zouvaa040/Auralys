/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0
 */

package com.metrolist.music.version

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matcher for the music video of a catalogue song, with titles and lengths taken from the live run of 2026-11-07
 * (VideoReliabilityLiveTest): most music videos of Denny Caknan are 19 to 100 seconds longer than the catalogue audio, and
 * the old 15 s window threw all of them away.
 */
class TrackMatcherVideoWindowTest {
    private fun t(
        id: String,
        title: String,
        artist: String,
        sec: Int,
        video: Boolean = true,
        official: Boolean = false,
    ) = TrackInfo(id, title, listOf(artist), sec, isVideo = video, isOfficialVideo = official)

    private fun audio(
        title: String,
        sec: Int,
        artist: String = "Denny Caknan",
    ) = t("AUDIO", title, artist, sec, video = false)

    /** The window the resolver uses for the video direction. */
    private val wide = com.metrolist.music.video.VideoEligibility.COUNTERPART_MAX_INTRO_SEC

    @Test
    fun `the music video 19 and 37 seconds longer is found with the window, not without it`() {
        val kartonyono = audio("Kartonyono Medot Janji", 271)
        val omv = t("WlmWXoP0C0s", "Kartonyono Medot Janji", "Denny Caknan", 290, official = true)
        assertNull("old window", TrackMatcher.best(kartonyono, listOf(omv), wantVideo = true))
        assertEquals("WlmWXoP0C0s", TrackMatcher.best(kartonyono, listOf(omv), wantVideo = true, maxLongerSec = wide)?.id)

        val losDol = audio("Los Dol", 289)
        val omv2 = t("77s99NET9Mw", "Los Dol", "Denny Caknan", 326, official = true)
        assertNull(TrackMatcher.best(losDol, listOf(omv2), wantVideo = true))
        assertEquals("77s99NET9Mw", TrackMatcher.best(losDol, listOf(omv2), wantVideo = true, maxLongerSec = wide)?.id)
    }

    @Test
    fun `the official video 103 seconds longer is not offered, its sync cannot be known`() {
        val negoro = audio("Negoro Angin", 313)
        val omv = t("WZYUSaHlGlk", "Negoro Angin", "Denny Caknan", 416, official = true)
        assertNull(TrackMatcher.best(negoro, listOf(omv), wantVideo = true, maxLongerSec = wide))
    }

    private val negoroCandidates =
        listOf(
            t("WZYUSaHlGlk", "Negoro Angin", "Denny Caknan", 416, official = true),
            t("FbqsT0lloEY", "DENNY CAKNAN - NEGORO ANGIN LIRIK DAN TERJEMAHAN", "Panen Music", 311),
            t("RpqgTJfxR1A", "DJ TIKTOK TERBARU 2026🎵DJ NEGORO ANGIN - DENNY CAKNAN 🎵DJ TERBELAH JADI DUA", "MAAXWIIN FVNKY", 7029),
            t("Xp5ipdm7kMc", "NEGORO ANGIN - Karaoke Nada WANITA / CEWEK ( Denny Caknan ) Koplo Pargoy Jaranan FEMALE", "Paman DIDIT KARAOKE", 364),
            t("G6Sfyq_lQlc", "Negoro Angin", "Bagus Faisal", 326, official = true),
            t("fOrdcQ9CE5w", "Negoro Angin - Denny Caknan | Lirik Lagu", "Indolirik", 313),
            t("jvMlx5H8UuA", "Negoro Angin - Muslih Fvnky | Lirik Lagu ", "VibeLyrics", 352),
            t("425cFI5nmPk", "Negoro Angin", "Dangdut Everywhere, Dinda Teratu, Hasantoys", 478, official = true),
            t("oJQcZmZHScw", "Negoro Angin (Denny Caknan) Live Muda Saputra Entertainment | VIRAL TIKTOK", "Muda Saputra Entertainment", 425),
        )

    @Test
    fun `of the real candidates of Negoro Angin only the lyric video of the same length is taken, and only with the rule on`() {
        val negoro = audio("Negoro Angin", 313)
        assertNull("without the rule nothing matches", TrackMatcher.best(negoro, negoroCandidates, wantVideo = true, maxLongerSec = wide))
        assertEquals(
            "fOrdcQ9CE5w",
            TrackMatcher.best(negoro, negoroCandidates, wantVideo = true, maxLongerSec = wide, titleNamesArtist = true)?.id,
        )
    }

    @Test
    fun `the lyric rule needs the artist in the title, the same length within 4 seconds, and the same version`() {
        val negoro = audio("Negoro Angin", 313)

        fun best(c: TrackInfo) = TrackMatcher.best(negoro, listOf(c), wantVideo = true, maxLongerSec = wide, titleNamesArtist = true)
        val ok = t("u1", "Negoro Angin - Denny Caknan | Lirik Lagu", "Indolirik", 313)
        assertEquals("u1", best(ok)?.id)
        assertEquals("4 s is still the same recording", "u1", best(ok.copy(durationSec = 317))?.id)
        assertNull("5 s is not", best(ok.copy(durationSec = 318)))
        assertNull("an unknown length is not trusted", best(ok.copy(durationSec = null)))
        assertNull("another artist in the title", best(ok.copy(title = "Negoro Angin - Muslih Fvnky | Lirik Lagu")))
        assertNull("a live version", best(ok.copy(title = "Negoro Angin (Live) - Denny Caknan")))
        assertNull("a cover", best(ok.copy(title = "Negoro Angin Cover - Denny Caknan")))
        assertNull("a remix", best(ok.copy(title = "DJ Negoro Angin Remix - Denny Caknan")))
        assertNull("a part of the artist name is not the artist", best(ok.copy(title = "Negoro Angin - Denny")))
    }

    @Test
    fun `the real artist match always beats a lyric video of a fan channel`() {
        val negoro = audio("Negoro Angin", 313)
        val lyric = t("lyric", "Negoro Angin - Denny Caknan | Lirik Lagu", "Indolirik", 313)
        val official = t("official", "Negoro Angin", "Denny Caknan", 330, official = true)
        assertEquals("official", TrackMatcher.best(negoro, listOf(lyric, official), wantVideo = true, maxLongerSec = wide, titleNamesArtist = true)?.id)
        assertEquals("official", TrackMatcher.best(negoro, listOf(official, lyric), wantVideo = true, maxLongerSec = wide, titleNamesArtist = true)?.id)
    }

    @Test
    fun `the lyric rule does nothing for the audio direction`() {
        val video = t("V", "Negoro Angin", "Denny Caknan", 416, official = true)
        val fan = t("fan", "Negoro Angin - Denny Caknan", "Fan Channel", 416, video = false)
        assertNull(TrackMatcher.best(video, listOf(fan), wantVideo = false, titleNamesArtist = true))
    }

    @Test
    fun `artist names are found as whole words in a title`() {
        assertTrue(TrackMatcher.namesArtist(listOf("Denny Caknan"), "DENNY CAKNAN - NEGORO ANGIN"))
        assertTrue(TrackMatcher.namesArtist(listOf("Denny Caknan"), "Negoro Angin (Denny Caknan) Live"))
        assertTrue(TrackMatcher.namesArtist(listOf("Hindia", "Baskara"), "Everybody Lie - Hindia | Lirik"))
        assertFalse(TrackMatcher.namesArtist(listOf("Denny Caknan"), "Negoro Angin Dennyz Caknan"))
        assertFalse(TrackMatcher.namesArtist(listOf("Ed"), "Ed Sheeran Shape of You"))
        assertFalse(TrackMatcher.namesArtist(emptyList(), "anything"))
    }

    @Test
    fun `more real pairs - the lengths of the live run (34 songs, 2026-11-07)`() {
        // song, video, expected with the window of 45 s and the lyric rule on
        val table =
            listOf(
                Triple(audio("Sugeng Dalu", 360), t("W1e1ZZidrAg", "Sugeng Dalu", "Denny Caknan", 360, official = true), true),
                Triple(audio("Los Dol", 289), t("77s99NET9Mw", "Los Dol", "Denny Caknan", 326, official = true), true),
                Triple(audio("Kartonyono Medot Janji", 271), t("WlmWXoP0C0s", "Kartonyono Medot Janji", "Denny Caknan", 290, official = true), true),
                Triple(audio("Negoro Angin", 313), t("WZYUSaHlGlk", "Negoro Angin", "Denny Caknan", 416, official = true), false),
                Triple(audio("As It Was", 168, "Harry Styles"), t("Y8cnCtzu1Gg", "As It Was", "Harry Styles", 168, official = true), true),
                Triple(audio("bad guy", 195, "Billie Eilish"), t("DyDfgMOUjCI", "bad guy", "Billie Eilish", 206, official = true), true),
                Triple(audio("Dynamite", 200, "BTS"), t("gdZLi9oWNZg", "Dynamite", "BTS", 224, official = true), true),
                Triple(audio("Hello", 296, "Adele"), t("YQHsXMglC9A", "Hello (Official Music Video)", "Adele", 367, official = true), false),
                Triple(audio("Bohemian Rhapsody", 355, "Queen"), t("fJ9rUzIMcZQ", "Bohemian Rhapsody", "Queen", 360, official = true), true),
                Triple(audio("Hati-Hati di Jalan", 242, "Tulus"), t("qC1CfeAT3b4", "HATI-HATI DI JALAN - Tulus | Lirik Lagu", "Catfly", 243), true),
                Triple(audio("Hype Boy", 180, "NewJeans"), t("Axulq2hfrnc", "NewJeans - Hype Boy (Lyrics)", "7clouds K-pop", 179), true),
                Triple(audio("Sambate Ati", 328, "Happy Asmara"), t("jgvfC5FL2wQ", "Happy Asmara - Sambate Ati | Dangdut Koplo (Official Music Video)", "Tupa Records", 333), false),
            )
        for ((song, clip, expected) in table) {
            val found = TrackMatcher.best(song, listOf(clip), wantVideo = true, maxLongerSec = wide, titleNamesArtist = true)
            assertEquals("${song.title} ${song.durationSec}s against ${clip.title} ${clip.durationSec}s", expected, found != null)
        }
    }
}
