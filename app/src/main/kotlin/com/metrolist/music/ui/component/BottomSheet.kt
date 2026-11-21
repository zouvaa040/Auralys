/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.metrolist.music.constants.NavigationBarAnimationSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * Bottom Sheet
 * Modified from [ViMusic](https://github.com/vfsfitvnm/ViMusic)
 */
@Composable
fun BottomSheet(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    background: @Composable (BoxScope.() -> Unit) = { },
    onDismiss: (() -> Unit)? = null,
    collapsedContent: @Composable BoxScope.() -> Unit,
    isExpandable: Boolean = true,
    // how far (px) the whole collapsed sheet is pushed down, read at draw time. The tap and drag area moves with it,
    // so it always sits under what is drawn (a folded mini player used to leave a dead, tappable strip behind).
    collapsedShiftPx: () -> Float = { 0f },
    // the full player: its parts rise into place one after the other while the sheet opens (see Modifier.sheetReveal)
    // instead of the whole content growing as one piece
    premium: Boolean = false,
    // drawn last, above everything, not moved with the sheet (the flying cover of the player)
    overlayContent: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    SideEffect { state.premiumMotion = premium }
    if (premium) {
        // from the finger going down on the bar (warming), not from the first movement: the recomposition of every glass bar
        // that the change causes then happens before the opening starts, not in its first frame
        // keyed by the state: the player's state is created again whenever its collapsed bound changes (the navigation bar comes or goes),
        // and an unkeyed remember kept reading the OLD one, whose progress stayed above 0 for good: the glass was stuck on plain blur
        // for as long as a mini player existed (owner's screenshot 2026-11-17: no lens while music plays)
        val open by remember(state) { derivedStateOf { state.progress > 0.001f || state.warming } }
        LaunchedEffect(open) {
            if (open) {
                GlassMotion.sheetMoving = true
            } else {
                // the lens comes back a moment after the mini player has settled
                kotlinx.coroutines.delay(200)
                GlassMotion.sheetMoving = false
            }
        }
        DisposableEffect(Unit) { onDispose { GlassMotion.sheetMoving = false } }
    }
    val maxCorner = if (com.metrolist.music.ui.theme.isGlassStyle) 28.dp else 16.dp
    
    Box(
        modifier = modifier
            .graphicsLayer {
                if (premium) {
                    // the colour of the player leads the sheet a little: in by 45 percent of the way
                    val t = (state.progress / 0.45f).coerceIn(0f, 1f)
                    alpha = t * t * (3f - 2f * t)
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                } else {
                    // background fades during about 10%-61% progress
                    alpha = (1.4f * (state.progress.coerceAtLeast(0.1f) - 0.1f).pow(0.5f)).coerceIn(0f, 1f)
                }
            }
            .fillMaxSize(),
        content = background
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            // Use graphicsLayer for offset to ensure hardware acceleration and 120Hz support
            .graphicsLayer {
                val y = (state.expandedBound - state.value)
                    .toPx()
                    .coerceAtLeast(0f)
                translationY = y + collapsedShiftPx() * (1f - state.progress.coerceIn(0f, 1f))
            }
            .pointerInput(state, isExpandable) {
                if (!isExpandable) return@pointerInput
                val velocityTracker = VelocityTracker()

                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        velocityTracker.addPointerInputChange(change)
                        state.dispatchRawDelta(dragAmount)
                    },
                    onDragCancel = {
                        velocityTracker.resetTracking()
                        state.snapTo(state.collapsedBound)
                    },
                    onDragEnd = {
                        val velocity = -velocityTracker.calculateVelocity().y
                        velocityTracker.resetTracking()
                        state.performFling(velocity, onDismiss)
                    }
                )
            }
            .graphicsLayer {
                // corners relax continuously while the sheet opens instead of snapping at the end
                val p = state.progress.coerceIn(0f, 1f)
                val cornerRadius = (1f - p) * maxCorner.toPx()
                shape = RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius)
                // The rounded corners only matter while the sheet opens. A permanent clip cut the mini player's shadow, rim and lens
                // along the sheet's top edge (the collapsed sheet's top IS the mini player's top).
                clip = p > 0.001f
            }
    ) {
        if (!state.isCollapsed && !state.isDismissed) {
            PredictiveBackHandler { progress ->
                val initialValue = state.value
                try {
                    val range = initialValue - state.collapsedBound
                    progress.collect { event ->
                        state.snapToAndWait(
                            initialValue - range * event.progress.coerceIn(0f, 1f)
                        )
                    }
                    state.collapseSoft()
                } catch (_: CancellationException) {
                    state.expandSoft()
                }
            }
        }

        // main content
        if (!state.isCollapsed || state.warming || state.lingering) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // the full player rises, settles and fades in while the mini player grows away
                        val p = state.progress.coerceIn(0f, 1f)
                        val q = ((p - 0.08f) / 0.92f).coerceIn(0f, 1f)
                        if (premium) {
                            // fades in with the sheet, in step with the mini player fading out (both are done by about 45 percent):
                            // no moment where the screen is covered and empty, no moving of the whole
                            val t = ((p - 0.08f) / 0.42f).coerceIn(0f, 1f)
                            alpha = t * t * (3f - 2f * t)
                            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                        } else {
                            alpha = ((p - 0.10f) * 3.2f).coerceIn(0f, 1f)
                            val sc = 0.95f + 0.05f * q
                            scaleX = sc
                            scaleY = sc
                            translationY = (1f - q) * 36.dp.toPx()
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.35f)
                        }
                    },
                content = content
            )
        }

        if (!state.isExpanded && (onDismiss == null || !state.isDismissed)) {
            Box(
                modifier =
                Modifier
                    .graphicsLayer {
                        val p = state.progress.coerceIn(0f, 1f)
                        if (premium) {
                            // leaves only when the player is already there to take over (it used to be gone at 31 percent)
                            alpha = (1f - p / 0.45f).coerceIn(0f, 1f).pow(1.5f)
                            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                        } else {
                            alpha = 1f - (p * 3.2f).coerceAtMost(1f)
                            // the mini player card swells toward the big player as it fades
                            val sc = 1f + 0.12f * p
                            scaleX = sc
                            scaleY = sc
                        }
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                    }.pointerInput(state) {
                        // only watches: nothing is consumed, the click and the drag below work as before
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            state.prepare()
                        }
                    }.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { if (isExpandable) state.expandSoft() },
                    )
                    .focusable(false)
                    .fillMaxWidth()
                    .height(state.collapsedBound),
                content = collapsedContent,
            )
        }
    }
    if (overlayContent != null) {
        Box(modifier = Modifier.fillMaxSize(), content = overlayContent)
    }
}

