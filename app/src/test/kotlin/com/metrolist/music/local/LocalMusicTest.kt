/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMusicTest {
    @Test
    fun `song id and MediaStore id convert both ways`() {
        assertEquals("LOCAL_1234", LocalMusic.songIdFor(1234L))
        assertEquals(1234L, LocalMusic.mediaStoreIdOf("LOCAL_1234"))
        assertTrue(LocalMusic.isLocalSongId("LOCAL_0"))
    }

    @Test
    fun `ids that are not device songs are rejected`() {
        assertNull(LocalMusic.mediaStoreIdOf(null))
        assertNull(LocalMusic.mediaStoreIdOf(""))
        assertNull(LocalMusic.mediaStoreIdOf("dQw4w9WgXcQ")) // a YouTube id
        assertNull(LocalMusic.mediaStoreIdOf("LOCAL_"))
        assertNull(LocalMusic.mediaStoreIdOf("LOCAL_12a"))
        assertNull(LocalMusic.mediaStoreIdOf("LOCAL_-5"))
        assertNull(LocalMusic.mediaStoreIdOf("LOCAL_ARTIST_ab12"))
        assertNull(LocalMusic.mediaStoreIdOf("LOCAL_ALBUM_7"))
        assertNull(LocalMusic.mediaStoreIdOf("LOCAL_" + "9".repeat(30))) // would overflow a Long
        assertFalse(LocalMusic.isLocalSongId("local_5"))
    }

    @Test
    fun `content address is derived from the id`() {
        assertEquals("content://media/external/audio/media/42", LocalMusic.contentUriOf("LOCAL_42"))
        assertNull(LocalMusic.contentUriOf("dQw4w9WgXcQ"))
        assertNull(LocalMusic.contentUriOf(null))
    }

    @Test
    fun `cover address round trips`() {
        assertEquals("localaudio://77", LocalMusic.coverUrlOf(77L))
        assertEquals(77L, LocalMusic.mediaStoreIdOfCover("localaudio://77"))
        assertEquals(77L, LocalMusic.mediaStoreIdOfCover("localaudio://77?size=2"))
        assertNull(LocalMusic.mediaStoreIdOfCover("https://i.ytimg.com/vi/abc/hq.jpg"))
        assertNull(LocalMusic.mediaStoreIdOfCover("localaudio://abc"))
        assertNull(LocalMusic.mediaStoreIdOfCover(null))
    }

    @Test
    fun `artist id is stable and ignores case and spacing around the name`() {
        val id = LocalMusic.artistIdFor("Denny Caknan")
        assertTrue(id.startsWith(LocalMusic.ARTIST_PREFIX))
        assertEquals(id, LocalMusic.artistIdFor("  denny caknan "))
        assertEquals(id, LocalMusic.artistIdFor("DENNY CAKNAN"))
        assertNotEquals(id, LocalMusic.artistIdFor("Denny Caknan 2"))
        assertTrue(LocalMusic.isLocalArtistId(id))
        assertFalse(LocalMusic.isLocalSongId(id))
    }

    @Test
    fun `artist and album ids cannot be taken for YouTube browse ids`() {
        assertFalse(LocalMusic.UNKNOWN_ARTIST_ID.startsWith("UC"))
        assertTrue(LocalMusic.isLocalAlbumId(LocalMusic.albumIdFor(9L)))
        assertFalse(LocalMusic.isLocalAlbumId("MPREb_abc"))
        assertFalse(LocalMusic.isLocalArtistId("UCabc"))
    }

    @Test
    fun `only content and file addresses are device files`() {
        assertTrue(isDeviceScheme("content"))
        assertTrue(isDeviceScheme("file"))
        assertFalse(isDeviceScheme("https"))
        assertFalse(isDeviceScheme(null)) // a bare YouTube id has no scheme
    }

    @Test
    fun `menu guard tells a phone song from a YouTube song`() {
        val local = com.metrolist.music.models.MediaMetadata(id = "LOCAL_77", title = "t", artists = emptyList(), duration = 10)
        val remote = local.copy(id = "dQw4w9WgXcQ")
        assertTrue(local.isLocalSong)
        assertFalse(remote.isLocalSong)
        assertTrue(com.metrolist.music.db.entities.SongEntity(id = "LOCAL_77", title = "t").isLocalSong)
        assertFalse(com.metrolist.music.db.entities.SongEntity(id = "LOCAL_ARTIST_ab", title = "t").isLocalSong)
        assertTrue(com.metrolist.music.db.entities.Song(com.metrolist.music.db.entities.SongEntity(id = "LOCAL_5", title = "t"), emptyList()).isLocalSong)
        assertFalse(com.metrolist.music.db.entities.Song(com.metrolist.music.db.entities.SongEntity(id = "abc", title = "t"), emptyList()).isLocalSong)
    }
}
