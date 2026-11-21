/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTrackRulesTest {
    private val labels = LocalLabels(unknownArtist = "Artis tidak dikenal", untitled = "Tanpa judul")

    private fun track(
        id: Long = 1,
        title: String? = "Lagu",
        artist: String? = "Artis",
        album: String? = "Album",
        durationMs: Long = 200_000,
        displayName: String? = "lagu.mp3",
        folder: String? = "Music/",
    ) = LocalTrack(
        mediaStoreId = id,
        title = title,
        artist = artist,
        album = album,
        albumId = 5,
        durationMs = durationMs,
        dateAddedSec = 100,
        dateModifiedSec = 200,
        year = 2020,
        displayName = displayName,
        folder = folder,
    )

    @Test
    fun `recording and ringtone folders are blocked`() {
        listOf(
            "Recordings/",
            "Recordings/Voice Recorder/",
            "Alarms/",
            "Notifications/",
            "Ringtones/",
            "Podcasts/",
            "Audiobooks/",
            "/storage/emulated/0/Alarms/tone.mp3",
            "/storage/emulated/0/MIUI/sound_recorder/rec.mp3",
            "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/2026/PTT.opus",
            "/storage/emulated/0/Call/call_rec/a.m4a",
            "Music\\Recordings\\x.mp3",
        ).forEach { assertTrue("should block $it", LocalTrackRules.isBlockedFolder(it)) }
    }

    @Test
    fun `normal music folders are allowed`() {
        listOf(
            "Music/",
            "Music/Rock/",
            "Download/",
            "/storage/emulated/0/Music/Alarms of Summer/song.mp3", // the folder name only contains the word
            "/storage/0000-0000/Lagu/x.flac",
            "",
            null,
        ).forEach { assertFalse("should allow $it", LocalTrackRules.isBlockedFolder(it)) }
    }

    @Test
    fun `voice note and ringtone formats are blocked`() {
        assertTrue(LocalTrackRules.isBlockedExtension("note.AMR"))
        assertTrue(LocalTrackRules.isBlockedExtension("tone.mid"))
        assertTrue(LocalTrackRules.isBlockedExtension("clip.3gp"))
        assertFalse(LocalTrackRules.isBlockedExtension("song.mp3"))
        assertFalse(LocalTrackRules.isBlockedExtension("song.flac"))
        assertFalse(LocalTrackRules.isBlockedExtension("noextension"))
        assertFalse(LocalTrackRules.isBlockedExtension(null))
    }

    @Test
    fun `short files are not music`() {
        assertFalse(LocalTrackRules.isEligible(track(durationMs = 29_999)))
        assertTrue(LocalTrackRules.isEligible(track(durationMs = 30_000)))
        assertFalse(LocalTrackRules.isEligible(track(durationMs = 0)))
    }

    @Test
    fun `eligibility combines duration, folder and format`() {
        assertTrue(LocalTrackRules.isEligible(track()))
        assertFalse(LocalTrackRules.isEligible(track(folder = "Recordings/")))
        assertFalse(LocalTrackRules.isEligible(track(displayName = "x.amr")))
    }

    @Test
    fun `placeholder and blank tags are dropped`() {
        assertNull(LocalTrackRules.cleanTag(null))
        assertNull(LocalTrackRules.cleanTag("   "))
        assertNull(LocalTrackRules.cleanTag("<unknown>"))
        assertNull(LocalTrackRules.cleanTag("<UNKNOWN>"))
        assertEquals("Judul", LocalTrackRules.cleanTag("  Judul "))
    }

    @Test
    fun `title falls back to the file name, then to the untitled label`() {
        assertEquals("Judul Asli", LocalTrackRules.titleOf(track(title = "Judul Asli"), labels))
        assertEquals("01 Nama File", LocalTrackRules.titleOf(track(title = "<unknown>", displayName = "01 Nama File.mp3"), labels))
        assertEquals("Tanpa.titik", LocalTrackRules.titleOf(track(title = null, displayName = "Tanpa.titik.flac"), labels))
        assertEquals("polos", LocalTrackRules.titleOf(track(title = null, displayName = "polos"), labels))
        assertEquals("Tanpa judul", LocalTrackRules.titleOf(track(title = null, displayName = null), labels))
    }

    @Test
    fun `artist and album are null when the tag is missing`() {
        assertNull(LocalTrackRules.artistOf(track(artist = "<unknown>")))
        assertNull(LocalTrackRules.albumOf(track(album = " ")))
        assertEquals("Artis", LocalTrackRules.artistOf(track()))
        assertEquals("Album", LocalTrackRules.albumOf(track()))
    }

    @Test
    fun `year and duration are normalised`() {
        assertNull(LocalTrackRules.yearOf(track().copy(year = 0)))
        assertNull(LocalTrackRules.yearOf(track().copy(year = null)))
        assertEquals(1999, LocalTrackRules.yearOf(track().copy(year = 1999)))
        assertEquals(200, LocalTrackRules.durationSeconds(track(durationMs = 200_400)))
        assertEquals(1, LocalTrackRules.durationSeconds(track(durationMs = 100)))
    }

    @Test
    fun `songs that disappeared are removed, but an empty scan removes nothing`() {
        val existing = listOf("LOCAL_1", "LOCAL_2", "LOCAL_3")
        assertEquals(listOf("LOCAL_2"), LocalTrackRules.idsToRemove(existing, setOf("LOCAL_1", "LOCAL_3", "LOCAL_9")))
        assertEquals(emptyList<String>(), LocalTrackRules.idsToRemove(existing, emptySet()))
        assertEquals(emptyList<String>(), LocalTrackRules.idsToRemove(emptyList(), setOf("LOCAL_1")))
        assertEquals(emptyList<String>(), LocalTrackRules.idsToRemove(existing, existing.toSet()))
    }
}
