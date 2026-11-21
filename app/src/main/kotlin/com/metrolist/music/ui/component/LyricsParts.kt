/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The drawing of the lyrics view in small stateless pieces: the system conditions that switch blur and motion off, the
 * soft fade of the top and bottom of the lyric area, the depth of a line (scale, opacity, blur), the sweep that follows
 * the singing, and the three dots of an instrumental gap. Everything that changes per frame is a provider read in the
 * draw or layer phase, so nothing here recomposes while a song plays. They know nothing about the player, so the
 * design gallery can draw them as they are.
 */

package com.metrolist.music.ui.component

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

// -------------------------------------------------------------------- conditions that switch effects off

/**
 * What the lyrics view may spend. [blurAllowed] is false on API below 31, on low-RAM phones, in battery saver, when the
 * system animation scale is 0 and when the user turned the blur off. [animate] is false when the animation scale is 0:
 * every state is then reached at once (the layout stays the same).
 */
@Immutable
internal data class LyricsMotionEnv(val blurAllowed: Boolean, val animate: Boolean) {
    companion object {
        val Full = LyricsMotionEnv(blurAllowed = true, animate = true)
        val NoBlur = LyricsMotionEnv(blurAllowed = false, animate = true)
        val Still = LyricsMotionEnv(blurAllowed = false, animate = false)
    }
}

private fun powerSaveOn(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

private fun animationScaleOf(context: Context): Float =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)

/** The motion conditions of this phone right now; battery saver and the animation scale are followed while the view is open. */
@Composable
internal fun rememberLyricsMotionEnv(userBlur: Boolean): LyricsMotionEnv {
    val context = LocalContext.current
    val lowRam = remember(context) { (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true }
    var powerSave by remember(context) { mutableStateOf(powerSaveOn(context)) }
    var animationScale by remember(context) { mutableFloatStateOf(animationScaleOf(context)) }
    DisposableEffect(context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    powerSave = powerSaveOn(context)
                }
            }
        runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    animationScale = animationScaleOf(context)
                }
            }
        runCatching {
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        }
        powerSave = powerSaveOn(context)
        animationScale = animationScaleOf(context)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
    }
    return remember(userBlur, lowRam, powerSave, animationScale) {
        LyricsMotionEnv(
            blurAllowed = LyricsMotion.blurAllowed(Build.VERSION.SDK_INT, lowRam, powerSave, animationScale, userBlur),
            animate = LyricsMotion.animationsEnabled(animationScale),
        )
    }
}

// -------------------------------------------------------------------- the fade at the top and the bottom

private val EdgeFadeBrush =
    Brush.verticalGradient(
        0f to Color.Transparent,
        LyricsMotion.FADE_TOP to Color.Black,
        1f - LyricsMotion.FADE_BOTTOM to Color.Black,
        1f to Color.Transparent,
    )

/**
 * Fades the top 13 percent and the bottom 14 percent of the lyric area into the page: an offscreen layer and a vertical
 * gradient that keeps only what it covers (DstIn). It only changes what is drawn, never what is touched, and it only
 * touches this subtree, so the cover or colour behind the lyrics stays as it is. [alpha] (read in the layer) fades the
 * whole area in when new lyrics come, in the same offscreen layer (no second buffer).
 */
internal fun Modifier.lyricsEdgeFade(alpha: (() -> Float)? = null): Modifier =
    graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
        if (alpha != null) this.alpha = alpha()
    }
        .drawWithContent {
            drawContent()
            drawRect(EdgeFadeBrush, blendMode = BlendMode.DstIn)
        }

// -------------------------------------------------------------------- the depth of a line

/** The animated look of one line. The values are States so only the layer reads them: a change never recomposes the line. */
@Stable
internal class LyricDepth(
    val scale: State<Float>,
    val alpha: State<Float>,
    val blurDp: State<Float>,
    val press: State<Float>,
)

