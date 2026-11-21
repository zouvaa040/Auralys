/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The one volume control of Auralys, used by the player menu and by the player page: a speaker icon (tap = mute and
 * restore), a slim ink track with a round thumb that grows while it is held, a speaker with waves at the right end, and a
 * percent label over the thumb while dragging. Ink on neutral, no accent colour.
 *
 * It does not own the volume: the caller passes a [value] provider and a setter. The provider is read only while drawing
 * (and in a snapshot flow), so a volume that changes 60 times a second never recomposes the menu or the player around it;
 * only the draw phase of the bar runs.
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.ui.theme.HorizonMotion
import com.metrolist.music.ui.theme.HorizonSpace
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** The measures of one use of the control. */
@Immutable
class VolumeControlSizes(
    /** Thickness of the track at rest and while held. */
    val trackRest: Dp,
    val trackActive: Dp,
    /** Diameter of the thumb at rest (0 = no thumb until it is held) and while held. */
    val thumbRest: Dp,
    val thumbActive: Dp,
    /** Clear space between the thumb and the two parts of the track (0 = the thumb sits on a continuous track). */
    val thumbGap: Dp,
)

object VolumeControlDefaults {
    /** The player menu: a standalone control, so the thumb is always there. */
    val Menu =
        VolumeControlSizes(trackRest = 6.dp, trackActive = 8.dp, thumbRest = 14.dp, thumbActive = 22.dp, thumbGap = 3.dp)

    /** The player page: the same bar as the seek bar above it (4 dp track that thickens, a thumb only while held). */
    val Player =
        VolumeControlSizes(trackRest = 4.dp, trackActive = 8.dp, thumbRest = 0.dp, thumbActive = 18.dp, thumbGap = 0.dp)

    /** Touch height of the whole row and width of each icon button. */
    val Target: Dp = HorizonSpace.Target

    /** Inset of a 24 dp glyph inside its 48 dp touch area: callers that want the glyph on a margin subtract this. */
    val GlyphInset: Dp = 12.dp

    /**
     * The unfilled part of the track, as a share of ink. The design doc says 20 percent; 25 percent (what the player page
     * already used) reads at 1.76:1 on the light sheet and 2.2:1 on the dark one instead of 1.57:1 and 1.8:1, so the bar
     * still shows where it ends. The meaning (thumb, filled part, icons) is carried at more than 7:1.
     */
    const val RestAlpha = 0.25f

    /** Opacity of the two speaker icons (the muted icon is full ink). */
    const val IconAlpha = 0.72f
}

/**
 * @param value the current level, 0..1. A provider, not a value: it is read in the draw phase only.
 * @param onValueChange the level the listener asked for (called while dragging, once per change).
 * @param memory remembers the level before a mute, one per scale (see [PlayerGainMuteMemory], [SystemVolumeMuteMemory]).
 * @param onValueChangeFinished the finger lifted (or a tap or a key press ended).
 */
