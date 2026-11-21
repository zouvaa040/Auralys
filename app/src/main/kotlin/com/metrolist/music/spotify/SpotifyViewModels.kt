/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * State of the Spotify library screens: the list of playlists, and one playlist (its songs from Spotify at once, matched to
 * YouTube Music in the background, playable, saveable as a normal local playlist). The network and matching code is in the
 * other files of this package; this only holds state and cancels its work when the screen closes.
 */

package com.metrolist.music.spotify

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.saveSongsAsPlaylist
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What a screen that reads from Spotify shows. */
sealed interface SpotifyLoad<out T> {
    object Loading : SpotifyLoad<Nothing>

    class Ready<T>(val value: T) : SpotifyLoad<T>

    /** [kind] says what went wrong in plain terms; the message of the exception is never shown. */
    class Failed(val kind: SpotifyException.Kind) : SpotifyLoad<Nothing>
}

internal fun Throwable.spotifyKind(): SpotifyException.Kind =
    (this as? SpotifyException)?.kind ?: SpotifyException.Kind.OTHER

@HiltViewModel
class SpotifyLibraryViewModel @Inject constructor() : ViewModel() {
    private val mutableState = MutableStateFlow<SpotifyLoad<List<SpotifyPlaylist>>>(SpotifyLoad.Loading)
    val state: StateFlow<SpotifyLoad<List<SpotifyPlaylist>>> = mutableState.asStateFlow()

    private var job: Job? = null

    init {
        load()
        // another account (or the same one signed in again): start over
        viewModelScope.launch { SpotifyAccount.epoch.drop(1).collect { load(force = true) } }
    }

    /** Reads the playlists (from the cache while it is fresh, unless [force]). */
    fun load(force: Boolean = false) {
        if (!force) {
            SpotifyLibraryCache.playlists()?.let {
                mutableState.value = SpotifyLoad.Ready(it)
                return
            }
        }
        job?.cancel()
        mutableState.value = SpotifyLoad.Loading
        // "try again" means try again: a failed token mint is not held against this attempt
        SpotifyAccount.forgetMintFailure()
        job =
            viewModelScope.launch(Dispatchers.IO) {
                mutableState.value =
                    try {
                        val playlists = SpotifyAccount.api.playlists()
                        SpotifyLibraryCache.put(playlists)
                        SpotifyLoad.Ready(playlists)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        SpotifyLoad.Failed(e.spotifyKind())
                    }
            }
    }
}

/** One song of a Spotify list with what is known about its YouTube Music version. */
data class SpotifyRow(
    val track: SpotifyTrack,
    val match: SpotifyMatch,
) {
    val song: SongItem? get() = (match as? SpotifyMatch.Found)?.song
}

/** The songs of a list that were found on YouTube Music, in list order, and the position in them where playback starts. */
class SpotifyQueue(val songs: List<SongItem>, val startIndex: Int)

/**
 * What a tap on a row plays: the matched songs of the list as the queue, starting at [startRow] (a row index; the first
 * matched song when null or when that row has no match). Rows without a match are left out, so the queue skips them.
 */
fun matchedQueue(rows: List<SpotifyRow>, startRow: Int? = null): SpotifyQueue {
    val songs = ArrayList<SongItem>()
    var start = 0
    rows.forEachIndexed { index, row ->
        val song = row.song ?: return@forEachIndexed
        if (index == startRow) start = songs.size
        songs += song
    }
    return SpotifyQueue(songs, start)
}

enum class SpotifySaveState { IDLE, SAVING, SAVED, FAILED }

data class SpotifyPlaylistUi(
    val load: SpotifyLoad<Unit> = SpotifyLoad.Loading,
    /** The name Spotify gives it; null for Liked Songs (the screen names it) and until it is known. */
    val title: String? = null,
    val owner: String? = null,
    val coverUrl: String? = null,
    /** How many songs Spotify says the list has (more than [rows] when the page limit stopped the read). */
    val total: Int = 0,
    val rows: List<SpotifyRow> = emptyList(),
    val matching: Boolean = false,
    /** Rows that have a result, found or not. */
    val processed: Int = 0,
    val matched: Int = 0,
    val save: SpotifySaveState = SpotifySaveState.IDLE,
    val savedPlaylistId: String? = null,
) {
    val missing: Int get() = processed - matched
    val truncated: Boolean get() = total > rows.size
}

