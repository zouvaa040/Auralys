/**
 * Auralys - the iOS "large title" of a main page.
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The page's title is shown LARGE (about 34 sp, bold) as the first row of its scrolling content. It scrolls away with the content,
 * and the small title of the top bar waits: it is hidden while the large one is on screen and fades in as the large one slides
 * under the bar. Nothing is stored per page and nothing recomposes while scrolling: the header measures where it is on the
 * screen after every layout and writes two numbers ([LargeTitleState.collapse], [LargeTitleState.pull]) that are only read
 * inside graphics layers.
 *
 * Two parts:
 *  - [LargeTitleHeader]: the large title row, as the first item of a list / the first child of a scrolling column. It does
 *    nothing at all while there is no [LargeTitleState] around ([LocalLargeTitle] is null and none was passed), so a page
 *    that carries it looks exactly as before until its screen provides a state.
 *  - [largeTitleBarTitle]: put on the small title of the top bar of that screen.
 *
 * A screen with its own top bar (Statistik) creates a [LargeTitleState] and passes it to both. The shared top bar of the main
 * tabs (MainActivity) provides one state through [LocalLargeTitle] and puts [largeTitleBarTitle] on its title; the pages of
 * the tabs (Pustaka, Kamu) then pick it up by themselves.
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.ui.theme.HorizonSpace

/** Height of the large title row: the 34 sp line and a little air above and below it. */
val LargeTitleHeight = 56.dp

/**
 * 0 while the large title is fully on screen, 1 once it has slid under the bar. It starts to go when 30 percent of the row is
 * under the bar and is gone when 92 percent is. [scrolledPx] is how far the row has moved up past its resting place (negative
 * while the page is pulled down); [heightPx] is the row's height. Plain numbers so it is unit-testable.
 */
fun largeTitleCollapse(
    scrolledPx: Float,
    heightPx: Float,
): Float {
    if (heightPx <= 0f) return 1f
    return ((scrolledPx - 0.30f * heightPx) / (0.62f * heightPx)).coerceIn(0f, 1f)
}

/** How far the page is pulled down past the top, 0..1 of the row's height (0 at rest and while scrolled). */
fun largeTitlePull(
    scrolledPx: Float,
    heightPx: Float,
): Float {
    if (heightPx <= 0f) return 0f
    return (-scrolledPx / heightPx).coerceIn(0f, 1f)
}

@Stable
class LargeTitleState {
    /** A page with a large title is on screen: the small title of the bar waits for it. Written by [LargeTitleHeader]. */
    var present by mutableStateOf(false)
        internal set

    /** 0 = the large title is fully shown, 1 = it has collapsed into the bar. Read in graphics layers only. */
    var collapse by mutableFloatStateOf(1f)
        internal set

    /** 0..1: the page is pulled down past its top (the large title swells a little with iOS animations). Read in graphics layers only. */
    var pull by mutableFloatStateOf(0f)
        internal set

    // Two headers can be alive at once while one page fades into the other (Pustaka -> Kamu): the newest one drives the state, and
    // when it goes the older one takes over with the numbers it measured last, so the leaving page cannot reset the arriving one.
    private val owners = ArrayList<Any>()
    private val latest = HashMap<Any, FloatArray>()

    internal fun register(owner: Any) {
        owners.remove(owner)
        owners.add(owner)
        latest[owner] = floatArrayOf(0f, 0f)
        // shown before the first draw: the small title must not flash for the first frame
        present = true
        collapse = 0f
        pull = 0f
    }

    internal fun unregister(owner: Any) {
        owners.remove(owner)
        latest.remove(owner)
        val next = owners.lastOrNull()
        if (next == null) {
            // no large title on screen any more: the bar's own title shows
            present = false
            collapse = 1f
            pull = 0f
        } else {
            latest[next]?.let {
                collapse = it[0]
                pull = it[1]
            }
        }
    }

    internal fun report(owner: Any, collapse: Float, pull: Float) {
        latest[owner]?.let {
            it[0] = collapse
            it[1] = pull
        }
        if (owners.lastOrNull() === owner) {
            if (this.collapse != collapse) this.collapse = collapse
            if (this.pull != pull) this.pull = pull
        }
    }
}

/** Provided by the shared top bar of the main tabs; null = the pages show no large title (and look as before). */
val LocalLargeTitle = staticCompositionLocalOf<LargeTitleState?> { null }

/**
 * The large title row. Put it first in the page's scrolling content (as an item of a lazy list, or the first child of a
 * scrolling column), directly under the space the page keeps for the top bar. Does nothing without a [state].
 */
@Composable
fun LargeTitleHeader(
    title: String,
    modifier: Modifier = Modifier,
    state: LargeTitleState? = LocalLargeTitle.current,
) {
    if (state == null) return
    val density = LocalDensity.current
    val heightPx = with(density) { LargeTitleHeight.toPx() }
    // where the bar ends: the page keeps exactly this much room above its content, so the row rests at this height of the window
    val barBottomPx = LocalPlayerAwareWindowInsets.current.getTop(density).toFloat()
    val ios = LocalIosMotion.current

    // the row left the screen (scrolled far away, or the page was left): the bar's own title shows
    val token = remember { Any() }
    DisposableEffect(state) {
        state.register(token)
        onDispose { state.unregister(token) }
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(LargeTitleHeight)
                .onGloballyPositioned { coordinates ->
                    val scrolled = barBottomPx - coordinates.positionInWindow().y
                    state.report(token, largeTitleCollapse(scrolled, heightPx), largeTitlePull(scrolled, heightPx))
                }.graphicsLayer {
                    alpha = 1f - state.collapse
                    if (ios) {
                        val swell = 1f + 0.04f * state.pull
                        scaleX = swell
                        scaleY = swell
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                }.padding(horizontal = HorizonSpace.Gutter),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = title,
            style =
                MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * For the small title of the screen's top bar: hidden while this page's large title is on screen, fading in as it collapses.
 * Without a page that has a large title (no [state], or [LargeTitleState.present] false) the title is simply shown.
 */
fun Modifier.largeTitleBarTitle(state: LargeTitleState?): Modifier =
    if (state == null) {
        this
    } else {
        this.graphicsLayer { alpha = if (state.present) state.collapse else 1f }
    }

/** Same, with the state of the shared bar ([LocalLargeTitle]). */
@Composable
fun Modifier.largeTitleBarTitle(): Modifier = largeTitleBarTitle(LocalLargeTitle.current)
