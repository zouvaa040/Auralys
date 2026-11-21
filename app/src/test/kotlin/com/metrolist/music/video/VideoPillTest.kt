package com.metrolist.music.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrolist.music.ui.theme.MetrolistTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Audio | Video pill is drawn 36 dp tall, but each half has a 48 dp touch area (REVIEW-2026-11-07 F12): a tap a few dp
 * above the drawn capsule still picks the half under it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w411dp-h860dp-xhdpi")
class VideoPillTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(
        loading: Boolean = false,
        onSelect: (Int) -> Unit,
    ) {
        rule.setContent {
            MetrolistTheme(darkTheme = true) {
                Box(Modifier.size(300.dp, 200.dp), contentAlignment = Alignment.Center) {
                    VideoPillContent(selected = 0, onSelect = onSelect, overCover = true, loading = loading)
                }
            }
        }
    }

    private val spinner =
        SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)

    @Test
    fun `the Video half shows a tiny spinner while the video is searched for`() {
        // the spinner never stops animating: the test clock is stepped by hand
        rule.mainClock.autoAdvance = false
        show(loading = true) {}
        rule.mainClock.advanceTimeByFrame()
        rule.onNode(spinner).assertExists()
    }

    @Test
    fun `no spinner when nothing is loading`() {
        show {}
        rule.onNode(spinner).assertDoesNotExist()
    }

    @Test
    fun `both halves are at least 48 dp tall`() {
        show {}
        val halves = rule.onAllNodes(isSelectable())
        halves[0].assertHeightIsAtLeast(48.dp)
        halves[1].assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `a tap above the drawn capsule, inside the touch area, picks the half under it`() {
        var picked = -1
        show { picked = it }
        // 3 dp from the top of the 48 dp area is 3 dp above the 36 dp capsule (it starts 6 dp down)
        rule.onAllNodes(isSelectable())[1].performTouchInput { click(Offset(width / 2f, 3.dp.toPx())) }
        rule.waitForIdle()
        assertEquals(1, picked)
    }

    @Test
    fun `a tap below the capsule picks the half too`() {
        var picked = -1
        show { picked = it }
        rule.onAllNodes(isSelectable())[1].performTouchInput { click(Offset(width / 2f, height - 3.dp.toPx())) }
        rule.waitForIdle()
        assertEquals(1, picked)
    }
}
