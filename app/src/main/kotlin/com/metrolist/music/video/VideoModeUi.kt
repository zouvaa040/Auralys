/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The screen side of the video mode: the controller wired to the app ([rememberVideoModeController]), the second player
 * ([ExoVideoPlayer]), the picture over the cover ([VideoModeSurface]) and the Audio | Video pill ([VideoModePill]).
 *
 * The picture is letterboxed (never cropped: a 16:9 picture in a portrait cover would lose most of its width) on a dark
 * blurred copy of the cover, so there are never grey bars. It fades in only after the first frame was drawn, so the cover
 * stays until then. In the full-bleed player it sits in the upper, not faded part of the cover and inherits the cover's
 * fade; the swipe to change song and the double tap seek keep working because nothing here handles touches.
 *
 * The pill follows the look of SearchSourceSwitch (a glass capsule with a sliding puck, no accent colour). Over the page
 * (full-bleed player) it uses the same colours; over a cover card it is white on a dark veil so it reads on any cover.
 */

package com.metrolist.music.video

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Looper
import android.view.TextureView
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import com.metrolist.innertube.YouTube
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.music.R
import com.metrolist.music.constants.DataSaverKey
import com.metrolist.music.constants.DataSaverMobileOnlyKey
import com.metrolist.music.constants.VideoAutoFromSearchKey
import com.metrolist.music.constants.VideoModeEnabledKey
import com.metrolist.music.constants.VideoModeKey
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.ui.component.glass
import com.metrolist.music.ui.player.rememberBlurredCover
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.safeDataStoreEdit
import com.metrolist.music.version.Counterpart
import com.metrolist.music.version.VersionResolver
import com.metrolist.music.version.toTrackInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.math.roundToInt

// ------------------------------------------------------------------------------------------------ the app side

/** Library messages: only the warnings and errors reach the log. */
private val libraryLogger =
    InnerTubeLogger { event ->
        if (event.level == InnerTubeLogLevel.WARN || event.level == InnerTubeLogLevel.ERROR) {
            Timber.tag(event.tag).w(event.message + event.details.entries.joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" })
        }
    }

/** [VideoModeHost] backed by the app's [PlayerConnection]. */
private class AppVideoModeHost(
    private val context: Context,
    private val playerConnection: PlayerConnection,
    private val scope: CoroutineScope,
) : VideoModeHost {
    var onAudioEvent: (Boolean) -> Unit = {}

    @Volatile var dataSaverOn: Boolean = false

    @Volatile var dataSaverMobileOnly: Boolean = true

    private var attached: Player? = null
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val listener =
        object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) = onAudioEvent(true)

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) = onAudioEvent(true)

            override fun onPlaybackStateChanged(playbackState: Int) = onAudioEvent(false)

            override fun onIsPlayingChanged(isPlaying: Boolean) = onAudioEvent(false)

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = onAudioEvent(false)
        }

    private fun audioPlayer(): Player? = runCatching { playerConnection.player as Player }.getOrNull()

    override fun audio(): AudioSnapshot? {
        val player = audioPlayer() ?: return null
        if (attached !== player) {
            attached?.removeListener(listener)
            player.addListener(listener)
            attached = player
        }
        return AudioSnapshot(
            mediaId = player.currentMediaItem?.mediaId,
            positionMs = player.currentPosition,
            playing = player.isPlaying,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            speed = player.playbackParameters.speed,
        )
    }

    fun detach() {
        attached?.removeListener(listener)
        attached = null
    }

    override fun isCasting(): Boolean = playerConnection.service.castConnectionHandler?.isCasting?.value == true

    override fun maxHeight(): Int =
        VideoStreamRepository.maxHeightFor(
            context = context,
            metered = connectivity?.isActiveNetworkMetered == true,
            dataSaverOn = dataSaverOn,
            dataSaverMobileOnly = dataSaverMobileOnly,
        )

    override fun toast(kind: VideoToast) {
        val text = if (kind == VideoToast.FAILED) R.string.video3_failed else R.string.video3_unavailable
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    override fun persistMode(mode: VideoMode) {
        scope.launch { context.safeDataStoreEdit { it[VideoModeKey] = mode.name } }
    }

    override suspend fun counterpartVideo(song: MediaMetadata): CounterpartVideo? {
        val resolver = VersionResolver.shared
        val info = song.toTrackInfo()
        if (!resolver.canOffer(info)) return null
        val found = withTimeoutOrNull(COUNTERPART_TIMEOUT_MS) { resolver.lookupInBackground(info).await() }
        if (found != null) return videoOf(found)
        // null is two different answers: "there is none" (the resolver keeps that) or "no answer" (slow or no network)
        resolver.peek(song.id)?.let { return it.counterpart?.let(::videoOf) }
        throw CounterpartLookupFailed(if (isOnline()) VideoFailureKind.TIMEOUT else VideoFailureKind.OFFLINE)
    }

    override fun prefetchAllowed(): Boolean =
        !VideoQuality.dataSaverActive(dataSaverOn, dataSaverMobileOnly, connectivity?.isActiveNetworkMetered == true)

    private fun isOnline(): Boolean =
        runCatching {
            val cm = connectivity ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false)
            caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(true)

    /** The resolver keeps every answer (also "none") for the session: reading it costs no search. */
    override fun knownCounterpart(song: MediaMetadata): KnownCounterpart? =
        VersionResolver.shared.peek(song.id)?.let { known -> KnownCounterpart(known.counterpart?.let(::videoOf)) }

    private fun videoOf(found: Counterpart): CounterpartVideo? =
        if (found.isVideo) CounterpartVideo(found.item.id, found.item.duration, found.offsetMs, found.official) else null

    private companion object {
        const val COUNTERPART_TIMEOUT_MS = 15_000L
    }
}

