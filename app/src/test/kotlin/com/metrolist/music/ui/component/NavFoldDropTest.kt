/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import com.metrolist.music.constants.NavigationBarHeight
import com.metrolist.music.constants.SlimNavBarHeight
import com.metrolist.music.ui.component.motion.NavFoldGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

/** The drop of the floating buttons when the Liquid Glass nav bar folds (same geometry as MainActivity / MiniPlayer / Player). */
class NavFoldDropTest {
    // MainActivity: navFold.miniShiftDp = NavFoldGeometry.miniShiftDp(MiniPlayerHeight, MiniPlayerBottomSpacing, pill)
    // (4.3.4: spacing + mini / 2 + bar top padding + pill / 2; the old formula left the capsule 4 / 8 dp too high)
    private fun miniShift(pill: Float) = NavFoldGeometry.miniShiftDp(64f, 8f, pill)

    @Test
    fun `both bars drop the sheet 78 dp, onto the bar's top edge`() {
        assertEquals(78f, navFoldDropDp(miniShift(72f), 72f, 64f), 0.0001f)
        assertEquals(78f, navFoldDropDp(miniShift(60f), 60f, 64f), 0.0001f)
    }

    @Test
    fun `a button above the mini player keeps its gap to the folded capsule`() {
        // the nav slots of MainActivity: NavigationBarHeight (80) and SlimNavBarHeight (68 since finding 18 of the 2026-11-12 review)
        for ((navPadding, pill) in listOf(NavigationBarHeight.value to 72f, SlimNavBarHeight.value to 60f)) {
            val drop = navFoldDropDp(miniShift(pill), pill, 64f)
            // measured upwards from the bottom inset: the capsule is top-aligned in the 64 dp box, so its top edge moves down
            // by exactly the drop; the button sits 16 dp above the box and moves down by the same amount
            val boxTop = navPadding + 8f + 64f
            val capsuleTopAfter = boxTop - drop
            val fabBottomAfter = boxTop + 16f - drop
            assertEquals(16f, fabBottomAfter - capsuleTopAfter, 0.0001f)
            // and the capsule top is the bar's top edge (BAR_TOP_DP below the nav slot's top)
            assertEquals(navPadding - NavFoldGeometry.BAR_TOP_DP, capsuleTopAfter, 0.0001f)
        }
    }

    @Test
    fun `without a pill taller than the box the drop is the plain shift`() {
        assertEquals(30f, navFoldDropDp(30f, 64f, 64f), 0.0001f)
    }
}
