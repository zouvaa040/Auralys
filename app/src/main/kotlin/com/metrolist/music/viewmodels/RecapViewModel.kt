/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Recap ("Replay"): a periodic listening recap built only from the local listening history (event table).
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject

enum class RecapPeriodKind { THIS_YEAR, LAST_30_DAYS, ALL_TIME, YEAR }

@Immutable
data class RecapPeriod(
    val kind: RecapPeriodKind,
    val year: Int = 0,
) {
    /** [from, to] of this period, in the same (local clock) time base the event table is written with. */
    fun range(now: LocalDateTime = LocalDateTime.now()): Pair<LocalDateTime, LocalDateTime> =
        when (kind) {
            RecapPeriodKind.THIS_YEAR -> LocalDateTime.of(now.year, 1, 1, 0, 0) to now
            RecapPeriodKind.LAST_30_DAYS -> now.minusDays(30) to now
            RecapPeriodKind.ALL_TIME -> LocalDateTime.of(1970, 1, 1, 0, 0) to now
            RecapPeriodKind.YEAR -> LocalDateTime.of(year, 1, 1, 0, 0) to LocalDateTime.of(year, 12, 31, 23, 59, 59)
        }
}

@Immutable
data class RecapSong(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val minutes: Long,
    val plays: Int,
    val song: Song?,
)

@Immutable
data class RecapArtist(
    val id: String,
    val name: String,
    val thumbnailUrl: String?,
    val minutes: Long,
)

@Immutable
data class RecapAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val minutes: Long,
)

@Immutable
data class RecapData(
    val period: RecapPeriod,
    val totalMinutes: Long,
    val songCount: Int,
    val artistCount: Int,
    val songs: List<RecapSong>,
    val artists: List<RecapArtist>,
    val albums: List<RecapAlbum>,
) {
    val isEmpty: Boolean get() = totalMinutes <= 0L && songs.isEmpty()
}

private data class RecapTotals(val minutes: Long, val songs: Int, val artists: Int)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecapViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase,
) : ViewModel() {
    val period = MutableStateFlow(RecapPeriod(RecapPeriodKind.THIS_YEAR))

    /** Earlier calendar years (before the current one) that have listening history, newest first. */
    val pastYears: StateFlow<List<Int>> =
        database
            .firstEvent()
            .map { first ->
                val firstYear = first?.event?.timestamp?.year ?: return@map emptyList()
                val thisYear = LocalDateTime.now().year
                (thisYear - 1 downTo firstYear).filter { y ->
                    val p = RecapPeriod(RecapPeriodKind.YEAR, y).range()
                    (database.getTotalPlayTimeInRange(p.first, p.second).first() ?: 0L) > 0L
                }
            }.flowOn(Dispatchers.IO)
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val hideVideoSongs =
        context.dataStore.data
            .map { it[HideVideoSongsKey] ?: false }
            .distinctUntilChanged()

    val data: StateFlow<RecapData?> =
        combine(period, hideVideoSongs) { p, hide -> p to hide }
            .flatMapLatest { (p, hideVideos) ->
                val (from, to) = p.range()
                val totals =
                    combine(
                        database.getTotalPlayTimeInRange(from, to),
                        database.getUniqueSongCountInRange(from, to),
                        database.getUniqueArtistCountInRange(from, to),
                    ) { ms, songs, artists -> RecapTotals((ms ?: 0L) / 60_000L, songs, artists) }
                combine(
                    database.mostPlayedSongsStats(fromTimeStamp = from, limit = SONG_QUERY_LIMIT, toTimeStamp = to),
                    database.mostPlayedSongs(fromTimeStamp = from, limit = SONG_QUERY_LIMIT, toTimeStamp = to),
                    database.mostPlayedArtists(fromTimeStamp = from, limit = MAX_ARTISTS, toTimeStamp = to),
                    database.mostPlayedAlbums(fromTimeStamp = from, limit = MAX_ALBUMS, toTimeStamp = to),
                    totals,
                ) { stats, songs, artists, albums, t ->
                    val songsById = songs.associateBy { it.song.id }
                    RecapData(
                        period = p,
                        totalMinutes = t.minutes,
                        songCount = t.songs,
                        artistCount = t.artists,
                        songs =
                            stats
                                .asSequence()
                                .filter { !hideVideos || !it.isVideo }
                                .distinctBy { it.id }
                                .take(MAX_SONGS)
                                .map { s ->
                                    val full = songsById[s.id]
                                    RecapSong(
                                        id = s.id,
                                        title = s.title,
                                        artist =
                                            full?.orderedArtists?.joinToString { it.name }?.takeIf { it.isNotBlank() }
                                                ?: s.artistName.orEmpty(),
                                        thumbnailUrl = s.thumbnailUrl.takeIf { it.isNotBlank() } ?: full?.song?.thumbnailUrl,
                                        minutes = (s.timeListened ?: 0L) / 60_000L,
                                        plays = s.songCountListened,
                                        song = full,
                                    )
                                }.toList(),
                        artists =
                            artists.map { a ->
                                RecapArtist(
                                    id = a.artist.id,
                                    name = a.artist.name,
                                    thumbnailUrl = a.artist.thumbnailUrl,
                                    minutes = (a.timeListened ?: 0).toLong() / 60_000L,
                                )
                            },
                        albums =
                            albums.map { al ->
                                RecapAlbum(
                                    id = al.album.id,
                                    title = al.album.title,
                                    artist = al.artists.joinToString { it.name },
                                    thumbnailUrl = al.album.thumbnailUrl,
                                    minutes = (al.timeListened ?: 0L) / 60_000L,
                                )
                            },
                    )
                }
            }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    fun select(p: RecapPeriod) {
        period.value = p
    }

    init {
        // Artists that were never opened have no avatar yet; fetch the few on screen once (same as StatsViewModel).
        viewModelScope.launch(Dispatchers.IO) {
            val fetched = HashSet<String>()
            data.collect { d ->
                if (d == null) return@collect
                val (from, to) = d.period.range()
                database
                    .mostPlayedArtists(fromTimeStamp = from, limit = MAX_ARTISTS, toTimeStamp = to)
                    .first()
                    .map { it.artist }
                    .filter { it.thumbnailUrl == null && it.isYouTubeArtist && fetched.add(it.id) }
                    .forEach { artist ->
                        YouTube.artist(artist.id).onSuccess { artistPage ->
                            database.query { update(artist, artistPage) }
                        }
                    }
            }
        }
    }

    companion object {
        const val MAX_SONGS = 25
        const val MAX_ARTISTS = 10
        const val MAX_ALBUMS = 10
        private const val SONG_QUERY_LIMIT = 40
    }
}
