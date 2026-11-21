/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.PauseListenHistoryKey
import com.metrolist.music.constants.RemoteHistoryMergeEnabledKey
import com.metrolist.music.constants.RemoteHistoryMergeLastAddedKey
import com.metrolist.music.constants.RemoteHistoryMergeLastSuccessKey
import com.metrolist.music.utils.RemoteHistoryMerger
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the settings rows of the history merge show. */
@Immutable
data class HistoryMergeUi(
    val signedIn: Boolean = false,
    val switchOn: Boolean = true,
    val running: Boolean = false,
    val failed: Boolean = false,
    val paused: Boolean = false,
    /** Epoch milliseconds of the last run that finished, or null when there was none. */
    val lastSuccessMs: Long? = null,
    val lastAdded: Int = 0,
)

@HiltViewModel
class RemoteHistoryMergeViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val merger: RemoteHistoryMerger,
) : ViewModel() {
    val ui: StateFlow<HistoryMergeUi> =
        combine(context.dataStore.data, merger.state) { prefs, state ->
            HistoryMergeUi(
                signedIn = "SAPISID" in parseCookieString(prefs[InnerTubeCookieKey].orEmpty()),
                switchOn = prefs[RemoteHistoryMergeEnabledKey] ?: true,
                running = state is RemoteHistoryMerger.State.Running,
                failed = state is RemoteHistoryMerger.State.Failed,
                paused = (prefs[PauseListenHistoryKey] ?: false) || state is RemoteHistoryMerger.State.Paused,
                lastSuccessMs = prefs[RemoteHistoryMergeLastSuccessKey],
                lastAdded = prefs[RemoteHistoryMergeLastAddedKey] ?: 0,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryMergeUi())

    fun setSwitch(on: Boolean) {
        viewModelScope.launch { context.safeDataStoreEdit { it[RemoteHistoryMergeEnabledKey] = on } }
    }

    fun mergeNow() = merger.mergeNow()
}
