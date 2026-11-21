/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0
 */

package com.metrolist.music.version

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reader of the Song | Video wrapper of the watch panel. The sample has the shape other YouTube Music clients document
 * (`playlistPanelVideoWrapperRenderer` with `primaryRenderer`, `counterpart[]` and a `segmentMap`). NOTE: a live signed-out
 * answer never carried this wrapper (measured 2026-11-07 on 4 songs); the parser is tolerant so an answer of another shape
 * is simply "no counterpart".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OfficialCounterpartParserTest {
    private fun renderer(
        id: String,
        title: String,
        length: String,
        type: String,
        artist: String = "Denny Caknan",
        artistId: String? = "UCdenny",
    ): String {
        val link = if (artistId != null) ""","navigationEndpoint":{"browseEndpoint":{"browseId":"$artistId"}}""" else ""
        return """{"playlistPanelVideoRenderer":{
            "title":{"runs":[{"text":"$title"}]},
            "lengthText":{"runs":[{"text":"$length"}]},
            "longBylineText":{"runs":[{"text":"$artist"$link},{"text":" • "},{"text":"Album"},{"text":" • "},{"text":"2024"}]},
            "videoId":"$id",
            "thumbnail":{"thumbnails":[{"url":"https://i/small","width":60},{"url":"https://i/$id","width":544}]},
            "navigationEndpoint":{"watchEndpoint":{"videoId":"$id","watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"$type"}}}}
        }}"""
    }

    private fun wrapperEntry(
        primary: String,
        counterpart: String,
        segment: String? = """{"primaryVideoStartTimeMilliseconds":"0","counterpartVideoStartTimeMilliseconds":"12500","durationMilliseconds":"200000"}""",
    ): String {
        val map = if (segment != null) ""","segmentMap":{"segment":[$segment]}""" else ""
        return """{"playlistPanelVideoWrapperRenderer":{"primaryRenderer":$primary,"counterpart":[{"counterpartRenderer":$counterpart$map}]}}"""
    }

    private fun answer(vararg entries: String): String =
        """{"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[
            {"tabRenderer":{"content":{"musicQueueRenderer":{"content":{"playlistPanelRenderer":{"contents":[${entries.joinToString(",")}]}}}}}}
        ]}}}}}"""

    private val audio = renderer("AUDIO000001", "Kartonyono Medot Janji", "4:31", "MUSIC_VIDEO_TYPE_ATV")
    private val clip = renderer("CLIP0000001", "Kartonyono Medot Janji", "4:50", "MUSIC_VIDEO_TYPE_OMV")

    @Test
    fun `the counterpart of the asked audio is read with its length, type and artist`() {
        val result = OfficialCounterpartParser.parse(answer(wrapperEntry(audio, clip)), "AUDIO000001")
        assertNotNull(result)
        val song = result!!.song
        assertEquals("CLIP0000001", song.id)
        assertEquals("Kartonyono Medot Janji", song.title)
        assertEquals(290, song.duration)
        assertEquals("MUSIC_VIDEO_TYPE_OMV", song.musicVideoType)
        assertEquals("Denny Caknan", song.artists.single().name)
        assertEquals("UCdenny", song.artists.single().id)
        assertEquals("https://i/CLIP0000001", song.thumbnail)
        assertTrue(song.isVideoSong)
    }

    @Test
    fun `the segment map gives the measured offset, the video starts 12_5 s into the video for the audio start`() {
        val result = OfficialCounterpartParser.parse(answer(wrapperEntry(audio, clip)), "AUDIO000001")!!
        assertEquals(12_500L, result.offsetMs)
        assertEquals(1, result.segments)
    }

    @Test
    fun `asking with the id of the video gives the audio, and the offset turns round`() {
        val result = OfficialCounterpartParser.parse(answer(wrapperEntry(audio, clip)), "CLIP0000001")!!
        assertEquals("AUDIO000001", result.song.id)
        assertEquals(-12_500L, result.offsetMs)
    }

    @Test
    fun `a map that starts later on the audio side - the audio starts 5 s in, the video 17_5 s - is the difference`() {
        val seg = """{"primaryVideoStartTimeMilliseconds":5000,"counterpartVideoStartTimeMilliseconds":17500,"durationMilliseconds":1000}"""
        val result = OfficialCounterpartParser.parse(answer(wrapperEntry(audio, clip, seg)), "AUDIO000001")!!
        assertEquals(12_500L, result.offsetMs)
    }

    @Test
    fun `no segment map means no offset, and an absurd one is not trusted`() {
        assertNull(OfficialCounterpartParser.parse(answer(wrapperEntry(audio, clip, segment = null)), "AUDIO000001")!!.offsetMs)
        val huge = """{"primaryVideoStartTimeMilliseconds":"0","counterpartVideoStartTimeMilliseconds":"600000","durationMilliseconds":"1"}"""
        assertNull(OfficialCounterpartParser.parse(answer(wrapperEntry(audio, clip, huge)), "AUDIO000001")!!.offsetMs)
        val garbage = """{"primaryVideoStartTimeMilliseconds":"abc"}"""
        assertNull(OfficialCounterpartParser.parse(answer(wrapperEntry(audio, clip, garbage)), "AUDIO000001")!!.offsetMs)
    }

    @Test
    fun `only the entry of the asked song counts`() {
        val other = wrapperEntry(renderer("OTHERAUDIO1", "Los Dol", "4:49", "MUSIC_VIDEO_TYPE_ATV"), renderer("OTHERCLIP01", "Los Dol", "5:26", "MUSIC_VIDEO_TYPE_OMV"))
        val result = OfficialCounterpartParser.parse(answer(other, wrapperEntry(audio, clip)), "AUDIO000001")
        assertEquals("CLIP0000001", result?.song?.id)
        assertNull(OfficialCounterpartParser.parse(answer(other), "AUDIO000001"))
    }

    @Test
    fun `an answer without a wrapper - the live signed-out shape - has no counterpart`() {
        assertNull(OfficialCounterpartParser.parse(answer(audio), "AUDIO000001"))
    }

    @Test
    fun `anything else is no counterpart and never an exception`() {
        assertNull(OfficialCounterpartParser.parse("", "x"))
        assertNull(OfficialCounterpartParser.parse("not json", "x"))
        assertNull(OfficialCounterpartParser.parse("[]", "x"))
        assertNull(OfficialCounterpartParser.parse("{}", "x"))
        assertNull(OfficialCounterpartParser.parse("""{"contents":{"singleColumnMusicWatchNextResultsRenderer":{}}}""", "x"))
        // a counterpart that lacks its title or id
        val broken = """{"playlistPanelVideoWrapperRenderer":{"primaryRenderer":$audio,"counterpart":[{"counterpartRenderer":{"playlistPanelVideoRenderer":{"videoId":"CLIP0000001"}}}]}}"""
        assertNull(OfficialCounterpartParser.parse(answer(broken), "AUDIO000001"))
    }

    @Test
    fun `artists without a channel link are split into names`() {
        val plain = renderer("CLIP0000001", "Title", "3:00", "MUSIC_VIDEO_TYPE_OMV", artist = "Hindia, Lomba Sihir & Baskara", artistId = null)
        val result = OfficialCounterpartParser.parse(answer(wrapperEntry(audio, plain)), "AUDIO000001")!!
        assertEquals(listOf("Hindia", "Lomba Sihir", "Baskara"), result.song.artists.map { it.name })
    }
}
