/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Songs that live on the phone (MediaStore audio). The idea of reading MediaStore on demand and playing
 * content:// URIs is the one BitChord uses (GPLv3); this is a fresh implementation on top of Auralys' Room
 * tables, so nothing here needs a schema change.
 */

package com.metrolist.music.local

import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.models.MediaMetadata
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * Ids and addresses of device songs.
 *
 * A song from the phone is a normal song row whose id is `LOCAL_<MediaStore id>`. The address to play is derived
 * from that id, so nothing else has to be stored. Artists and albums made from the same files get ids that no
 * YouTube screen can mistake for a browse id.
 */
object LocalMusic {
    const val SONG_PREFIX = "LOCAL_"
    const val ARTIST_PREFIX = "LOCAL_ARTIST_"
    const val ALBUM_PREFIX = "LOCAL_ALBUM_"

    /** The one artist row used for files with no artist tag. Its name is written at scan time (it follows the app language). */
    const val UNKNOWN_ARTIST_ID = ARTIST_PREFIX + "unknown"

    /** Address scheme of the cover of a device song: `localaudio://<MediaStore id>`. Loaded by [LocalCoverFetcher]. */
    const val COVER_SCHEME = "localaudio"

    private const val AUDIO_URI_BASE = "content://media/external/audio/media/"

    fun songIdFor(mediaStoreId: Long): String = SONG_PREFIX + mediaStoreId

    /** The MediaStore id inside a song id, or null when [id] is not a device song id (a YouTube id, an artist id, ...). */
    fun mediaStoreIdOf(id: String?): Long? {
        if (id == null || !id.startsWith(SONG_PREFIX)) return null
        val digits = id.substring(SONG_PREFIX.length)
        if (digits.isEmpty() || digits.length > 18 || !digits.all { it in '0'..'9' }) return null
        return digits.toLongOrNull()
    }

    fun isLocalSongId(id: String?): Boolean = mediaStoreIdOf(id) != null

    fun isLocalArtistId(id: String?): Boolean = id != null && id.startsWith(ARTIST_PREFIX)

    fun isLocalAlbumId(id: String?): Boolean = id != null && id.startsWith(ALBUM_PREFIX)

    /** `content://media/external/audio/media/<id>` for a device song id, or null for any other id. */
    fun contentUriOf(songId: String?): String? = mediaStoreIdOf(songId)?.let { AUDIO_URI_BASE + it }

    /** The address stored as the cover of a device song. */
    fun coverUrlOf(mediaStoreId: Long): String = "$COVER_SCHEME://$mediaStoreId"

    /** The MediaStore id inside a cover address made by [coverUrlOf], or null. */
    fun mediaStoreIdOfCover(url: String?): Long? {
        val prefix = "$COVER_SCHEME://"
        if (url == null || !url.startsWith(prefix)) return null
        return url.substring(prefix.length).substringBefore('?').substringBefore('/').toLongOrNull()
    }

    fun albumIdFor(mediaStoreAlbumId: Long): String = ALBUM_PREFIX + mediaStoreAlbumId

    /** The same name always gives the same id (case, accents-as-composed and spacing do not matter), so a rescan finds the artist again. */
    fun artistIdFor(name: String): String {
        val key = Normalizer.normalize(name.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(16)
        for (i in 0 until 8) hex.append("%02x".format(digest[i]))
        return ARTIST_PREFIX + hex
    }
}

/** True for a song that lives on the phone: it has no YouTube video, so YouTube-only menu actions must be hidden or skipped. */
val Song.isLocalSong: Boolean get() = LocalMusic.isLocalSongId(song.id)

val SongEntity.isLocalSong: Boolean get() = LocalMusic.isLocalSongId(id)

val MediaMetadata.isLocalSong: Boolean get() = LocalMusic.isLocalSongId(id)