@Composable
fun VolumeControl(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    sizes: VolumeControlSizes = VolumeControlDefaults.Menu,
    memory: VolumeMuteMemory = remember { VolumeMuteMemory() },
    ink: Color = MaterialTheme.colorScheme.onSurface,
    onInk: Color = MaterialTheme.colorScheme.surface,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val valueNow by rememberUpdatedState(value)
    val changeNow by rememberUpdatedState(onValueChange)
    val finishedNow by rememberUpdatedState(onValueChangeFinished)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources

    // what the bar shows: the finger while it is down, the outside value the rest of the time
    val current = remember { mutableFloatStateOf(Snapshot.withoutReadObservation { value() }.coerceIn(0f, 1f)) }
    var dragging by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val active = dragging || pressed
    val glide = remember { arrayOfNulls<Job>(1) }

    fun stopGlide() {
        glide[0]?.cancel()
        glide[0] = null
    }

    suspend fun glideTo(target: Float) {
        val from = current.floatValue
        if (abs(target - from) < 1e-4f) {
            current.floatValue = target
            return
        }
        animate(from, target, animationSpec = tween(HorizonMotion.StateMs, easing = HorizonMotion.Standard)) { v, _ ->
            current.floatValue = v
        }
    }

    // the level changed from outside (hardware keys, cast, Listen Together): follow it unless the finger is on the bar
    LaunchedEffect(Unit) {
        snapshotFlow { valueNow().coerceIn(0f, 1f) }.collectLatest { outside ->
            if (!dragging) {
                memory.commit(outside)
                glide[0]?.cancel()
                glideTo(outside)
            }
        }
    }
    // after the finger lifts, settle on what the source really holds (a step of the system volume, a clamped value)
    LaunchedEffect(active) {
        if (!active) {
            delay(280)
            glideTo(valueNow().coerceIn(0f, 1f))
        }
    }

    fun commitFinish() {
        memory.commit(current.floatValue)
        finishedNow?.invoke()
    }

    /** A level from the finger: snapped to the detents, felt at the detents. */
    fun fromFinger(raw: Float) {
        val f = VolumeMath.snap(raw)
        val before = current.floatValue
        if (f == before) return
        current.floatValue = f
        if (VolumeMath.detentCrossed(before, f)) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        changeNow(f)
    }

    /** A level from a tap on the icon, a key or an accessibility action: it glides there. */
    fun fromCommand(target: Float) {
        val f = target.coerceIn(0f, 1f)
        if (f == current.floatValue) return
        stopGlide()
        changeNow(f)
        glide[0] = scope.launch { glideTo(f) }
    }

    val muted by remember { derivedStateOf { VolumeMath.isMuted(current.floatValue) } }
    val muteLabel = stringResource(if (muted) R.string.auralys_volume_unmute else R.string.auralys_volume_mute)
    val iconTint = ink.copy(alpha = if (enabled) VolumeControlDefaults.IconAlpha else 0.38f)

    val progress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "volumeControlActive",
    )
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    val rest = ink.copy(alpha = VolumeControlDefaults.RestAlpha * (if (enabled) 1f else 0.6f))
    val filled = if (enabled) ink else ink.copy(alpha = 0.38f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().height(VolumeControlDefaults.Target),
    ) {
        // left: the quiet end, and the mute button
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(VolumeControlDefaults.Target)
                    .clip(CircleShape)
                    .semantics { contentDescription = muteLabel }
                    .clickable(enabled = enabled, role = Role.Button) {
                        val target = memory.toggle(current.floatValue)
                        haptic.performHapticFeedback(if (target == 0f) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        fromCommand(target)
                        finishedNow?.invoke()
                    },
        ) {
            Crossfade(
                targetState = muted,
                animationSpec = tween(HorizonMotion.StateMs),
                label = "volumeControlLeftIcon",
            ) { isMuted ->
                Icon(
                    painter = painterResource(if (isMuted) R.drawable.volume_off else R.drawable.volume_down),
                    contentDescription = null,
                    tint = if (isMuted && enabled) ink else iconTint,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // the bar
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics {
                        stateDescription = resources.getString(R.string.auralys_volume_state, VolumeMath.percent(current.floatValue))
                        progressBarRangeInfo = ProgressBarRangeInfo(current.floatValue, 0f..1f, 0)
                        if (enabled) {
                            setProgress { target ->
                                fromCommand(target)
                                finishedNow?.invoke()
                                true
                            }
                        }
                    }.onKeyEvent { event ->
                        if (!enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val now = current.floatValue
                        val target =
                            when (event.key) {
                                Key.DirectionRight, Key.DirectionUp -> VolumeMath.stepBy(now, VolumeMath.KeyStep)
                                Key.DirectionLeft, Key.DirectionDown -> VolumeMath.stepBy(now, -VolumeMath.KeyStep)
                                Key.PageUp -> VolumeMath.stepBy(now, VolumeMath.KeyStep * 2)
                                Key.PageDown -> VolumeMath.stepBy(now, -VolumeMath.KeyStep * 2)
                                Key.MoveHome -> 0f
                                Key.MoveEnd -> 1f
                                else -> return@onKeyEvent false
                            }
                        fromCommand(target)
                        finishedNow?.invoke()
                        true
                    }.focusable(enabled)
                    .pointerInput(enabled, sizes) {
                        if (!enabled) return@pointerInput
                        val pad = sizes.thumbActive.toPx() / 2f
                        detectTapGestures(
                            onPress = {
                                pressed = true
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    pressed = false
                                }
                            },
                            onTap = { at ->
                                stopGlide()
                                fromFinger(VolumeMath.fractionAt(at.x, pad, size.width - pad))
                                commitFinish()
                            },
                        )
                    }.pointerInput(enabled, sizes) {
                        if (!enabled) return@pointerInput
                        val pad = sizes.thumbActive.toPx() / 2f
                        detectHorizontalDragGestures(
                            onDragStart = { at ->
                                stopGlide()
                                dragging = true
                                fromFinger(VolumeMath.fractionAt(at.x, pad, size.width - pad))
                            },
                            onDragEnd = {
                                dragging = false
                                commitFinish()
                            },
                            onDragCancel = {
                                dragging = false
                                commitFinish()
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                fromFinger(VolumeMath.fractionAt(change.position.x, pad, size.width - pad))
                            },
                        )
                    },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val p = progress
                val f = current.floatValue.coerceIn(0f, 1f)
                val activeRadius = sizes.thumbActive.toPx() / 2f
                val start = activeRadius
                val end = max(size.width - activeRadius, start)
                val x = VolumeMath.positionAt(f, start, end)
                val cy = size.height / 2f

                val trackH = sizes.trackRest.toPx() + (sizes.trackActive.toPx() - sizes.trackRest.toPx()) * p
                val thumbR = (sizes.thumbRest.toPx() + (sizes.thumbActive.toPx() - sizes.thumbRest.toPx()) * p).coerceAtLeast(0f) / 2f
                val gap = if (sizes.thumbGap.value > 0f && thumbR > 0f && activeRadius > 0f) sizes.thumbGap.toPx() * (thumbR / activeRadius).coerceAtMost(1f) else 0f
                val top = cy - trackH / 2f

                fun pill(
                    left: Float,
                    right: Float,
                    color: Color,
                ) {
                    val len = right - left
                    if (len > 0.5f) {
                        drawRoundRect(color, Offset(left, top), Size(len, trackH), CornerRadius(minOf(trackH / 2f, len / 2f)))
                    }
                }

                if (gap > 0.5f) {
                    pill(start, x - thumbR - gap, filled)
                    pill(x + thumbR + gap, end, rest)
                } else {
                    pill(start, end, rest)
                    pill(start, x, filled)
                }
                if (thumbR > 0.5f) drawCircle(filled, thumbR, Offset(x, cy))

                // the percent label, over the thumb, only while it is held
                if (p > 0.02f) {
                    val a = p.coerceIn(0f, 1f)
                    val text = textMeasurer.measure(VolumeMath.label(f), labelStyle)
                    val bubbleH = 26.dp.toPx()
                    val bubbleW = max(text.size.width + 20.dp.toPx(), 44.dp.toPx())
                    val bubbleLeft = (x - bubbleW / 2f).coerceIn(0f, max(size.width - bubbleW, 0f))
                    val bubbleTop = cy - activeRadius - 4.dp.toPx() - bubbleH
                    scale(0.88f + 0.12f * a, Offset(x.coerceIn(bubbleLeft, bubbleLeft + bubbleW), bubbleTop + bubbleH)) {
                        drawRoundRect(ink.copy(alpha = a), Offset(bubbleLeft, bubbleTop), Size(bubbleW, bubbleH), CornerRadius(bubbleH / 2f))
                        drawText(
                            text,
                            color = onInk,
                            topLeft = Offset(bubbleLeft + (bubbleW - text.size.width) / 2f, bubbleTop + (bubbleH - text.size.height) / 2f),
                            alpha = a,
                        )
                    }
                }
            }
        }

        // right: the loud end
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(VolumeControlDefaults.Target)) {
            Icon(
                painter = painterResource(R.drawable.volume_up),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
