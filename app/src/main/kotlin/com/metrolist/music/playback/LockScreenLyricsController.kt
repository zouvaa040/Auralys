/**
 * Auralys - lyrics on the lock screen
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import com.metrolist.music.constants.LockScreenLyricsKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.extensions.currentMetadata
import com.metrolist.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsHelper
import com.metrolist.music.lyrics.LyricsUtils
import com.metrolist.music.lyrics.lyricsTextLooksSynced
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.screens.lockscreen.LockScreenLyricsActivity
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** What the lock screen page reads. The service fills it; main thread only. */
object LockScreenLyricsHost {
    val player = MutableStateFlow<Player?>(null)

    /** The timed lines exactly as the app's lyrics view gets them (head entry first); empty = nothing timed to follow. */
    val lines = MutableStateFlow<List<LyricsEntry>>(emptyList())
    val lyricOffsetMs = MutableStateFlow(0L)

    /** Set by the page while it exists: the lyrics are only watched then. */
    val showing = MutableStateFlow(false)
}

/**
 * Puts [LockScreenLyricsActivity] over the lock screen when the screen is turned off while music plays, so it is there
 * when the screen comes back on. The page is started at screen-off, not screen-on: starting it later shows the system
 * lock screen first. Starting a page from the background needs "display over other apps" (Android 10+).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockScreenLyricsController(
    private val context: Context,
    private val playerFlow: StateFlow<Player?>,
    private val database: MusicDatabase,
    private val lyricsHelper: LyricsHelper,
) {
    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate +
                CoroutineExceptionHandler { _, t -> Timber.tag(TAG).e(t, "Lock screen lyrics failed") },
        )
    private val currentMeta = MutableStateFlow<MediaMetadata?>(null)
    private var attachedPlayer: Player? = null
    private var receiver: BroadcastReceiver? = null
    private var fetchedFor: String? = null

    private val playerListener =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED)) {
                    currentMeta.value = player.currentMetadata
                }
            }
        }

    private data class StoredLyrics(
        val meta: MediaMetadata,
        val raw: String?,
        val offsetMs: Long,
    )

    fun start() {
        scope.launch { playerFlow.collect(::attach) }
        scope.launch {
            context.dataStore.data
                .map { it[LockScreenLyricsKey] ?: false }
                .distinctUntilChanged()
                .collect { on -> if (on) register() else unregister() }
        }
        scope.launch {
            combine(LockScreenLyricsHost.showing, currentMeta) { showing, meta -> if (showing) meta else null }
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .flatMapLatest { meta ->
                    if (meta == null) {
                        flowOf(null)
                    } else {
                        combine(database.lyrics(meta.id), database.song(meta.id)) { row, song ->
                            StoredLyrics(meta, row?.lyrics, (song?.song?.lyricsOffset ?: 0).toLong())
                        }
                    }
                }.collectLatest { stored ->
                    if (stored == null) {
                        LockScreenLyricsHost.lines.value = emptyList()
                        return@collectLatest
                    }
                    LockScreenLyricsHost.lyricOffsetMs.value = stored.offsetMs
                    LockScreenLyricsHost.lines.value = withContext(Dispatchers.Default) { timedLines(stored.raw) }
                    if (stored.raw == null) fetch(stored.meta)
                }
        }
    }

    fun release() {
        unregister()
        attachedPlayer?.removeListener(playerListener)
        attachedPlayer = null
        LockScreenLyricsHost.player.value = null
        scope.cancel()
    }

    private fun attach(player: Player?) {
        if (attachedPlayer === player) return
        attachedPlayer?.removeListener(playerListener)
        attachedPlayer = player
        player?.addListener(playerListener)
        currentMeta.value = player?.currentMetadata
        LockScreenLyricsHost.player.value = player
    }

    /** A song without stored lyrics is looked up once, like the island does. */
    private fun fetch(meta: MediaMetadata) {
        if (fetchedFor == meta.id) return
        fetchedFor = meta.id
        scope.launch {
            try {
                lyricsHelper.fillLyrics(meta)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Lyrics lookup for the lock screen failed")
            }
        }
    }

    private fun register() {
        if (receiver != null) return
        val r =
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context,
                    intent: Intent,
                ) {
                    if (intent.action == Intent.ACTION_SCREEN_OFF) onScreenOff()
                }
            }
        ContextCompat.registerReceiver(context, r, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    private fun unregister() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private fun onScreenOff() {
        val player = playerFlow.value ?: return
        if (!shouldShow(player)) return
        if (!Settings.canDrawOverlays(context)) {
            Timber.tag(TAG).i("Lock screen lyrics skipped: no permission to show over other apps")
            return
        }
        try {
            context.startActivity(
                Intent(context, LockScreenLyricsActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                ),
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not open the lock screen lyrics")
        }
    }

    private fun shouldShow(player: Player): Boolean {
        if (!player.playWhenReady || player.currentMediaItem == null) return false
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return false
        val mode = (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode
        return mode != AudioManager.MODE_IN_CALL && mode != AudioManager.MODE_IN_COMMUNICATION && mode != AudioManager.MODE_RINGTONE
    }

    private companion object {
        const val TAG = "LockScreenLyrics"

        /** Timed lyrics parsed the way LyricsViewModel does it (with the head entry, so an intro gap gets its dots). */
        fun timedLines(raw: String?): List<LyricsEntry> {
            val text = raw?.trim()
            if (text.isNullOrEmpty() || text == LYRICS_NOT_FOUND || !lyricsTextLooksSynced(text)) return emptyList()
            val parsed = runCatching { LyricsUtils.parseLyrics(text) }.getOrDefault(emptyList())
            return if (parsed.isEmpty()) emptyList() else listOf(LyricsEntry.HEAD_LYRICS_ENTRY) + parsed
        }
    }
}
