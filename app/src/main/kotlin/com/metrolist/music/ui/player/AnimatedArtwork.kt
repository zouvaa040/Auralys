/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Animated artwork ("canvas") over the cover in the full player. Idea and lifecycle rules after
 * Convx's ui/player/CanvasArtworkPlayer.kt (https://github.com/cosmictaserdev-creator/Convx, GPL-3.0);
 * rewritten for Auralys around a single muted ExoPlayer on a TextureView.
 */

package com.metrolist.music.ui.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.canvas.AnimatedArtworkRepository
import com.metrolist.music.constants.AnimatedArtworkKey
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.screens.ambient.AmbientModeState
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import kotlin.math.roundToInt

/**
 * Draws the song's motion artwork over whatever sits below it (the normal cover), fading in
 * only once the first video frame is on screen. Draws nothing when the setting is off, when no
 * artwork exists or on any failure, so the cover simply stays.
 *
 * [active] says whether the artwork is on screen (e.g. the player sheet is expanded). While it is
 * false nothing is fetched and the video player is released. The video also pauses with the
 * music and while the app is in the background.
 */
@Composable
fun AnimatedArtworkOverlay(
    mediaMetadata: MediaMetadata?,
    active: () -> Boolean,
    modifier: Modifier = Modifier,
    inAmbientMode: Boolean = false,
) {
    val enabled by rememberPreference(AnimatedArtworkKey, defaultValue = false)
    if (!enabled || mediaMetadata == null || mediaMetadata.isEpisode) return

    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val playerConnection = LocalPlayerConnection.current
    val playingFlow: StateFlow<Boolean> = remember(playerConnection) {
        playerConnection?.isEffectivelyPlaying ?: MutableStateFlow(false)
    }
    val musicPlaying by playingFlow.collectAsStateWithLifecycle()

    val currentActive by rememberUpdatedState(active)
    val visible by remember(inAmbientMode) {
        derivedStateOf { currentActive() && (inAmbientMode || !AmbientModeState.isVisible) }
    }

    val songId = mediaMetadata.id
    // Reopening the player on a song whose clip is already known skips the wait and the lookup
    var videoFile by remember(songId) { mutableStateOf(ArtworkSession.fileFor(songId)) }
    LaunchedEffect(songId) {
        if (videoFile != null) return@LaunchedEffect
        // Nothing is fetched until the artwork is actually shown, and never ahead of playback.
        snapshotFlow { visible }.first { it }
        delay(600)
        videoFile = AnimatedArtworkRepository.videoFile(
            context = context,
            title = mediaMetadata.title,
            artist = mediaMetadata.artists.joinToString(", ") { it.name },
            album = mediaMetadata.album?.title,
            durationSec = mediaMetadata.duration.takeIf { it > 0 },
            isVideo = mediaMetadata.isVideoSong,
        )?.also { ArtworkSession.remember(songId, it) }
    }

    val file = videoFile ?: return
    val shown = visible && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    // Brief hides (a small drag on the sheet) only pause; the decoder is released after a while hidden.
    var retained by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file, shown) {
        if (shown) {
            retained = true
        } else {
            delay(8_000)
            retained = false
        }
    }
    if (retained) {
        LoopingVideo(
            file = file,
            playing = musicPlaying && shown,
            modifier = modifier,
        )
    }
}

@Composable
private fun LoopingVideo(
    file: File,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var player by remember(file) { mutableStateOf<ExoPlayer?>(null) }
    var firstFrame by remember(file) { mutableStateOf(false) }
    var failed by remember(file) { mutableStateOf(false) }
    var aspect by remember(file) { mutableFloatStateOf(0f) }
    // true when the player was kept from an earlier opening: the clip continues where it stopped
    var resumed by remember(file) { mutableStateOf(false) }

    DisposableEffect(file) {
        val lease = ArtworkSession.acquire(context, file)
        if (lease == null) {
            failed = true
            return@DisposableEffect onDispose { }
        }
        val exo = lease.player
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrame = true
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
                ArtworkSession.forget(file)
                AnimatedArtworkRepository.forget(file)
            }
        }
        try {
            exo.addListener(listener)
            resumed = lease.resumed
            exo.videoSize.takeIf { it.width > 0 && it.height > 0 }?.let {
                aspect = it.width * it.pixelWidthHeightRatio / it.height
            }
            player = exo
        } catch (_: Throwable) {
            failed = true
        }
        onDispose {
            player = null
            runCatching {
                exo.removeListener(listener)
                exo.playWhenReady = false
                exo.clearVideoSurface()
            }
            ArtworkSession.release(lease)
        }
    }

    LaunchedEffect(player) {
        val exo = player ?: return@LaunchedEffect
        if (resumed) {
            // A kept player gets a new surface: seeking to where it stands makes it draw that frame there, even while paused.
            withFrameNanos { }
            runCatching { exo.seekTo(exo.currentPosition) }
        }
    }

    LaunchedEffect(player, playing, failed) {
        player?.playWhenReady = playing && !failed
    }

    if (failed) return

    val fade by animateFloatAsState(
        targetValue = if (firstFrame) 1f else 0f,
        animationSpec = tween(durationMillis = if (resumed) 160 else 700),
        label = "animatedArtworkFade",
    )

    Box(modifier = modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                // ExoPlayer installs its own SurfaceTextureListener in setVideoTextureView.
                TextureView(ctx).apply { isOpaque = false }
            },
            update = { textureView -> player?.setVideoTextureView(textureView) },
            onRelease = { textureView -> player?.clearVideoTextureView(textureView) },
            modifier = Modifier
                .then(if (aspect > 0f) Modifier.cropToAspect(aspect) else Modifier.fillMaxSize())
                .graphicsLayer { alpha = fade },
        )
    }
}

