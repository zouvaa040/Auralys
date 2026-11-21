/**
 * Auralys - the iOS rubber-band overscroll ("gulir memantul ala iOS").
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Only while Settings > Appearance > "iOS-style animations" is on (see [IosOverscrollHost], called from MetrolistTheme);
 * with the option off nothing changes and every list keeps the platform's stretch.
 *
 * What the listener feels, as on a UIScrollView:
 *  - dragging past the first or last row the content follows the finger with resistance (the displacement grows slower and
 *    slower, 55 percent curve, never more than 40 percent of the list);
 *  - dragging back the other way first brings the stretch back, and only then scrolls the content;
 *  - letting go springs it back with a low-damping spring (it overshoots a little), and a fling that arrives at the edge
 *    kicks the content past it by a small amount that grows with the speed.
 *
 * The effect is passive: it never consumes what the list could not scroll, so the nested scrolling of the parents (the top
 * bar, the player sheet, pull to refresh) gets exactly the same leftover as before. It only shows a displacement.
 * The displacement is drawn (a translate around drawContent, in the draw phase only): nothing recomposes or relays out per
 * frame, and the list's own touch handling does not see its own movement, so there is no feedback in the drag.
 *
 * API checked against the compose-foundation classes of the build (OverscrollEffect: applyToScroll, applyToFling,
 * isInProgress, node; OverscrollFactory: createOverscrollEffect, equals, hashCode).
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/** The UIScrollView rubber band as two pure functions (unit-testable). [dim] is the size of the list on that axis, in px. */
internal object IosRubber {
    const val C = 0.55f
    const val MAX_FRACTION = 0.4f

    /** How far the content is shown displaced after the finger travelled [raw] px past the edge. */
    fun offsetOf(raw: Float, dim: Float): Float {
        if (raw <= 0f || dim <= 0f) return 0f
        return ((1f - 1f / (raw * C / dim + 1f)) * dim).coerceAtMost(dim * MAX_FRACTION)
    }

    /** The finger travel that gives the displacement [offset] (its sign is ignored). The inverse of [offsetOf]. */
    fun rawOf(offset: Float, dim: Float): Float {
        val shown = abs(offset)
        if (shown <= 0f || dim <= 0f) return 0f
        val y = (shown / dim).coerceIn(0f, 0.95f)
        return dim / C * (1f / (1f - y) - 1f)
    }
}

internal class IosOverscrollEffect : OverscrollEffect {
    private var offsetX by mutableFloatStateOf(0f)
    private var offsetY by mutableFloatStateOf(0f)

    /** Size of the list in px, written by the draw node (a plain field: it is only read while scrolling). */
    var viewport: IntSize = IntSize.Zero

    /** What the draw node shows; reading them in draw() subscribes the draw phase, so a change only redraws. */
    val shownX: Float get() = offsetX
    val shownY: Float get() = offsetY

    override val isInProgress: Boolean
        get() = abs(offsetX) > 0.5f || abs(offsetY) > 0.5f

    override val node: DelegatableNode = IosOverscrollNode(this)

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        val drag = source == NestedScrollSource.UserInput
        val dimX = viewport.width.toFloat()
        val dimY = viewport.height.toFloat()

        // 1) a drag back the other way first brings the stretch back (the finger travel that did it is consumed)
        var relaxedX = 0f
        var relaxedY = 0f
        if (drag) {
            if (offsetY != 0f && delta.y != 0f && sign(delta.y) != sign(offsetY)) {
                val raw = IosRubber.rawOf(offsetY, dimY)
                val used = min(raw, abs(delta.y))
                offsetY = sign(offsetY) * IosRubber.offsetOf(raw - used, dimY)
                relaxedY = sign(delta.y) * used
            }
            if (offsetX != 0f && delta.x != 0f && sign(delta.x) != sign(offsetX)) {
                val raw = IosRubber.rawOf(offsetX, dimX)
                val used = min(raw, abs(delta.x))
                offsetX = sign(offsetX) * IosRubber.offsetOf(raw - used, dimX)
                relaxedX = sign(delta.x) * used
            }
        }

        // 2) the list scrolls what it can
        val left = Offset(delta.x - relaxedX, delta.y - relaxedY)
        val scrolled = performScroll(left)

        // 3) what it could not scroll is shown as a stretch (a drag only; a fling is handled in applyToFling). It is NOT consumed.
        if (drag) {
            val rest = left - scrolled
            if (abs(rest.y) > 0.5f && dimY > 0f) offsetY = stretch(offsetY, rest.y, dimY)
            if (abs(rest.x) > 0.5f && dimX > 0f) offsetX = stretch(offsetX, rest.x, dimX)
        }
        return Offset(relaxedX, relaxedY) + scrolled
    }

    private fun stretch(current: Float, rest: Float, dim: Float): Float {
        val base = if (current != 0f && sign(current) == sign(rest)) IosRubber.rawOf(current, dim) else 0f
        return sign(rest) * IosRubber.offsetOf(base + abs(rest), dim)
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        // the list flings (and the parents get their share); what nobody could use arrives at the edge as a kick
        val consumed = performFling(velocity)
        springBack(velocity - consumed)
    }

    private suspend fun springBack(remaining: Velocity) {
        val kickX = kick(remaining.x, viewport.width.toFloat())
        val kickY = kick(remaining.y, viewport.height.toFloat())
        val moveX = offsetX != 0f || kickX != 0f
        val moveY = offsetY != 0f || kickY != 0f
        if (!moveX && !moveY) return
        coroutineScope {
            if (moveX) {
                launch {
                    animate(initialValue = offsetX, targetValue = 0f, initialVelocity = kickX, animationSpec = SpringBack) { value, _ -> offsetX = value }
                }
            }
            if (moveY) {
                launch {
                    animate(initialValue = offsetY, targetValue = 0f, initialVelocity = kickY, animationSpec = SpringBack) { value, _ -> offsetY = value }
                }
            }
        }
    }

    /** Half of the speed that was left, at most 3 list-sizes per second (the spring then overshoots by about 10 percent of the list). */
    private fun kick(velocity: Float, dim: Float): Float =
        if (abs(velocity) < 40f || dim <= 0f) 0f else (velocity * 0.5f).coerceIn(-3f * dim, 3f * dim)

    private companion object {
        val SpringBack = spring<Float>(dampingRatio = 0.62f, stiffness = 260f)
    }
}

/** Draws the list's content moved by the stretch. Only the drawing moves: the touch handling of the list stays where it is. */
private class IosOverscrollNode(
    private val effect: IosOverscrollEffect,
) : Modifier.Node(),
    DrawModifierNode {
    override fun ContentDrawScope.draw() {
        effect.viewport = IntSize(size.width.roundToInt(), size.height.roundToInt())
        val x = effect.shownX
        val y = effect.shownY
        if (x == 0f && y == 0f) {
            drawContent()
        } else {
            translate(left = x, top = y) { this@draw.drawContent() }
        }
    }
}

internal object IosOverscrollFactory : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = IosOverscrollEffect()

    // one factory for the whole app: equal to itself only (a different factory would re-create every effect)
    override fun hashCode(): Int = 0x105C0DE

    override fun equals(other: Any?): Boolean = other === this
}

/**
 * Gives every scrollable below it the iOS rubber band while [enabled]; otherwise it passes on whatever factory was there
 * (the platform's stretch), so the option can be switched at any time without the content below being rebuilt.
 */
@Composable
fun IosOverscrollHost(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val outer = LocalOverscrollFactory.current
    CompositionLocalProvider(
        LocalOverscrollFactory provides (if (enabled) IosOverscrollFactory else outer),
        content = content,
    )
}
