/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Swipe gestures of one queue row (the decisions are in [QueueSwipeLogic]):
 *  - right: "Putar berikutnya". Past the threshold the action runs when the finger lets go and the row springs back.
 *    A row that is playing, or already next, stretches like a rubber band and says so instead.
 *  - left:  "Hapus". Past the threshold the row slides out and is removed.
 * While the finger is down a neutral pill (icon over a short label) is revealed behind the row. Horizon: ink on neutral
 * surfaces, no accent; the danger colour only on the trash icon. The drag offset is read in draw and layer lambdas only,
 * so a swipe invalidates one row's layers and never recomposes the list.
 */

package com.metrolist.music.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.theme.HorizonMotion
import com.metrolist.music.ui.theme.HorizonRadius
import com.metrolist.music.ui.theme.HorizonSpace
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which side of the row the pill is on while it is dragged. */
private enum class SwipeSide { None, Right, Left }

/**
 * Per-row gesture state. [offset] is read only inside layer and draw lambdas; [side] is read in composition by the
 * reveal layer alone, so the row's content does not recompose while it is dragged.
 */
@Stable
private class QueueSwipeState {
    /** Where the row is, px, positive to the right. */
    var offset by mutableFloatStateOf(0f)

    /** Which pill is shown. Set when the row leaves the rest position, cleared when it is back. */
    var side by mutableStateOf(SwipeSide.None)

    /** Row width in px. Not state: nothing draws from it before the first drag. */
    var widthPx = 0f

    /** How far the finger has travelled, signed. The row's [offset] is derived from it. */
    var travel = 0f

    /** The animation after the finger lets go. */
    var settle: Job? = null
}

/** How long a removed row may stay at the edge before it is assumed that the removal did not happen and it comes back. */
private const val RemoveGraceMs = 450L
private const val SlideOutMs = 150

/**
 * One queue row with the swipe gestures around [content].
 *
 * @param rightAction what a swipe to the right does; [QueueSwipeLogic.RightAction.Disabled] turns that direction off.
 * @param leftEnabled whether a swipe to the left removes the row.
 * @param onPlayNext called once when a swipe to the right commits on a row with [QueueSwipeLogic.RightAction.PlayNext].
 * @param onRemove called once when a swipe to the left commits, after the row has slid out.
 * @param onRightRejected called when a swipe to the right commits on a row where it does nothing (playing, already next),
 * with the reason, so the screen can say it.
 * @param lockedHint the queue is locked: both directions stretch like a rubber band (no pill), tick once at 20 percent of
 * the width, and [onLockedHint] is called when the finger lets go past it. Nothing else runs. The caller passes
 * [QueueSwipeLogic.RightAction.Disabled] and `leftEnabled = false` with it.
 */
@Composable
fun QueueSwipeRow(
    rightAction: QueueSwipeLogic.RightAction,
    leftEnabled: Boolean,
    onPlayNext: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    onRightRejected: (QueueSwipeLogic.RightAction) -> Unit = {},
    lockedHint: Boolean = false,
    onLockedHint: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val st = remember { QueueSwipeState() }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()

    val rightNow by rememberUpdatedState(rightAction)
    val leftNow by rememberUpdatedState(leftEnabled)
    val playNextNow by rememberUpdatedState(onPlayNext)
    val removeNow by rememberUpdatedState(onRemove)
    val rejectedNow by rememberUpdatedState(onRightRejected)
    val lockedNow by rememberUpdatedState(lockedHint)
    val lockedHintNow by rememberUpdatedState(onLockedHint)
    val hapticNow by rememberUpdatedState(haptic)

    val armed by remember {
        derivedStateOf {
            if (lockedNow) {
                QueueSwipeLogic.isHintArmed(st.offset, st.widthPx)
            } else {
                QueueSwipeLogic.isArmed(st.offset, st.widthPx) &&
                    (st.offset < 0f || rightNow == QueueSwipeLogic.RightAction.PlayNext)
            }
        }
    }
    // the light tick when the row crosses the threshold (on the way in only); read from a flow so that the crossing does
    // not recompose the row
    LaunchedEffect(Unit) {
        snapshotFlow { armed }.collect { if (it) hapticNow.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    }

    val dragState =
        rememberDraggableState { delta ->
            st.travel += delta
            val pull =
                when {
                    lockedNow -> QueueSwipeLogic.Pull.Rubber
                    st.travel > 0f -> QueueSwipeLogic.rightPull(rightNow)
                    else -> QueueSwipeLogic.leftPull(leftNow)
                }
            st.offset = QueueSwipeLogic.offsetFor(st.travel, st.widthPx, pull)
            if (st.offset > 0f) {
                st.side = SwipeSide.Right
            } else if (st.offset < 0f) {
                st.side = SwipeSide.Left
            }
        }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .onSizeChanged { st.widthPx = it.width.toFloat() }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = rightAction != QueueSwipeLogic.RightAction.Disabled || leftEnabled || lockedHint,
                    onDragStarted = {
                        st.settle?.cancel()
                        st.settle = null
                        st.travel = st.offset
                    },
                    onDragStopped = { velocity ->
                        val travel = st.travel
                        val toward = if (travel >= 0f) velocity else -velocity
                        val commit = QueueSwipeLogic.shouldCommit(travel, st.widthPx, toward / density)
                        st.settle =
                            scope.launch {
                                when {
                                    lockedNow -> {
                                        if (QueueSwipeLogic.hintCommit(travel, st.widthPx)) lockedHintNow()
                                        st.springBack()
                                    }
                                    travel > 0f -> {
                                        if (commit) {
                                            when (val action = rightNow) {
                                                QueueSwipeLogic.RightAction.PlayNext -> {
                                                    hapticNow.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    playNextNow()
                                                }
                                                QueueSwipeLogic.RightAction.Disabled -> {}
                                                else -> rejectedNow(action)
                                            }
                                        }
                                        st.springBack()
                                    }
                                    travel < 0f && commit && leftNow -> {
                                        hapticNow.performHapticFeedback(HapticFeedbackType.LongPress)
                                        animate(
                                            initialValue = st.offset,
                                            targetValue = -st.widthPx,
                                            animationSpec = tween(SlideOutMs, easing = HorizonMotion.Standard),
                                        ) { value, _ -> st.offset = value }
                                        removeNow()
                                        // normally the row is gone from the list by now and this coroutine is cancelled with it
                                        delay(RemoveGraceMs)
                                        st.springBack()
                                    }
                                    else -> st.springBack()
                                }
                            }
                    },
                ),
    ) {
        SwipeReveal(
            st = st,
            rightAction = rightAction,
            locked = lockedHint,
            armed = { armed },
            modifier = Modifier.matchParentSize(),
        )
        Box(modifier = Modifier.graphicsLayer { translationX = st.offset }) {
            content()
        }
    }
}

