/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Drop-in for Material 3's Slider. In the Liquid Glass style it gets a frosted capsule track with a glossy fill and a
 * round glass thumb that swells while it is held, in the spirit of Kyant0's LiquidSlider. It is drawn directly instead of
 * sampling the backdrop, so a screen full of sliders costs nothing extra. Editorial keeps the stock Material slider.
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.metrolist.music.ui.theme.LocalUiStyle
import com.metrolist.music.ui.theme.UiStyle

private val ThumbWidth = 36.dp
private val ThumbHeight = 24.dp
private val TrackHeight = 10.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    if (LocalUiStyle.current != UiStyle.GLASS) {
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            colors = colors,
            interactionSource = interactionSource,
        )
        return
    }

    val dragged by interactionSource.collectIsDraggedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val held = dragged || pressed
    val lift by animateFloatAsState(
        targetValue = if (held) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "glassSliderLift",
    )
    val dark = glassUsesDarkAppearance()

    val sliderState = remember(valueRange, steps) { SliderState(value = value, steps = steps, trackRange = valueRange) }
    sliderState.value = value

    androidx.compose.material3.Slider(
        state = sliderState,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        thumb = {
            Box(
                Modifier
                    .size(ThumbWidth, ThumbHeight)
                    .graphicsLayer {
                        val s = 1f + 0.16f * lift
                        scaleX = s
                        scaleY = s
                    }
                    .alpha(if (enabled) 1f else 0.5f)
                    .shadow(if (held) 7.dp else 4.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(0.4f), spotColor = Color.Black.copy(0.4f))
                    .background(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 1f - 0.16f * lift), Color(0xFFE9E9EF).copy(alpha = 1f - 0.16f * lift))),
                        CircleShape,
                    )
                    .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0.35f)))), CircleShape),
            )
        },
        track = { state ->
            GlassTrack(state, valueRange, colors, enabled, dark)
        },
    )
}

/** The state based overload is passed straight through, so call sites that build their own thumb/track keep working. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Slider(
    state: SliderState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SliderColors = SliderDefaults.colors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    thumb: @Composable (SliderState) -> Unit = { SliderDefaults.Thumb(interactionSource = interactionSource, colors = colors, enabled = enabled) },
    track: @Composable (SliderState) -> Unit = { SliderDefaults.Track(sliderState = it, colors = colors, enabled = enabled) },
) {
    androidx.compose.material3.Slider(
        state = state,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        thumb = thumb,
        track = track,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlassTrack(
    state: SliderState,
    range: ClosedFloatingPointRange<Float>,
    colors: SliderColors,
    enabled: Boolean,
    dark: Boolean,
) {
    val fraction = ((state.value - range.start) / (range.endInclusive - range.start).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
    val active = if (enabled) colors.activeTrackColor else colors.disabledActiveTrackColor
    val rim = Color.White.copy(alpha = if (dark) 0.22f else 0.55f)
    val groove = if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(TrackHeight)
            .drawBehind {
                val h = size.height
                val corner = CornerRadius(h / 2f, h / 2f)
                // groove
                drawRoundRect(groove, size = size, cornerRadius = corner)
                // glossy fill, ending under the middle of the thumb
                val thumbW = ThumbWidth.toPx()
                val end = (thumbW / 2f + fraction * (size.width - thumbW)).coerceAtLeast(h)
                drawRoundRect(
                    Brush.horizontalGradient(listOf(active.copy(alpha = 0.78f), active)),
                    size = Size(end, h),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.38f), Color.Transparent), endY = h * 0.65f),
                    size = Size(end, h),
                    cornerRadius = corner,
                )
                // hairline rim
                drawRoundRect(
                    rim,
                    topLeft = Offset(0.5f, 0.5f),
                    size = Size(size.width - 1f, h - 1f),
                    cornerRadius = corner,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f),
                )
            },
    )
}