private val NoBlurState: State<Float> = object : State<Float> { override val value: Float get() = 0f }

/**
 * Blur effects by radius, made once: a line's layer is updated on every frame of its depth animation, and a new
 * RenderEffect each frame made the system blur the line again each frame. The radius is rounded to [BLUR_STEP_PX] (not
 * visible) so a glide needs a dozen effects, not one per frame per line.
 */
private object BlurEffects {
    const val BLUR_STEP_PX = 0.5f
    private val cache = HashMap<Int, BlurEffect>()

    fun of(radiusPx: Float): BlurEffect {
        val step = (radiusPx / BLUR_STEP_PX).toInt().coerceAtLeast(1)
        return cache.getOrPut(step) { BlurEffect(step * BLUR_STEP_PX, step * BLUR_STEP_PX, TileMode.Decal) }
    }
}

/** A value that eases to [target] (or jumps when [animate] is off). It holds no coroutine while it is at rest, which matters with a hundred lines. */
@Composable
private fun animatedValue(target: Float, spec: AnimationSpec<Float>, animate: Boolean): State<Float> {
    val anim = remember { Animatable(target) }
    LaunchedEffect(target, animate) {
        if (animate && anim.value != target) anim.animateTo(target, spec) else anim.snapTo(target)
    }
    return anim.asState()
}

/**
 * Eases the scale, the opacity and the blur of a line towards their targets. With [animate] off they jump. The blur is only
 * animated when [blurEnabled] (so phones that cannot blur carry no animation for it).
 */
@Composable
internal fun rememberLyricDepth(scale: Float, alpha: Float, blurDp: Float, pressed: Boolean, animate: Boolean, blurEnabled: Boolean): LyricDepth {
    val s = animatedValue(scale, spring(dampingRatio = 1f, stiffness = 300f), animate)
    val a = animatedValue(alpha, tween(320), animate)
    val b = if (blurEnabled) animatedValue(blurDp, tween(420), animate) else NoBlurState
    val p = animatedValue(if (pressed) LyricsMotion.PRESS_SCALE else 1f, tween(120), animate)
    return remember(s, a, b, p) { LyricDepth(s, a, b, p) }
}

/**
 * Applies the depth in the layer phase: scale about the start of the line (or its middle or its end, [originX] 0, 0.5, 1),
 * opacity, and the blur (it can be drawn from API 31; nothing is clipped, so the glyphs are not cut). The measured size does not change.
 */
