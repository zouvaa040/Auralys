/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.local

import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.AlbumArtistMap
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.SongAlbumMap
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** What one scan changed. */
data class ScanResult(
    val added: Int,
    val updated: Int,
    val removed: Int,
    /** Songs the user had touched (like, playlist, history) whose file is gone: kept, hidden until the file is back. */
    val unavailable: Int = 0,
    /** True when many songs looked gone at once (card out, MediaStore reset): nothing was removed. */
    val removalSkipped: Boolean = false,
    /** Device songs in the library after the scan. */
    val total: Int,
)

/**
 * Brings the song, artist and album tables in line with the audio files on the phone.
 *
 * The files are read in small batches and each batch is one transaction, so memory stays flat for thousands of
 * songs and an interrupted scan leaves the tables consistent. Only rows made here (ids `LOCAL_...`) are touched.
 *
 * A song that was not seen is not simply deleted (that would also empty playlists, likes and history):
 *  1. nothing is removed when a scan fails half way (the exception leaves before the removal step);
 *  2. nothing is removed when many songs vanish at once ([massRemovalLooksWrong]: a memory card that is out, a MediaStore reset);
 *  3. a song whose file can still be opened ([fileExists]) is kept;
 *  4. a song the user touched is kept but marked unavailable (`isLocal = 0`: hidden from "Di perangkat", still in its playlists),
 *     and comes back by itself when the file is seen again;
 *  5. only the other songs, which carry nothing of the user, are deleted.
 */
