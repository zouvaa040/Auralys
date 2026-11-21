/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Liquid Glass variant adapted from Convx (com.convx.music.ui.component.shimmer.ShimmerHost, GPL-3.0).
 */

package com.metrolist.music.ui.component.shimmer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.metrolist.music.ui.theme.LocalUiStyle
import com.metrolist.music.ui.theme.UiStyle
import com.valentinilk.shimmer.defaultShimmerTheme
import com.valentinilk.shimmer.shimmer

/**
 * Hosts a block of loading placeholders under ONE shimmer sweep: the whole column is a single
 * shimmer area, so a list of placeholders costs one animation, not one per item.
 *
 * Liquid Glass: the sweep is isolated in its own layer (so its per-frame invalidation never
 * re-records the parent or the glass backdrop) and the block fades out towards the bottom.
 * Editorial: unchanged flat look.
 */
@Composable
fun ShimmerHost(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    showGradient: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = LocalUiStyle.current == UiStyle.GLASS
    Column(
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = verticalArrangement,
        modifier =
            if (glass) {
                modifier
                    // Isolate shimmer frame invalidation from the parent (and the glass backdrop).
                    .graphicsLayer()
                    .shimmer()
                    .then(if (showGradient) Modifier.bottomFade() else Modifier)
            } else {
                modifier.shimmer()
            },
        content = content,
    )
}

/** Fades the block out towards the bottom. The brush is built once per size, never per frame. */
private fun Modifier.bottomFade(): Modifier =
    this
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithCache {
            val fade = Brush.verticalGradient(listOf(Color.Black, Color.Transparent))
            onDrawWithContent {
                drawContent()
                drawRect(brush = fade, blendMode = BlendMode.DstIn)
            }
        }

/**
 * Fill for placeholder shapes. Editorial: the original flat container colour. Liquid Glass: a frosted,
 * accent-tinted pane (light on dark, smoky on light) that reads as glass once the shimmer modulates it.
 */
@Composable
fun shimmerPlaceholderColor(): Color {
    val scheme = MaterialTheme.colorScheme
    if (LocalUiStyle.current != UiStyle.GLASS) return scheme.surfaceContainerHigh
    val primary = scheme.primary
    val dark = scheme.surface.luminance() < 0.5f
    return remember(primary, dark) {
        if (dark) {
            lerp(Color.White, primary, 0.22f).copy(alpha = 0.55f)
        } else {
            lerp(Color.Black, primary, 0.35f).copy(alpha = 0.26f)
        }
    }
}

val ShimmerTheme =
    defaultShimmerTheme.copy(
        animationSpec =
        infiniteRepeatable(
            animation =
            tween(
                durationMillis = 800,
                easing = LinearEasing,
                delayMillis = 250,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        shaderColors =
        listOf(
            Color.Unspecified.copy(alpha = 0.25f),
            Color.Unspecified.copy(alpha = 0.50f),
            Color.Unspecified.copy(alpha = 0.25f),
        ),
    )