/**
 * Keeps the decoder and the playing position of the last motion artwork alive while the full player is closed.
 *
 * The player content leaves composition shortly after the sheet collapses, which used to release the decoder, so every
 * reopening paid the lookup delay, built a new decoder and restarted the clip from its first frame (static cover first,
 * then the clip from 0 again). Now the clip continues where it stopped. At most one player is kept, for [KEEP_MS]; a
 * second screen that wants the same clip at the same time (ambient mode) gets a private player. Main thread only.
 */
private object ArtworkSession {
    private const val KEEP_MS = 90_000L
    private const val MAX_SONGS = 8

    class Lease(val player: ExoPlayer, val resumed: Boolean, val shared: Boolean)

    private val handler = Handler(Looper.getMainLooper())
    private val files = object : LinkedHashMap<String, File>(MAX_SONGS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, File>): Boolean = size > MAX_SONGS
    }
    private var keptFile: File? = null
    private var kept: ExoPlayer? = null
    private var holders = 0
    private val dropLater = Runnable { dropKept() }

    fun fileFor(songId: String): File? = files[songId]?.takeIf { it.isFile }

    fun remember(songId: String, file: File) {
        files[songId] = file
    }

    fun acquire(context: Context, file: File): Lease? {
        handler.removeCallbacks(dropLater)
        val keep = kept
        if (keep != null && keptFile == file && holders == 0) {
            holders = 1
            return Lease(keep, resumed = true, shared = true)
        }
        // Another screen holds the kept player right now (same or other clip): this one gets a private player.
        if (holders > 0) return newPlayer(context, file)?.let { Lease(it, resumed = false, shared = false) }
        // Nobody holds anything: a kept player for another clip is not needed any more.
        dropKept()
        val exo = newPlayer(context, file) ?: return null
        kept = exo
        keptFile = file
        holders = 1
        return Lease(exo, resumed = false, shared = true)
    }

    fun release(lease: Lease) {
        if (!lease.shared || kept !== lease.player) {
            runCatching { lease.player.release() }
            return
        }
        holders = 0
        handler.removeCallbacks(dropLater)
        handler.postDelayed(dropLater, KEEP_MS)
    }

    /** A clip that failed to play must not be kept for the next opening. */
    fun forget(file: File) {
        if (keptFile != file) return
        // While a screen still holds it, that screen releases it on dispose (kept !== lease.player by then).
        if (holders == 0) runCatching { kept?.release() }
        kept = null
        keptFile = null
        holders = 0
        handler.removeCallbacks(dropLater)
    }

    private fun dropKept() {
        if (holders > 0) return
        runCatching { kept?.release() }
        kept = null
        keptFile = null
    }

    private fun newPlayer(context: Context, file: File): ExoPlayer? {
        val exo = runCatching { ExoPlayer.Builder(context.applicationContext).build() }.getOrNull() ?: return null
        return try {
            exo.volume = 0f
            exo.repeatMode = Player.REPEAT_MODE_ONE
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            exo.playWhenReady = false
            exo.prepare()
            exo
        } catch (_: Throwable) {
            runCatching { exo.release() }
            null
        }
    }
}

/** Measures the child at [aspect] so it covers the parent completely (centre crop). */
private fun Modifier.cropToAspect(aspect: Float): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val childWidth: Int
    val childHeight: Int
    if (height == 0 || width.toFloat() / height > aspect) {
        childWidth = width
        childHeight = (width / aspect).roundToInt()
    } else {
        childWidth = (height * aspect).roundToInt()
        childHeight = height
    }
    val placeable = measurable.measure(Constraints.fixed(childWidth.coerceAtLeast(0), childHeight.coerceAtLeast(0)))
    layout(width, height) {
        placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2)
    }
}