/**
 * The controller of the player screen. Created with the Thumbnail and let go when it leaves the composition (the second
 * player is released with it). [enabled] is an extra gate from the caller (for example the hidden cover setting).
 */
@Composable
fun rememberVideoModeController(
    playerConnection: PlayerConnection,
    isPlayerExpanded: () -> Boolean,
    @Suppress("UNUSED_PARAMETER") mediaMetadata: MediaMetadata?,
    isListenTogetherGuest: Boolean,
    enabled: Boolean = true,
): VideoModeController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val featureOn by rememberPreference(VideoModeEnabledKey, true)
    val storedMode by rememberPreference(VideoModeKey, VideoMode.AUDIO.name)
    val autoFromSearch by rememberPreference(VideoAutoFromSearchKey, true)
    val dataSaverOn by rememberPreference(DataSaverKey, false)
    val dataSaverMobileOnly by rememberPreference(DataSaverMobileOnlyKey, true)

    val host = remember(playerConnection) { AppVideoModeHost(context, playerConnection, scope) }
    val controller =
        remember(playerConnection) {
            VideoModeController(
                host = host,
                scope = scope,
                repository = VideoStreamRepository.shared,
                playerFactory = ExoVideoPlayerFactory(context),
            )
        }
    SideEffect {
        host.dataSaverOn = dataSaverOn
        host.dataSaverMobileOnly = dataSaverMobileOnly
        host.onAudioEvent = controller::onAudioEvent
        controller.expanded = isPlayerExpanded
        // the song and "app on screen" are read from the player and the lifecycle themselves, not from what this
        // composition saw: it may be a stale one (a paused composition), and the two observers below keep them current
        controller.update(
            VideoModeInputs(
                song = playerConnection.mediaMetadata.value,
                enabled = featureOn && enabled,
                storedMode = VideoMode.fromStored(storedMode),
                autoFromSearch = autoFromSearch,
                listenTogetherGuest = isListenTogetherGuest,
                started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            ),
        )
    }
    // Leaving and coming back reach the controller straight from the lifecycle (Compose pauses recomposition in the
    // background, so a SideEffect would never run there): REVIEW-2026-11-07 F2
    DisposableEffect(controller, lifecycle) {
        val observer = videoLifecycleObserver(controller)
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // a song change in the background is followed too (a flow collector, not a recomposition)
    LaunchedEffect(controller, playerConnection) {
        controller.followSongs(playerConnection.mediaMetadata)
    }
    // the sheet opens and closes without any recomposition of ours: look at once
    val expandedNow by rememberUpdatedState(isPlayerExpanded)
    LaunchedEffect(controller) {
        snapshotFlow { expandedNow() }.collect { controller.tick() }
    }
    // 250 ms while a picture runs or a timer waits, 1 s otherwise; nothing at all while the app is in the background
    LaunchedEffect(controller, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                controller.tick()
                delay(if (controller.busy) VideoSyncLogic.TICK_MS else IDLE_TICK_MS)
            }
        }
    }
    DisposableEffect(controller) {
        onDispose {
            controller.dispose()
            host.detach()
        }
    }
    return controller
}

private const val IDLE_TICK_MS = 1_000L

/** STOP and START of the app, handed to the controller at once. Separate so a fake lifecycle can test it. */
internal fun videoLifecycleObserver(controller: VideoModeController): LifecycleEventObserver =
    LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_STOP -> controller.onAppStarted(false)
            Lifecycle.Event.ON_START -> controller.onAppStarted(true)
            else -> Unit
        }
    }

