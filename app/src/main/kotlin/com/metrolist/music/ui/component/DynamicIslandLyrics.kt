/**
 * Auralys - lyric parts of the Dynamic Island overlay
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The same idea as the lyrics of the Windows island: one line with the sung part laid over it, and the lines as a list
 * that slides on a spring. Everything that moves per frame (the sweep, the slide) is read in the draw or layer phase, so
 * a line that is being sung never recomposes anything.
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Width of the soft edge of the sung part, in dp. */
private const val SWEEP_FEATHER_DP = 10f

/**
 * One line in a dim ink with the sung part ([sweep], 0..1, read in the draw phase) laid over it in full ink. A line wider
 * than its room is not cut: when [follow] is set it glides to the left (to the right for a right-to-left line) while it
 * is sung, so the part being sung stays in view.
 */
@Composable
internal fun IslandSweepText(
    text: String,
    rtl: Boolean,
    ink: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    sweep: () -> Float,
    modifier: Modifier = Modifier,
    follow: Boolean = false,
    dim: Float = 0.5f,
    /** A line shorter than its room rests in the middle of it (a right-to-left one at the right edge). */
    center: Boolean = false,
    /** A soft edge on the sung part (an offscreen layer and a gradient on every frame); off = a plain clip, much cheaper. */
    soft: Boolean = true,
) {
    val featherPx = with(LocalDensity.current) { SWEEP_FEATHER_DP.dp.toPx() }
    Box(
        modifier
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints(minWidth = 0, maxWidth = Constraints.Infinity, minHeight = 0, maxHeight = constraints.maxHeight))
                val width = min(placeable.width, constraints.maxWidth)
                val overflow = max(0, placeable.width - width)
                val boxWidth = max(width, constraints.minWidth)
                // a short right-to-left line rests at the right edge of its room
                val x =
                    when {
                        rtl && placeable.width < boxWidth -> boxWidth - placeable.width
                        center && placeable.width < boxWidth -> (boxWidth - placeable.width) / 2
                        else -> 0
                    }
                layout(boxWidth, placeable.height) {
                    placeable.placeWithLayer(x, 0) {
                        if (follow && overflow > 0) {
                            val glide = IslandLyricsLogic.followFraction(sweep())
                            translationX = -overflow * (if (rtl) 1f - glide else glide)
                        }
                    }
                }
            },
    ) {
        Text(
            text = text,
            color = ink.copy(alpha = ink.alpha * dim),
            fontSize = fontSize,
            fontWeight = fontWeight,
            lineHeight = fontSize * 1.25f,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
        Text(
            text = text,
            color = ink,
            fontSize = fontSize,
            fontWeight = fontWeight,
            lineHeight = fontSize * 1.25f,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            modifier =
                Modifier
                    .then(if (soft) Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen } else Modifier)
                    .drawWithContent {
                        val p = sweep().coerceIn(0f, 1f)
                        val w = size.width
                        if (!soft) {
                            if (p >= 1f) {
                                drawContent()
                            } else if (w > 0f) {
                                clipRect(left = if (rtl) w * (1f - p) else 0f, right = if (rtl) w else w * p) { this@drawWithContent.drawContent() }
                            }
                            return@drawWithContent
                        }
                        drawContent()
                        if (p >= 1f) return@drawWithContent
                        if (w <= 0f) return@drawWithContent
                        val feather = min(featherPx, w)
                        val brush =
                            if (rtl) {
                                val left = w * (1f - p)
                                Brush.horizontalGradient(
                                    colorStops =
                                        arrayOf(
                                            0f to Color.Transparent,
                                            (left / w).coerceIn(0f, 1f) to Color.Transparent,
                                            ((left + feather) / w).coerceIn(0f, 1f) to Color.Black,
                                            1f to Color.Black,
                                        ),
                                    startX = 0f,
                                    endX = w,
                                )
                            } else {
                                val edge = w * p
                                Brush.horizontalGradient(
                                    colorStops =
                                        arrayOf(
                                            0f to Color.Black,
                                            ((edge - feather) / w).coerceIn(0f, 1f) to Color.Black,
                                            (edge / w).coerceIn(0f, 1f) to Color.Transparent,
                                            1f to Color.Transparent,
                                        ),
                                    startX = 0f,
                                    endX = w,
                                )
                            }
                        drawRect(brush = brush, blendMode = BlendMode.DstIn)
                    },
        )
    }
}

