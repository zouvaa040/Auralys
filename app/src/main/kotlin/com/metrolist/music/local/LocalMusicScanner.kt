/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.di.ApplicationScope
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/** The permission that lets the app read the phone's audio files. */
object LocalMusicPermission {
    fun permissionFor(sdkInt: Int = Build.VERSION.SDK_INT): String =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permissionFor()) == PackageManager.PERMISSION_GRANTED
}

/** What the "Di perangkat" screen shows about the scan. */
data class LocalScanState(
    val scanning: Boolean = false,
    /** Files handled so far in the running scan. */
    val progress: Int = 0,
    val lastResult: ScanResult? = null,
    val failed: Boolean = false,
)

/**
 * Runs [LocalLibrarySync] on the app's own scope, so leaving the screen does not cancel a scan half way.
 * One scan at a time; asking again while one runs does nothing.
 */
@Singleton
class LocalMusicScanner
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val _state = MutableStateFlow(LocalScanState())
        val state: StateFlow<LocalScanState> = _state.asStateFlow()

        @Volatile private var job: Job? = null

        fun hasPermission(): Boolean = LocalMusicPermission.isGranted(context)

        /** Starts a scan unless one is running or the permission is missing. */
        @Synchronized
        fun startScan() {
            if (job?.isActive == true || !hasPermission()) return
            _state.value = _state.value.copy(scanning = true, progress = 0, failed = false)
            job =
                scope.launch(Dispatchers.IO) {
                    try {
                        val labels =
                            LocalLabels(
                                unknownArtist = context.getString(R.string.local_unknown_artist),
                                untitled = context.getString(R.string.local_untitled),
                            )
                        val result =
                            LocalLibrarySync(database, fileExists = ::fileStillThere).run(MediaStoreAudioSource(context.contentResolver), labels) { done ->
                                _state.value = _state.value.copy(progress = done)
                            }
                        context.dataStore.edit { it[LAST_SCAN_KEY] = System.currentTimeMillis() }
                        _state.value = LocalScanState(lastResult = result)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        _state.value = _state.value.copy(scanning = false)
                        throw e
                    } catch (e: Exception) {
                        // a denied or half-revoked permission, a busy MediaStore: nothing was removed, say so and move on
                        Timber.tag("LocalMusic").w(e, "Scan failed")
                        _state.value = LocalScanState(failed = true)
                    }
                }
        }

        /**
         * True when the file behind a MediaStore id can still be opened. Only "file not found" counts as gone;
         * any other failure (permission, busy provider) keeps the song.
         */
        private fun fileStillThere(mediaStoreId: Long): Boolean =
            try {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaStoreId)
                context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            } catch (e: FileNotFoundException) {
                false
            } catch (e: Exception) {
                true
            }

        /**
         * The automatic scan: runs when the permission is already granted and the last scan is older than
         * [minIntervalMs]. Skipped while the phone is in battery saver. Never throws.
         */
        suspend fun scanIfDue(minIntervalMs: Long = AUTO_SCAN_INTERVAL_MS) {
            try {
                if (!hasPermission()) return
                val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (power?.isPowerSaveMode == true) return
                val last = context.dataStore.data.first()[LAST_SCAN_KEY] ?: 0L
                if (!isDue(last, System.currentTimeMillis(), minIntervalMs)) return
                startScan()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("LocalMusic").d(e, "Automatic scan skipped")
            }
        }

        companion object {
            const val AUTO_SCAN_INTERVAL_MS = 10 * 60 * 1000L
            private val LAST_SCAN_KEY = longPreferencesKey("local_music_last_scan")

            /** True when more than [minIntervalMs] passed since [lastScanMs] (also when the clock went back). */
            fun isDue(lastScanMs: Long, nowMs: Long, minIntervalMs: Long): Boolean =
                lastScanMs <= 0L || nowMs < lastScanMs || nowMs - lastScanMs >= minIntervalMs
        }
    }
