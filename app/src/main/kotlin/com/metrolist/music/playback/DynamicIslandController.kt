/**
 * Auralys - Dynamic Island overlay controller
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.MainActivity
import com.metrolist.music.constants.DarkModeKey
import com.metrolist.music.constants.DynamicIslandCompactContent
import com.metrolist.music.constants.DynamicIslandDefaultY
import com.metrolist.music.constants.DynamicIslandEnabledKey
import com.metrolist.music.constants.DynamicIslandLongPressAction
import com.metrolist.music.constants.DynamicIslandMaxY
import com.metrolist.music.constants.DynamicIslandMinY
import com.metrolist.music.constants.DynamicIslandXOffsetKey
import com.metrolist.music.constants.DynamicIslandXOffsetLimit
import com.metrolist.music.constants.DynamicIslandTapAction
import com.metrolist.music.constants.DynamicIslandXKey
import com.metrolist.music.constants.DynamicIslandXLandscapeKey
import com.metrolist.music.constants.DynamicIslandYKey
import com.metrolist.music.constants.DynamicIslandYLandscapeKey
import com.metrolist.music.constants.IconFamilyKey
import com.metrolist.music.constants.SelectedThemeColorKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.extensions.currentMetadata
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.extensions.togglePlayPause
import com.metrolist.music.lyrics.LyricsHelper
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.component.DynamicIslandActions
import com.metrolist.music.ui.component.DynamicIslandConfig
import com.metrolist.music.ui.component.DynamicIslandDefaultAccent
import com.metrolist.music.ui.component.DynamicIslandDefaultCoverBottom
import com.metrolist.music.ui.component.DynamicIslandDefaultCoverTop
import com.metrolist.music.ui.component.DynamicIslandDimens
import com.metrolist.music.ui.component.IconFamily
import com.metrolist.music.ui.component.IslandLyrics
import com.metrolist.music.ui.component.IslandLyricsLogic
import com.metrolist.music.ui.component.IslandLyricsStatus
import com.metrolist.music.ui.component.LyricsPlayheadSource
import com.metrolist.music.ui.component.PlayerPlayheadSource
import com.metrolist.music.ui.component.DynamicIslandOverlayContent
import com.metrolist.music.ui.component.DynamicIslandUiState
import com.metrolist.music.ui.component.IslandGeometry
import com.metrolist.music.ui.component.IslandRect
import com.metrolist.music.ui.component.IslandScreenSpec
import com.metrolist.music.ui.component.IslandWindowGeometry
import com.metrolist.music.ui.component.toDynamicIslandConfig
import com.metrolist.music.ui.screens.settings.DarkMode
import com.metrolist.music.ui.theme.DefaultThemeColor
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Shows a small "Dynamic Island" pill over other apps while music is playing and the
 * Auralys UI is in the background.
 *
 * Cost when inactive: one DataStore collector for the master switch. Only when the switch is
 * on is the process lifecycle observed, and only while the app is in the background (and the
 * overlay permission is granted) is a [Player.Listener] attached. The overlay view itself is
 * attached to the WindowManager only while it is actually visible.
 *
 * Owned by [MusicService]: call [start] once the player exists and [release] in onDestroy.
 * Must be used from the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DynamicIslandController(
    private val context: Context,
    private val playerFlow: StateFlow<Player?>,
    private val database: MusicDatabase,
    private val lyricsHelper: LyricsHelper,
) {
    // The overlay is a nice-to-have: whatever goes wrong inside it must never take the app down with it.
    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate +
                CoroutineExceptionHandler { _, t -> Timber.tag(TAG).e(t, "Dynamic Island failed") },
        )
    private val ui = DynamicIslandUiState()

    /**
     * The island lives in an accessibility overlay of [IslandAccessibilityService], the only window a normal app can put in
     * front of the status bar (an app overlay is drawn below it and cannot be pressed there). [frontContext] is the
     * connected service; it only changes through [syncHostMode], while no view is attached, so every window call of one
     * view goes to the window manager that added it.
     */
    private var frontContext: Context? = null

    private val overlayContext: Context
        get() = frontContext ?: context

    private val windowManager: WindowManager
        get() = (overlayContext.getSystemService(Context.WINDOW_SERVICE) ?: context.getSystemService(Context.WINDOW_SERVICE)) as WindowManager

    /** Follows the service: a new connection (the user turned it on, or it restarted) gets a fresh view; losing it removes the view. */
    private fun syncHostMode() {
        val service: Context? = IslandAccessibilityHost.service.value
        if (frontContext === service) return
        removeViewNow()
        frontContext = service
        statusBarCache = -1
        if (service != null && player != null) evaluate()
    }

    private var rootView: IslandTouchLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var viewOwner: OverlayViewOwner? = null
    private var configCallbacks: ComponentCallbacks? = null

    /** The island follows the screen when it is turned: it keeps its own place in landscape and is laid out again for the new size. */
    private var orientationCallbacks: ComponentCallbacks? = null
    private var landscape = false

    private var player: Player? = null

    /** Where the song is, for the lyric line of the island (a frame clock reads it while a line is shown). */
    private val playhead = PlayerPlayheadSource { player }
    private var xFraction = 0f
    private var yDp = DynamicIslandDefaultY

    // the saved places: upright, and landscape (null = the island has none of its own there yet)
    private var portraitX = 0f
    private var portraitY = DynamicIslandDefaultY
    private var landX: Float? = null
    private var landY: Int? = null
    private var xOffsetDp = 0
    private var currentMediaId: String? = null
    private var dismissedMediaId: String? = null
    private var wasPlaying = false
    private var pausedAt = 0L

    private var dragArmed = false
    private var dragging = false
    private var dragStartX = 0
    private var dragStartY = 0

    /**
     * The island is two windows. The VISUAL window ([rootView]) always has the size of the open card and never takes a touch
     * (FLAG_NOT_TOUCHABLE), so it is never resized when the card opens or closes: every resize of an overlay window showed
     * for one frame the old picture at the new place (a screen recording showed the island jumping sideways and down for one
     * frame when the card opened and 0.4 s after it closed). The TOUCH window ([hitView]) is invisible and exactly as big as
     * what is visible (the pill, or the card while it is open or closing); it hands every touch to the visual window's
     * root. Resizing a window that draws nothing cannot glitch, and the apps next to the pill keep every touch.
     */
    private var hitView: IslandHitLayout? = null
    private var hitParams: WindowManager.LayoutParams? = null

    /** The touch window is card sized: from the tap that opens the card until the card has closed again. */
    private var hitOpen = false

    /** Last geometry applied to the window (pill, card and both windows on screen). */
    private var geometry: IslandGeometry? = null

    /** Display rotation watcher (the process configuration may follow a stopped Auralys activity instead of the screen). */
    private var displayListener: DisplayManager.DisplayListener? = null

    /** True while the seek bar is being dragged (auto-collapse is suspended). */
    private var sliderActive = false

    /** The lyrics of [lyricsFor] are being watched in the database; [lyricsFetchedFor] already asked the sources. */
    private var lyricsJob: Job? = null
    private var lyricsFetchJob: Job? = null
    private var lyricsFor: String? = null
    private var lyricsFetchedFor: String? = null
    private var flashJob: Job? = null
    private var graceJob: Job? = null

    /** Since when the player has been idle although the user wants music (the moment between two songs, an error); 0 = not idle. */
    private var unloadedAt = 0L

    private var mainJob: Job? = null
    private var pauseJob: Job? = null
    private var artJob: Job? = null
    private var collapseJob: Job? = null
    private var removeJob: Job? = null

    private val config: DynamicIslandConfig
        get() = ui.config

    /** A paused song keeps the island this long (a quick pause and play does not blink it), then it shrinks away. */
    private val pauseTimeoutMs: Long
        get() = PAUSE_HIDE_MS

    private val autoCollapseMs: Long
        get() = config.autoCollapseSec * 1000L

    /** After a button/seek interaction the card stays open a little longer than the plain auto-collapse. */
    private val interactionCollapseMs: Long
        get() = autoCollapseMs + 2_000L

    private val playerListener =
        object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                evaluate()
            }
        }

    fun start() {
        if (mainJob != null) return
        mainJob =
            scope.launch {
                // the island needs its accessibility service: without it nothing is shown, and it starts by itself when it is turned on
                combine(
                    context.dataStore.data
                        .map { it[DynamicIslandEnabledKey] ?: false }
                        .catch { emit(false) },
                    IslandAccessibilityHost.service,
                ) { enabled, service -> enabled && service != null }
                    .distinctUntilChanged()
                    .flatMapLatest { enabled ->
                        if (!enabled) {
                            flowOf(false)
                        } else {
                            ProcessLifecycleOwner.get().lifecycle.currentStateFlow
                                .map { !it.isAtLeast(Lifecycle.State.STARTED) }
                        }
                    }.distinctUntilChanged()
                    .collectLatest { appInBackground ->
                        if (appInBackground) {
                            observeWhileInBackground()
                        } else {
                            removeViewNow()
                            frontContext = null
                        }
                    }
            }
    }

    fun release() {
        mainJob?.cancel()
        mainJob = null
        unwatchOrientation()
        player?.removeListener(playerListener)
        playhead.detach()
        player = null
        removeViewNow()
        scope.cancel()
    }

    // ---------------------------------------------------------------- observation

    /**
     * Landscape = the SCREEN is wider than tall. The service's own configuration is the process configuration, which on
     * Android 10+ follows the process's last activity (Auralys' own, stopped in the background) rather than the screen,
     * so it could still say "landscape" (island hidden) while the launcher or another app is upright, and its change
     * callback may not come at all. The overlay's window context and the display listener follow the real screen.
     */
    private fun landscapeNow(): Boolean {
        val w = screenWidth()
        val h = screenHeight()
        return if (w > 0 && h > 0) w > h else context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    /** The lite look (see [DynamicIslandUiState.lite]): a landscape screen, or the phone saves power. */
    private fun updateLite() {
        val lite = landscape || runCatching { context.getSystemService(PowerManager::class.java)?.isPowerSaveMode }.getOrNull() == true
        if (ui.lite != lite) ui.lite = lite
    }

    private fun onScreenMaybeRotated() {
        statusBarCache = -1
        updateCamera()
        val nowLandscape = landscapeNow()
        val turned = nowLandscape != landscape
        landscape = nowLandscape
        updateLite()
        if (turned && !dragging) applyPositionPrefs()
        // also a half turn (landscape to the other landscape) changes where the cut-out and the status bar are
        updatePosition()
    }

    /** Where the island is for the current orientation: its landscape place when it has one, else the upright one. */
    private fun applyPositionPrefs() {
        val x = if (landscape) (landX ?: portraitX) else portraitX
        val y = if (landscape) (landY ?: portraitY) else portraitY
        xFraction = x.coerceIn(-1f, 1f)
        yDp = y.coerceIn(DynamicIslandMinY, DynamicIslandMaxY)
    }

    private fun watchOrientation() {
        if (orientationCallbacks != null) return
        landscape = landscapeNow()
        updateLite()
        val callbacks =
            object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) = onScreenMaybeRotated()

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() = Unit
            }
        context.registerComponentCallbacks(callbacks)
        orientationCallbacks = callbacks
        try {
            val listener =
                object : DisplayManager.DisplayListener {
                    override fun onDisplayAdded(displayId: Int) = Unit

                    override fun onDisplayRemoved(displayId: Int) = Unit

                    override fun onDisplayChanged(displayId: Int) {
                        if (displayId == Display.DEFAULT_DISPLAY) onScreenMaybeRotated()
                    }
                }
            context.getSystemService(DisplayManager::class.java)?.registerDisplayListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
            displayListener = listener
        } catch (_: Exception) {
        }
    }

    private fun unwatchOrientation() {
        orientationCallbacks?.let {
            try {
                context.unregisterComponentCallbacks(it)
            } catch (_: Exception) {
            }
        }
        orientationCallbacks = null
        displayListener?.let {
            try {
                context.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(it)
            } catch (_: Exception) {
            }
        }
        displayListener = null
    }

    private suspend fun observeWhileInBackground() {
        watchOrientation()
        try {
            coroutineScope {
                launch {
                    // the user turns the accessibility service on or off while the island runs
                    IslandAccessibilityHost.service.collect { syncHostMode() }
                }
                launch {
                    context.dataStore.data
                        .map { prefs ->
                            IslandPrefs(
                                x = prefs[DynamicIslandXKey] ?: 0f,
                                y = prefs[DynamicIslandYKey] ?: DynamicIslandDefaultY,
                                xLand = prefs[DynamicIslandXLandscapeKey],
                                yLand = prefs[DynamicIslandYLandscapeKey],
                                xOffset = prefs[DynamicIslandXOffsetKey] ?: 0,
                                config = prefs.toDynamicIslandConfig(),
                                appAccent = prefs[SelectedThemeColorKey] ?: DefaultThemeColor.toArgb(),
                                dark =
                                    when (prefs[DarkModeKey].toEnum(DarkMode.AUTO)) {
                                        DarkMode.ON -> true
                                        DarkMode.OFF -> false
                                        DarkMode.AUTO -> null
                                    },
                                iconFamily = prefs[IconFamilyKey].toEnum(IconFamily.DEFAULT),
                            )
                        }.catch { }
                        .distinctUntilChanged()
                        .collect { p ->
                            portraitX = p.x
                            portraitY = p.y
                            landX = p.xLand
                            landY = p.yLand
                            if (!dragging) {
                                applyPositionPrefs()
                                xOffsetDp = p.xOffset.coerceIn(-DynamicIslandXOffsetLimit, DynamicIslandXOffsetLimit)
                            }
                            val previous = ui.config
                            ui.config = p.config
                            ui.scale = p.config.size.scale
                            ui.appAccent = Color(p.appAccent)
                            ui.dark = p.dark
                            ui.iconFamily = p.iconFamily
                            // Size, width, layout and position apply live while the island is visible.
                            updatePosition()
                            if (player != null &&
                                (
                                    previous.showLyrics != p.config.showLyrics ||
                                        previous.compactContent != p.config.compactContent
                                )
                            ) {
                                evaluate()
                            }
                            if (previous.autoCollapseSec != p.config.autoCollapseSec && collapseJob != null) {
                                scheduleCollapse()
                            }
                        }
                }
                playerFlow.collectLatest { newPlayer ->
                    attachPlayer(newPlayer)
                    awaitCancellation()
                }
            }
        } finally {
            unwatchOrientation()
            player?.removeListener(playerListener)
            playhead.detach()
            cancelLyrics()
            player = null
            pauseJob?.cancel()
            removeViewNow()
        }
    }

    private fun attachPlayer(newPlayer: Player?) {
        if (player !== newPlayer) {
            player?.removeListener(playerListener)
            player = newPlayer
            newPlayer?.addListener(playerListener)
            playhead.attach(newPlayer)
        }
        pauseJob?.cancel()
        pausedAt = 0L
        wasPlaying = newPlayer?.let { wantsToPlay(it) } ?: false
        evaluate()
    }

    private fun wantsToPlay(p: Player): Boolean =
        p.playWhenReady && (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)

    private fun evaluate() {
        try {
            evaluateUnsafe()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Dynamic Island update failed")
        }
    }

    private fun evaluateUnsafe() {
        updateLite()
        val p = player
        val meta = p?.currentMetadata
        if (p == null || meta == null) {
            syncLyrics(null)
            hide()
            return
        }
        if (meta.id != currentMediaId) {
            currentMediaId = meta.id
            dismissedMediaId = null
            loadArtwork(meta.thumbnailUrl)
            flashTitle()
        }
        ui.title = meta.title
        ui.artist = meta.artists.joinToString { it.name }
        // "wants to play", not Player.isPlaying: that one is false for a moment at every song change, seek and stall, and the
        // equaliser, the cover spin and the title marquee all dropped and restarted each time
        ui.isPlaying = wantsToPlay(p)
        ui.canSkipPrevious = p.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)
        ui.canSkipNext = p.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)

        val state = p.playbackState
        val loaded = state == Player.STATE_READY || state == Player.STATE_BUFFERING
        val playing = wantsToPlay(p)
        val timeout = pauseTimeoutMs
        if (playing) {
            pausedAt = 0L
            pauseJob?.cancel()
        } else if (wasPlaying && loaded) {
            pausedAt = SystemClock.elapsedRealtime()
            pauseJob?.cancel()
            pauseJob =
                scope.launch {
                    delay(timeout + 50L)
                    evaluate()
                }
        }
        wasPlaying = playing

        val recentlyPaused = pausedAt != 0L && SystemClock.elapsedRealtime() - pausedAt < timeout
        // A paused island goes by itself, unless the user is looking at the open card; it comes back when the music plays.
        val keepWhilePaused = recentlyPaused || (ui.expanded && pausedAt != 0L)
        var shouldShow = loaded && dismissedMediaId != meta.id && (playing || keepWhilePaused)
        // The player is idle for a moment between two songs (and while a stream is looked up again): that is not "stopped".
        // Hiding then closed the card, removed the view and put it back with the pop-in a moment later.
        if (!shouldShow && p.playWhenReady && !loaded && ui.visible && dismissedMediaId != meta.id) {
            val now = SystemClock.elapsedRealtime()
            if (unloadedAt == 0L) unloadedAt = now
            if (now - unloadedAt < HIDE_GRACE_MS) {
                shouldShow = true
                graceJob?.cancel()
                graceJob =
                    scope.launch {
                        delay(HIDE_GRACE_MS - (now - unloadedAt) + 20L)
                        evaluate()
                    }
            }
        } else {
            unloadedAt = 0L
            graceJob?.cancel()
            graceJob = null
        }
        syncLyrics(if (shouldShow) meta else null)
        if (shouldShow) show() else hide()
    }

    /** The lyric pill shows "title - artist" for a few seconds after a song change, like the toast of the Windows island. */
    private fun flashTitle() {
        flashJob?.cancel()
        ui.titleFlash = true
        flashJob =
            scope.launch {
                delay(TITLE_FLASH_MS)
                ui.titleFlash = false
            }
    }

    // ---------------------------------------------------------------- lyrics

    /** Watches the lyrics of [meta] in the database while the island is shown and the lyric option is on; null stops. */
    private fun syncLyrics(meta: MediaMetadata?) {
        if (meta == null || !config.showLyrics) {
            cancelLyrics()
            return
        }
        if (lyricsJob?.isActive == true && lyricsFor == meta.id) {
            ensureLyricsFetch(meta)
            return
        }
        cancelLyrics()
        lyricsFor = meta.id
        ui.lyrics = IslandLyrics.Loading
        ui.lyricIndex = -1
        ui.lyricSweep = 0f
        lyricsJob =
            scope.launch {
                combine(
                    database.lyrics(meta.id),
                    database.song(meta.id),
                ) { row, song -> row?.lyrics to (song?.song?.lyricsOffset ?: 0) }
                    .distinctUntilChanged()
                    .collectLatest { (raw, offset) ->
                        ui.lyricOffsetMs = offset.toLong()
                        val parsed = withContext(Dispatchers.Default) { IslandLyricsLogic.fromStored(raw) }
                        ui.lyrics = parsed
                        ui.lyricIndex = -1
                        ui.lyricSweep = 0f
                        if (raw == null) ensureLyricsFetch(meta)
                    }
            }
    }

    private fun cancelLyrics() {
        lyricsJob?.cancel()
        lyricsJob = null
        lyricsFetchJob?.cancel()
        lyricsFetchJob = null
        lyricsFor = null
        lyricsFetchedFor = null
        if (ui.lyrics.status != IslandLyricsStatus.OFF) {
            ui.lyrics = IslandLyrics.Off
            ui.lyricIndex = -1
            ui.lyricSweep = 0f
        }
    }

    /**
     * A song without stored lyrics is looked up once, and only when the island needs the lines: the card is open or the pill
     * shows the lyric line. The helper waits through a lost connection and shares the lookup with the player screen.
     */
    private fun ensureLyricsFetch(meta: MediaMetadata) {
        if (lyricsFetchedFor == meta.id) return
        if (ui.lyrics.status != IslandLyricsStatus.LOADING) return
        val lyricPill = config.compactContent == DynamicIslandCompactContent.COVER_LYRICS || config.compactContent == DynamicIslandCompactContent.LYRICS_ONLY
        if (!(ui.expanded || lyricPill)) return
        lyricsFetchedFor = meta.id
        lyricsFetchJob?.cancel()
        lyricsFetchJob =
            scope.launch {
                try {
                    lyricsHelper.fillLyrics(meta)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Lyrics lookup for the island failed")
                }
            }
    }

    /** Puts the lyric line and its sung part where the song is now, before the card or the lyric page is shown. */
    private fun primeLyricPosition() {
        val p = player ?: return
        if (ui.lyrics.status != IslandLyricsStatus.SYNCED) return
        val lines = ui.lyrics.lines
        val at = p.currentPosition + ui.lyricOffsetMs
        val index = IslandLyricsLogic.indexAt(lines, at)
        ui.lyricIndex = index
        ui.lyricSweep = IslandLyricsLogic.sweepAt(lines, index, at)
    }

    private fun onLyricsNeeded() {
        player?.currentMetadata?.let { if (lyricsFor == it.id) ensureLyricsFetch(it) }
    }

    private fun loadArtwork(url: String?) {
        artJob?.cancel()
        if (url.isNullOrBlank()) {
            clearArtwork()
            return
        }
        // The old cover and its colours stay on screen until the new cover is ready (emptying them at once made the cover blink
        // to the note icon and the colours jump to grey on every song change). A key without a picture = the cover is on its
        // way and there is nothing to keep (the Liquid glass waits for it); the glass keeps its old skin until the new one is baked.
        if (ui.artwork == null) ui.artworkKey = url
        artJob =
            scope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        try {
                            val request =
                                ImageRequest.Builder(context)
                                    .data(url)
                                    .size(ART_SIZE_PX, ART_SIZE_PX)
                                    .allowHardware(false)
                                    .build()
                            val raw = context.imageLoader.execute(request).image?.toBitmap() ?: return@withContext null
                            val bitmap =
                                if (raw.width > ART_SIZE_PX || raw.height > ART_SIZE_PX) {
                                    val ratio = ART_SIZE_PX.toFloat() / maxOf(raw.width, raw.height)
                                    Bitmap.createScaledBitmap(
                                        raw,
                                        (raw.width * ratio).roundToInt().coerceAtLeast(1),
                                        (raw.height * ratio).roundToInt().coerceAtLeast(1),
                                        true,
                                    )
                                } else {
                                    raw
                                }
                            bitmap to colorsFrom(bitmap)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    } ?: run {
                        // the cover could not be loaded: the glass bakes its stand-in scene instead of waiting
                        clearArtwork()
                        return@launch
                    }
                val colors = result.second
                ui.accent = colors.accent?.let { Color(it) } ?: DynamicIslandDefaultAccent
                ui.coverTop = colors.top?.let { Color(it) } ?: DynamicIslandDefaultCoverTop
                ui.coverBottom = colors.bottom?.let { Color(it) } ?: DynamicIslandDefaultCoverBottom
                ui.artworkKey = url
                ui.artwork = result.first.asImageBitmap()
            }
    }

    /** No cover (the song has none, or it could not be loaded): the placeholder and the default colours. */
    private fun clearArtwork() {
        ui.artwork = null
        ui.artworkKey = null
        ui.accent = DynamicIslandDefaultAccent
        ui.coverTop = DynamicIslandDefaultCoverTop
        ui.coverBottom = DynamicIslandDefaultCoverBottom
    }

    /** Accent (bright, for the equaliser/glow) and a deep two-stop background for the "Cover colour" style. */
    private fun colorsFrom(bitmap: Bitmap): ArtColors =
        try {
            val palette = Palette.from(bitmap).maximumColorCount(16).generate()
            val accentSwatch = palette.lightVibrantSwatch ?: palette.vibrantSwatch ?: palette.dominantSwatch
            val accent =
                accentSwatch?.rgb?.let { rgb ->
                    if (ColorUtils.calculateLuminance(rgb) < 0.35) {
                        ColorUtils.blendARGB(rgb, android.graphics.Color.WHITE, 0.45f)
                    } else {
                        rgb
                    }
                }
            val mid = (palette.mutedSwatch ?: palette.vibrantSwatch ?: palette.dominantSwatch)?.rgb
            val dark = (palette.darkMutedSwatch ?: palette.darkVibrantSwatch ?: palette.dominantSwatch)?.rgb ?: mid
            ArtColors(
                accent = accent,
                top = mid?.let { deepen(it, 0.38f, 0.22) },
                bottom = dark?.let { deepen(it, 0.62f, 0.08) },
            )
        } catch (e: Exception) {
            ArtColors(null, null, null)
        }

    /** Darkens [rgb] by [amount] and keeps darkening until its luminance is at most [maxLuminance]. */
    private fun deepen(
        rgb: Int,
        amount: Float,
        maxLuminance: Double,
    ): Int {
        var c = ColorUtils.blendARGB(rgb, android.graphics.Color.BLACK, amount)
        var guard = 0
        while (ColorUtils.calculateLuminance(c) > maxLuminance && guard < 6) {
            c = ColorUtils.blendARGB(c, android.graphics.Color.BLACK, 0.25f)
            guard++
        }
        return c or 0xFF000000.toInt()
    }

    // ---------------------------------------------------------------- visibility

    private fun show() {
        removeJob?.cancel()
        removeJob = null
        if (rootView == null && !attachView()) return
        setTouchable(true)
        ui.visible = true
    }

    private fun hide() {
        if (rootView == null) return
        ui.visible = false
        // a fading island takes no more touches (before Android 12 they reach the app below; from 12 the system decides)
        setTouchable(false)
        collapse()
        if (removeJob?.isActive == true) return
        removeJob =
            scope.launch {
                // after the island has shrunk into its centre
                delay(DynamicIslandDimens.HIDE_MS + 80L)
                removeViewNow()
            }
    }

    private fun dismissUntilNextTrack() {
        dismissedMediaId = currentMediaId
        dragArmed = false
        dragging = false
        hide()
    }

    private fun attachView(): Boolean =
        try {
            attachViewUnsafe()
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Unable to build Dynamic Island overlay")
            rootView = null
            params = null
            viewOwner?.destroy()
            viewOwner = null
            false
        }

    private fun attachViewUnsafe(): Boolean {
        if (frontContext == null) return false
        val owner = OverlayViewOwner()
        val root = IslandTouchLayout(overlayContext, touchCallbacks)
        val composeView =
            ComposeView(overlayContext).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { DynamicIslandOverlayContent(state = ui, actions = islandActions) }
            }
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
        root.addView(
            composeView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        val lp = buildLayoutParams()
        rootView = root
        params = lp
        viewOwner = owner
        hitOpen = false
        computeGeometry(lp)
        updateCamera()
        var rootAdded = false
        return try {
            windowManager.addView(root, lp)
            rootAdded = true
            val hit = IslandHitLayout(overlayContext) { rootView }
            val hp = buildHitParams()
            applyHitRect(hp)
            hitView = hit
            hitParams = hp
            windowManager.addView(hit, hp)
            val callbacks =
                object : ComponentCallbacks {
                    override fun onConfigurationChanged(newConfig: Configuration) {
                        statusBarCache = -1
                        updatePosition()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onLowMemory() = Unit
                }
            context.registerComponentCallbacks(callbacks)
            configCallbacks = callbacks
            true
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Unable to add Dynamic Island overlay")
            if (rootAdded) runCatching { windowManager.removeViewImmediate(root) }
            rootView = null
            params = null
            hitView = null
            hitParams = null
            viewOwner = null
            owner.destroy()
            false
        }
    }

    private fun removeViewNow() {
        removeJob?.cancel()
        removeJob = null
        collapseJob?.cancel()
        collapseJob = null
        configCallbacks?.let {
            try {
                context.unregisterComponentCallbacks(it)
            } catch (_: Exception) {
            }
        }
        configCallbacks = null
        hitOpen = false
        ui.visible = false
        ui.expanded = false
        ui.lyricPage = false
        flashJob?.cancel()
        flashJob = null
        graceJob?.cancel()
        graceJob = null
        ui.titleFlash = false
        sliderActive = false
        dragArmed = false
        dragging = false
        val hit = hitView
        hitView = null
        hitParams = null
        hit?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Unable to remove Dynamic Island touch window")
            }
        }
        val root = rootView ?: return
        rootView = null
        params = null
        try {
            windowManager.removeViewImmediate(root)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unable to remove Dynamic Island overlay")
        }
        viewOwner?.destroy()
        viewOwner = null
    }

    @SuppressLint("RtlHardcoded")
    @Suppress("DEPRECATION")
    private fun buildLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // never touchable: the touch window on top of it takes the touches (see [hitView])
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Absolute placement: x/y are the window's top-left corner in screen pixels.
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "AuralysDynamicIsland"
            disableMoveAnimation(this)
        }

    /** The invisible window that takes the touches: it draws nothing, so it can be moved and resized freely. */
    @SuppressLint("RtlHardcoded")
    @Suppress("DEPRECATION")
    private fun buildHitParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "AuralysDynamicIslandTouch"
            disableMoveAnimation(this)
        }

    /** Puts the touch window on what is visible: the card while it is open or closing, else the pill. True when it changed. */
    private fun applyHitRect(lp: WindowManager.LayoutParams): Boolean {
        val g = geometry ?: return false
        val r = if (hitOpen || ui.expanded) g.card else g.pill
        val changed = r.x != lp.x || r.y != lp.y || r.width != lp.width || r.height != lp.height
        lp.x = r.x
        lp.y = r.y
        lp.width = r.width
        lp.height = r.height
        return changed
    }

    private fun updateHit() {
        val view = hitView ?: return
        val lp = hitParams ?: return
        if (applyHitRect(lp)) {
            try {
                windowManager.updateViewLayout(view, lp)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Unable to move Dynamic Island touch window")
            }
        }
    }

    /**
     * The system animates a window's position change (a short slide from the old place, WindowState.startMoveAnimation)
     * unless the window opts out. The island moves its window when the card opens/closes and while it is dragged; with
     * the slide the content was drawn at the new place while the window still slid from the old one (the pill "jumped"
     * sideways). Without it a move+resize is applied together with the first frame drawn at the new size.
     * Public switch from Android 14; before that the same private flag bit (stable since Android 4.x) set by reflection,
     * skipped silently where the field is not reachable.
     */
    private fun disableMoveAnimation(lp: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            lp.setCanPlayMoveAnimation(false)
            return
        }
        try {
            val field = WindowManager.LayoutParams::class.java.getField("privateFlags")
            field.setInt(lp, field.getInt(lp) or PRIVATE_FLAG_NO_MOVE_ANIMATION)
        } catch (_: Throwable) {
        }
    }

    /** Makes the window take touches or let them go (hide animation). */
    private fun setTouchable(touchable: Boolean) {
        val root = hitView ?: return
        val lp = hitParams ?: return
        val flags =
            if (touchable) {
                lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
        if (flags == lp.flags) return
        lp.flags = flags
        try {
            windowManager.updateViewLayout(root, lp)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unable to change Dynamic Island touch flags")
        }
    }

    // ---------------------------------------------------------------- positioning

    private val density: Float
        get() = overlayContext.resources.displayMetrics.density

    private fun screenWidth(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return windowManager.currentWindowMetrics.bounds.width()
            } catch (_: Exception) {
            }
        }
        return overlayContext.resources.displayMetrics.widthPixels
    }

    /**
     * The status bar height as the display reported it last, read again only after a rotation or another display change.
     * It was read on every geometry pass (each expand, collapse and settings change), and an app that hides its status bar
     * (a video, a game, an immersive page) can report another inset: the pill then jumped by that much.
     */
    private var statusBarCache = -1

    private fun statusBarHeight(): Int {
        if (statusBarCache > 0) return statusBarCache
        val height = readStatusBarHeight()
        if (height > 0) statusBarCache = height
        return height
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun readStatusBarHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val insets =
                    windowManager.currentWindowMetrics.windowInsets
                        .getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
                if (insets.top > 0) return insets.top
            } catch (_: Exception) {
            }
        }
        val res = overlayContext.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else (24 * density).roundToInt()
    }

    private fun screenHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return windowManager.currentWindowMetrics.bounds.height()
            } catch (_: Exception) {
            }
        }
        return overlayContext.resources.displayMetrics.heightPixels
    }

    private fun compactHeightPx(): Int = ceil(config.pillHeight * ui.scale * density).toInt()

    private fun screenSpec(): IslandScreenSpec =
        IslandScreenSpec(
            screenWidth = screenWidth(),
            screenHeight = screenHeight(),
            statusBar = statusBarHeight(),
            density = density,
            scale = ui.scale,
            pillWidthDp = DynamicIslandDimens.compactWidth(config),
            pillHeightDp = config.pillHeight,
            expandedWidthDp = config.expandedWidth,
            expandedHeightDp = DynamicIslandDimens.expandedHeight(config),
            xFraction = xFraction,
            yDp = yDp,
            xOffsetDp = xOffsetDp,
            compactFrameDp = IslandWindowGeometry.compactFrameDp(config),
            attached = config.attached,
            compactFlareDp = DynamicIslandDimens.compactFlare(config),
        )

    private fun currentPill(): IslandRect = IslandWindowGeometry.compute(screenSpec()).pill

    /** Horizontal room left of the compact pill when it is pushed fully to the right. */
    private fun compactFreeWidth(): Int = (screenWidth() - currentPill().width).coerceAtLeast(0)

    /**
     * Sizes and places the window for the current mode and publishes the island geometry to [ui] (the overlay root
     * takes the in-window offsets of whichever window size it is really laid out at). Compact: the window is the pill
     * plus a small frame, so the app next to the pill keeps every touch. Open card / morph: the card (and the pill)
     * plus the glow frame. Returns true when the params changed.
     */
    private fun computeGeometry(lp: WindowManager.LayoutParams): Boolean {
        val g = IslandWindowGeometry.compute(screenSpec())
        geometry = g
        ui.expandedWidth = g.cardWidthDp
        ui.compactShiftX = g.shiftX.toFloat()
        ui.compactShiftY = g.shiftY.toFloat()
        ui.expandedWindowWanted = true
        ui.geometry = g
        val w = g.expandedWindow
        val changed = w.x != lp.x || w.y != lp.y || w.width != lp.width || w.height != lp.height
        lp.x = w.x
        lp.y = w.y
        lp.width = w.width
        lp.height = w.height
        return changed
    }

    /** Reads the camera hole at the top edge from the display cut-out (see [DynamicIslandUiState.cameraKnown]). */
    private fun updateCamera() {
        val width = screenWidth()
        val rects: List<android.graphics.Rect> =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    windowManager.currentWindowMetrics.windowInsets.displayCutout?.boundingRects.orEmpty()
                } else {
                    rootView?.rootWindowInsets?.displayCutout?.boundingRects.orEmpty()
                }
            }.getOrDefault(emptyList())
        // a hole in the top edge that is not wider than half the screen (a notch band or a punch hole)
        val hole = rects.firstOrNull { it.top <= 2 && it.width() > 0 && it.width() * 2 <= width }
        ui.cameraCenterPx = hole?.centerX() ?: -1
        ui.cameraKnown = true
    }

    private fun updatePosition() {
        if (dragging) return
        val root = rootView ?: return
        val lp = params ?: return
        if (computeGeometry(lp)) {
            try {
                windowManager.updateViewLayout(root, lp)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Unable to move Dynamic Island overlay")
            }
        }
        updateHit()
    }

    private fun persistPosition() {
        val x = xFraction
        val y = yDp
        val inLandscape = landscape
        // a place dragged in landscape belongs to landscape; the upright one stays where it was
        if (inLandscape) {
            landX = x
            landY = y
        } else {
            portraitX = x
            portraitY = y
        }
        scope.launch {
            context.safeDataStoreEdit {
                if (inLandscape) {
                    it[DynamicIslandXLandscapeKey] = x
                    it[DynamicIslandYLandscapeKey] = y
                } else {
                    it[DynamicIslandXKey] = x
                    it[DynamicIslandYKey] = y
                }
            }
        }
    }

    // ---------------------------------------------------------------- interaction

    private fun haptic(type: Int) {
        if (config.haptics) rootView?.performHapticFeedback(type)
    }

    private fun scheduleCollapse(delayMs: Long = autoCollapseMs) {
        collapseJob?.cancel()
        collapseJob = null
        if (!ui.expanded || sliderActive) return
        collapseJob =
            scope.launch {
                delay(delayMs)
                collapseJob = null
                collapse()
            }
    }

    private fun expand() {
        if (ui.expanded || rootView == null) return
        // The visual window is already card sized: only the touch window grows (it draws nothing), and the card opens in this very frame.
        hitOpen = true
        updateHit()
        primeLyricPosition()
        ui.expanded = true
        onLyricsNeeded()
        scheduleCollapse()
    }

    private fun collapse() {
        collapseJob?.cancel()
        collapseJob = null
        sliderActive = false
        if (!ui.expanded) return
        ui.expanded = false
        ui.lyricPage = false
        // The touch window shrinks to the pill in onCollapseSettled, once the morph has finished.
    }

    private fun togglePlay() {
        try {
            player?.togglePlayPause()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "togglePlayPause failed")
        }
    }

    private fun openApp() {
        collapse()
        val intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unable to open Auralys from Dynamic Island")
        }
    }

    private val islandActions =
        object : DynamicIslandActions {
            override fun onTap() {
                // A tap on the expanded card's background does nothing (no accidental collapse).
                if (ui.expanded) {
                    scheduleCollapse()
                    return
                }
                when (config.tapAction) {
                    DynamicIslandTapAction.EXPAND -> {
                        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                        expand()
                    }
                    DynamicIslandTapAction.OPEN_APP -> openApp()
                    DynamicIslandTapAction.PLAY_PAUSE -> {
                        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                        togglePlay()
                    }
                }
            }

            override fun onLongPress() {
                if (ui.expanded) return
                haptic(HapticFeedbackConstants.LONG_PRESS)
                when (config.longPressAction) {
                    DynamicIslandLongPressAction.DRAG -> dragArmed = true
                    DynamicIslandLongPressAction.HIDE -> dismissUntilNextTrack()
                }
            }

            override fun onOpenApp() = openApp()

            override fun onPrevious() {
                scheduleCollapse(interactionCollapseMs)
                val p = player ?: return
                try {
                    p.seekToPrevious()
                    if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) p.prepare()
                    p.playWhenReady = true
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "seekToPrevious failed")
                }
            }

            override fun onTogglePlay() {
                scheduleCollapse(interactionCollapseMs)
                togglePlay()
            }

            override fun onNext() {
                scheduleCollapse(interactionCollapseMs)
                val p = player ?: return
                try {
                    p.seekToNext()
                    if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) p.prepare()
                    p.playWhenReady = true
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "seekToNext failed")
                }
            }

            override fun readProgress(): LongArray? {
                val p = player ?: return null
                val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                return longArrayOf(p.currentPosition.coerceAtLeast(0L), duration)
            }

            override fun onSeek(positionMs: Long) {
                scheduleCollapse(interactionCollapseMs)
                val p = player ?: return
                try {
                    if (p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) p.seekTo(positionMs)
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "seekTo failed")
                }
            }

            override fun onSliderDrag(active: Boolean) {
                if (!ui.expanded) {
                    sliderActive = false
                    return
                }
                sliderActive = active
                if (active) {
                    collapseJob?.cancel()
                    collapseJob = null
                } else {
                    scheduleCollapse(interactionCollapseMs)
                }
            }

            override fun onToggleLyrics() {
                scheduleCollapse(interactionCollapseMs)
                ui.lyricPage = !ui.lyricPage
                onLyricsNeeded()
            }

            override fun playhead(): LyricsPlayheadSource = playhead

            override fun onCollapseSettled() {
                if (ui.expanded) return
                hitOpen = false
                updateHit()
                // A paused island kept only because the card was open goes away as soon as the card is closed.
                evaluate()
            }
        }

    private val touchCallbacks =
        object : IslandTouchLayout.Callbacks {
            override fun isCompact(): Boolean = !ui.expanded

            override fun isDragArmed(): Boolean = dragArmed

            override fun canSwipeCollapse(): Boolean = ui.expanded && !sliderActive

            override fun onTouchDown() {
                dragArmed = false
                scheduleCollapse()
            }

            override fun onOutsideTouch() {
                if (ui.expanded) collapse()
            }

            override fun onFrameTouch() {
                // the glow frame around the open card is not the card: same as a touch outside the island
                if (ui.expanded) collapse()
            }

            override fun onDragStart() {
                dragging = true
                val pill = currentPill()
                dragStartX = pill.x
                dragStartY = pill.y
            }

            override fun onDragMove(dx: Float, dy: Float) {
                val root = rootView ?: return
                val lp = params ?: return
                if (!dragging) return
                val free = compactFreeWidth()
                val top = statusBarHeight()
                val offsetPx = (xOffsetDp * density).roundToInt()
                val pillLeft = (dragStartX + dx).roundToInt().coerceIn(0, free)
                val lowest = maxOf(0, screenHeight() - compactHeightPx())
                val pillTop =
                    (dragStartY + dy).roundToInt().coerceIn(
                        (top + (DynamicIslandMinY * density).roundToInt()).coerceIn(0, lowest),
                        (top + (DynamicIslandMaxY * density).roundToInt()).coerceIn(0, lowest),
                    )
                xFraction = if (free > 0) ((pillLeft - offsetPx).toFloat() / (free / 2f) - 1f).coerceIn(-1f, 1f) else 0f
                // glued to the top edge, the island only slides sideways: the saved height stays as it was
                if (!config.attached) yDp = ((pillTop - top) / density).roundToInt().coerceIn(DynamicIslandMinY, DynamicIslandMaxY)
                if (computeGeometry(lp)) {
                    try {
                        windowManager.updateViewLayout(root, lp)
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Unable to drag Dynamic Island overlay")
                    }
                }
                updateHit()
            }

            override fun onDragEnd(moved: Boolean) {
                dragArmed = false
                dragging = false
                // Long-press released without dragging: nothing to do.
                if (!moved) return
                persistPosition()
            }

            override fun onSwipeUp() {
                // Swipe up on the expanded card closes it; on the compact pill it hides the island.
                if (ui.expanded) collapse() else dismissUntilNextTrack()
            }
        }

    /** Everything the overlay reads from DataStore, collected as one value. */
    private data class IslandPrefs(
        val x: Float,
        val y: Int,
        val xLand: Float?,
        val yLand: Int?,
        val xOffset: Int,
        val config: DynamicIslandConfig,
        val appAccent: Int,
        /** The app's own light/dark choice; null follows the system. */
        val dark: Boolean?,
        /** The app's icon family: the overlay window is outside MetrolistTheme. */
        val iconFamily: IconFamily,
    )

    /** Artwork-derived colours. */
    private class ArtColors(
        val accent: Int?,
        val top: Int?,
        val bottom: Int?,
    )

    companion object {
        private const val TAG = "DynamicIsland"
        private const val ART_SIZE_PX = 128

        /** WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION (1 << 6, @hide; public setter from Android 14). */
        private const val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x40

        /** A paused song keeps the island this long before it shrinks away. */
        private const val PAUSE_HIDE_MS = 1_500L

        /** The island stays this long while the player is idle although the user wants music. */
        private const val HIDE_GRACE_MS = 800L

        /** How long the lyric pill shows the title after a song change. */
        private const val TITLE_FLASH_MS = 3_000L

    }
}

