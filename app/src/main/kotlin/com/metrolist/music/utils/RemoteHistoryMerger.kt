/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Decides when the listening history of the signed-in account is merged into the phone (see [RemoteHistoryMerge]) and
 * keeps the result for the settings page. Runs: right after the first launch with an account (this also covers a fresh
 * login, which restarts the app), at most once every six hours when the app is opened, and when the user asks.
 * Never from the playback service, and not while the data saver applies.
 */

package com.metrolist.music.utils

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.datastore.preferences.core.Preferences
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.R
import com.metrolist.music.constants.AccountChannelHandleKey
import com.metrolist.music.constants.AccountEmailKey
import com.metrolist.music.constants.DataSaverKey
import com.metrolist.music.constants.DataSaverMobileOnlyKey
import com.metrolist.music.constants.DataSyncIdKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.PauseListenHistoryKey
import com.metrolist.music.constants.RemoteHistoryMergeAccountKey
import com.metrolist.music.constants.RemoteHistoryMergeCoveredDayKey
import com.metrolist.music.constants.RemoteHistoryMergeEnabledKey
import com.metrolist.music.constants.RemoteHistoryMergeFloorDayKey
import com.metrolist.music.constants.RemoteHistoryMergeLastAddedKey
import com.metrolist.music.constants.RemoteHistoryMergeLastAttemptKey
import com.metrolist.music.constants.RemoteHistoryMergeLastSuccessKey
import com.metrolist.music.constants.RemoteHistoryMergeRemovedKey
import com.metrolist.music.constants.StreakRecoveriesKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteHistoryMerger
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** What the settings page shows besides the stored result. */
    sealed interface State {
        data object Idle : State

        data object Running : State

        data class Failed(val reason: String?) : State

        /** The user paused the listen history: nothing is written to it, so nothing is merged. */
        data object Paused : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** An automatic check is waiting for its start delay (opening the app many times does not stack them). */
    private val autoWaiting = AtomicBoolean(false)

    /** A merge is reading or writing right now. */
    private val importing = AtomicBoolean(false)

    /** The app came to the foreground. Cheap; does nothing unless a run is due. */
    fun onAppOpened() = launchRun(manual = false)

    /** The user asked for it. */
    fun mergeNow() = launchRun(manual = true)

    private fun launchRun(manual: Boolean) {
        if (manual) {
            if (importing.get()) return
        } else if (!autoWaiting.compareAndSet(false, true)) {
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                // let the first screen and the library sync go first
                if (!manual) delay(START_DELAY_MS)
                runOnce(manual)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "History merge failed")
                _state.value = State.Failed(e.message)
            } finally {
                if (!manual) autoWaiting.set(false)
            }
        }
    }

    private suspend fun runOnce(manual: Boolean) {
        val prefs = context.dataStore.data.first()
        val cookie = prefs[InnerTubeCookieKey].orEmpty()
        val signedIn = cookie.isNotBlank() && "SAPISID" in parseCookieString(cookie)
        val fingerprint = fingerprintOf(prefs)
        val storedFingerprint = prefs[RemoteHistoryMergeAccountKey]
        val lastSuccess = prefs[RemoteHistoryMergeLastSuccessKey]
        val accountChanged = storedFingerprint != null && storedFingerprint != fingerprint
        val neverRan = accountChanged || lastSuccess == null
        val nowMs = System.currentTimeMillis()

        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val metered = connectivity?.isActiveNetworkMetered == true
        val dataSaver = (prefs[DataSaverKey] ?: false) && (!(prefs[DataSaverMobileOnlyKey] ?: true) || metered)

        val decision =
            RemoteHistoryMergeGate.decide(
                manual = manual,
                signedIn = signedIn,
                switchOn = prefs[RemoteHistoryMergeEnabledKey] ?: true,
                listenHistoryPaused = prefs[PauseListenHistoryKey] ?: false,
                dataSaverActive = dataSaver,
                online = connectivity?.activeNetwork != null,
                neverRan = neverRan,
                nowMs = nowMs,
                lastSuccessMs = if (accountChanged) null else lastSuccess,
                lastAttemptMs = prefs[RemoteHistoryMergeLastAttemptKey],
            )
        if (decision != RemoteHistoryMergeGate.Decision.RUN) {
            Timber.tag(TAG).d("History merge not started: %s (manual=%s)", decision, manual)
            if (manual) {
                _state.value =
                    when (decision) {
                        RemoteHistoryMergeGate.Decision.SKIP_PAUSED -> State.Paused
                        RemoteHistoryMergeGate.Decision.SKIP_OFFLINE -> State.Failed("offline")
                        else -> _state.value
                    }
            }
            return
        }

        // the cookie reaches the YouTube object a moment after the app starts
        var waited = 0L
        while (YouTube.cookie.isNullOrBlank() && waited < COOKIE_WAIT_MS) {
            delay(500)
            waited += 500
        }
        if (YouTube.cookie.isNullOrBlank()) {
            Timber.tag(TAG).d("History merge not started: the session is not ready")
            return
        }

        // one merge at a time: a manual run that starts while an automatic one is working just waits for its result
        if (!importing.compareAndSet(false, true)) return
        try {
            merge(manual, prefs, fingerprint, accountChanged, neverRan, nowMs)
        } finally {
            importing.set(false)
        }
    }

    private suspend fun merge(
        manual: Boolean,
        prefs: Preferences,
        fingerprint: String,
        accountChanged: Boolean,
        neverRan: Boolean,
        nowMs: Long,
    ) {
        _state.value = State.Running
        context.safeDataStoreEdit { edit ->
            if (accountChanged) {
                // another account: what was remembered about the old one does not apply
                edit.remove(RemoteHistoryMergeCoveredDayKey)
                edit.remove(RemoteHistoryMergeFloorDayKey)
                edit.remove(RemoteHistoryMergeRemovedKey)
                edit.remove(RemoteHistoryMergeLastSuccessKey)
            }
            edit[RemoteHistoryMergeAccountKey] = fingerprint
            edit[RemoteHistoryMergeLastAttemptKey] = nowMs
        }
        val fresh = if (accountChanged) context.dataStore.data.first() else prefs

        val covered = fresh[RemoteHistoryMergeCoveredDayKey]
        val floor = fresh[RemoteHistoryMergeFloorDayKey]
        val full = manual || neverRan || covered == null || database.eventCountNow() == 0
        val outcome =
            RemoteHistoryImporter(database)
                .run(
                    ImportRequest(
                        mode = if (full) ImportMode.FULL else ImportMode.INCREMENTAL,
                        stopBelowDay = RemoteHistoryMergeGate.stopBelowDay(full, covered, floor),
                        floorDay = floor?.let(LocalDate::ofEpochDay),
                        removed = RemovedPlays.parse(fresh[RemoteHistoryMergeRemovedKey]),
                    ),
                )
        Timber
            .tag(TAG)
            .i(
                "History merge done: full=%s pages=%d entries=%d added=%d complete=%s capped=%s skippedTitles=%d",
                full, outcome.pages, outcome.entries, outcome.added, outcome.complete, outcome.capped, outcome.skippedTitles.size,
            )

        if (outcome.added > 0) handBackRecoveries()
        if (outcome.complete) {
            context.safeDataStoreEdit { edit ->
                edit[RemoteHistoryMergeLastSuccessKey] = System.currentTimeMillis()
                edit[RemoteHistoryMergeLastAddedKey] = outcome.added
                edit[RemoteHistoryMergeCoveredDayKey] = LocalDate.now().toEpochDay()
            }
            _state.value = State.Idle
            if (outcome.added > 0 && (manual || neverRan)) showAdded(outcome.added)
        } else {
            _state.value = State.Failed(outcome.error?.message ?: "timeout")
        }
    }

    /** A recovery spent for a day that the merged plays now fill is handed back. */
    private suspend fun handBackRecoveries() {
        try {
            val played = database.listeningDays().first().toSet()
            context.safeDataStoreEdit { edit ->
                val entries = StreakRecoveries.parse(edit[StreakRecoveriesKey])
                val kept = refundFilledRecoveries(entries, played)
                if (kept.size != entries.size) edit[StreakRecoveriesKey] = StreakRecoveries.serialize(kept)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not hand back the streak recoveries")
        }
    }

    private fun showAdded(count: Int) {
        Handler(Looper.getMainLooper()).post {
            Toast
                .makeText(
                    context,
                    context.resources.getQuantityString(R.plurals.auralys_history_merge_done, count, count),
                    Toast.LENGTH_SHORT,
                ).show()
        }
    }

    private fun fingerprintOf(prefs: Preferences): String {
        val raw = listOf(prefs[DataSyncIdKey], prefs[AccountEmailKey], prefs[AccountChannelHandleKey]).joinToString("|") { it.orEmpty() }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "HistoryMerge"
        private const val START_DELAY_MS = 6_000L
        private const val COOKIE_WAIT_MS = 10_000L

        private val bookkeeping = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

        /** The user cleared the listen history: the account's days up to today are not merged back. */
        fun noteHistoryCleared(context: Context, today: LocalDate = LocalDate.now()) {
            val app = context.applicationContext
            bookkeeping.launch {
                app.safeDataStoreEdit { it[RemoteHistoryMergeFloorDayKey] = today.toEpochDay() }
            }
        }

        /** The user removed one play from the history: the next merge does not bring that entry back. */
        fun noteEventRemoved(context: Context, songId: String, at: java.time.LocalDateTime) {
            val app = context.applicationContext
            bookkeeping.launch {
                app.safeDataStoreEdit {
                    it[RemoteHistoryMergeRemovedKey] =
                        RemovedPlays.add(it[RemoteHistoryMergeRemovedKey], at.toLocalDate().toEpochDay(), songId)
                }
            }
        }

        /** Logout: everything remembered about the account goes (the switch stays). */
        fun forget(edit: androidx.datastore.preferences.core.MutablePreferences) {
            edit.remove(RemoteHistoryMergeAccountKey)
            edit.remove(RemoteHistoryMergeCoveredDayKey)
            edit.remove(RemoteHistoryMergeFloorDayKey)
            edit.remove(RemoteHistoryMergeRemovedKey)
            edit.remove(RemoteHistoryMergeLastSuccessKey)
            edit.remove(RemoteHistoryMergeLastAttemptKey)
            edit.remove(RemoteHistoryMergeLastAddedKey)
        }
    }
}