// ------------------------------------------------------------------------------------------------ the second player

class ExoVideoPlayerFactory(
    private val context: Context,
) : VideoPlayerFactory {
    override fun create(
        info: VideoStreamInfo,
        startMs: Long,
        useStartHint: Boolean,
        clock: VideoClock,
        events: VideoPlayerEvents,
    ): VideoPlayerHandle {
        val timeMap = SabrTimeMap(info.durationMs)
        val settings = SabrDataSourceSettings(startHintMs = if (useStartHint) ({ startMs }) else ({ 0L }))
        val source = LibrarySabrChunkSource(httpClient = { YouTube.extractionTransport().httpClient }, bootstrap = info.bootstrap, logger = libraryLogger)
        val dataSources = SabrVideoDataSourceFactory(source, timeMap, clock, settings)
        val mediaSource =
            ProgressiveMediaSource
                .Factory(dataSources, sabrExtractorsFactory(info.format.container, timeMap))
                .createMediaSource(MediaItem.fromUri(Uri.parse("sabr-video://${info.videoId}")))
        return ExoVideoPlayer(context, mediaSource, startMs, events) { dataSources.shutdown() }
    }
}

/**
 * The decoders of the default selector, hardware ones first: a software decoder (libvpx on the CPU) is only the last
 * resort when the hardware decoder fails to start (decoder fallback is on). REVIEW-2026-11-07 F3.
 */
private object HardwareFirstSelector : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> =
        DecoderChoice.hardwareFirst(
            MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder),
        ) { it.hardwareAccelerated }
}

/**
 * ONE ExoPlayer with ONLY a video renderer: no audio renderer (so nothing can play a sound or take the audio focus),
 * no wake lock, a small buffer. It is silent by construction; `volume = 0` is only a second belt.
 */
private class ExoVideoPlayer(
    context: Context,
    mediaSource: androidx.media3.exoplayer.source.MediaSource,
    startMs: Long,
    private val events: VideoPlayerEvents,
    private val onRelease: () -> Unit,
) : VideoPlayerHandle {
    private val exo: ExoPlayer

    private val listener =
        object : Player.Listener {
            override fun onRenderedFirstFrame() = events.onFirstFrame()

            override fun onPlayerError(error: PlaybackException) = events.onError(error)

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    events.onVideoAspect(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
                }
            }
        }

    init {
        val renderers =
            RenderersFactory { handler, videoListener, _, _, _ ->
                arrayOf<Renderer>(
                    MediaCodecVideoRenderer
                        .Builder(context)
                        .setMediaCodecSelector(HardwareFirstSelector)
                        .setAllowedJoiningTimeMs(5_000L)
                        .setEnableDecoderFallback(true)
                        .setEventHandler(handler)
                        .setEventListener(videoListener)
                        .setMaxDroppedFramesToNotify(50)
                        .build(),
                )
            }
        val loadControl =
            DefaultLoadControl
                .Builder()
                .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
                .setBufferDurationsMs(6_000, 14_000, 800, 1_500)
                .setTargetBufferBytes(8 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .setBackBuffer(2_000, false)
                .build()
        exo =
            ExoPlayer
                .Builder(context.applicationContext, renderers)
                .setLoadControl(loadControl)
                .setLooper(Looper.getMainLooper())
                .setHandleAudioBecomingNoisy(false)
                .setWakeMode(C.WAKE_MODE_NONE)
                .build()
        exo.volume = 0f
        exo.trackSelectionParameters =
            exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        exo.addListener(listener)
        exo.setMediaSource(mediaSource, startMs.coerceAtLeast(0L))
        exo.playWhenReady = false
        exo.prepare()
    }

    override val positionMs: Long get() = exo.currentPosition
    override val durationMs: Long get() = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
    override val isReady: Boolean get() = exo.playbackState == Player.STATE_READY
    override val isBuffering: Boolean get() = exo.playbackState == Player.STATE_BUFFERING

    override var playWhenReady: Boolean
        get() = exo.playWhenReady
        set(value) {
            if (exo.playWhenReady != value) exo.playWhenReady = value
        }

    override var speed: Float
        get() = exo.playbackParameters.speed
        set(value) {
            exo.playbackParameters = PlaybackParameters(value)
        }

    override fun seekTo(positionMs: Long) = exo.seekTo(positionMs.coerceAtLeast(0L))

    override fun setSurface(view: TextureView?) {
        if (view == null) exo.clearVideoSurface() else exo.setVideoTextureView(view)
    }

    override fun release() {
        runCatching { exo.removeListener(listener) }
        runCatching { exo.clearVideoSurface() }
        runCatching { exo.release() }
        onRelease()
    }
}

