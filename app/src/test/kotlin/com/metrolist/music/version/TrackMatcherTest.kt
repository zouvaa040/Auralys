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

/** Fixtures only, no network. Titles follow the owner's "negoro angin" example. */
class TrackMatcherTest {
    private fun track(
        id: String,
        title: String,
        artist: String,
        sec: Int?,
        video: Boolean,
        official: Boolean = false,
    ) = TrackInfo(id, title, listOf(artist), sec, isVideo = video, isOfficialVideo = official)

    private val audio = track("aud", "Negoro Angin", "Denny Caknan", 224, video = false)
    private val omv = track("omv", "NEGORO ANGIN (Official Music Video)", "Denny Caknan", 231, video = true, official = true)

    @Test
    fun `packaging is dropped and the title proper stays`() {
        assertEquals("negoro angin", TrackMatcher.parseTitle("NEGORO ANGIN (Official Music Video)").core)
        assertEquals("negoro angin", TrackMatcher.parseTitle("Negoro Angin [Lirik]").core)
        assertEquals("negoro angin", TrackMatcher.parseTitle("Negoro Angin Official Video").core)
        assertEquals("negoro angin", TrackMatcher.parseTitle("Negoro Angin (feat. Rafiq)").core)
        assertEquals("negoro angin", TrackMatcher.parseTitle("Negoro Angin feat. Rafiq").core)
        assertEquals("paniyon sa", TrackMatcher.parseTitle("Paniyon Sa (From \"Satyamev Jayate\")").core)
        assertEquals("negoro angin", TrackMatcher.parseTitle("Negoro Angin | Denny Caknan", listOf("Denny Caknan")).core)
    }

    @Test
    fun `artist prefix is dropped when it names the artist`() {
        val parts = TrackMatcher.parseTitle("Denny Caknan - Negoro Angin (Official Audio)", listOf("Denny Caknan"))
        assertEquals("negoro angin", parts.core)
        assertTrue(parts.versions.isEmpty())
    }

    @Test
    fun `version markers are collected from brackets, suffixes and the tail`() {
        assertEquals(setOf("live"), TrackMatcher.parseTitle("Negoro Angin (Live)").versions)
        assertEquals(setOf("live"), TrackMatcher.parseTitle("Negoro Angin Live").versions)
        assertEquals(setOf("remix"), TrackMatcher.parseTitle("Negoro Angin - Remix").versions)
        assertEquals(setOf("spedup"), TrackMatcher.parseTitle("Negoro Angin (Sped Up)").versions)
        assertEquals(setOf("slowed", "reverb"), TrackMatcher.parseTitle("Negoro Angin (Slowed + Reverb)").versions)
        assertEquals(setOf("8d"), TrackMatcher.parseTitle("Negoro Angin (8D Audio)").versions)
        assertEquals(setOf("acoustic"), TrackMatcher.parseTitle("Negoro Angin (Akustik)").versions)
        assertEquals(setOf("instrumental"), TrackMatcher.parseTitle("Negoro Angin (Instrumental)").versions)
        assertTrue(TrackMatcher.parseTitle("Negoro Angin (Official Music Video)").versions.isEmpty())
    }

    @Test
    fun `a title made only of marker words is kept`() {
        assertEquals("live", TrackMatcher.parseTitle("Live").core)
        assertEquals("live and let die", TrackMatcher.parseTitle("Live and Let Die").core)
    }

    @Test
    fun `video finds the catalogue audio`() {
        val others =
            listOf(
                track("cover", "Negoro Angin (Cover)", "Nina", 230, video = false),
                track("other", "Negoro Angin", "Muslih Fvnky", 224, video = false),
                audio,
            )
        assertEquals("aud", TrackMatcher.best(omv, others, wantVideo = false)?.id)
    }

    @Test
    fun `audio finds the music video and prefers the official one`() {
        val ugc = track("ugc", "Negoro Angin - Denny Caknan (Lirik)", "Denny Caknan Fans", 225, video = true)
        val candidates = listOf(ugc, omv)
        assertEquals("omv", TrackMatcher.best(audio, candidates, wantVideo = true)?.id)
    }

