/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * "iOS-style animations" (Settings > Appearance). When on, even the smallest interaction answers with a spring:
 * taps squash and bounce back, screens slide with parallax, sheets and dialogs settle with a spring.
 * Everything runs inside graphicsLayer / transition specs (no recomposition per frame) and costs nothing when off.
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val LocalIosMotion = compositionLocalOf { false }

/** Squash-and-bounce on touch. No-op (and no gesture handler) while iOS animations are off. */
fun Modifier.iosTap(pressedScale: Float = 0.90f): Modifier = composed {
    val on = LocalIosMotion.current
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    if (!on) {
        this
    } else {
        this
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    scope.launch { scale.animateTo(pressedScale, spring(dampingRatio = 0.62f, stiffness = 1100f)) }
                    waitForUpOrCancellation()
                    scope.launch { scale.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = 380f)) }
                }
            }
            .graphicsLayer {
                val s = scale.value
                scaleX = s
                scaleY = s
            }
    }
}

/**
 * Staggered entrance of the [index]-th row or card of a group: it rises about 22 dp and fades in with a soft spring, [index] x
 * 45 ms after the first one. Only the first [limit] ones play it (a long list never makes the lower rows wait, and a row that
 * scrolls back in after that appears at once). No-op while iOS animations are off. Runs in graphicsLayer: no recomposition per frame.
 */
fun Modifier.iosAppear(
    index: Int,
    limit: Int = 10,
): Modifier = composed {
    if (!LocalIosMotion.current || index >= limit) {
        this
    } else {
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            delay(index * 45L)
            progress.animateTo(1f, spring(dampingRatio = 0.74f, stiffness = 280f))
        }
        this.graphicsLayer {
            val p = progress.value
            alpha = p.coerceIn(0f, 1f)
            translationY = (1f - p) * 22.dp.toPx()
        }
    }
}

/** Drop-in for Material3's IconButton: same parameters, plus the iOS tap bounce when enabled. */
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.IconButton(
        onClick = onClick,
        modifier = modifier.iosTap(0.78f),
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        content = content,
    )
}
