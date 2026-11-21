/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Scroll-direction tracking for the Liquid Glass "fold on scroll" navigation bar.
 * Idea adapted from Convx (com.convx.music.ui.component.floatingtabbar.FloatingTabBarScrollConnection, GPL-3.0).
 */

package com.metrolist.music.ui.component.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Folds the bar after the content has really scrolled down by [foldThresholdPx] and unfolds it after it
 * has scrolled back up by [unfoldThresholdPx] (or the user pulls past the top). The running distance is a
 * plain field and resets whenever the direction changes, so small back-and-forth jiggles never toggle it.
 *
 * [folded] is the only snapshot state and it changes only on a threshold crossing: scrolling itself
 * causes no recomposition. The connection never consumes anything, so scrolling is not affected.
 */
@Stable
class NavBarFoldState internal constructor(
    private val foldThresholdPx: Float,
    private val unfoldThresholdPx: Float,
) {
    var folded by mutableStateOf(false)
        private set

    /**
     * 0 = bar open, 1 = folded to the pill; mirrors the bar's own animation so the mini player can follow it.
     * Only ever read inside layout/graphics lambdas, so it never recomposes anything.
     */
    var progress by mutableFloatStateOf(0f)

    /** Set while the bar is not on screen (settings and other sub-pages), so the mini player ignores [progress]. */
    var hideFollow by mutableStateOf(false)

    /** [progress] as the mini player should follow it. */
    val followProgress: Float get() = if (hideFollow) 0f else progress

    /** Height of the folded pill (dp): the mini player takes the same size so the two line up. Set by the host. */
    internal var pillSizeDp: Float = 72f

    /** How far (dp) the mini player must drop to sit level with the folded pill. Set by the host, plain field. */
    internal var miniShiftDp: Float = 0f

    /** Plain fields: written from composition, read from scroll callbacks. */
    internal var enabled: Boolean = false
    internal var canFold: () -> Boolean = { true }

    private var travelled = 0f

    fun expand() {
        travelled = 0f
        if (folded) folded = false
    }

    private fun track(dy: Float) {
        if ((travelled > 0f && dy < 0f) || (travelled < 0f && dy > 0f)) travelled = 0f
        travelled += dy
        if (!folded && travelled <= -foldThresholdPx) {
            travelled = 0f
            if (canFold()) folded = true
        } else if (folded && travelled >= unfoldThresholdPx) {
            travelled = 0f
            folded = false
        }
    }

    val connection: NestedScrollConnection =
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!enabled) return Offset.Zero
                val dy = consumed.y
                if (dy != 0f) {
                    track(dy)
                } else if (available.y > 0f && folded) {
                    // pulling down at the top of a list: always bring the bar back
                    expand()
                }
                return Offset.Zero
            }
        }
}

/**
 * Geometry of the fold, in dp, shared by the bar (AppNavigation), the host (MainActivity), the mini player and the sheet
 * drop (Player / BottomSheet), so the folded mini player is drawn, and tapped, exactly beside the pill. Plain functions
 * returning floats: called from layout and graphics-layer lambdas, nothing is allocated per frame.
 *
 * Layout it describes: the nav bar slot is [navPadding] tall above the system inset; the bar (and the folded pill) sit
 * [BAR_TOP_DP] below the slot's top edge, [pill] tall. The collapsed mini player is [miniHeight] tall and floats
 * [miniSpacing] above the slot; while folding its capsule stays top-aligned in its box and grows to [pill].
 */
object NavFoldGeometry {
    /** Gap between the top of the nav bar slot and the bar / the folded pill (AppNavigation's `padding(top = 6.dp)`). */
    const val BAR_TOP_DP = 6f

    /** Side margin of the bar and of the folded pill. */
    const val BAR_SIDE_DP = 22f

    /** Gap between the folded pill and the folded mini player. */
    const val PILL_GAP_DP = 10f

    /** Side margin of the mini player when the bar is open. */
    const val MINI_SIDE_DP = 12f

    /** Bar (and pill) height: slim 60, normal 72. */
    fun pillSizeDp(slim: Boolean): Float = if (slim) 60f else 72f

    /**
     * How far the centre of the mini player moves down to the centre of the folded pill. Measured from the bottom of the
     * screen: the mini player's centre is at inset + navPadding + spacing + mini / 2, the pill's at
     * inset + navPadding - BAR_TOP - pill / 2, so navPadding cancels out. (Before 4.3.4 this was
     * navPadding + spacing + mini / 2 - (BAR_TOP + pill / 2), which is only right when navPadding = 2 * BAR_TOP + pill:
     * the folded mini player sat 4 dp too high next to the normal bar and 8 dp too high next to the slim one.)
     */
    fun miniShiftDp(miniHeightDp: Float, miniSpacingDp: Float, pillDp: Float): Float =
        miniSpacingDp + miniHeightDp / 2f + BAR_TOP_DP + pillDp / 2f

    /** Start inset of the mini player capsule at fold progress [p]: from its own margin to just past the pill. */
    fun miniStartDp(p: Float, pillDp: Float): Float =
        MINI_SIDE_DP + (BAR_SIDE_DP + pillDp + PILL_GAP_DP - MINI_SIDE_DP) * p

    /** End inset of the mini player capsule at fold progress [p]: from its own margin to the bar's margin. */
    fun miniEndDp(p: Float): Float = MINI_SIDE_DP + (BAR_SIDE_DP - MINI_SIDE_DP) * p

    /** Height of the mini player capsule at fold progress [p]: from its own height to the pill's. */
    fun miniHeightDp(p: Float, pillDp: Float, miniHeightDp: Float): Float = miniHeightDp + (pillDp - miniHeightDp) * p
}

/** The fold state of the navigation bar, for things that react to it (the mini player). Null when there is none. */
val LocalNavFold = staticCompositionLocalOf<NavBarFoldState?> { null }

@Composable
fun rememberNavBarFoldState(
    enabled: Boolean,
    canFold: () -> Boolean,
): NavBarFoldState {
    val density = LocalDensity.current
    val state =
        remember(density) {
            with(density) { NavBarFoldState(foldThresholdPx = 56.dp.toPx(), unfoldThresholdPx = 28.dp.toPx()) }
        }
    state.enabled = enabled
    state.canFold = canFold
    LaunchedEffect(enabled) { if (!enabled) state.expand() }
    return state
}
