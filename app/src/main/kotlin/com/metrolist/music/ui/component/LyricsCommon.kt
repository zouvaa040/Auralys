/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.metrolist.music.lyrics.LyricsEntry

sealed class LyricsListItem {
    data class Line(val index: Int, val entry: LyricsEntry) : LyricsListItem()
    data class Indicator(
        val afterLineIndex: Int,
        val gapMs: Long,
        val gapStartMs: Long,
        val gapEndMs: Long,
        val nextAgent: String?
    ) : LyricsListItem()
}

/**
 * The instrumental gap of the lyrics: three dots (see [LyricDots]) that fill up one after the other as the gap runs down
 * and breathe while it is on. The row grows and fades in when the gap starts and folds away when it ends.
 * Progress is read through [currentPositionProvider] in the draw phase, so it never recomposes per frame, and only while
 * the row is open.
 *
 * @param horizontal where the dots sit in the row: 0 at the start, 0.5 in the middle, 1 at the end (like the text)
 * @param animate false when the system animation scale is 0: the row is there or not, at once
 */
@Composable
internal fun IntervalIndicator(
    gapStartMs: Long,
    gapEndMs: Long,
    currentPositionProvider: () -> Long,
    visible: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    horizontal: Float = 0.5f,
    animate: Boolean = true,
) {
    val enter = remember { Animatable(if (visible && !animate) 1f else 0f) }
    LaunchedEffect(visible, animate) {
        val target = if (visible) 1f else 0f
        if (animate) enter.animateTo(target, tween(260, easing = FastOutSlowInEasing)) else enter.snapTo(target)
    }
    // the height is taken in the layout phase, so the fold does not recompose the dots
    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val height = (placeable.height * enter.value).roundToInt()
                layout(placeable.width, height) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                alpha = enter.value
                val s = 0.85f + 0.15f * enter.value
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(horizontal, 0.5f)
                clip = true
            }
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        LyricDots(
            breathing = visible && animate,
            // a folded row reads no position, so the dots of the other gaps do not redraw on every frame
            progress = { if (enter.value > 0f) LyricsMotion.gapProgress(currentPositionProvider(), gapStartMs, gapEndMs) else 0f },
            ink = color,
            horizontal = horizontal,
        )
    }
}
