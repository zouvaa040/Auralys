/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistTrackChooseMatchTest {
    private fun item(
        id: String,
        title: String,
        artist: String,
        sec: Int,
        video: Boolean = false,
    ) = SongItem(
        id = id,
        title = title,
        artists = listOf(Artist(artist, null)),
        duration = sec,
        musicVideoType = if (video) "MUSIC_VIDEO_TYPE_OMV" else "MUSIC_VIDEO_TYPE_ATV",
        thumbnail = "",
    )

    @Test
    fun `translated song title matches on artist and duration`() {
        val track = ExternalTrack("Spring Day", listOf("BTS"), 274_000)
        val candidates = listOf(
            item("piano", "Spring Day", "Piano Peace", 297),
            item("kr", "봄날", "BTS (방탄소년단)", 275),
        )
        assertEquals("kr", PlaylistLinkParser.chooseMatch(track, candidates)?.id)
    }

    @Test
    fun `music video is accepted when no song exists`() {
        val track = ExternalTrack("Pelangi", listOf("Hivi!"), 221_000)
        val candidates = listOf(
            item("a", "Pelangi", "Hivi!", 218, video = true),
            item("b", "Pelangi", "Hivi!", 222, video = true),
        )
        assertEquals("b", PlaylistLinkParser.chooseMatch(track, candidates)?.id)
    }

    @Test
    fun `title with artist prefix on a channel is accepted`() {
        val track = ExternalTrack("Pelangi", listOf("Hivi!"), 218_000)
        val candidates = listOf(item("c", "HIVI! - Pelangi", "GuanPetteson", 218, video = true))
        assertEquals("c", PlaylistLinkParser.chooseMatch(track, candidates)?.id)
    }

    @Test
    fun `same title by another artist with a far duration is rejected`() {
        val track = ExternalTrack("Spring Day", listOf("BTS"), 274_000)
        val candidates = listOf(item("piano", "Spring Day", "Piano Peace", 297))
        assertNull(PlaylistLinkParser.chooseMatch(track, candidates))
    }

    @Test
    fun `different edit length is rejected by the strict match but found by the closest match`() {
        val track = ExternalTrack("Cinta Mulia", listOf("Koes Plus"), 173_000)
        val candidates = listOf(
            item("tiara", "CINTA MULIA", "Tiara Amora dan Simpatik Music", 398),
            item("song", "Cinta Mulia", "Koes Plus", 197),
            item("video", "Koes Plus - Cinta Mulia", "Koes Plus", 198, video = true),
        )
        assertNull(PlaylistLinkParser.chooseMatch(track, candidates))
        assertEquals("song", PlaylistLinkParser.chooseClosestMatch(track, candidates)?.id)
    }

    @Test
    fun `closest match still needs the same artist`() {
        val track = ExternalTrack("Cinta Mulia", listOf("Koes Plus"), 173_000)
        val candidates = listOf(item("tiara", "CINTA MULIA", "Tiara Amora dan Simpatik Music", 175))
        assertNull(PlaylistLinkParser.chooseClosestMatch(track, candidates))
    }

    @Test
    fun `closer duration wins between two same title songs`() {
        val track = ExternalTrack("Song", listOf("Artist"), 200_000)
        val candidates = listOf(
            item("far", "Song", "Artist", 208),
            item("near", "Song", "Artist", 201),
        )
        assertEquals("near", PlaylistLinkParser.chooseMatch(track, candidates)?.id)
    }
}