/**
 * The lines as one list that slides on a spring to the line being sung: no jump between lines, the lines away from it
 * shrink and fade. [index] is the line being sung (-1 = before the first), [sweep] its sung part. [fontSize] and
 * [spacing] are the text size and the distance between two lines.
 */
@Composable
internal fun IslandLyricRows(
    lines: List<IslandLyricLine>,
    index: Int,
    sweep: () -> Float,
    ink: Color,
    fontSize: TextUnit,
    spacing: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    soft: Boolean = true,
) {
    val target = index.coerceAtLeast(0)
    val scroll = animateFloatAsState(target.toFloat(), spring(dampingRatio = 0.85f, stiffness = 150f), label = "lyricScroll")
    val spacingPx = with(LocalDensity.current) { spacing.toPx() }
    Box(modifier.clipToBounds()) {
        for (d in -2..2) {
            val i = target + d
            if (i < 0 || i >= lines.size) continue
            val line = lines[i]
            androidx.compose.runtime.key(i) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth()
                        .graphicsLayer {
                            val dist = abs(i - scroll.value)
                            translationY = (i - scroll.value) * spacingPx
                            alpha = if (dist <= 1f) 1f - 0.55f * dist else (0.45f - 0.45f * (dist - 1f)).coerceAtLeast(0f)
                            val s = 1f - 0.20f * min(dist, 1f)
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        },
                ) {
                    if (i == index) {
                        IslandSweepText(
                            text = line.text,
                            rtl = line.rtl,
                            ink = ink,
                            fontSize = fontSize,
                            fontWeight = FontWeight.Bold,
                            sweep = sweep,
                            soft = soft,
                            follow = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            text = line.text,
                            color = ink,
                            fontSize = fontSize,
                            fontWeight = FontWeight.Bold,
                            lineHeight = fontSize * 1.25f,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** The camera hole in a lyric line, as distances from the start of the text, in dp. */
@androidx.compose.runtime.Immutable
internal data class IslandLyricGap(
    val startDp: Float,
    val endDp: Float,
)

/**
 * A line for a pill with the camera in the middle: the line is split where the hole is, the first part sits left of it
 * (up to [gapStart]), the second right of it (from [gapEnd]); the sung part [sweep] runs through the first part, then the
 * second. A right-to-left line is left as it is.
 */
@Composable
internal fun IslandGapLine(
    text: String,
    rtl: Boolean,
    ink: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    sweep: () -> Float,
    gapStart: androidx.compose.ui.unit.Dp,
    gapEnd: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    soft: Boolean = true,
) {
    if (rtl) {
        IslandSweepText(text, true, ink, fontSize, fontWeight, sweep, modifier.fillMaxWidth(), follow = true, soft = soft)
        return
    }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    val style = remember(fontSize, fontWeight) { androidx.compose.ui.text.TextStyle(fontSize = fontSize, fontWeight = fontWeight) }
    val leftPx = with(density) { gapStart.toPx() }
    val widthOf: (String) -> Float = { s -> if (s.isEmpty()) 0f else measurer.measure(s, style, maxLines = 1, softWrap = false).size.width.toFloat() }
    val parts = remember(text, leftPx, style) { IslandLyricsLogic.splitAround(text, leftPx, widthOf) }
    val first = parts.first
    val second = parts.second
    val widths = remember(first, second, style) { widthOf(first) to widthOf(second) }
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(gapStart.coerceAtLeast(0.dp))) {
            if (first.isNotEmpty()) {
                IslandSweepText(
                    text = first, rtl = false, ink = ink, fontSize = fontSize, fontWeight = fontWeight,
                    sweep = { IslandLyricsLogic.splitSweep(sweep(), widths.first, widths.second).first },
                    modifier = Modifier.fillMaxWidth(), soft = soft,
                )
            }
        }
        // a hole that starts before the text only takes the part that is over the text
        androidx.compose.foundation.layout.Spacer(Modifier.width((gapEnd - gapStart.coerceAtLeast(0.dp)).coerceAtLeast(0.dp)))
        Box(Modifier.weight(1f)) {
            if (second.isNotEmpty()) {
                IslandSweepText(
                    text = second, rtl = false, ink = ink, fontSize = fontSize, fontWeight = fontWeight,
                    sweep = { IslandLyricsLogic.splitSweep(sweep(), widths.first, widths.second).second },
                    modifier = Modifier.fillMaxWidth(), follow = true, soft = soft,
                )
            }
        }
    }
}