class LocalLibrarySync(
    private val database: MusicDatabase,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    /** True when the file behind a MediaStore id can still be read. The default says "gone" (the scanner passes a real check). */
    private val fileExists: (Long) -> Boolean = { false },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun run(
        source: LocalAudioSource,
        labels: LocalLabels,
        onProgress: (Int) -> Unit = {},
    ): ScanResult =
        withContext(Dispatchers.IO) {
            val existingIds = database.localSongIds()
            val seen = HashSet<String>(existingIds.size.coerceAtLeast(16))
            val ensuredArtists = HashSet<String>()
            val ensuredAlbums = HashSet<String>()
            var added = 0
            var updated = 0

            source.read(BATCH_SIZE) { rows ->
                val tracks = rows.filter(LocalTrackRules::isEligible)
                if (tracks.isNotEmpty()) {
                    database.withTransaction {
                        val counts = upsertBatch(tracks, labels, seen, ensuredArtists, ensuredAlbums)
                        added += counts.first
                        updated += counts.second
                    }
                }
                onProgress(seen.size)
            }

            val missing = LocalTrackRules.idsToRemove(existingIds, seen)
            val skipped = massRemovalLooksWrong(missing.size, existingIds.size)
            // files that MediaStore forgot but that can still be read are kept
            val gone =
                if (skipped) {
                    emptyList()
                } else {
                    missing.filter { id -> LocalMusic.mediaStoreIdOf(id)?.let(fileExists) != true }
                }
            val touched = HashSet<String>()
            gone.chunked(DELETE_CHUNK).forEach { touched += database.touchedSongIds(it) }
            val toDelete = gone.filter { it !in touched }
            database.withTransaction {
                touched.toList().chunked(DELETE_CHUNK).forEach { markSongsUnavailable(it) }
                toDelete.chunked(DELETE_CHUNK).forEach { deleteSongsByIdList(it) }
                // the number of songs and the length of every album are recounted from the song table
                recountLocalAlbums()
                deleteEmptyLocalAlbums()
                deleteEmptyLocalArtists()
            }

            ScanResult(
                added = added,
                updated = updated,
                removed = toDelete.size,
                unavailable = touched.size,
                removalSkipped = skipped,
                total = database.localSongIds().size,
            )
        }

    /** Returns (added, updated). */
    private fun MusicDatabase.upsertBatch(
        tracks: List<LocalTrack>,
        labels: LocalLabels,
        seen: MutableSet<String>,
        ensuredArtists: MutableSet<String>,
        ensuredAlbums: MutableSet<String>,
    ): Pair<Int, Int> {
        val ids = tracks.map { LocalMusic.songIdFor(it.mediaStoreId) }
        val existing = songEntitiesByIdList(ids).associateBy { it.id }
        val withArtist = songIdsWithArtist(ids).toHashSet()
        val withAlbum = songIdsWithAlbum(ids).toHashSet()
        var added = 0
        var updated = 0

        for (track in tracks) {
            val id = LocalMusic.songIdFor(track.mediaStoreId)
            seen += id

            val artistName = LocalTrackRules.artistOf(track)
            val artistId = if (artistName == null) LocalMusic.UNKNOWN_ARTIST_ID else LocalMusic.artistIdFor(artistName)
            if (ensuredArtists.add(artistId)) {
                insert(
                    ArtistEntity(
                        id = artistId,
                        name = artistName ?: labels.unknownArtist,
                        isLocal = true,
                    ),
                )
                // the label follows the app language
                if (artistName == null) renameArtist(artistId, labels.unknownArtist)
            }

            val albumName = LocalTrackRules.albumOf(track)
            val albumId = if (albumName != null && track.albumId != null) LocalMusic.albumIdFor(track.albumId) else null
            if (albumId != null && ensuredAlbums.add(albumId)) {
                insert(
                    AlbumEntity(
                        id = albumId,
                        title = albumName!!,
                        year = LocalTrackRules.yearOf(track),
                        thumbnailUrl = LocalMusic.coverUrlOf(track.mediaStoreId),
                        songCount = 0,
                        duration = 0,
                        isLocal = true,
                    ),
                )
                insert(AlbumArtistMap(albumId = albumId, artistId = artistId, order = 0))
            }

            val modified = epoch(track.dateModifiedSec) ?: now()
            val duration = LocalTrackRules.durationSeconds(track)
            val title = LocalTrackRules.titleOf(track, labels)
            // a touched song that was marked unavailable and whose file is back
            val old = existing[id]?.let { if (it.isLocal) it else it.copy(isLocal = true).also { back -> update(back) } }

            if (old == null) {
                insert(
                    SongEntity(
                        id = id,
                        title = title,
                        duration = duration,
                        thumbnailUrl = LocalMusic.coverUrlOf(track.mediaStoreId),
                        albumId = albumId,
                        albumName = albumName,
                        year = LocalTrackRules.yearOf(track),
                        dateModified = modified,
                        inLibrary = epoch(track.dateAddedSec) ?: now(),
                        isLocal = true,
                    ),
                )
                insert(SongArtistMap(songId = id, artistId = artistId, position = 0))
                if (albumId != null) insert(SongAlbumMap(songId = id, albumId = albumId, index = 0))
                added++
            } else if ((old.dateModified != modified && old.dateModified != legacyUtc(track.dateModifiedSec)) || old.duration != duration) {
                // the file was changed (tags edited, replaced): take the tags again
                update(
                    old.copy(
                        title = title,
                        duration = duration,
                        thumbnailUrl = LocalMusic.coverUrlOf(track.mediaStoreId),
                        albumId = albumId,
                        albumName = albumName,
                        year = LocalTrackRules.yearOf(track),
                        dateModified = modified,
                        isLocal = true,
                    ),
                )
                songArtistMap(id).forEach { delete(it) }
                insert(SongArtistMap(songId = id, artistId = artistId, position = 0))
                deleteSongAlbumMaps(id)
                if (albumId != null) insert(SongAlbumMap(songId = id, albumId = albumId, index = 0))
                updated++
            } else {
                // untouched file: keep the row (and any edit the user made), only repair links lost by a library wipe
                if (old.dateModified != modified) {
                    // rows made before the zone fix hold the UTC reading of the dates: move them to local time once
                    val shiftedAdded = legacyUtc(track.dateAddedSec)
                    update(
                        old.copy(
                            dateModified = modified,
                            inLibrary = if (old.inLibrary != null && old.inLibrary == shiftedAdded) epoch(track.dateAddedSec) else old.inLibrary,
                        ),
                    )
                }
                if (id !in withArtist) insert(SongArtistMap(songId = id, artistId = artistId, position = 0))
                if (albumId != null && id !in withAlbum) insert(SongAlbumMap(songId = id, albumId = albumId, index = 0))
            }
        }
        return added to updated
    }

    private fun epoch(seconds: Long): LocalDateTime? = epochToLocal(seconds, zone)

    private fun legacyUtc(seconds: Long): LocalDateTime? = epochToLocal(seconds, ZoneOffset.UTC)

    companion object {
        const val BATCH_SIZE = 200

        /** More than this many missing songs, and more than [MASS_REMOVAL_PERCENT] percent of the library, is not a normal clean-up. */
        const val MASS_REMOVAL_MIN = 20
        const val MASS_REMOVAL_PERCENT = 30

        fun massRemovalLooksWrong(missing: Int, existing: Int): Boolean =
            missing > MASS_REMOVAL_MIN && missing.toLong() * 100 > existing.toLong() * MASS_REMOVAL_PERCENT

        /** MediaStore dates are UTC epoch seconds; the app stores local time everywhere else (`LocalDateTime.now()`). */
        fun epochToLocal(seconds: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime? =
            if (seconds <= 0L) null else LocalDateTime.ofInstant(Instant.ofEpochSecond(seconds), zone)

        private const val DELETE_CHUNK = 400
    }
}