// ------------------------------------------------------------------------------------------------ the picture

/**
 * The backdrop of the picture: the song cover (its own cover, the playing item is never swapped), enlarged and blurred,
 * with a light dark veil. It fades in once the first frame is there. In the full-bleed player it is the overlay of the
 * cover page, so it dissolves into the page together with the cover. Draws nothing unless the song is in video mode.
 */
@Composable
fun VideoModeBackdrop(
    controller: VideoModeController,
    coverUrl: String?,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    if (!(state.pillVisible && state.mode == VideoMode.VIDEO)) return
    val fade by animateFloatAsState(if (state.showing) 1f else 0f, tween(300), label = "videoBackdropFade")
    val blurred by rememberBlurredCover(coverUrl)
    Box(modifier = modifier.fillMaxSize().clipToBounds().graphicsLayer { alpha = fade }) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        blurred?.let {
            // a little larger than the box: the blurred edge never shows
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = BACKDROP_SCALE; scaleY = BACKDROP_SCALE },
            )
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (blurred == null) 0f else BACKDROP_VEIL)))
    }
}

private const val BACKDROP_SCALE = 1.3f
private const val BACKDROP_VEIL = 0.22f

/**
 * The picture itself: the whole 16:9 frame (letterboxed, never cropped), centred in the cover area (in the full-bleed
 * player: between the header and the pill). It owns the TextureView the player draws on, which exists from the moment the
 * song is in video mode so the first frame can be drawn, and it fades in with that frame. It handles no touches: the swipe
 * and the double tap of the cover page keep working.
 */
@Composable
fun VideoModePicture(
    controller: VideoModeController,
    modifier: Modifier = Modifier,
    hero: Boolean = false,
) {
    val state by controller.state.collectAsState()
    if (!(state.pillVisible && state.mode == VideoMode.VIDEO)) return
    val fade by animateFloatAsState(if (state.showing) 1f else 0f, tween(300), label = "videoFade")
    val topSafe = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() } + HEADER_HEIGHT
    // the view carries its own alpha, like the motion artwork's view (an AndroidView does not always follow a parent layer)
    AndroidView(
        factory = { ctx -> TextureView(ctx).apply { isOpaque = false } },
        update = { controller.bindSurface(it) },
        // only if this view is still the bound one: a late release of an old carousel item must not unbind the new surface
        onRelease = { controller.unbindSurface(it) },
        modifier = modifier.fillMaxSize().fitAspect(state.aspect, if (hero) topSafe else null, if (hero) PILL_RESERVE else 0.dp).graphicsLayer { alpha = fade },
    )
}

/** Backdrop and picture in one box: the square cover card, where nothing is faded. */
@Composable
fun VideoModeSurface(
    controller: VideoModeController,
    coverUrl: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        VideoModeBackdrop(controller, coverUrl)
        VideoModePicture(controller)
    }
}

private val HEADER_HEIGHT = 72.dp

/** The room the pill takes at the bottom of the cover area (capsule 36 dp + its padding + a little air). */
private val PILL_RESERVE = 56.dp

/**
 * Measures the child at [aspect] so the whole picture is visible (letterbox) and places it in the middle of the box. With
 * [heroTopSafe] (the full-bleed player) the middle is the middle of the room between that distance from the top (below the
 * header) and [bottomReserve] above the bottom (the pill), not of the whole box.
 */
private fun Modifier.fitAspect(
    aspect: Float,
    heroTopSafe: Dp?,
    bottomReserve: Dp,
): Modifier =
    layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight || aspect <= 0f) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val boxW = constraints.maxWidth
        val boxH = constraints.maxHeight
        var w = boxW
        var h = (boxW / aspect).roundToInt()
        if (h > boxH) {
            h = boxH
            w = (boxH * aspect).roundToInt()
        }
        val placeable = measurable.measure(Constraints.fixed(w.coerceAtLeast(0), h.coerceAtLeast(0)))
        val x = (boxW - w) / 2
        val y = pictureTop(boxH, h, heroTopSafe?.roundToPx(), bottomReserve.roundToPx())
        layout(boxW, boxH) { placeable.place(x, y) }
    }

/** The top edge of a picture of height [h] in a box of height [boxH]; pure, tested. */
internal fun pictureTop(
    boxH: Int,
    h: Int,
    topSafe: Int?,
    bottomReserve: Int,
): Int {
    if (topSafe == null) return ((boxH - h) / 2).coerceAtLeast(0)
    val roomTop = topSafe.coerceAtLeast(0)
    val roomBottom = (boxH - bottomReserve).coerceAtLeast(roomTop)
    return (roomTop + (roomBottom - roomTop - h) / 2).coerceIn(0, (boxH - h).coerceAtLeast(0))
}

