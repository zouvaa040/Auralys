/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsHitMatchTest {
    @Test
    fun `a video name Artist - Title matches a hit that holds only the title`() {
        assertTrue(LyricsHitMatch.sameTitle("Kartonyono Medot Janji", "Denny Caknan - Kartonyono Medot Janji"))
        assertTrue(LyricsHitMatch.sameTitle("Denny Caknan - Kartonyono Medot Janji", "Kartonyono Medot Janji"))
        // the packaging is stripped by LyricsQuery first, so the wanted title is what the provider sees
        assertTrue(LyricsHitMatch.sameTitle("Kartonyono Medot Janji", LyricsQuery.title("Denny Caknan - Kartonyono Medot Janji (Official Music Video)")))
    }

    @Test
    fun `whole recording hit matches when both sides use the Artist - Title shape`() {
        assertTrue(LyricsHitMatch.sameTitle("Denny Caknan - Kartonyono Medot Janji", "Denny Caknan - Kartonyono Medot Janji"))
    }

    @Test
    fun `a different song is still rejected`() {
        assertFalse(LyricsHitMatch.sameTitle("Lagu Lain", "Denny Caknan - Kartonyono Medot Janji"))
        assertFalse(LyricsHitMatch.sameTitle("Kartonyono Medot", "Denny Caknan - Kartonyono Medot Janji"))
    }

    @Test
    fun `version words still decide with the Artist - Title shape`() {
        assertFalse(LyricsHitMatch.sameTitle("Song (Live)", "Artis - Song"))
        assertFalse(LyricsHitMatch.sameTitle("Song", "Artis - Song (Remix)"))
        assertTrue(LyricsHitMatch.sameTitle("Song (Live)", "Artis - Song (Live)"))
        assertTrue(LyricsHitMatch.sameTitle("Song - Live", "Song (Live)"))
        assertFalse(LyricsHitMatch.sameTitle("Song - Live Version", "Song"))
    }

    @Test
    fun `suffix labels are still ignored`() {
        assertTrue(LyricsHitMatch.sameTitle("Song - 2011 Remaster", "Song"))
        assertTrue(LyricsHitMatch.sameTitle("Song", "Song - Remastered 2011"))
    }

    @Test
    fun `isSameRecording accepts the right song of a video name and still checks artist and length`() {
        val wanted = LyricsQuery.title("Denny Caknan - Kartonyono Medot Janji (Official Music Video)")
        assertTrue(LyricsHitMatch.isSameRecording("Kartonyono Medot Janji", "Denny Caknan", 240.0, wanted, "Denny Caknan", 242, 4.0))
        assertFalse(LyricsHitMatch.isSameRecording("Kartonyono Medot Janji", "Denny Caknan", 200.0, wanted, "Denny Caknan", 242, 4.0))
        assertFalse(LyricsHitMatch.isSameRecording("Kartonyono Medot Janji", "Penyanyi Lain", 240.0, wanted, "Denny Caknan", 242, 4.0))
    }
}