/**
 * The invisible touch window of the island: it covers exactly what is visible and passes every touch on to the root of the
 * visual window, in that window's own coordinates (the raw screen position stays as it was, which is what the drag and the
 * swipe use). The visual window itself is never touchable.
 */
@SuppressLint("ViewConstructor")
internal class IslandHitLayout(
    context: Context,
    private val target: () -> View?,
) : FrameLayout(context) {
    private val place = IntArray(2)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val view = target() ?: return false
        view.getLocationOnScreen(place)
        val copy = MotionEvent.obtain(ev)
        copy.setLocation(ev.rawX - place[0], ev.rawY - place[1])
        val handled = view.dispatchTouchEvent(copy)
        copy.recycle()
        // this window IS the island: a touch on it is never meant for the app below
        return handled || ev.actionMasked != MotionEvent.ACTION_OUTSIDE
    }
}

/**
 * Root of the overlay window. Lets Compose handle taps/buttons, and takes over the gesture
 * (using raw screen coordinates, so moving the window under the finger does not feed back)
 * for long-press dragging and swipe-up dismissal of the compact pill.
 */
@SuppressLint("ViewConstructor")
internal class IslandTouchLayout(
    context: Context,
    private val callbacks: Callbacks,
) : FrameLayout(context) {
    interface Callbacks {
        fun isCompact(): Boolean
        fun isDragArmed(): Boolean
        fun canSwipeCollapse(): Boolean
        fun onTouchDown()
        fun onOutsideTouch()

        /** A touch went down inside the window but on nothing of the island (the frame around the shape). */
        fun onFrameTouch()
        fun onDragStart()
        fun onDragMove(dx: Float, dy: Float)
        fun onDragEnd(moved: Boolean)
        fun onSwipeUp()
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val swipeThreshold = 20f * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f

    /** Where the finger was when the drag began (after the touch slop): the pill moves from there, so it does not leap by the slop. */
    private var anchorX = 0f
    private var anchorY = 0f
    private var intercepted = false
    private var dragStarted = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                callbacks.onOutsideTouch()
                return false
            }
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                intercepted = false
                dragStarted = false
                callbacks.onTouchDown()
            }
        }
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP && !intercepted && callbacks.isDragArmed()) {
            callbacks.onDragEnd(moved = false)
        }
        return handled
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_MOVE) return false
        val dx = ev.rawX - downX
        val dy = ev.rawY - downY
        if (callbacks.isCompact()) {
            if (dx * dx + dy * dy > touchSlop * touchSlop) {
                intercepted = true
                if (callbacks.isDragArmed()) {
                    dragStarted = true
                    anchorX = ev.rawX
                    anchorY = ev.rawY
                    callbacks.onDragStart()
                }
                return true
            }
        } else if (callbacks.canSwipeCollapse() && dy < -touchSlop && abs(dy) > abs(dx) * 1.2f) {
            // Clearly vertical upward swipe on the expanded card (the seek bar is horizontal).
            intercepted = true
            return true
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            // only reaches here when no part of the island (Compose) took the touch
            MotionEvent.ACTION_DOWN -> if (!callbacks.isCompact()) callbacks.onFrameTouch()
            MotionEvent.ACTION_MOVE -> {
                if (dragStarted) callbacks.onDragMove(ev.rawX - anchorX, ev.rawY - anchorY)
            }
            MotionEvent.ACTION_UP -> {
                if (dragStarted) {
                    dragStarted = false
                    callbacks.onDragEnd(moved = true)
                } else if (intercepted) {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (dy < -swipeThreshold && abs(dy) > abs(dx)) callbacks.onSwipeUp()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragStarted) {
                    dragStarted = false
                    callbacks.onDragEnd(moved = true)
                }
            }
        }
        return true
    }
}

/** Minimal lifecycle + saved-state owner so a ComposeView can live in a service window. */
internal class OverlayViewOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = registry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        if (registry.currentState != Lifecycle.State.DESTROYED) {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