@HiltViewModel
class SpotifyPlaylistViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val database: MusicDatabase,
) : ViewModel() {
    val playlistId: String = savedStateHandle.get<String>("playlistId").orEmpty()
    val isLiked: Boolean = playlistId == SPOTIFY_LIKED_ID

    private val known = SpotifyLibraryCache.find(playlistId)
    private val mutableState =
        MutableStateFlow(
            SpotifyPlaylistUi(
                title = known?.name?.takeIf { it.isNotBlank() },
                owner = known?.owner,
                coverUrl = if (isLiked) SPOTIFY_LIKED_COVER else known?.imageUrl,
            ),
        )
    val state: StateFlow<SpotifyPlaylistUi> = mutableState.asStateFlow()

    private var loadJob: Job? = null
    private var matchJob: Job? = null
    private val runner = SpotifyMatchRunner()

    // results written by the matching coroutines, published to [state] a few times a second
    private val lock = Any()
    private var matches: Array<SpotifyMatch> = emptyArray()

    @Volatile private var dirty = false

    init {
        load()
        // another account (or the same one signed in again): start over
        viewModelScope.launch { SpotifyAccount.epoch.drop(1).collect { load() } }
    }

    fun load() {
        loadJob?.cancel()
        matchJob?.cancel()
        mutableState.update { it.copy(load = SpotifyLoad.Loading, rows = emptyList(), matching = false, processed = 0, matched = 0) }
        // opening the list, or "try again": a failed token mint is not held against this attempt
        SpotifyAccount.forgetMintFailure()
        loadJob =
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val result =
                        SpotifyAccount.api.tracks(playlistId) { partial ->
                            // a long list fills in page by page
                            mutableState.update {
                                it.copy(
                                    rows = partial.tracks.map { track -> SpotifyRow(track, SpotifyMatch.Pending) },
                                    total = partial.total,
                                )
                            }
                        }
                    val header = result.header
                    mutableState.update {
                        it.copy(
                            load = SpotifyLoad.Ready(Unit),
                            title = if (isLiked) null else header.name ?: it.title,
                            owner = header.owner ?: it.owner,
                            coverUrl = header.imageUrl ?: it.coverUrl,
                            total = result.total,
                            rows = result.tracks.map { track -> SpotifyRow(track, SpotifyMatch.Pending) },
                        )
                    }
                    startMatching(result.tracks)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutableState.update { it.copy(load = SpotifyLoad.Failed(e.spotifyKind())) }
                }
            }
    }

    private fun startMatching(tracks: List<SpotifyTrack>) {
        matchJob?.cancel()
        if (tracks.isEmpty()) return
        // what the session already knows shows at once, so reopening a list is instant
        synchronized(lock) { matches = Array(tracks.size) { SpotifyMatchCache.shared.get(tracks[it].id) ?: SpotifyMatch.Pending } }
        publish(tracks)
        mutableState.update { it.copy(matching = true) }
        matchJob =
            viewModelScope.launch(Dispatchers.Default) {
                val ticker =
                    launch {
                        while (isActive) {
                            delay(PUBLISH_EVERY_MS)
                            if (dirty) publish(tracks)
                        }
                    }
                try {
                    runner.run(tracks, SpotifyMatchCache.shared) { index, match ->
                        synchronized(lock) { if (index < matches.size) matches[index] = match }
                        dirty = true
                    }
                } finally {
                    ticker.cancel()
                    // a cancelled run (screen closed, or a newer run started) leaves the state to whoever cancelled it
                    if (isActive) {
                        publish(tracks)
                        mutableState.update { it.copy(matching = false) }
                    }
                }
            }
    }

    private fun publish(tracks: List<SpotifyTrack>) {
        dirty = false
        val snapshot = synchronized(lock) { matches.copyOf() }
        if (snapshot.size != tracks.size) return
        var processed = 0
        var matched = 0
        val rows =
            tracks.mapIndexed { index, track ->
                val match = snapshot[index]
                if (match !is SpotifyMatch.Pending) processed++
                if (match is SpotifyMatch.Found) matched++
                SpotifyRow(track, match)
            }
        mutableState.update { it.copy(rows = rows, processed = processed, matched = matched) }
    }

    /** Looks again for the songs that were not found (the matcher also says "not found" when YouTube was busy). */
    fun retryMissing() {
        val current = mutableState.value
        if (current.matching) return
        val tracks = current.rows.map { it.track }
        SpotifyMatchCache.shared.forgetMisses(current.rows.filter { it.match is SpotifyMatch.Missing }.map { it.track.id })
        startMatching(tracks)
    }

    /** Creates a normal local playlist from the matched songs. */
    fun save() {
        val current = mutableState.value
        if (current.save == SpotifySaveState.SAVING || current.save == SpotifySaveState.SAVED) return
        val songs = current.rows.mapNotNull { it.song }.distinctBy { it.id }
        if (songs.isEmpty()) return
        mutableState.update { it.copy(save = SpotifySaveState.SAVING) }
        val name =
            if (isLiked) context.getString(R.string.spotify_liked_saved_name) else current.title ?: context.getString(R.string.spotify_title)
        viewModelScope.launch(Dispatchers.IO) {
            val id =
                try {
                    database.saveSongsAsPlaylist(name = name, coverUrl = current.coverUrl, songs = songs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            mutableState.update {
                if (id != null) it.copy(save = SpotifySaveState.SAVED, savedPlaylistId = id) else it.copy(save = SpotifySaveState.FAILED)
            }
        }
    }

    private companion object {
        const val PUBLISH_EVERY_MS = 150L
    }
}
