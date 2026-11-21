/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * State holder of the "Sinkron ke Auralys PC" screen. The QR text goes in, a [SyncUiState] comes out; the session keeps
 * running if the user leaves the screen. Logs counts only, never the key or any data.
 */

package com.metrolist.music.sync

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.MusicDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

sealed interface SyncUiState {
    data object Idle : SyncUiState

    data class Running(val step: SyncStep, val pcName: String) : SyncUiState

    data class Finished(val pcName: String, val summary: MergeSummary, val backup: File?) : SyncUiState

    data class Declined(val pcName: String) : SyncUiState

    data class Failed(val kind: SyncErrorKind, val detail: String?) : SyncUiState
}

@HiltViewModel
class SyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) : ViewModel() {
    private val mutableState = MutableStateFlow<SyncUiState>(SyncUiState.Idle)
    val state: StateFlow<SyncUiState> = mutableState.asStateFlow()

    private var job: Job? = null
    private var session: SyncSession? = null

    @Volatile
    private var cancelled = false

    val isRunning: Boolean get() = mutableState.value is SyncUiState.Running

    /** [code] is the text of the scanned QR or the pasted `auralys://sync?...` line. */
    fun start(code: String) {
        if (isRunning) return
        val invite =
            try {
                SyncInvite.parse(code)
            } catch (e: SyncException) {
                mutableState.value = SyncUiState.Failed(e.kind, null)
                return
            }
        val pcName = invite.pcName
        cancelled = false
        mutableState.value = SyncUiState.Running(SyncStep.CONNECTING, pcName)
        val newSession =
            SyncAndroid.createSession(context, database) { step ->
                mutableState.value = SyncUiState.Running(step, pcName)
            }
        session = newSession
        job =
            viewModelScope.launch(Dispatchers.Default) {
                mutableState.value =
                    try {
                        val outcome = newSession.run(invite)
                        val summary = outcome.summary
                        if (outcome.declined || summary == null) {
                            Timber.tag(TAG).i("PC declined the merge")
                            SyncUiState.Declined(outcome.pcName)
                        } else {
                            Timber.tag(TAG).i(
                                "Merged: +%d songs, +%d plays, +%d playlists",
                                summary.songsAdded,
                                summary.eventsAdded,
                                summary.playlistsCreated,
                            )
                            SyncUiState.Finished(outcome.pcName, summary, outcome.backupFile)
                        }
                    } catch (e: CancellationException) {
                        SyncUiState.Idle
                    } catch (e: SyncException) {
                        Timber.tag(TAG).w("Sync failed: %s", e.kind)
                        if (cancelled) return@launch
                        SyncUiState.Failed(e.kind, if (e.kind == SyncErrorKind.PC_ERROR || e.kind == SyncErrorKind.LOCAL) e.cause?.message ?: e.message else null)
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Sync crashed")
                        if (cancelled) return@launch
                        SyncUiState.Failed(SyncErrorKind.LOCAL, e.message)
                    }
            }
    }

    fun cancel() {
        cancelled = true
        session?.abort()
        job?.cancel()
        mutableState.value = SyncUiState.Idle
    }

    fun reset() {
        if (!isRunning) mutableState.value = SyncUiState.Idle
    }

    /** Copies the safety backup to a place the file picker of "Restore" can reach. */
    suspend fun saveBackupCopy(uri: Uri, backup: File): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out -> backup.inputStream().use { it.copyTo(out) } } != null
            }.getOrDefault(false)
        }

    override fun onCleared() {
        session?.abort()
        super.onCleared()
    }

    private companion object {
        const val TAG = "Sync"
    }
}