    @Test
    fun `an audio candidate never answers a request for a video and the other way round`() {
        assertNull(TrackMatcher.best(omv, listOf(omv.copy(id = "v2")), wantVideo = false))
        assertNull(TrackMatcher.best(audio, listOf(audio.copy(id = "a2")), wantVideo = true))
    }

    @Test
    fun `different version is rejected in both directions`() {
        val remix = track("remix", "Negoro Angin (Remix)", "Denny Caknan", 224, video = false)
        val live = track("live", "Negoro Angin (Live)", "Denny Caknan", 224, video = false)
        val spedUp = track("sped", "Negoro Angin (Sped Up)", "Denny Caknan", 180, video = false)
        assertNull(TrackMatcher.best(omv, listOf(remix, live, spedUp), wantVideo = false))
        // a live video must not turn into the studio audio
        val liveVideo = track("lv", "Negoro Angin (Live)", "Denny Caknan", 224, video = true)
        assertNull(TrackMatcher.best(liveVideo, listOf(audio), wantVideo = false))
        assertEquals("live", TrackMatcher.best(liveVideo, listOf(audio, live), wantVideo = false)?.id)
    }

    @Test
    fun `different artist is rejected`() {
        val coverBand = track("c", "Negoro Angin", "Guyon Waton", 224, video = false)
        assertNull(TrackMatcher.best(omv, listOf(coverBand), wantVideo = false))
        // no artist on either side: nothing vouches for the match
        assertNull(TrackMatcher.best(omv.copy(artists = emptyList()), listOf(audio), wantVideo = false))
    }

    @Test
    fun `topic channel artist counts as the same artist`() {
        val topic = audio.copy(id = "t", artists = listOf("Denny Caknan - Topic"))
        assertEquals("t", TrackMatcher.best(omv, listOf(topic), wantVideo = false)?.id)
    }

    @Test
    fun `duration only ranks inside the window and rejects beyond it`() {
        val near = audio.copy(id = "near", durationSec = 229)
        val far = audio.copy(id = "far", durationSec = 240)
        val tooFar = audio.copy(id = "toofar", durationSec = 231 + 16)
        assertEquals("near", TrackMatcher.best(omv, listOf(far, near), wantVideo = false)?.id)
        assertNull(TrackMatcher.best(omv, listOf(tooFar), wantVideo = false))
        // exactly 15 s is still accepted, unknown length is accepted
        assertEquals("edge", TrackMatcher.best(omv, listOf(audio.copy(id = "edge", durationSec = 216)), wantVideo = false)?.id)
        assertEquals("nolen", TrackMatcher.best(omv, listOf(audio.copy(id = "nolen", durationSec = null)), wantVideo = false)?.id)
    }

    @Test
    fun `the upload itself and episodes are never returned`() {
        assertNull(TrackMatcher.best(audio, listOf(audio), wantVideo = false))
        assertNull(TrackMatcher.best(omv, listOf(audio.copy(isEpisode = true)), wantVideo = false))
    }

    @Test
    fun `sameRecording works for ranking without the length window`() {
        val longIntro = audio.copy(id = "x", durationSec = 400)
        assertFalse(TrackMatcher.sameRecording(omv, longIntro))
        assertTrue(TrackMatcher.sameRecording(omv, longIntro, strictDuration = false))
        assertFalse(TrackMatcher.sameRecording(omv, audio.copy(id = "y", title = "Negoro Angin (Remix)"), strictDuration = false))
    }

    @Test
    fun `queries use the cleaned title with the artist first and never the raw title`() {
        val q = TrackMatcher.queries(omv)
        assertEquals(listOf("negoro angin denny caknan", "negoro angin"), q)
        assertEquals(
            listOf("negoro angin sped up denny caknan", "negoro angin sped up"),
            TrackMatcher.queries(omv.copy(title = "Negoro Angin (Sped Up) [Official Video]")),
        )
        assertTrue(TrackMatcher.queries(omv.copy(title = "   ")).isEmpty())
    }
}
