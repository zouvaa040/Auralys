/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Flow adapted from Convx's AppleMusicImportViewModel
 * (https://github.com/cosmictaserdev-creator/Convx, GPL-3.0); see NOTICE.
 */

package com.metrolist.music.viewmodels

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.ExternalPlaylist
import com.metrolist.music.utils.PlaylistLinkException
import com.metrolist.music.utils.PlaylistLinkFetcher
import com.metrolist.music.utils.PlaylistLinkSource
import com.metrolist.music.utils.PlaylistTrackMatcher
import com.metrolist.music.utils.SpotifyWebFailure
import com.metrolist.music.utils.SpotifyWebReader
import com.metrolist.music.utils.saveSongsAsPlaylist
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

enum class LinkImportPhase { INPUT, READING, MATCHING, SAVING, DONE }

enum class LinkImportError { INVALID_LINK, FETCH_FAILED, EMPTY, NO_MATCHES, SAVE_FAILED }

data class LinkImportUiState(
    /** null = dialog hidden. */
    val source: PlaylistLinkSource? = null,
    val link: String = "",
    val phase: LinkImportPhase = LinkImportPhase.INPUT,
    val playlistTitle: String = "",
    val total: Int = 0,
    val processed: Int = 0,
    val matched: Int = 0,
    /** True while the rest of a long Spotify playlist is read from the web page; [readCount] / [readTotal] show progress. */
    val readingFull: Boolean = false,
    val readCount: Int = 0,
    val readTotal: Int = 0,
    /** Why the rest of a long Spotify playlist could not be read (null when it was read fully or not tried). */
    val readFailure: SpotifyWebFailure? = null,
    /** Technical details for [readFailure], shown only when the user opens "technical details". */
    val readDiag: String = "",
    /** "Title - Artist" of tracks that were not found (capped). */
    val unmatched: List<String> = emptyList(),
    val unmatchedCount: Int = 0,
    val mayBeTruncated: Boolean = false,
    val declaredCount: Int? = null,
    val createdPlaylistId: String? = null,
    val error: LinkImportError? = null,
    val cancelled: Boolean = false,
) {
    val isBusy: Boolean
        get() = phase == LinkImportPhase.READING || phase == LinkImportPhase.MATCHING || phase == LinkImportPhase.SAVING
}

/**
 * Imports a public Spotify / Apple Music playlist link into a new local playlist.
 * Matching runs with at most [MAX_PARALLEL] concurrent YouTube searches and a short pause after each.
 */
@HiltViewModel
class PlaylistLinkImportViewModel @Inject constructor(
    private val database: MusicDatabase,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LinkImportUiState())
    val state: StateFlow<LinkImportUiState> = mutableState.asStateFlow()

    private var job: Job? = null

    fun open(source: PlaylistLinkSource) {
        if (state.value.isBusy) return
        mutableState.value = LinkImportUiState(source = source)
    }

    fun updateLink(link: String) {
        mutableState.update { it.copy(link = link, error = null, cancelled = false) }
    }

    /** Hides the dialog; also stops a running import. */
    fun close() {
        job?.cancel()
        job = null
        mutableState.value = LinkImportUiState()
    }

    /** Stops a running import and goes back to the link field. Nothing is saved. */
    fun cancel() {
        job?.cancel()
        job = null
        mutableState.update {
            it.copy(
                phase = LinkImportPhase.INPUT,
                processed = 0,
                matched = 0,
                total = 0,
                readingFull = false,
                readCount = 0,
                readTotal = 0,
                readFailure = null,
                readDiag = "",
                unmatched = emptyList(),
                unmatchedCount = 0,
                cancelled = true,
            )
        }
    }

    /**
     * [activity] is only used to host the hidden web page that completes long Spotify playlists; without it
     * (or when that page cannot be read) a Spotify playlist is limited to the first 100 songs.
     */
    fun start(activity: Activity? = null) {
        val snapshot = state.value
        val source = snapshot.source ?: return
        if (snapshot.isBusy) return
        if (!PlaylistLinkFetcher.looksValid(source, snapshot.link)) {
            mutableState.update { it.copy(error = LinkImportError.INVALID_LINK) }
            return
        }
        mutableState.update {
            it.copy(
                phase = LinkImportPhase.READING,
                error = null,
                cancelled = false,
                processed = 0,
                matched = 0,
                total = 0,
                readingFull = false,
                readCount = 0,
                readTotal = 0,
                readFailure = null,
                readDiag = "",
                unmatched = emptyList(),
                unmatchedCount = 0,
                createdPlaylistId = null,
            )
        }
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val fetched = PlaylistLinkFetcher.fetch(source, snapshot.link).getOrElse { error ->
                    val reason = (error as? PlaylistLinkException)?.reason
                    fail(
                        when (reason) {
                            PlaylistLinkException.Reason.INVALID_LINK -> LinkImportError.INVALID_LINK
                            PlaylistLinkException.Reason.EMPTY -> LinkImportError.EMPTY
                            else -> LinkImportError.FETCH_FAILED
                        },
                    )
                    return@launch
                }
                val playlist = if (activity != null && fetched.mayBeTruncated && fetched.spotifyLink != null) {
                    readWholeSpotifyPlaylist(activity, fetched)
                } else {
                    fetched
                }
                runImport(playlist)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Playlist link import failed")
                fail(LinkImportError.FETCH_FAILED)
            }
        }
    }

    /**
     * Spotify's embed stops at 100 songs. Reads the full list from the public web page instead; on any failure
     * the 100 songs already read are kept (and the dialog says the rest was not read).
     */
    private suspend fun readWholeSpotifyPlaylist(activity: Activity, embed: ExternalPlaylist): ExternalPlaylist {
        val link = embed.spotifyLink ?: return embed
        mutableState.update { it.copy(readingFull = true, readCount = embed.tracks.size, readTotal = 0) }
        val result = SpotifyWebReader.read(activity, link) { read, total ->
            mutableState.update { it.copy(readCount = read, readTotal = total) }
        }
        val web = result.playlist
        if (web == null || web.tracks.size <= embed.tracks.size) {
            mutableState.update { it.copy(readFailure = result.failure, readDiag = result.diag) }
            return embed
        }
        if (!web.complete) mutableState.update { it.copy(readFailure = result.failure, readDiag = result.diag) }
        return embed.copy(
            tracks = web.tracks,
            declaredCount = web.total.takeIf { it > 0 },
            mayBeTruncated = !web.complete,
            skipped = 0,
        )
    }

    private fun fail(error: LinkImportError) {
        mutableState.update { it.copy(phase = LinkImportPhase.INPUT, error = error) }
    }

    private suspend fun runImport(playlist: ExternalPlaylist) {
        val tracks = playlist.tracks
        mutableState.update {
            it.copy(
                phase = LinkImportPhase.MATCHING,
                playlistTitle = playlist.title,
                total = tracks.size,
                mayBeTruncated = playlist.mayBeTruncated,
                declaredCount = playlist.declaredCount,
            )
        }

        val results = arrayOfNulls<SongItem>(tracks.size)
        val processed = AtomicInteger(0)
        val matched = AtomicInteger(0)
        val semaphore = Semaphore(MAX_PARALLEL)
        coroutineScope {
            tracks.forEachIndexed { index, track ->
                launch {
                    val song = semaphore.withPermit {
                        val found = PlaylistTrackMatcher.match(track)
                        delay(REQUEST_PAUSE_MS)
                        found
                    }
                    results[index] = song
                    val done = processed.incrementAndGet()
                    val ok = if (song != null) matched.incrementAndGet() else matched.get()
                    mutableState.update { it.copy(processed = done, matched = ok) }
                }
            }
        }

        val unmatchedNames = tracks.indices.filter { results[it] == null }.map { tracks[it].displayName }
        val songs = results.filterNotNull().distinctBy { it.id }
        mutableState.update {
            it.copy(
                unmatched = unmatchedNames.take(MAX_LISTED_UNMATCHED),
                unmatchedCount = unmatchedNames.size,
            )
        }
        if (songs.isEmpty()) {
            fail(LinkImportError.NO_MATCHES)
            return
        }

        mutableState.update { it.copy(phase = LinkImportPhase.SAVING) }
        val playlistId = try {
            withContext(Dispatchers.IO) { save(playlist, songs) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Saving imported playlist failed")
            null
        }
        if (playlistId == null) {
            fail(LinkImportError.SAVE_FAILED)
            return
        }
        mutableState.update {
            it.copy(
                phase = LinkImportPhase.DONE,
                createdPlaylistId = playlistId,
                matched = songs.size,
            )
        }
    }

    /** Same building blocks as the CSV/M3U import (see saveSongsAsPlaylist, shared with the Spotify library). */
    private suspend fun save(playlist: ExternalPlaylist, songs: List<SongItem>): String =
        database.saveSongsAsPlaylist(
            name = playlist.title.trim().ifEmpty { playlist.source.name },
            coverUrl = playlist.coverUrl,
            songs = songs,
        )

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }

    companion object {
        // internal: the Spotify library's background matching (spotify/SpotifyMatching.kt) is as gentle as this import
        internal const val MAX_PARALLEL = 3
        internal const val REQUEST_PAUSE_MS = 150L
        private const val MAX_LISTED_UNMATCHED = 30
    }
}
