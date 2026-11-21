/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.lyrics.LyricsHelper
import com.metrolist.music.lyrics.LyricsResult
import com.metrolist.music.lyrics.LyricsStore
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.NetworkConnectivityObserver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class LyricsMenuViewModel
@Inject
constructor(
    private val lyricsHelper: LyricsHelper,
    val database: MusicDatabase,
    private val networkConnectivity: NetworkConnectivityObserver,
) : ViewModel() {
    private var job: Job? = null
    val results = MutableStateFlow(emptyList<LyricsResult>())
    val isLoading = MutableStateFlow(false)

    private val _isNetworkAvailable = MutableStateFlow(false)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    init {
        viewModelScope.launch {
            networkConnectivity.networkStatus.collect { isConnected ->
                _isNetworkAvailable.value = isConnected
            }
        }

        _isNetworkAvailable.value = try {
            networkConnectivity.isCurrentlyConnected()
        } catch (e: Exception) {
            true // Assume connected as fallback
        }
    }

    fun search(
        mediaId: String,
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ) {
        isLoading.value = true
        results.value = emptyList()
        job?.cancel()
        job =
            viewModelScope.launch(Dispatchers.IO) {
                lyricsHelper.getAllLyrics(mediaId, title, artist, duration, album) { result ->
                    results.update {
                        it + result
                    }
                }
                isLoading.value = false
            }
    }

    fun cancelSearch() {
        job?.cancel()
        job = null
    }

    /**
     * Refetch: the song is looked up again. The lyrics on screen stay until better ones are found; offline, with every
     * source failing or with nothing found they are kept (before, the row was deleted first and replaced by whatever
     * came back, "not found" while offline included). [onDone] gets what happened, on the main thread.
     */
    fun refetchLyrics(
        mediaMetadata: MediaMetadata,
        @Suppress("UNUSED_PARAMETER") lyricsEntity: LyricsEntity?,
        onDone: (LyricsStore.Result) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching { lyricsHelper.refetch(mediaMetadata) }.getOrDefault(LyricsStore.Result.NO_CONNECTION)
                }
            onDone(result)
        }
    }

    /** A result of the lyrics search list taken for the song (the text it replaces is kept as "Previous lyrics"). */
    fun saveFromSearch(
        mediaId: String,
        lyrics: String,
        provider: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { lyricsHelper.saveByHand(mediaId, lyrics, provider) } }
    }
}
