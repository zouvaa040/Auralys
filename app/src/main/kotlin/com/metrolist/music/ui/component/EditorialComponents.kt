/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Shared building blocks of the Horizon design: flat neutral surfaces, plain sentence-case labels, a divider, press
 * feedback. (The file keeps its old name so call sites need no edit.)
 * Everything here is allocation-light and gradient/blur free.
 */

package com.metrolist.music.ui.component

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import com.metrolist.music.ui.theme.HorizonMotion
import com.metrolist.music.ui.theme.HorizonRadius
import com.metrolist.music.ui.theme.HorizonSpace
import com.metrolist.music.ui.theme.UiStyle
import com.metrolist.music.ui.theme.LocalUiStyle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 1px rule along the bottom edge (editorial style only; the glass style prefers whitespace). */
@Composable
fun Modifier.hairlineBottom(color: Color = MaterialTheme.colorScheme.outlineVariant): Modifier =
    if (LocalUiStyle.current == UiStyle.GLASS) this else this.drawBehind {
        val y = size.height - 0.5f
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
    }

/** 1px rule along the top edge (editorial style only). */
@Composable
fun Modifier.hairlineTop(color: Color = MaterialTheme.colorScheme.outlineVariant): Modifier =
    if (LocalUiStyle.current == UiStyle.GLASS) this else this.drawBehind {
        drawLine(color, Offset(0f, 0.5f), Offset(size.width, 0.5f), strokeWidth = 1.dp.toPx())
    }

/** Panel: a flat surface-2 pane in the Datar style, a glass control pane in the Kaca style. */
@Composable
fun Modifier.editorialPanel(
    shape: Shape = RoundedCornerShape(12.dp),
    container: Color = Color.Unspecified,
    border: Color = Color.Unspecified,
): Modifier = this.glass(
    if (LocalUiStyle.current == UiStyle.GLASS && shape is RoundedCornerShape) RoundedCornerShape(HorizonRadius.Lg) else shape,
)

/** Separator: the divider token (ink at 8 percent on light pages, white at 10 percent on dark ones). */
@Composable
fun EditorialDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    androidx.compose.foundation.layout.Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

/**
 * Small plain label (a group title, a source line, a format). Sentence case as written, 13 sp semi-bold, secondary
 * colour: no capitals, no letter spacing, no accent.
 */
@Composable
fun MicroLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Section header: a plain 20 sp title with an optional small line above it. No rule underneath. */
@Composable
fun EditorialHeader(
    title: String,
    modifier: Modifier = Modifier,
    kicker: String? = null,
    horizontalPadding: Dp = HorizonSpace.Gutter,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 12.dp)) {
        kicker?.let { MicroLabel(it) }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Soft press feedback: scales the element down slightly while a finger is on it.
 * Observes pointer events WITHOUT consuming them (clickable keeps working) and only
 * reads animation state in the graphicsLayer lambda, so nothing recomposes.
 */
fun Modifier.pressScale(pressedScale: Float = HorizonMotion.PressScale): Modifier = composed {
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ios = LocalIosMotion.current
    // iOS animations: a clearly deeper squash and a bouncier return (it used to be 2 points deeper, which nobody saw)
    val down = if (ios) pressedScale - 0.05f else pressedScale
    val upDamping = if (ios) 0.32f else 0.55f
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                scope.launch {
                    scale.animateTo(
                        down,
                        androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 900f),
                    )
                }
                waitForUpOrCancellation()
                scope.launch {
                    scale.animateTo(
                        1f,
                        androidx.compose.animation.core.spring(dampingRatio = upDamping, stiffness = 500f),
                    )
                }
            }
        }
        .graphicsLayer {
            val s = scale.value
            scaleX = s
            scaleY = s
        }
}

/**
 * Horizon rule 12: list rows do not stagger in, and nothing re-plays its entrance each time it scrolls back into
 * view. Kept as a no-op so the call sites need no edit (they are removed in the cleanup sweep).
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.riseIn(delayMillis: Int = 0, distance: Dp = 14.dp): Modifier = this

/**
 * Quick scale "pop" whenever [active] flips to true (skips the initial composition).
 */
fun Modifier.popWhen(active: Boolean): Modifier = composed {
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    androidx.compose.runtime.LaunchedEffect(active) {
        if (first[0]) {
            first[0] = false
        } else if (active) {
            scale.animateTo(1.3f, androidx.compose.animation.core.tween(90))
            scale.animateTo(
                1f,
                androidx.compose.animation.core.spring(dampingRatio = 0.35f, stiffness = 500f),
            )
        }
    }
    this.graphicsLayer {
        val s = scale.value
        scaleX = s
        scaleY = s
    }
}


/** Horizon: the page has no grain. Kept as a no-op so the call sites need no edit. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun Modifier.paperGrain(alpha: Float = 0.05f): Modifier = this
