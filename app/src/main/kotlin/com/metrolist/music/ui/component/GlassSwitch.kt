/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Drop-in replacement for Material3's Switch (same parameter names), the same in both styles (Horizon, DESIGN-V2.md 2.3):
 * a 52 x 32 capsule, ink track with a page-coloured thumb when on, surface-3 track with a white thumb when off. No
 * accent. The thumb stretches while pressed, slides with a springy overshoot and can be swiped.
 * `colors` and `thumbContent` are accepted for source compatibility and not used.
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Thumb colour of the off state: white on light pages, a soft grey-white on dark ones. */
internal val SwitchThumbOffLight = Color(0xFFFFFFFF)
internal val SwitchThumbOffDark = Color(0xFFD9DCE3)

/** The 1 dp outline of the off track: ink at this strength is 3:1 or more against the page in every mode (unit tested). */
internal const val SwitchOutlineAlpha = 0.5f

/** When the off track has to be made from ink (a translucent container slot), this much ink is laid over the page. */
internal const val SwitchOffTintAlpha = 0.14f

/** The colours of one state of the switch. */
internal class SwitchLook(
    val track: Color,
    val thumb: Color,
    val outline: Color,
)

/**
 * Track, thumb and outline of the switch. On: ink track, page-coloured thumb (the outline is the track itself, so none is
 * seen). Off: an OPAQUE track, a light thumb and a 1 dp ink outline, so the state is not carried by the thumb alone. A
 * container slot that is translucent (the "Kustom" scheme in the Kaca style: 80 percent white) would make a near-white
 * track under a white thumb, so it is replaced by a tint of ink laid over the page colour.
 */
internal fun switchLook(
    checked: Boolean,
    dark: Boolean,
    primary: Color,
    onPrimary: Color,
    onSurface: Color,
    surface: Color,
    offContainer: Color,
): SwitchLook {
    if (checked) return SwitchLook(track = primary, thumb = onPrimary, outline = primary)
    val track =
        if (offContainer.alpha >= 0.99f) offContainer else onSurface.copy(alpha = SwitchOffTintAlpha).compositeOver(surface)
    return SwitchLook(
        track = track,
        thumb = if (dark) SwitchThumbOffDark else SwitchThumbOffLight,
        outline = onSurface.copy(alpha = SwitchOutlineAlpha),
    )
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current

    val trackW = 52.dp
    val trackH = 32.dp
    val pad = 2.dp
    val thumbH = 28.dp
    val thumbW by animateDpAsState(
        targetValue = if (pressed) 34.dp else 28.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 900f),
        label = "toggleThumbW",
    )
    val fraction by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 520f),
        label = "toggleFraction",
    )
    val look =
        switchLook(
            checked = checked,
            dark = dark,
            primary = scheme.primary,
            onPrimary = scheme.onPrimary,
            onSurface = scheme.onSurface,
            surface = scheme.surface,
            offContainer = scheme.surfaceContainerHighest,
        )
    val trackColor by animateColorAsState(targetValue = look.track, label = "toggleTrack")
    val thumbColor by animateColorAsState(targetValue = look.thumb, label = "toggleThumb")
    val outlineColor by animateColorAsState(targetValue = look.outline, label = "toggleOutline")
    val trackShape = remember { RoundedCornerShape(50) }
    val canToggle = enabled && onCheckedChange != null

    Box(
        modifier = modifier
            .size(trackW, trackH)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(trackShape)
            .background(trackColor)
            .border(1.dp, outlineColor, trackShape)
            .then(
                if (canToggle) {
                    Modifier
                        .toggleable(
                            value = checked,
                            enabled = true,
                            role = Role.Switch,
                            interactionSource = source,
                            indication = null,
                            onValueChange = {
                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onCheckedChange?.invoke(it)
                            },
                        )
                        .pointerInput(checked) {
                            detectHorizontalDragGestures { change, dx ->
                                change.consume()
                                if (dx > 4f && !checked) {
                                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    onCheckedChange?.invoke(true)
                                } else if (dx < -4f && checked) {
                                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    onCheckedChange?.invoke(false)
                                }
                            }
                        }
                } else Modifier,
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset {
                    val travel = (trackW - thumbW - pad * 2).toPx()
                    IntOffset((pad.toPx() + travel * fraction).roundToInt(), 0)
                }
                .size(thumbW, thumbH)
                .graphicsLayer {
                    // elevation E1: a soft shadow under the thumb
                    shadowElevation = 2.dp.toPx()
                    shape = RoundedCornerShape(50)
                    clip = false
                    ambientShadowColor = Color.Black.copy(alpha = if (dark) 0.35f else 0.18f)
                    spotShadowColor = Color.Black.copy(alpha = if (dark) 0.45f else 0.25f)
                }
                .clip(RoundedCornerShape(50))
                .background(thumbColor),
        )
    }
}