/**
 * One part of the full player coming into place while its sheet opens: between [from] and [to] (0..1 of the sheet's progress)
 * it fades in, rises [rise] and, with [scaleFrom] below 1, grows from that scale around the height [pivotY] (0 = top, 1 =
 * bottom). The parts use windows that start one after the other (cover, title, line, bar, buttons, volume, dock), so the player
 * builds itself from the cover down. It reads the sheet's progress, so it follows the finger while the sheet is dragged and
 * plays backwards when it closes (the last part leaves first). With iOS animations on the rise is half as long again, the
 * start scale is smaller and the growth overshoots a little before it settles. Runs in graphicsLayer: nothing recomposes.
 */
fun Modifier.sheetReveal(
    state: BottomSheetState,
    from: Float,
    to: Float,
    rise: Dp = 24.dp,
    scaleFrom: Float = 1f,
    pivotY: Float = 0.5f,
    fade: Boolean = true,
): Modifier =
    composed {
        val ios = LocalIosMotion.current
        graphicsLayer {
            val t = ((state.progress - from) / (to - from)).coerceIn(0f, 1f)
            val smooth = t * t * (3f - 2f * t)
            if (fade) alpha = smooth
            translationY = (1f - smooth) * rise.toPx() * (if (ios) 1.5f else 1f)
            if (scaleFrom != 1f) {
                val start = if (ios) scaleFrom - 0.06f else scaleFrom
                // ease-out-back: passes 1 a little and settles back
                val u = t - 1f
                val grown = if (ios) 1f + 3f * u * u * u + 2f * u * u else smooth
                val scale = start + (1f - start) * grown
                scaleX = scale
                scaleY = scale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, pivotY)
            }
        }
    }

