/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Auralys brand pieces: the mark (striped sun + horizon + sound wave) in the brand colours of the navy icon,
 * the loading animation that replaces every circular spinner (neutral ink), and the startup splash on the page colour.
 * Flat colours only, no gradients, no blur.
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.music.ui.theme.LocalHorizon
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * The colours of the navy and cyan launcher icon. They are the only colour the app itself owns: the logo, the splash,
 * the About header and empty states use them, and nothing else (no control, no text, no divider).
 */
object AuralysBrandColors {
    val Navy = Color(0xFF0B2236)
    val Blue = Color(0xFF14507A)
    val Cyan = Color(0xFF6AD1F2)
    val Foam = Color(0xFFEAF8FD)

    /** The sun on a dark page is the cyan of the icon, on a light page its deep blue (the cyan has too little contrast there). */
    fun sun(dark: Boolean): Color = if (dark) Cyan else Blue
}

/** The brand sun for the running theme. */
@Composable
fun brandSunColor(): Color = AuralysBrandColors.sun(LocalHorizon.current.dark)

// ---- geometry of the mark (design space 512 x 512) ----
private const val SunCx = 256f
private const val SunCy = 340f
private const val SunR = 190f
private const val Horizon = 340f

// sun bands: [top, bottom] in design space, separated by widening gaps
private val SunBands = floatArrayOf(
    150f, 250f,
    258f, 278f,
    290f, 304f,
    320f, 326f,
)

/**
 * Draws the Senja mark.
 * @param rise 0f = sun hidden below the horizon, 1f = fully risen
 * @param wave 0f..1f how much of the wave has been drawn
 * @param phase horizontal wave offset in periods (for the looping loader)
 */
fun DrawScope.drawAuralysMark(
    sun: Color,
    ink: Color,
    rise: Float = 1f,
    wave: Float = 1f,
    phase: Float = 0f,
) {
    val s = size.minDimension / 512f
    val ox = (size.width - 512f * s) / 2f
    val oy = (size.height - 512f * s) / 2f
    withTransform({
        translate(ox, oy)
        scale(s, s, pivot = Offset.Zero)
    }) {
        // sun, clipped so it rises from behind the horizon
        clipRect(left = 0f, top = 0f, right = 512f, bottom = Horizon) {
            val dy = (1f - rise) * (SunR + 20f)
            var i = 0
            while (i < SunBands.size) {
                clipRect(left = 0f, top = SunBands[i] + dy, right = 512f, bottom = SunBands[i + 1] + dy) {
                    drawCircle(sun, radius = SunR, center = Offset(SunCx, SunCy + dy))
                }
                i += 2
            }
        }
        // horizon line
        drawRoundRect(
            color = ink,
            topLeft = Offset(96f, Horizon),
            size = Size(320f, 10f),
            cornerRadius = CornerRadius(5f, 5f),
        )
        // sound wave under the horizon
        if (wave > 0.001f) {
            val path = Path()
            val steps = 60
            val last = (steps * wave.coerceIn(0f, 1f)).toInt().coerceAtLeast(1)
            for (i in 0..last) {
                val t = i / steps.toFloat()
                val x = 176f + 160f * t
                val y = 392f - 12f * sin(2.0 * PI * (3.0 * t + phase)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                color = ink,
                style = Stroke(width = 9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

@Composable
fun AuralysMark(
    modifier: Modifier = Modifier,
    sun: Color = brandSunColor(),
    ink: Color = MaterialTheme.colorScheme.onSurface,
    rise: Float = 1f,
    wave: Float = 1f,
    phase: Float = 0f,
) {
    Canvas(modifier) { drawAuralysMark(sun, ink, rise, wave, phase) }
}

/**
 * Loading animation: the sun gently breathes while the wave flows. Cheap: one infinite
 * transition, everything is read inside the draw block (no recomposition per frame).
 */
@Composable
fun AuralysLoader(
    modifier: Modifier = Modifier,
    sun: Color = MaterialTheme.colorScheme.primary,
    ink: Color = MaterialTheme.colorScheme.onSurface,
) {
    val transition = rememberInfiniteTransition(label = "auralysLoader")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "wavePhase",
    )
    val rise by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sunRise",
    )
    Canvas(modifier) { drawAuralysMark(sun, ink, rise = rise, wave = 1f, phase = phase) }
}

// ---------------------------------------------------------------------------------------
// Drop-in replacements for the Material3 circular loaders: same names + parameter names, so
// call sites only need to swap the import. Indeterminate -> Auralys loader.
// ---------------------------------------------------------------------------------------

@Composable
fun CircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    strokeWidth: Dp = 4.dp,
    trackColor: Color = Color.Unspecified,
    strokeCap: StrokeCap = StrokeCap.Round,
) {
    val sun = color.takeOrElse { MaterialTheme.colorScheme.primary }
    val ink = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    AuralysLoader(modifier.size(40.dp), sun = sun, ink = ink)
}

@Composable
fun CircularProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    strokeWidth: Dp = 4.dp,
    trackColor: Color = Color.Unspecified,
    strokeCap: StrokeCap = StrokeCap.Round,
    gapSize: Dp = 4.dp,
) {
    androidx.compose.material3.CircularProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = color.takeOrElse { MaterialTheme.colorScheme.primary },
        strokeWidth = strokeWidth,
        trackColor = trackColor.takeOrElse { MaterialTheme.colorScheme.outlineVariant },
        strokeCap = strokeCap,
        gapSize = gapSize,
    )
}

@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val sun = color.takeOrElse { MaterialTheme.colorScheme.primary }
    AuralysLoader(modifier.size(48.dp), sun = sun)
}

