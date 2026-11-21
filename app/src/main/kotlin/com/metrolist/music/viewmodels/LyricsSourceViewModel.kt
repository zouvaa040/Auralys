/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.lyrics.LyricsBackup
import com.metrolist.music.lyrics.LyricsHelper
import com.metrolist.music.lyrics.LyricsSources
import com.metrolist.music.lyrics.LyricsStore
import com.metrolist.music.models.MediaMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The source picker of the lyrics view and the "try again" of the not-found screen. Every write goes through LyricsHelper. */
@HiltViewModel
class LyricsSourceViewModel
@Inject
constructor(
    private val lyricsHelper: LyricsHelper,
) : ViewModel() {
    private val _providers = MutableStateFlow<List<String>>(emptyList())

    /** Every source in the user's order. */
    val providers: StateFlow<List<String>> = _providers.asStateFlow()

    private val _switching = MutableStateFlow<String?>(null)

    /** The source being switched to (its lyrics are still coming), or null. */
    val switching: StateFlow<String?> = _switching.asStateFlow()

    private val _previous = MutableStateFlow<LyricsBackup.Saved?>(null)

    /** The text the song had before its lyrics were replaced ("Previous lyrics"), or null. */
    val previous: StateFlow<LyricsBackup.Saved?> = _previous.asStateFlow()

    private val _retrying = MutableStateFlow(false)

    /** True while "Try again" of the not-found screen runs. */
    val retrying: StateFlow<Boolean> = _retrying.asStateFlow()

    /** songId to the source the user picked. */
    val choices: Flow<Map<String, String>> = lyricsHelper.choices.all

    fun sourcesOf(mediaId: String): StateFlow<LyricsSources.State> = lyricsHelper.sourcesOf(mediaId)

    fun isUnavailable(
        mediaId: String,
        provider: String,
    ): Boolean = mediaId.startsWith("LOCAL_") && provider in LyricsSources.VIDEO_ID_PROVIDERS

    /** The picker opened: list the sources, read "Previous lyrics", and ask, once, every enabled one that has not answered. */
    fun open(mediaMetadata: MediaMetadata) {
        viewModelScope.launch {
            _previous.value = null
            _providers.value = lyricsHelper.orderedProviders().map { it.name }
            _previous.value = lyricsHelper.previousOf(mediaMetadata.id)
            lyricsHelper.fetchAllSources(mediaMetadata)
        }
    }

    /**
     * Shows the lyrics of [provider] (they are kept per song) and remembers the pick. Another version is only taken with
     * [useAnyway] (its "Use anyway" button); a first tap that finds another version changes nothing.
     */
    fun choose(
        mediaMetadata: MediaMetadata,
        provider: String,
        useAnyway: Boolean = false,
        onDone: (LyricsHelper.PickResult) -> Unit,
    ) {
        if (_switching.value != null) return
        viewModelScope.launch {
            _switching.value = provider
            val result =
                withContext(Dispatchers.IO) {
                    runCatching { lyricsHelper.choose(mediaMetadata, provider, useAnyway) }.getOrDefault(LyricsHelper.PickResult.NO_CONNECTION)
                }
            _switching.value = null
            onDone(result)
        }
    }

    /** Back to the automatic choice for this song; the lyrics stay as they are when it finds nothing or is offline. */
    fun chooseAutomatic(
        mediaMetadata: MediaMetadata,
        onDone: (LyricsHelper.AutoResult) -> Unit,
    ) {
        if (_switching.value != null) return
        viewModelScope.launch {
            _switching.value = AUTO
            val result =
                withContext(Dispatchers.IO) {
                    runCatching { lyricsHelper.automatic(mediaMetadata) }.getOrDefault(LyricsHelper.AutoResult.NO_CONNECTION)
                }
            _switching.value = null
            onDone(result)
        }
    }

    /** "Previous lyrics" taken back. */
    fun choosePrevious(
        mediaMetadata: MediaMetadata,
        onDone: (Boolean) -> Unit,
    ) {
        if (_switching.value != null) return
        viewModelScope.launch {
            _switching.value = PREVIOUS
            val ok = withContext(Dispatchers.IO) { runCatching { lyricsHelper.restorePrevious(mediaMetadata) }.getOrDefault(false) }
            _switching.value = null
            onDone(ok)
        }
    }

    /** "Try again" on the not-found screen. */
    fun retryNow(
        mediaMetadata: MediaMetadata,
        onDone: (LyricsStore.Result) -> Unit,
    ) {
        if (_retrying.value) return
        viewModelScope.launch {
            _retrying.value = true
            val result =
                withContext(Dispatchers.IO) {
                    runCatching { lyricsHelper.retryNow(mediaMetadata) }.getOrDefault(LyricsStore.Result.NO_CONNECTION)
                }
            _retrying.value = false
            onDone(result)
        }
    }

    companion object {
        const val AUTO = "\u0000auto"
        const val PREVIOUS = "\u0000previous-row"
    }
}