internal fun Modifier.lyricDepth(depth: LyricDepth, originX: Float): Modifier =
    graphicsLayer {
        val s = depth.scale.value * depth.press.value
        scaleX = s
        scaleY = s
        transformOrigin = TransformOrigin(originX, 0.5f)
        alpha = depth.alpha.value
        val b = depth.blurDp.value
        if (b > 0.05f) {
            renderEffect = BlurEffects.of(b * density)
            compositingStrategy = CompositingStrategy.Auto
        } else {
            renderEffect = null
            // the text does not overlap itself, so each draw can carry the opacity: no offscreen buffer per line
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
    }

// -------------------------------------------------------------------- the sweep over the active line

/** What the sweep needs to know about a line: when it is sung, and (if the source has them) when each word is. */
@Immutable
internal class SweepTiming(
    val startMs: Long,
    val endMs: Long,
    val wordTexts: List<String>? = null,
    val wordStarts: LongArray? = null,
    val wordEnds: LongArray? = null,
)

/** Width of the soft edge of the sweep. */
internal const val SWEEP_EDGE_DP = 14f

private class SweepState {
    var layout: TextLayoutResult? = null

    // The soft edge of the sweep: one gradient from full ink to clear over the edge width, moved under the text by its
    // local matrix (before, a new Brush and a new shader were made on every frame).
    private var edgeShader: LinearGradient? = null
    private var edgeInk: Color = Color.Unspecified
    private var edgeWidth = -1f
    private val edgeMatrix = Matrix()
    private var edgeBrush: ShaderBrush? = null

    fun edgeBrush(ink: Color, edgePx: Float, x: Float): ShaderBrush {
        var shader = edgeShader
        if (shader == null || edgeInk != ink || edgeWidth != edgePx) {
            shader = LinearGradient(0f, 0f, edgePx, 0f, ink.toArgb(), ink.copy(alpha = 0f).toArgb(), Shader.TileMode.CLAMP)
            edgeShader = shader
            edgeInk = ink
            edgeWidth = edgePx
            val made = shader
            edgeBrush = object : ShaderBrush() {
                override fun createShader(size: Size): Shader = made
            }
        }
        edgeMatrix.setTranslate(x - edgePx, 0f)
        shader.setLocalMatrix(edgeMatrix)
        return edgeBrush!!
    }
    private var preparedFor: TextLayoutResult? = null
    private var preparedTiming: SweepTiming? = null
    private var preparedEdge = -1f
    var widths = FloatArray(0)
    var curve: LyricsMotion.SweepCurve? = null

    fun prepare(layout: TextLayoutResult, edgePx: Float, timing: SweepTiming) {
        if (preparedFor === layout && preparedTiming === timing && preparedEdge == edgePx) return
        preparedFor = layout
        preparedTiming = timing
        preparedEdge = edgePx
        widths = FloatArray(layout.lineCount) { layout.getLineRight(it) - layout.getLineLeft(it) }
        curve = buildCurve(layout, edgePx, timing)
    }

    private fun buildCurve(layout: TextLayoutResult, edgePx: Float, timing: SweepTiming): LyricsMotion.SweepCurve? {
        val texts = timing.wordTexts ?: return null
        val starts = timing.wordStarts ?: return null
        val ends = timing.wordEnds ?: return null
        val spans = LyricsMotion.wordSpans(layout.layoutInput.text.text, texts) ?: return null
        val n = texts.size
        val from = FloatArray(n)
        val to = FloatArray(n)
        for (i in 0 until n) {
            val s = spans[2 * i]
            val e = spans[2 * i + 1]
            if (e <= s) { // a word that is only a bracket: it takes no room
                from[i] = if (i > 0) to[i - 1] else 0f
                to[i] = from[i]
                continue
            }
            val last = e - 1
            val lineStart = layout.getLineForOffset(s)
            val lineEnd = layout.getLineForOffset(last)
            // the soft edge is centred on the word boundary: half of it runs ahead of the word
            from[i] = LyricsMotion.fractionAt(lineStart, layout.getBoundingBox(s).left - layout.getLineLeft(lineStart) + edgePx / 2f, widths, edgePx)
            to[i] = LyricsMotion.fractionAt(lineEnd, layout.getBoundingBox(last).right - layout.getLineLeft(lineEnd) + edgePx / 2f, widths, edgePx)
        }
        from[0] = 0f
        to[n - 1] = 1f
        return LyricsMotion.sweepCurve(starts, ends, from, to)
    }

    fun fraction(positionMs: Long, timing: SweepTiming): Float =
        curve?.fraction(positionMs) ?: LyricsMotion.lineSweepFraction(positionMs, timing.startMs, timing.endMs)
}

private fun DrawScope.drawSweep(
    layout: TextLayoutResult,
    state: SweepState,
    ink: Color,
    fraction: Float,
    unsungAlpha: Float,
    lineAlpha: Float,
    edgePx: Float,
) {
    // The unsung part stays at 40 percent of full ink however faint the whole line still is (it is fading in), so
    // the line never dips when it becomes the active one.
    val base = (unsungAlpha / lineAlpha.coerceAtLeast(0.05f)).coerceAtMost(ink.alpha)
    drawText(layout, color = ink.copy(alpha = base))
    if (fraction <= 0f) return
    val edge = LyricsMotion.sweepEdge(fraction, state.widths, edgePx)
    if (edge.line > 0) {
        clipRect(left = 0f, top = 0f, right = size.width, bottom = layout.getLineBottom(edge.line - 1)) { drawText(layout, color = ink) }
    }
    val x = layout.getLineLeft(edge.line) + edge.offset
    val brush = state.edgeBrush(ink, edgePx, x)
    clipRect(left = 0f, top = layout.getLineTop(edge.line), right = size.width, bottom = layout.getLineBottom(edge.line)) {
        drawText(layout, brush = brush)
    }
}

/**
 * A lyric line. While [sweeping] (the line is being sung) the unsung part is drawn at 40 percent ink and the sung part
 * in full ink, left to right, with a soft moving edge about 14 dp wide that follows [positionProvider] (lyric time, ms).
 * The position is only read while drawing. Otherwise the line is plain text in [ink] (the line's own opacity comes from
 * the layer around it). Left to right text only: right-to-left and joined scripts use the word machinery of the caller.
 */
@Composable
internal fun LyricSweepText(
    text: String,
    style: TextStyle,
    ink: Color,
    sweeping: Boolean,
    positionProvider: () -> Long,
    timing: SweepTiming,
    lineAlpha: () -> Float,
    modifier: Modifier = Modifier,
    unsungAlpha: Float = 0.4f,
    fillWidth: Boolean = true,
) {
    val edgePx = with(LocalDensity.current) { SWEEP_EDGE_DP.dp.toPx() }
    // the layout is kept for the life of the line (the text reports it when it changes), the curve is rebuilt when the layout or the timing is new
    val state = remember { SweepState() }
    val textStyle = remember(style, ink) { style.copy(color = ink) }
    Text(
        text = text,
        style = textStyle,
        onTextLayout = { state.layout = it },
        modifier =
            modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier).drawWithContent {
                val layout = state.layout
                if (!sweeping || layout == null) {
                    drawContent()
                } else {
                    state.prepare(layout, edgePx, timing)
                    drawSweep(layout, state, ink, state.fraction(positionProvider(), timing), unsungAlpha, lineAlpha(), edgePx)
                }
            },
    )
}

