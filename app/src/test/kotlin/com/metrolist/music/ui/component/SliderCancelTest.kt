/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * The wavy and the squiggly progress sliders end a drag in every way it can end. A drag that never ended left the
 * player's held slider position set, and the lyrics (which follow a held slider) stood still on it until the slider
 * was touched again.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w411dp-h860dp-xhdpi")
class SliderCancelTest {
    @get:Rule
    val rule = createComposeRule()

    private var finished = 0
    private val range = mutableStateOf(0f..100f)
    private val enabled = mutableStateOf(true)

    @Composable
    private fun Slider(squiggly: Boolean) {
        MetrolistTheme(darkTheme = true) {
            Box(Modifier.width(300.dp).height(48.dp).testTag("slider")) {
                if (squiggly) {
                    SquigglySlider(
                        value = 10f, onValueChange = {}, modifier = Modifier.fillMaxSize(), enabled = enabled.value,
                        valueRange = range.value, onValueChangeFinished = { finished++ }, isPlaying = false,
                    )
                } else {
                    WavySlider(
                        value = 10f, onValueChange = {}, modifier = Modifier.fillMaxSize(), valueRange = range.value,
                        onValueChangeFinished = { finished++ }, isPlaying = false, enabled = enabled.value,
                    )
                }
            }
        }
    }

    private fun show(squiggly: Boolean) {
        rule.mainClock.autoAdvance = false // the wave animates for ever
        rule.setContent { Slider(squiggly) }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun startDrag() {
        rule.onNodeWithTag("slider").performTouchInput {
            down(Offset(60f, centerY))
            moveBy(Offset(80f, 0f))
            moveBy(Offset(80f, 0f))
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun cancelledDragEnds(squiggly: Boolean) {
        show(squiggly)
        startDrag()
        assertEquals(0, finished)
        rule.onNodeWithTag("slider").performTouchInput { cancel() }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals("a cancelled drag must end", 1, finished)
    }

    private fun rangeChangeEnds(squiggly: Boolean) {
        show(squiggly)
        startDrag()
        range.value = 0f..200f // the song's length became known while the finger is down
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals("a drag cut by a new range must end", 1, finished)
    }

    private fun disablingEnds(squiggly: Boolean) {
        show(squiggly)
        startDrag()
        enabled.value = false // e.g. a Listen Together guest
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals("a drag cut by disabling the slider must end", 1, finished)
    }

    private fun releasedDragEndsOnce(squiggly: Boolean) {
        show(squiggly)
        startDrag()
        rule.onNodeWithTag("slider").performTouchInput { up() }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(1, finished)
    }

    @Test fun `wavy - a cancelled drag ends`() = cancelledDragEnds(false)

    @Test fun `wavy - a new range during a drag ends it`() = rangeChangeEnds(false)

    @Test fun `wavy - disabling during a drag ends it`() = disablingEnds(false)

    @Test fun `wavy - a released drag ends once`() = releasedDragEndsOnce(false)

    @Test fun `squiggly - a cancelled drag ends`() = cancelledDragEnds(true)

    @Test fun `squiggly - a new range during a drag ends it`() = rangeChangeEnds(true)

    @Test fun `squiggly - disabling during a drag ends it`() = disablingEnds(true)

    @Test fun `squiggly - a released drag ends once`() = releasedDragEndsOnce(true)
}
