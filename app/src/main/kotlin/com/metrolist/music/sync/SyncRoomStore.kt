/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The Room side of the sync: reads the phone's data with plain SELECTs (nothing is added to the Room schema or the DAO)
 * and applies a [MergePlan] through the existing DAO inserts inside ONE transaction.
 *
 * Library rules: a song that only arrives because of a playlist or the play history gets inLibrary = null and liked = false,
 * so it never shows up in the library or the liked list; only `liked` entries of the snapshot do that.
 */

package com.metrolist.music.sync

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.AlbumArtistMap
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.SearchHistory
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId

class RoomSyncStore(
    private val database: MusicDatabase,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val nowLocal: (ZoneId) -> LocalDateTime = { LocalDateTime.now(it) },
) : SyncStore {
    // ------------------------------------------------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------------------------------------------------

    private class RawPlaylist(
        val id: String,
        val name: String,
        val browseId: String?,
        val createdAt: Long?,
        val editable: Boolean,
        val songs: MutableList<String> = ArrayList(),
    )

    private class Raw(
        val allSongIds: Set<String>,
        val deviceSongIds: Set<String>,
        val liked: Map<String, Long?>,
        val playlists: List<RawPlaylist>,
        val savedPlaylistRows: Map<SavedKey, LocalSavedRow>,
        val albumRows: Map<SavedKey, LocalSavedRow>,
        val artistRows: Map<SavedKey, LocalSavedRow>,
        val events: List<SyncEvent>,
        val searchesNewestFirst: List<String>,
    )

    private fun <T> SupportSQLiteDatabase.rows(sql: String, read: (Cursor) -> T): List<T> =
        query(sql).use { cursor ->
            val out = ArrayList<T>(cursor.count.coerceAtLeast(0))
            while (cursor.moveToNext()) out += read(cursor)
            out
        }

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)

    private fun readRaw(zone: ZoneId): Raw {
        val db = database.openHelper.readableDatabase
        val allSongIds = db.rows("SELECT id FROM song") { it.getString(0) }.toHashSet()
        // Songs that are files on the phone have no YouTube id and mean nothing on the other side.
        val deviceSongIds = db.rows("SELECT id FROM song WHERE isLocal = 1") { it.getString(0) }.toHashSet()
        val liked = LinkedHashMap<String, Long?>()
        db.rows("SELECT id, likedDate FROM song WHERE liked = 1 AND isLocal = 0 ORDER BY likedDate, rowid") {
            it.getString(0) to it.longOrNull(1)?.let { stored -> SyncTime.dbToEpoch(stored, zone) }
        }.forEach { (id, at) -> liked[id] = at }

        // System lists like "most played this week" (LP_...) are rebuilt by the app and never synced.
        val playlists =
            db.rows(
                "SELECT id, name, browseId, createdAt, isEditable FROM playlist " +
                    "WHERE bookmarkedAt IS NOT NULL AND id NOT LIKE 'LP\\_%' ESCAPE '\\' " +
                    "AND (browseId IS NULL OR browseId <> 'LM') ORDER BY rowid",
            ) {
                RawPlaylist(
                    id = it.getString(0),
                    name = it.getString(1),
                    browseId = it.stringOrNull(2),
                    createdAt = it.longOrNull(3),
                    editable = it.getInt(4) != 0,
                )
            }
        val byId = playlists.associateBy { it.id }
        db.query("SELECT playlistId, songId FROM playlist_song_map ORDER BY playlistId, position, id").use { cursor ->
            while (cursor.moveToNext()) byId[cursor.getString(0)]?.songs?.add(cursor.getString(1))
        }

        fun saved(kind: String, sql: String, idIndex: Int, bookmarkedIndex: Int, localIdIndex: Int?) =
            db.rows(sql) {
                SyncMerger.savedKey(kind, it.getString(idIndex)) to
                    LocalSavedRow(it.getInt(bookmarkedIndex) != 0, localIdIndex?.let { index -> it.getString(index) })
            }.toMap()

        val savedPlaylists =
            saved(
                SyncSaved.PLAYLIST,
                "SELECT browseId, bookmarkedAt IS NOT NULL, id FROM playlist WHERE browseId IS NOT NULL",
                0,
                1,
                2,
            )
        val albums = saved(SyncSaved.ALBUM, "SELECT id, bookmarkedAt IS NOT NULL FROM album WHERE isLocal = 0", 0, 1, null)
        val artists = saved(SyncSaved.ARTIST, "SELECT id, bookmarkedAt IS NOT NULL FROM artist WHERE isLocal = 0", 0, 1, null)

        val events =
            db.rows("SELECT songId, timestamp, playTime FROM event ORDER BY timestamp, id") {
                SyncEvent(it.getString(0), SyncTime.dbToEpoch(it.getLong(1), zone), it.getLong(2))
            }.filter { it.id !in deviceSongIds }

        val searches = db.rows("SELECT `query` FROM search_history ORDER BY id DESC") { it.getString(0) }
        return Raw(allSongIds, deviceSongIds, liked, playlists, savedPlaylists, albums, artists, events, searches)
    }

    override suspend fun readLocal(): LocalState =
        withContext(Dispatchers.IO) {
            val raw = readRaw(zone())
            val saved = HashMap<SavedKey, LocalSavedRow>()
            saved.putAll(raw.savedPlaylistRows)
            saved.putAll(raw.albumRows)
            saved.putAll(raw.artistRows)
            LocalState(
                songIds = raw.allSongIds,
                liked = raw.liked,
                playlists = raw.playlists.filter { it.isExported() }.map { LocalPlaylist(it.id, it.name, it.browseId, it.songs) },
                saved = saved,
                events = raw.events.mapTo(HashSet()) { EventKey(it.id, it.at) },
                searches = raw.searchesNewestFirst.mapTo(HashSet()) { it.trim() },
            )
        }

    /** Own playlists (local or editable YouTube ones) travel as playlists; saved foreign YouTube playlists as `saved`. */
    private fun RawPlaylist.isExported() = browseId == null || editable

    override suspend fun exportSnapshot(sidMap: Map<String, String>, createdAt: Long): Snapshot =
        withContext(Dispatchers.IO) {
            val zone = zone()
            val raw = readRaw(zone)
            val db = database.openHelper.readableDatabase

            val playlists =
                raw.playlists.filter { it.isExported() }.map { playlist ->
                    SyncPlaylist(
                        sid = sidMap[playlist.id] ?: playlist.id,
                        name = playlist.name,
                        createdAt = playlist.createdAt?.let { SyncTime.dbToEpoch(it, zone) } ?: 0L,
                        songs = playlist.songs.filter { it !in raw.deviceSongIds },
                        browseId = playlist.browseId,
                    )
                }
            val liked = raw.liked.map { (id, at) -> SyncLiked(id, at) }

            val referenced = HashSet<String>()
            liked.forEach { referenced += it.id }
            playlists.forEach { referenced += it.songs }
            raw.events.forEach { referenced += it.id }

            val artistsBySong = HashMap<String, MutableList<Pair<String, String>>>()
            db.query(
                "SELECT m.songId, a.id, a.name FROM song_artist_map m JOIN artist a ON a.id = m.artistId " +
                    "ORDER BY m.songId, m.position",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val songId = cursor.getString(0)
                    if (songId in referenced) {
                        artistsBySong.getOrPut(songId) { ArrayList() } += cursor.getString(1) to cursor.getString(2)
                    }
                }
            }
            val songs = ArrayList<SyncSong>(referenced.size)
            db.query("SELECT id, title, duration, thumbnailUrl, albumId, albumName FROM song ORDER BY rowid").use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    if (id !in referenced) continue
                    val artists = artistsBySong[id].orEmpty()
                    songs +=
                        SyncSong(
                            id = id,
                            title = cursor.getString(1),
                            artist = artists.joinToString(", ") { it.second },
                            // Only real YouTube channel ids mean something on the other side.
                            artistId = artists.firstOrNull()?.first?.takeIf { it.startsWith("UC") },
                            album = cursor.stringOrNull(5),
                            albumId = cursor.stringOrNull(4),
                            duration = cursor.getInt(2).takeIf { it > 0 },
                            thumb = cursor.stringOrNull(3),
                        )
                }
            }

            val albumArtists = HashMap<String, MutableList<String>>()
            db.query(
                "SELECT m.albumId, a.name FROM album_artist_map m JOIN artist a ON a.id = m.artistId " +
                    "ORDER BY m.albumId, m.`order`",
            ).use { cursor ->
                while (cursor.moveToNext()) albumArtists.getOrPut(cursor.getString(0)) { ArrayList() } += cursor.getString(1)
            }
            val saved = ArrayList<SyncSaved>()
            db.rows(
                "SELECT id, title, thumbnailUrl FROM album WHERE bookmarkedAt IS NOT NULL AND isLocal = 0 ORDER BY rowid",
            ) {
                SyncSaved(
                    SyncSaved.ALBUM,
                    it.getString(0),
                    it.getString(1),
                    albumArtists[it.getString(0)]?.joinToString(", "),
                    it.stringOrNull(2),
                )
            }.let(saved::addAll)
            db.rows(
                "SELECT id, name, thumbnailUrl FROM artist WHERE bookmarkedAt IS NOT NULL AND isLocal = 0 " +
                    "AND isPodcastChannel = 0 AND id LIKE 'UC%' ORDER BY rowid",
            ) { SyncSaved(SyncSaved.ARTIST, it.getString(0), it.getString(1), null, it.stringOrNull(2)) }.let(saved::addAll)
            db.rows(
                "SELECT browseId, name, thumbnailUrl FROM playlist WHERE bookmarkedAt IS NOT NULL AND browseId IS NOT NULL " +
                    "AND browseId <> 'LM' AND isEditable = 0 AND id NOT LIKE 'LP\\_%' ESCAPE '\\' ORDER BY rowid",
            ) { SyncSaved(SyncSaved.PLAYLIST, it.getString(0), it.getString(1), null, it.stringOrNull(2)) }.let(saved::addAll)

            Snapshot(
                v = SYNC_PROTOCOL_VERSION,
                platform = "android",
                createdAt = createdAt,
                songs = songs,
                liked = liked,
                playlists = playlists,
                saved = saved,
                events = raw.events,
                searches = raw.searchesNewestFirst.take(100),
                // The listening streak/days are derived from the events here, so there is nothing separate to send.
                days = emptyList(),
            )
        }

    // ------------------------------------------------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------------------------------------------------

    override suspend fun apply(plan: MergePlan) {
        if (plan.summary.isEmpty && plan.likeUpdates.isEmpty() && plan.appends.isEmpty()) return
        withContext(Dispatchers.IO) {
            val zone = zone()
            val now = nowLocal(zone)
            fun local(epoch: Long) = SyncTime.epochToLocal(epoch, zone)

            val db = database.openHelper.readableDatabase
            val artistIds = db.rows("SELECT id FROM artist") { it.getString(0) }.toHashSet()
            val artistByName = HashMap<String, String>()
            db.rows("SELECT name, id FROM artist ORDER BY rowid DESC") { it.getString(0) to it.getString(1) }
                .forEach { (name, id) -> artistByName[name] = id }
            val minSearchId = db.rows("SELECT MIN(id) FROM search_history") { it.longOrNull(0) }.firstOrNull() ?: 0L

            val likeById = plan.likeUpdates.associateBy { it.songId }
            val newSongIds = plan.newSongs.mapTo(HashSet()) { it.id }

            database.withTransaction {
                // 1. songs first: everything else points at them (foreign keys cascade on delete)
                for (song in plan.newSongs) {
                    val like = likeById[song.id]?.takeIf { it.newlyLiked }
                    val likedAt = if (like != null) like.at?.let(::local) ?: now else null
                    insert(
                        SongEntity(
                            id = song.id,
                            title = song.title,
                            duration = song.duration ?: -1,
                            thumbnailUrl = song.thumb,
                            albumId = song.albumId,
                            albumName = song.album,
                            liked = like != null,
                            likedDate = likedAt,
                            inLibrary = likedAt,
                        ),
                    )
                    if (song.artist.isNotBlank()) {
                        val single = !song.artist.contains(", ")
                        val known = song.artistId?.takeIf { it in artistIds } ?: artistByName[song.artist]
                        val artistId =
                            known ?: run {
                                val id = if (single && song.artistId != null) song.artistId else ArtistEntity.generateArtistId()
                                insert(ArtistEntity(id = id, name = song.artist, channelId = if (single) song.artistId else null))
                                artistIds += id
                                artistByName[song.artist] = id
                                id
                            }
                        insert(SongArtistMap(songId = song.id, artistId = artistId, position = 0))
                    }
                }

                // 2. likes of songs that were already here, or the earlier liked time
                for (like in plan.likeUpdates) {
                    if (like.songId in newSongIds && like.newlyLiked) continue
                    val entity = songEntity(like.songId) ?: continue
                    val likedAt = like.at?.let(::local)
                    update(
                        if (like.newlyLiked) {
                            val date = likedAt ?: now
                            entity.copy(liked = true, likedDate = date, inLibrary = entity.inLibrary ?: date)
                        } else {
                            entity.copy(likedDate = likedAt ?: entity.likedDate)
                        },
                    )
                }

                // 3. playlists: new copies, then songs appended to the ones that matched
                for (playlist in plan.newPlaylists) {
                    insert(
                        PlaylistEntity(
                            id = playlist.localId,
                            name = playlist.name,
                            createdAt = local(playlist.createdAt),
                            lastUpdateTime = now,
                            isEditable = true,
                            bookmarkedAt = now,
                        ),
                    )
                    playlist.songIds.forEachIndexed { index, songId ->
                        insert(PlaylistSongMap(playlistId = playlist.localId, songId = songId, position = index))
                    }
                }
                for (append in plan.appends) {
                    var position = nextPlaylistSongPosition(append.localId)
                    for (songId in append.songIds) {
                        insert(PlaylistSongMap(playlistId = append.localId, songId = songId, position = position++))
                    }
                    updatePlaylistLastUpdated(append.localId, now)
                }

                // 4. albums, artists and online playlists the other side saved
                for (op in plan.savedOps) {
                    val item = op.saved
                    when (item.kind) {
                        SyncSaved.ALBUM -> {
                            val existing = albumEntity(item.id)
                            if (existing != null) {
                                update(existing.copy(bookmarkedAt = existing.bookmarkedAt ?: now))
                            } else {
                                insert(
                                    AlbumEntity(
                                        id = item.id,
                                        title = item.title.ifBlank { item.id },
                                        thumbnailUrl = item.thumb,
                                        songCount = 0,
                                        duration = 0,
                                        bookmarkedAt = now,
                                    ),
                                )
                                item.subtitle?.let { artistByName[it] }?.let { artistId ->
                                    insert(AlbumArtistMap(albumId = item.id, artistId = artistId, order = 0))
                                }
                            }
                        }
                        SyncSaved.ARTIST -> {
                            val existing = artistEntity(item.id)
                            if (existing != null) {
                                update(existing.copy(bookmarkedAt = existing.bookmarkedAt ?: now))
                            } else {
                                insert(
                                    ArtistEntity(
                                        id = item.id,
                                        name = item.title.ifBlank { item.id },
                                        thumbnailUrl = item.thumb,
                                        channelId = item.id,
                                        bookmarkedAt = now,
                                    ),
                                )
                                artistIds += item.id
                                artistByName.putIfAbsent(item.title, item.id)
                            }
                        }
                        SyncSaved.PLAYLIST -> {
                            val existing = op.existingLocalId?.let { playlistBlocking(it)?.playlist }
                            if (existing != null) {
                                update(existing.copy(bookmarkedAt = existing.bookmarkedAt ?: now))
                            } else {
                                insert(
                                    PlaylistEntity(
                                        name = item.title.ifBlank { item.id },
                                        browseId = SyncMerger.normBrowseId(item.id),
                                        thumbnailUrl = item.thumb,
                                        isEditable = false,
                                        bookmarkedAt = now,
                                    ),
                                )
                            }
                        }
                    }
                }

                // 5. play history, oldest first; the song's play-time counter grows by the same milliseconds
                val addedMs = HashMap<String, Long>()
                for (event in plan.events.sortedBy { it.at }) {
                    insert(Event(songId = event.id, timestamp = local(event.at), playTime = event.ms))
                    addedMs.merge(event.id, event.ms, Long::plus)
                }
                for ((songId, ms) in addedMs) if (ms > 0) incrementTotalPlayTime(songId, ms)

                // 6. search history: the app lists newest id first, so the new queries get ids below every existing one
                //    and show up after the phone's own searches, in the order the other side had them
                val base = minOf(0L, minSearchId)
                plan.searches.forEachIndexed { index, query ->
                    insert(SearchHistory(id = base - 1 - index, query = query))
                }
            }
        }
    }
}