// -------------------------------------------------------------------- the dots of an instrumental gap

/**
 * Three dots, 25 percent ink, each filled with a growing disc as [progress] (0..1 over the gap) passes its third. While
 * [breathing] they swell and shrink between 0.92 and 1.08 over 1.1 seconds. [horizontal] is where the dots sit in the
 * row (0 start, 0.5 centre, 1 end), the same as the text.
 */
@Composable
internal fun LyricDots(
    breathing: Boolean,
    progress: () -> Float,
    ink: Color,
    modifier: Modifier = Modifier,
    horizontal: Float = 0f,
) {
    val phase: State<Float>? =
        if (breathing) {
            rememberInfiniteTransition(label = "lyricDots").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(LyricsMotion.BREATH_MS, easing = LinearEasing), RepeatMode.Reverse),
                label = "lyricDotsBreath",
            )
        } else {
            null
        }
    Box(modifier.fillMaxWidth(), contentAlignment = BiasAlignment(horizontal * 2f - 1f, 0f)) {
        Row(
            Modifier.graphicsLayer {
                val s = phase?.let { LyricsMotion.breathScale(it.value) } ?: 1f
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(horizontal, 0.5f)
            },
            horizontalArrangement = Arrangement.spacedBy(LyricsMotion.DOT_SPACING_DP.dp),
        ) {
            repeat(LyricsMotion.DOT_COUNT) { i ->
                Canvas(Modifier.size(LyricsMotion.DOT_SIZE_DP.dp)) {
                    drawCircle(ink.copy(alpha = LyricsMotion.DOT_BASE_ALPHA))
                    val fill = LyricsMotion.dotFill(progress(), i)
                    if (fill > 0f) drawCircle(ink, radius = size.minDimension / 2f * fill)
                }
            }
        }
    }
}
