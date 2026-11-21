/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ambient mode: a calm full-screen "now playing" view. The idea and the window handling
 * (keep the screen on, hide the system bars, restore everything on exit) are adapted from
 * Convx's AmbientModeScreen (https://github.com/cosmictaserdev-creator/Convx, GPL-3.0);
 * the layout and the cover-coloured background are written for Auralys.
 */

package com.metrolist.music.ui.screens.ambient

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.metrolist.music.ui.component.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.ui.player.AnimatedArtworkOverlay
import com.metrolist.music.ui.theme.isGlassStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** True while the ambient screen is on screen, so the full player's animated artwork can pause behind it. */
object AmbientModeState {
    var isVisible by mutableStateOf(false)
        internal set
}

internal val DefaultAmbientColors = listOf(Color(0xFF26222E), Color(0xFF1C2A33), Color(0xFF111015))

private const val CONTROLS_AUTO_HIDE_MS = 5_000L

@Composable
fun AmbientModeScreen(navController: NavController) {
    val playerConnection = LocalPlayerConnection.current
    if (playerConnection == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val context = LocalContext.current
    val view = LocalView.current
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsStateWithLifecycle()
    val canSkipNext by playerConnection.canSkipNext.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    // Keep the screen on and go immersive for as long as this screen is shown.
    DisposableEffect(view) {
        AmbientModeState.isVisible = true
        view.keepScreenOn = true
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        val window = dialogWindow ?: view.context.findActivity()?.window
        if (dialogWindow != null) {
            dialogWindow.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            dialogWindow.setDimAmount(0f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dialogWindow.attributes = dialogWindow.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            AmbientModeState.isVisible = false
            view.keepScreenOn = false
            // A dialog window disappears with the screen; only an activity window needs its bars back.
            if (dialogWindow == null) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Colours from the cover, softened towards black so the screen stays calm in a dark room.
    var palette by remember { mutableStateOf(DefaultAmbientColors) }
    val thumbnailUrl = mediaMetadata?.thumbnailUrl
    LaunchedEffect(thumbnailUrl) {
        if (thumbnailUrl == null) return@LaunchedEffect
        extractAmbientColors(context, thumbnailUrl)?.let { palette = it }
    }
    val colorA by animateColorAsState(palette[0], tween(1600), label = "ambientA")
    val colorB by animateColorAsState(palette[1], tween(1600), label = "ambientB")
    val colorC by animateColorAsState(palette[2], tween(1600), label = "ambientC")

    // Slow drift at ~20 fps (only redraws, never recomposes), stopped while the app is in the background.
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        val origin = SystemClock.uptimeMillis() - (phase.floatValue * DRIFT_PERIOD_MS).toLong()
        while (true) {
            phase.floatValue = ((SystemClock.uptimeMillis() - origin) % DRIFT_PERIOD_MS) / DRIFT_PERIOD_MS.toFloat()
            delay(50)
        }
    }

    // Clock, refreshed on the minute.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(started) {
        while (started) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L + 50L)
        }
    }
    val timeText = remember(now) { android.text.format.DateFormat.getTimeFormat(context).format(Date(now)) }

    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    LaunchedEffect(controlsVisible, interaction) {
        if (controlsVisible) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }
    val touch = { interaction++ }

    val glass = isGlassStyle
    val artShape = RoundedCornerShape(if (glass) 28.dp else 10.dp)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .drawBehind {
                val t = phase.floatValue * 2f * PI.toFloat()
                val w = size.width
                val h = size.height
                val r = max(w, h)
                drawRect(colorC)
                val c1 = Offset(w * (0.28f + 0.14f * cos(t)), h * (0.30f + 0.10f * sin(t)))
                drawCircle(Brush.radialGradient(listOf(colorA, Color.Transparent), c1, r * 0.75f), r * 0.75f, c1)
                val c2 = Offset(w * (0.74f + 0.12f * sin(t + 1.3f)), h * (0.72f + 0.10f * cos(t + 0.7f)))
                drawCircle(Brush.radialGradient(listOf(colorB, Color.Transparent), c2, r * 0.7f), r * 0.7f, c2)
                drawRect(Color.Black.copy(alpha = 0.22f))
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    controlsVisible = !controlsVisible
                    interaction++
                })
            },
    ) {
        val landscape = maxWidth > maxHeight
        val artSize: Dp = if (landscape) {
            min(maxHeight * 0.72f, maxWidth * 0.42f)
        } else {
            min(maxWidth * 0.80f, maxHeight * 0.48f)
        }
        val metadata = mediaMetadata

        val artwork: @Composable () -> Unit = {
            Box(
                modifier = Modifier
                    .size(artSize)
                    .shadow(32.dp, artShape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
                    .clip(artShape)
                    .background(Color.White.copy(alpha = 0.06f))
                    .then(if (glass) Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), artShape) else Modifier),
            ) {
                AsyncImage(
                    model = remember(thumbnailUrl) {
                        ImageRequest.Builder(context).data(thumbnailUrl).crossfade(600).build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                AnimatedArtworkOverlay(
                    mediaMetadata = metadata,
                    active = { started },
                    inAmbientMode = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val info: @Composable (TextAlign, Alignment.Horizontal) -> Unit = { align, horizontal ->
            Column(horizontalAlignment = horizontal) {
                Text(
                    text = metadata?.title.orEmpty(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = align,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = metadata?.artists?.joinToString { it.name }.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = align,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AnimatedVisibility(visible = controlsVisible, enter = fadeIn(tween(250)), exit = fadeOut(tween(400))) {
                    AmbientControls(
                        isPlaying = isPlaying,
                        canSkipPrevious = canSkipPrevious,
                        canSkipNext = canSkipNext,
                        glass = glass,
                        onPrevious = { touch(); playerConnection.seekToPrevious() },
                        onPlayPause = { touch(); playerConnection.togglePlayPause() },
                        onNext = { touch(); playerConnection.seekToNext() },
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
        }

        if (landscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 48.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                artwork()
                Spacer(Modifier.width(48.dp))
                Box(Modifier.weight(1f)) { info(TextAlign.Start, Alignment.Start) }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                artwork()
                Spacer(Modifier.height(36.dp))
                Box(Modifier.widthIn(max = artSize + 48.dp)) { info(TextAlign.Center, Alignment.CenterHorizontally) }
            }
        }

        Text(
            text = timeText,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Light,
            color = Color.White.copy(alpha = 0.78f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(400)),
            modifier = Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(12.dp),
        ) {
            AmbientIconButton(
                iconRes = R.drawable.close,
                contentDescription = stringResource(R.string.ambient_mode_close),
                glass = glass,
                size = 44.dp,
                iconSize = 22.dp,
                onClick = { navController.popBackStack() },
            )
        }
    }
}

private const val DRIFT_PERIOD_MS = 48_000L

@Composable
private fun AmbientControls(
    isPlaying: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    glass: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        AmbientIconButton(
            iconRes = R.drawable.skip_previous,
            contentDescription = stringResource(R.string.ambient_mode_previous),
            glass = glass,
            size = 52.dp,
            iconSize = 26.dp,
            enabled = canSkipPrevious,
            onClick = onPrevious,
        )
        AmbientIconButton(
            iconRes = if (isPlaying) R.drawable.pause else R.drawable.play,
            contentDescription = stringResource(if (isPlaying) R.string.ambient_mode_pause else R.string.ambient_mode_play),
            glass = glass,
            size = 68.dp,
            iconSize = 34.dp,
            onClick = onPlayPause,
        )
        AmbientIconButton(
            iconRes = R.drawable.skip_next,
            contentDescription = stringResource(R.string.ambient_mode_next),
            glass = glass,
            size = 52.dp,
            iconSize = 26.dp,
            enabled = canSkipNext,
            onClick = onNext,
        )
    }
}

/** Glass: frosted white disc with a rim. Editorial: a hairline ring, no fill. */
@Composable
private fun AmbientIconButton(
    iconRes: Int,
    contentDescription: String,
    glass: Boolean,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (glass) {
                    Modifier
                        .background(Color.White.copy(alpha = 0.14f))
                        .border(1.dp, Color.White.copy(alpha = 0.24f), CircleShape)
                } else {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.38f), CircleShape)
                },
            )
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = if (enabled) 0.95f else 0.35f),
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Three calm colours from the cover (two glows and a base), or null when the cover cannot be read. */
internal suspend fun extractAmbientColors(context: Context, url: String): List<Color>? = withContext(Dispatchers.IO) {
    try {
        val bitmap = context.imageLoader
            .execute(
                ImageRequest.Builder(context)
                    .data(url)
                    .size(96)
                    .allowHardware(false)
                    .build(),
            ).image
            ?.toBitmap() ?: return@withContext null
        val p = Palette.from(bitmap).maximumColorCount(12).generate()
        val fallback = DefaultAmbientColors[0].toArgb()
        val dominant = p.getDominantColor(fallback)
        val glowA = Color(p.getVibrantColor(p.getMutedColor(dominant)))
        val glowB = Color(p.getDarkVibrantColor(p.getLightMutedColor(dominant)))
        val base = Color(p.getDarkMutedColor(dominant))
        listOf(
            lerp(glowA, Color.Black, 0.30f),
            lerp(glowB, Color.Black, 0.35f),
            lerp(base, Color.Black, 0.62f),
        )
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