@Composable
fun ContainedLoadingIndicator(
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    indicatorColor: Color = Color.Unspecified,
    containerShape: Shape = MaterialTheme.shapes.large,
) {
    Box(
        modifier
            .size(56.dp)
            .background(containerColor.takeOrElse { MaterialTheme.colorScheme.surfaceContainer }, containerShape),
        contentAlignment = Alignment.Center,
    ) {
        AuralysLoader(
            Modifier.size(36.dp),
            sun = indicatorColor.takeOrElse { MaterialTheme.colorScheme.primary },
        )
    }
}

// ---------------------------------------------------------------------------------------
// Startup splash: sun rises, wave draws itself, wordmark fades in, then the whole thing fades.
// Shown once per process.
// ---------------------------------------------------------------------------------------

object AuralysSplashState {
    @Volatile
    var shown = false
}

/**
 * Startup splash. All animation state is read in draw / graphicsLayer lambdas only, so the
 * composition stays still while it plays (no per-frame recomposition).
 * [onReady] fires once the mark is fully drawn: the host starts composing the real app then,
 * while the splash is static, so the heavy first composition cannot stutter the animation.
 */
@Composable
fun AuralysSplash(
    onReady: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rise = remember { Animatable(0f) }
    val wave = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val out = remember { Animatable(1f) }
    val ready by rememberUpdatedState(onReady)
    val finished by rememberUpdatedState(onFinished)
    val sun = brandSunColor()
    val ink = MaterialTheme.colorScheme.onSurface
    val background = MaterialTheme.colorScheme.background

    LaunchedEffect(Unit) {
        kotlinx.coroutines.coroutineScope {
            launch { rise.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
            launch { wave.animateTo(1f, tween(420, delayMillis = 330, easing = FastOutSlowInEasing)) }
            launch { word.animateTo(1f, tween(360, delayMillis = 420)) }
        }
        // everything is on screen and nothing moves now: let the app compose behind it
        ready()
        kotlinx.coroutines.delay(420)
        out.animateTo(0f, tween(240))
        AuralysSplashState.shown = true
        finished()
    }

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = out.value }
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(168.dp)) {
                drawAuralysMark(sun, ink, rise = rise.value, wave = wave.value)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Auralys",
                style = MaterialTheme.typography.headlineLarge,
                color = ink,
                modifier = Modifier.graphicsLayer { alpha = word.value },
            )
        }
    }
}
