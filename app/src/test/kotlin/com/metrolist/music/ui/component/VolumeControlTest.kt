/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrolist.music.ui.theme.MetrolistTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The shared volume control through real touches (Robolectric): semantics, tap, drag, the mute button, accessibility and
 * keyboard actions, and that dragging does not recompose what is around the bar.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w411dp-h860dp-xhdpi")
class VolumeControlTest {
    @get:Rule
    val rule = createComposeRule()

    private val level = mutableFloatStateOf(0.4f)
    private val emitted = mutableListOf<Float>()
    private var finished = 0
    private var parentCompositions = 0
    private val memory = VolumeMuteMemory()

    private val isSlider = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Composable
    private fun Parent(enabled: Boolean) {
        // does not read the level: it must not recompose while the bar is dragged
        SideEffect { parentCompositions++ }
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            VolumeControl(
                value = { level.floatValue },
                onValueChange = {
                    level.floatValue = it
                    emitted += it
                },
                enabled = enabled,
                memory = memory,
                onValueChangeFinished = { finished++ },
            )
        }
    }

    private fun show(enabled: Boolean = true, dark: Boolean = false) {
        rule.setContent {
            MetrolistTheme(darkTheme = dark) {
                Surface { Parent(enabled) }
            }
        }
        rule.waitForIdle()
    }

    private fun pad() = with(rule.density) { 11.dp.toPx() }

    // ------------------------------------------------------------ what it says

    @Test
    fun `the bar tells a screen reader the volume and its range`() {
        show()
        val node = rule.onNode(isSlider)
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Volume 40%"))
        val info = node.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0.4f, info.current, 1e-4f)
        assertEquals(0f, info.range.start, 0f)
        assertEquals(1f, info.range.endInclusive, 0f)
    }

    @Test
    fun `the state follows the level when it is changed from outside`() {
        show()
        level.floatValue = 0.7f
        rule.waitForIdle()
        rule.onNode(isSlider).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Volume 70%"))
        assertTrue("an outside change is not an emitted change", emitted.isEmpty())
    }

    @Test
    fun `the touch height is at least 48 dp for the bar and the mute button`() {
        show()
        rule.onNode(isSlider).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Mute").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    // ------------------------------------------------------------ touches

    @Test
    fun `a tap on the bar sets that level`() {
        show()
        rule.onNode(isSlider).performTouchInput {
            val start = pad()
            val end = width - pad()
            click(Offset(start + 0.75f * (end - start), centerY))
        }
        rule.waitForIdle()
        assertEquals(0.75f, level.floatValue, 0.02f)
        assertEquals(1, finished)
    }

    @Test
    fun `a tap at the far ends gives exactly 0 and 100`() {
        show()
        rule.onNode(isSlider).performTouchInput { click(Offset(1f, centerY)) }
        rule.waitForIdle()
        assertEquals(0f, level.floatValue, 0f)
        rule.onNode(isSlider).performTouchInput { click(Offset(width - 1f, centerY)) }
        rule.waitForIdle()
        assertEquals(1f, level.floatValue, 0f)
    }

    @Test
    fun `a drag follows the finger, never goes back, and ends once`() {
        show()
        level.floatValue = 0.2f
        rule.waitForIdle()
        rule.onNode(isSlider).performTouchInput {
            val start = pad()
            val end = width - pad()
            swipe(Offset(start + 0.2f * (end - start), centerY), Offset(start + 0.8f * (end - start), centerY), durationMillis = 400)
        }
        rule.waitForIdle()
        assertEquals(0.8f, level.floatValue, 0.03f)
        assertTrue("the drag emitted several levels", emitted.size > 3)
        assertEquals("rising all the way", emitted.sorted(), emitted)
        assertTrue("every level is on the bar", emitted.all { it in 0f..1f })
        assertEquals(1, finished)
    }

    @Test
    fun `dragging does not recompose what is around the bar`() {
        show()
        val before = parentCompositions
        rule.onNode(isSlider).performTouchInput {
            val start = pad()
            val end = width - pad()
            swipe(Offset(start + 0.1f * (end - start), centerY), Offset(start + 0.9f * (end - start), centerY), durationMillis = 400)
        }
        rule.waitForIdle()
        assertTrue("it moved", level.floatValue > 0.8f)
        assertEquals("the parent was recomposed by the drag", before, parentCompositions)
    }

    @Test
    fun `the thumb clicks onto 50 percent`() {
        show()
        level.floatValue = 0.3f
        rule.waitForIdle()
        rule.onNode(isSlider).performTouchInput {
            val start = pad()
            val end = width - pad()
            click(Offset(start + 0.505f * (end - start), centerY))
        }
        rule.waitForIdle()
        assertEquals(0.5f, level.floatValue, 0f)
    }

    // ------------------------------------------------------------ the mute button

    @Test
    fun `the left icon mutes and a second tap restores the level before`() {
        show()
        rule.onNodeWithContentDescription("Mute").performClick()
        rule.waitForIdle()
        assertEquals(0f, level.floatValue, 0f)
        rule.onNode(isSlider).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Volume 0%"))
        // the button now offers the opposite
        rule.onNodeWithContentDescription("Unmute").performClick()
        rule.waitForIdle()
        assertEquals(0.4f, level.floatValue, 1e-4f)
        rule.onNode(isSlider).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Volume 40%"))
        assertEquals(listOf(0f, 0.4f), emitted)
    }

    @Test
    fun `mute after a drag restores the dragged level, not the first one`() {
        show()
        rule.onNode(isSlider).performTouchInput {
            val start = pad()
            val end = width - pad()
            click(Offset(start + 0.8f * (end - start), centerY))
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Mute").performClick()
        rule.waitForIdle()
        assertEquals(0f, level.floatValue, 0f)
        rule.onNodeWithContentDescription("Unmute").performClick()
        rule.waitForIdle()
        assertEquals(0.8f, level.floatValue, 0.02f)
    }

    // ------------------------------------------------------------ accessibility and keyboard

    @Test
    fun `a screen reader can set the progress`() {
        show()
        rule.onNode(isSlider).performSemanticsAction(SemanticsActions.SetProgress) { it(0.9f) }
        rule.waitForIdle()
        assertEquals(0.9f, level.floatValue, 1e-4f)
        rule.onNode(isSlider).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Volume 90%"))
    }

    @Test
    fun `arrow keys step by five percent`() {
        show()
        rule.onNode(isSlider).requestFocus()
        rule.onNode(isSlider).performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        assertEquals(0.45f, level.floatValue, 1e-4f)
        rule.onNode(isSlider).performKeyInput { pressKey(Key.DirectionLeft) }
        rule.onNode(isSlider).performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        assertEquals(0.35f, level.floatValue, 1e-4f)
    }

    // ------------------------------------------------------------ disabled

    @Test
    fun `a disabled control ignores touches and offers no progress action`() {
        show(enabled = false)
        rule.onNode(isSlider).performTouchInput { click(Offset(width - 5f, centerY)) }
        rule.waitForIdle()
        assertEquals(0.4f, level.floatValue, 0f)
        assertTrue(emitted.isEmpty())
        assertNull(rule.onNode(isSlider).fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress))
    }

    @Test
    fun `an enabled control offers the progress action`() {
        show()
        assertNotNull(rule.onNode(isSlider).fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress))
        assertFalse(abs(level.floatValue - 0.4f) > 1e-4f)
    }

    @Test
    fun `the dark sheet renders too`() {
        show(dark = true)
        rule.onNode(isSlider).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Volume 40%"))
    }
}