@Stable
class BottomSheetState(
    draggableState: DraggableState,
    private val coroutineScope: CoroutineScope,
    private val animatable: Animatable<Dp, AnimationVector1D>,
    private val onAnchorChanged: (Int) -> Unit,
    val collapsedBound: Dp,
    // true while the sheet, resting collapsed, follows a CHANGE OF ITS OWN COLLAPSED BOUND (the navigation bar appears or leaves
    // with the page, the bar folds): it moves, but it is not opening
    private val adjustingState: androidx.compose.runtime.State<Boolean> = mutableStateOf(false),
) : DraggableState by draggableState {
    /** See [adjustingState]. */
    val adjusting: Boolean get() = adjustingState.value

    /** Set by BottomSheet(premium): the full player's tap open/close uses a calmer critical spring and a warm-up. */
    var premiumMotion by mutableStateOf(false)

    /** True while the full player is already composed (invisible) but the opening has not started: its first frame is the heavy one. */
    var warming by mutableStateOf(false)
        private set

    /** True for a moment after the sheet has closed: the player is taken down when nothing moves any more, not in the last frame. */
    var lingering by mutableStateOf(false)
        private set

    val dismissedBound: Dp
        get() = animatable.lowerBound!!

    val expandedBound: Dp
        get() = animatable.upperBound!!

    val value by animatable.asState()

    val isDismissed by derivedStateOf {
        value == animatable.lowerBound!!
    }

    val isCollapsed by derivedStateOf {
        adjustingState.value || value == collapsedBound
    }

    val isExpanded by derivedStateOf {
        value == animatable.upperBound
    }

    // How far the player is open, 0..1. While the sheet only follows a new collapsed bound this is 0: the value is still on its way
    // to the new bound, and measured against it that looked like a player that is opening (the mini player faded, the flying
    // cover appeared as a big disc, the glass dropped its lens: owner's videos of 2026-11-17, going to and from Pengaturan).
    val progress by derivedStateOf {
        if (adjustingState.value) {
            0f
        } else {
            1f - (animatable.upperBound!! - animatable.value) / (animatable.upperBound!! - collapsedBound)
        }
    }

    fun collapse(animationSpec: AnimationSpec<Dp>) {
        onAnchorChanged(collapsedAnchor)
        coroutineScope.launch {
            animatable.animateTo(collapsedBound, calmSpec(animationSpec))
            if (premiumMotion) {
                lingering = true
                try {
                    kotlinx.coroutines.delay(300)
                } finally {
                    lingering = false
                }
            }
        }
    }

    fun expand(animationSpec: AnimationSpec<Dp>) {
        onAnchorChanged(expandedAnchor)
        prepareJob?.cancel()
        coroutineScope.launch {
            try {
                animatable.animateTo(animatable.upperBound!!, calmSpec(animationSpec))
            } finally {
                warming = false
            }
        }
    }

    private fun collapse() {
        collapse(SpringSpec())
    }

    private fun expand() {
        expand(SpringSpec())
    }

    fun collapseSoft() {
        collapse(spring(stiffness = if (premiumMotion) PremiumStiffness else Spring.StiffnessMediumLow))
    }

    fun expandSoft() {
        expand(spring(stiffness = if (premiumMotion) PremiumStiffness else Spring.StiffnessMediumLow))
    }

    private var prepareJob: kotlinx.coroutines.Job? = null

    /** Every sheet's springs are wrapped so that a long frame (the first frame of a heavy composition) moves them by at most 32 ms. */
    private fun calmSpec(spec: AnimationSpec<Dp>): AnimationSpec<Dp> =
        if (spec is FiniteAnimationSpec<Dp>) FrameCappedSpec(spec) else spec

    /**
     * The finger is down on the collapsed bar: compose the full player now, invisible, so that by the time the finger is up
     * (about 80-150 ms later) it is ready and the opening starts at once and runs on a finished page. (A waiting step inside the
     * tap made the open feel slow; composing in the middle of the opening made it jump.) Released after 1.5 s when nothing opened.
     */
    fun prepare() {
        if (!premiumMotion || !isCollapsed || warming) return
        warming = true
        prepareJob =
            coroutineScope.launch {
                kotlinx.coroutines.delay(1500)
                if (isCollapsed) warming = false
            }
    }

    fun dismiss() {
        onAnchorChanged(dismissedAnchor)
        coroutineScope.launch {
            animatable.animateTo(animatable.lowerBound!!)
        }
    }
    
    fun snapTo(value: Dp) {
        coroutineScope.launch {
            animatable.snapTo(value)
        }
    }

    suspend fun snapToAndWait(value: Dp) {
        animatable.snapTo(value)
    }

    fun performFling(velocity: Float, onDismiss: (() -> Unit)?) {
        if (velocity > 250) {
            expand()
        } else if (velocity < -250) {
            if (value < collapsedBound && onDismiss != null) {
                dismiss()
                onDismiss.invoke()
            } else {
                collapse()
            }
        } else {
            val l0 = dismissedBound
            val l1 = (collapsedBound - dismissedBound) / 2
            val l2 = (expandedBound - collapsedBound) / 2
            val l3 = expandedBound

            when (value) {
                in l0..l1 -> {
                    if (onDismiss != null) {
                        dismiss()
                        onDismiss.invoke()
                    } else {
                        collapse()
                    }
                }

                in l1..l2 -> collapse()
                in l2..l3 -> expand()
                else -> Unit
            }
        }
    }

    val preUpPostDownNestedScrollConnection
        get() = object : NestedScrollConnection {
            var isTopReached = false

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (isExpanded && available.y < 0) {
                    isTopReached = false
                }

                return if (isTopReached && available.y < 0 && source == NestedScrollSource.UserInput) {
                    dispatchRawDelta(available.y)
                    available
                } else {
                    Offset.Zero
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!isTopReached) {
                    isTopReached = consumed.y == 0f && available.y > 0
                }

                return if (isTopReached && source == NestedScrollSource.UserInput) {
                    dispatchRawDelta(available.y)
                    available
                } else {
                    Offset.Zero
                }
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                return if (isTopReached) {
                    val velocity = -available.y
                    performFling(velocity, null)

                    available
                } else {
                    Velocity.Zero
                }
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                isTopReached = false
                return Velocity.Zero
            }
        }
}