private suspend fun QueueSwipeState.springBack() {
    animate(
        initialValue = offset,
        targetValue = 0f,
        animationSpec = spring(dampingRatio = HorizonMotion.SheetDamping, stiffness = HorizonMotion.SheetStiffness),
    ) { value, _ -> offset = value }
    side = SwipeSide.None
    travel = 0f
}

// ------------------------------------------------------------------------------------------------------------ reveal

/** What the pill shows for a side. */
private class RevealSpec(
    val label: String,
    val icon: Int,
    val iconTint: Color,
    val labelTint: Color,
    /** The swipe does nothing here: the pill stays on the base tone and the icon does not pop. */
    val muted: Boolean,
)

/**
 * The pill behind the row. It spans the part of the row that the sliding row has uncovered, inset from the screen edge
 * and from the row, and holds the icon over its label, anchored at the screen edge. It is composed only while a swipe is
 * on screen (the list has hundreds of rows).
 */
@Composable
private fun SwipeReveal(
    st: QueueSwipeState,
    rightAction: QueueSwipeLogic.RightAction,
    locked: Boolean,
    armed: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val side = st.side
    // a locked queue only stretches the row: a pill that narrow could not show anything
    if (side == SwipeSide.None || locked) return

    val colors = MaterialTheme.colorScheme
    val spec =
        when {
            side == SwipeSide.Left ->
                RevealSpec(stringResource(R.string.delete), R.drawable.delete, colors.error, colors.onSurface, muted = false)
            rightAction == QueueSwipeLogic.RightAction.IsCurrent ->
                RevealSpec(stringResource(R.string.auralys_queue_is_current), R.drawable.play, colors.onSurfaceVariant, colors.onSurfaceVariant, muted = true)
            rightAction == QueueSwipeLogic.RightAction.AlreadyNext ->
                RevealSpec(stringResource(R.string.auralys_queue_already_next), R.drawable.check, colors.onSurfaceVariant, colors.onSurfaceVariant, muted = true)
            else ->
                RevealSpec(stringResource(R.string.play_next), R.drawable.playlist_play, colors.onSurface, colors.onSurface, muted = false)
        }

    val fill by animateColorAsState(
        targetValue = if (armed() && !spec.muted) colors.surfaceContainerHighest else colors.surfaceContainerHigh,
        animationSpec = tween(HorizonMotion.StateMs),
        label = "queueSwipeFill",
    )
    val pop by animateFloatAsState(
        targetValue = if (armed() && !spec.muted) 1.15f else 1f,
        animationSpec = spring(dampingRatio = HorizonMotion.PressDamping, stiffness = 600f),
        label = "queueSwipePop",
    )
    val onRight = side == SwipeSide.Right
    val inset = HorizonSpace.S12
    val gap = HorizonSpace.S8
    val verticalInset = HorizonSpace.S4
    val corner = HorizonRadius.Md

    Box(
        modifier =
            modifier.drawWithContent {
                val offset = st.offset
                val insetPx = inset.toPx()
                val gapPx = gap.toPx()
                val left = if (onRight) insetPx else size.width + offset + gapPx
                val right = if (onRight) offset - gapPx else size.width - insetPx
                val width = right - left
                if (width > 0f) {
                    val top = verticalInset.toPx()
                    val fade = (width / 40.dp.toPx()).coerceIn(0f, 1f)
                    drawRoundRect(
                        color = fill,
                        topLeft = Offset(left, top),
                        size = Size(width, size.height - 2f * top),
                        cornerRadius = CornerRadius(corner.toPx()),
                        alpha = fade,
                    )
                    clipRect(left = left, top = 0f, right = right, bottom = size.height) {
                        this@drawWithContent.drawContent()
                    }
                }
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .align(if (onRight) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = inset + HorizonSpace.S16)
                    .graphicsLayer {
                        val limit = if (spec.muted) QueueSwipeLogic.RubberFraction else QueueSwipeLogic.ThresholdFraction
                        val shown = QueueSwipeLogic.revealAlpha(QueueSwipeLogic.progress(st.offset, st.widthPx, limit))
                        alpha = shown
                        val scale = 0.88f + 0.12f * shown
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(if (onRight) 0f else 1f, 0.5f)
                    },
        ) {
            Icon(
                painter = painterResource(spec.icon),
                contentDescription = null,
                tint = spec.iconTint,
                modifier =
                    Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            scaleX = pop
                            scaleY = pop
                        },
            )
            // the note rows only stretch to about 20 percent of the width: too narrow for a label, the snackbar says it
            if (!spec.muted) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = spec.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = spec.labelTint,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