// ------------------------------------------------------------------------------------------------ the pill

/**
 * "Audio | Video". [hero] = over the page (the full-bleed player, the bottom of the cover has faded into it): the colours of
 * SearchSourceSwitch. Otherwise it sits over a cover card: white on a dark veil.
 */
@Composable
fun VideoModePill(
    controller: VideoModeController,
    hero: Boolean,
    modifier: Modifier = Modifier,
    /** True while the playback error is on screen: the pill would sit on top of it (REVIEW-2026-11-07 F12). */
    hidden: Boolean = false,
) {
    val state by controller.state.collectAsState()
    AnimatedVisibility(visible = state.pillVisible && !hidden, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        VideoPillContent(
            selected = if (state.mode == VideoMode.VIDEO) 1 else 0,
            onSelect = { controller.onUserPick(if (it == 1) VideoMode.VIDEO else VideoMode.AUDIO) },
            overCover = !hero,
            loading = state.loading,
        )
    }
}

@Composable
internal fun VideoPillContent(
    selected: Int,
    onSelect: (Int) -> Unit,
    overCover: Boolean,
    /** A tiny spinner in the Video half while the video is searched for or prepared. */
    loading: Boolean = false,
) {
    val labels = listOf(stringResource(R.string.video3_audio), stringResource(R.string.video3_video))
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val shape = RoundedCornerShape(50)
    val position =
        animateFloatAsState(
            targetValue = selected.toFloat(),
            animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
            label = "videoPillPuck",
        )
    val track =
        if (overCover) {
            Modifier.clip(shape).background(Color.Black.copy(alpha = 0.38f)).border(0.8.dp, Color.White.copy(alpha = 0.22f), shape)
        } else {
            Modifier.glass(shape)
        }
    val puck =
        when {
            overCover -> Modifier.clip(shape).background(Color.White.copy(alpha = 0.26f)).border(0.8.dp, Color.White.copy(alpha = 0.30f), shape)
            dark -> Modifier.clip(shape).background(Color.White.copy(alpha = 0.14f)).border(0.8.dp, Color.White.copy(alpha = 0.12f), shape)
            else ->
                Modifier
                    .shadow(3.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.14f))
                    .clip(shape)
                    .background(scheme.surfaceContainerLow)
        }
    // The capsule is drawn 36 dp tall as before; the two touch areas on top of it are 48 dp tall (the minimum), so they reach
    // 6 dp above and below the capsule. The outer bottom padding shrank by the same 6 dp: nothing moves on screen.
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .padding(bottom = 6.dp)
                .width(PILL_WIDTH)
                .height(PILL_TOUCH_HEIGHT),
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(PILL_HEIGHT)
                    .then(track)
                    .padding(3.dp),
        ) {
            val segmentWidth = maxWidth / 2
            val segmentPx = constraints.maxWidth / 2f
            Box(
                modifier =
                    Modifier
                        .offset { IntOffset((position.value * segmentPx).roundToInt(), 0) }
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .then(puck),
            )
        }
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 3.dp)) {
            labels.forEachIndexed { index, label ->
                PillSegment(label, index == selected, overCover, spinner = loading && index == 1) { onSelect(index) }
            }
        }
    }
}

private val PILL_WIDTH = 168.dp

/** The drawn capsule, and the touch area of one half of it (never below 48 dp). */
private val PILL_HEIGHT = 36.dp
private val PILL_TOUCH_HEIGHT = 48.dp

@Composable
private fun RowScope.PillSegment(
    label: String,
    selected: Boolean,
    overCover: Boolean,
    spinner: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val color =
        when {
            overCover -> Color.White.copy(alpha = if (selected) 1f else 0.72f)
            selected -> scheme.onSurface
            else -> scheme.onSurfaceVariant
        }
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .selectable(
                    selected = selected,
                    interactionSource = interaction,
                    indication = null,
                    role = Role.RadioButton,
                    onClick = onClick,
                ),
    ) {
        // the press feedback keeps the size of the capsule's half (30 dp tall) although the touch area is 48 dp tall
        Box(
            Modifier
                .fillMaxWidth()
                .height(PILL_HEIGHT - 6.dp)
                .clip(RoundedCornerShape(50))
                .indication(interaction, LocalIndication.current),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (spinner) {
                CircularProgressIndicator(modifier = Modifier.size(11.dp), color = color, strokeWidth = 1.5.dp)
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = label,
                color = color,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