/**
 * A spring that cannot jump. The animation clock runs in real time, so when one frame takes 150 ms (a heavy composition, a shader
 * built for the first time) the spring is evaluated 150 ms further on and the sheet skips most of its opening in one step: that
 * is what the eye reads as "patah-patah". Here the clock the spring is evaluated on advances by at most [MAX_STEP_NS] per frame,
 * so a long frame becomes a short pause and the motion goes on from where it was. The curve is the spring's own, nothing else changes.
 */
internal class FrameCappedSpec<T>(
    private val base: FiniteAnimationSpec<T>,
) : FiniteAnimationSpec<T> {
    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<T, V>): VectorizedFiniteAnimationSpec<V> =
        FrameCapped(base.vectorize(converter))

    private class FrameCapped<V : AnimationVector>(
        private val inner: VectorizedFiniteAnimationSpec<V>,
    ) : VectorizedFiniteAnimationSpec<V> {
        override val isInfinite: Boolean get() = false
        private var lastReal = -1L
        private var virtual = 0L

        private fun clock(real: Long): Long {
            if (real != lastReal) {
                if (lastReal >= 0L) virtual += minOf(real - lastReal, MAX_STEP_NS)
                lastReal = real
            }
            return virtual
        }

        // a little longer than the spring: the clock may lag behind the real time after a long frame
        override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long =
            inner.getDurationNanos(initialValue, targetValue, initialVelocity) + TAIL_NS

        override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
            inner.getValueFromNanos(clock(playTimeNanos), initialValue, targetValue, initialVelocity)

        override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
            inner.getVelocityFromNanos(clock(playTimeNanos), initialValue, targetValue, initialVelocity)

        override fun getEndVelocity(initialValue: V, targetValue: V, initialVelocity: V): V =
            inner.getEndVelocity(initialValue, targetValue, initialVelocity)
    }

    private companion object {
        const val MAX_STEP_NS = 32_000_000L
        const val TAIL_NS = 160_000_000L
    }
}

/** The full player's tap open/close: a critical spring (no bounce), a little faster than the default medium-low 400. */
private const val PremiumStiffness = 480f

const val expandedAnchor = 2
const val collapsedAnchor = 1
const val dismissedAnchor = 0

@Composable
fun rememberBottomSheetState(
    dismissedBound: Dp,
    expandedBound: Dp,
    collapsedBound: Dp = dismissedBound,
    initialAnchor: Int = dismissedAnchor,
): BottomSheetState {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    var previousAnchor by rememberSaveable {
        mutableIntStateOf(initialAnchor)
    }
    val animatable = remember {
        Animatable(0.dp, Dp.VectorConverter)
    }
    // shared by every state that the keyed remember below creates for a new set of bounds
    val adjusting = remember { mutableStateOf(false) }
    val adjustToken = remember { intArrayOf(0) }

    return remember(dismissedBound, expandedBound, collapsedBound, coroutineScope) {
        val initialValue = when (previousAnchor) {
            expandedAnchor -> expandedBound
            collapsedAnchor -> collapsedBound
            dismissedAnchor -> dismissedBound
            else -> error("Unknown BottomSheet anchor")
        }

        animatable.updateBounds(dismissedBound.coerceAtMost(expandedBound), expandedBound)
        // a resting collapsed sheet that follows a new collapsed bound is "adjusting", not opening
        val token = ++adjustToken[0]
        val resting = previousAnchor == collapsedAnchor
        coroutineScope.launch {
            // (a state write inside the composition itself is not allowed: it is made here, in the first step of the coroutine)
            if (resting) adjusting.value = true
            try {
                animatable.animateTo(initialValue, NavigationBarAnimationSpec)
            } finally {
                if (token == adjustToken[0]) adjusting.value = false
            }
        }

        BottomSheetState(
            draggableState = DraggableState { delta ->
                coroutineScope.launch {
                    animatable.snapTo(animatable.value - with(density) { delta.toDp() })
                }
            },
            onAnchorChanged = { previousAnchor = it },
            coroutineScope = coroutineScope,
            animatable = animatable,
            collapsedBound = collapsedBound,
            adjustingState = adjusting,
        )
    }
}
