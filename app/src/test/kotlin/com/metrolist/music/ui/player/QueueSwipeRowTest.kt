/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.down
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrolist.music.ui.player.QueueSwipeLogic.RightAction
import com.metrolist.music.ui.theme.MetrolistTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [QueueSwipeRow] driven with real touch input under Robolectric: the callbacks fire once, the row springs back after
 * "play next", the disabled states do nothing, the haptics come at the threshold and at the commit.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w411dp-h860dp-xhdpi")
class QueueSwipeRowTest {
    @get:Rule
    val rule = createComposeRule()

    private class Haptics : HapticFeedback {
        val log = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            log += hapticFeedbackType
        }

        fun count(type: HapticFeedbackType) = log.count { it == type }
    }

    private val haptics = Haptics()
    private var playNext = 0
    private var removed = 0
    private var taps = 0
    private var hints = 0
    private val rejected = mutableListOf<RightAction>()
    private val rightState = mutableStateOf(RightAction.PlayNext)
    private val leftState = mutableStateOf(true)
    private val lockedState = mutableStateOf(false)

    private fun show(
        right: RightAction = RightAction.PlayNext,
        left: Boolean = true,
        locked: Boolean = false,
    ) {
        rightState.value = right
        leftState.value = left
        lockedState.value = locked
        rule.setContent {
            MetrolistTheme(darkTheme = false) {
                CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                    QueueSwipeRow(
                        rightAction = rightState.value,
                        leftEnabled = leftState.value,
                        onPlayNext = { playNext++ },
                        onRemove = { removed++ },
                        onRightRejected = { rejected += it },
                        lockedHint = lockedState.value,
                        onLockedHint = { hints++ },
                        modifier = Modifier.testTag("swipe"),
                    ) {
                        Box(Modifier.fillMaxWidth().height(64.dp).testTag("content").clickable { taps++ })
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /** Where the row's left edge is, in dp (0 at rest). */
    private fun contentLeft(): Float = rule.onNodeWithTag("content").getUnclippedBoundsInRoot().left.value

    private fun assertNothingHappened() {
        assertEquals(0, playNext)
        assertEquals(0, removed)
        assertEquals(0, hints)
        assertEquals(emptyList<RightAction>(), rejected)
    }

    private fun widthPx(): Float = rule.onNodeWithTag("swipe").fetchSemanticsNode().size.width.toFloat()

    // ------------------------------------------------------------------------------------------------- play next

    @Test
    fun `a swipe to the right plays next once and the row comes back to rest`() {
        show()
        rule.onNodeWithTag("swipe").performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertEquals(1, playNext)
        assertEquals(0, removed)
        assertEquals(emptyList<RightAction>(), rejected)
        assertEquals(0f, contentLeft(), 0.5f)
        assertEquals(1, haptics.count(HapticFeedbackType.SegmentTick))
        assertEquals(1, haptics.count(HapticFeedbackType.LongPress))
    }

    @Test
    fun `the row follows the finger, ticks once at the threshold and commits with a stronger haptic`() {
        show()
        val w = widthPx()
        rule.onNodeWithTag("swipe").performTouchInput {
            down(Offset(10f, centerY))
            moveBy(Offset(100f, 0f), delayMillis = 100)
        }
        rule.waitForIdle()
        assertTrue("row moved with the finger: ${contentLeft()}", contentLeft() > 20f)
        assertEquals("no tick before the threshold", 0, haptics.log.size)

        rule.onNodeWithTag("swipe").performTouchInput { moveBy(Offset(0.45f * w, 0f), delayMillis = 100) }
        rule.waitForIdle()
        assertEquals("one light tick at the threshold", listOf(HapticFeedbackType.SegmentTick), haptics.log)
        assertEquals("nothing runs while the finger is down", 0, playNext)

        rule.onNodeWithTag("swipe").performTouchInput { moveBy(Offset(10f, 0f), delayMillis = 50) }
        rule.waitForIdle()
        assertEquals("still one tick", 1, haptics.count(HapticFeedbackType.SegmentTick))

        rule.onNodeWithTag("swipe").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(1, playNext)
        assertEquals(listOf(HapticFeedbackType.SegmentTick, HapticFeedbackType.LongPress), haptics.log)
        assertEquals(0f, contentLeft(), 0.5f)
    }

    @Test
    fun `a slow short drag springs back and does nothing`() {
        show()
        val w = widthPx()
        rule.onNodeWithTag("swipe").performTouchInput {
            down(Offset(10f, centerY))
            moveBy(Offset(0.15f * w, 0f), delayMillis = 600)
            up()
        }
        rule.waitForIdle()
        assertNothingHappened()
        assertEquals(0f, contentLeft(), 0.5f)
        assertTrue(haptics.log.isEmpty())
    }

    @Test
    fun `between 20 and 40 percent a slow release does not commit`() {
        show()
        val w = widthPx()
        rule.onNodeWithTag("swipe").performTouchInput {
            down(Offset(10f, centerY))
            moveBy(Offset(0.32f * w, 0f), delayMillis = 800)
            up()
        }
        rule.waitForIdle()
        assertNothingHappened()
        assertEquals(0f, contentLeft(), 0.5f)
    }

    @Test
    fun `between 20 and 40 percent a quick flick commits`() {
        show()
        val w = widthPx()
        rule.onNodeWithTag("swipe").performTouchInput {
            down(Offset(10f, centerY))
            // a flick is many quick move events (a single one would give the velocity tracker nothing to measure)
            repeat(4) { moveBy(Offset(0.08f * w, 0f), delayMillis = 8) }
            up()
        }
        rule.waitForIdle()
        assertEquals(1, playNext)
        assertEquals(0f, contentLeft(), 0.5f)
    }

    @Test
    fun `two swipes in a row play next twice`() {
        show()
        repeat(2) {
            rule.onNodeWithTag("swipe").performTouchInput { swipeRight() }
            rule.waitForIdle()
        }
        assertEquals(2, playNext)
        assertEquals(0f, contentLeft(), 0.5f)
    }

    // -------------------------------------------------------------------------------------------------- remove

    @Test
    fun `a swipe to the left removes once and brings the row back when nothing was removed`() {
        show()
        rule.onNodeWithTag("swipe").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(1, removed)
        assertEquals(0, playNext)
        assertEquals(1, haptics.count(HapticFeedbackType.SegmentTick))
        assertEquals(1, haptics.count(HapticFeedbackType.LongPress))
        // the callback of this test does not remove the row: after the grace period it is back
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(0f, contentLeft(), 0.5f)
        assertEquals(1, removed)
    }

    @Test
    fun `a left drag below the threshold removes nothing`() {
        show()
        val w = widthPx()
        rule.onNodeWithTag("swipe").performTouchInput {
            down(Offset(width - 10f, centerY))
            moveBy(Offset(-0.25f * w, 0f), delayMillis = 700)
            up()
        }
        rule.waitForIdle()
        assertNothingHappened()
        assertEquals(0f, contentLeft(), 0.5f)
    }

    // ------------------------------------------------------------------------- rows where the right swipe is a note

    @Test
    fun `a swipe to the right on the playing song does nothing but say so`() {
        show(right = RightAction.IsCurrent)
        rule.onNodeWithTag("swipe").performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertEquals(0, playNext)
        assertEquals(0, removed)
        assertEquals(listOf(RightAction.IsCurrent), rejected)
        assertEquals(0f, contentLeft(), 0.5f)
        assertTrue("no tick, no commit haptic: ${haptics.log}", haptics.log.isEmpty())
    }

    @Test
    fun `a swipe to the right on the song that is already next does nothing but say so`() {
        show(right = RightAction.AlreadyNext)
        rule.onNodeWithTag("swipe").performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertEquals(0, playNext)
        assertEquals(listOf(RightAction.AlreadyNext), rejected)
        assertEquals(0f, contentLeft(), 0.5f)
    }

    @Test
    fun `a row that only gets a note stretches a little and stops`() {
        show(right = RightAction.AlreadyNext)
        val w = widthPx()
        rule.onNodeWithTag("swipe").performTouchInput {
            down(Offset(10f, centerY))
            moveBy(Offset(0.9f * w, 0f), delayMillis = 200)
        }
        rule.waitForIdle()
        val left = contentLeft()
        val wDp = widthPx() / rule.density.density
        assertTrue("moved: $left", left > 10f)
        assertTrue("stays under 22 percent of the width: $left of $wDp", left < wDp * QueueSwipeLogic.RubberFraction)
        rule.onNodeWithTag("swipe").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(listOf(RightAction.AlreadyNext), rejected)
        assertEquals(0f, contentLeft(), 0.5f)
    }

    // ------------------------------------------------------------------------------------------ switched off

    @Test
    fun `with the right swipe off the row does not move to the right`() {
        show(right = RightAction.Disabled, left = true)
        val w = widthPx()
        rule.onNodeWithTag("swipe").performTouchInput {
            down(Offset(10f, centerY))
            moveBy(Offset(0.6f * w, 0f), delayMillis = 100)
        }
        rule.waitForIdle()
        assertEquals(0f, contentLeft(), 0.5f)
        rule.onNodeWithTag("swipe").performTouchInput { up() }
        rule.waitForIdle()
        assertNothingHappened()
        assertTrue(haptics.log.isEmpty())
    }

    @Test
    fun `with the left swipe off the row does not move to the left and removes nothing`() {
        show(right = RightAction.PlayNext, left = false)
        rule.onNodeWithTag("swipe").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertNothingHappened()
        assertEquals(0f, contentLeft(), 0.5f)
        assertTrue(haptics.log.isEmpty())
    }

    @Test
    fun `with both swipes off (guest, locked queue, selection mode) a swipe changes nothing`() {
        show(right = RightAction.Disabled, left = false)
        rule.onNodeWithTag("swipe").performTouchInput { swipeRight() }
        rule.waitForIdle()
        rule.onNodeWithTag("swipe").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertNothingHappened()
        assertEquals(0f, contentLeft(), 0.5f)
        assertTrue(haptics.log.isEmpty())
    }

    @Test
    fun `switching the queue lock on while the row is shown turns the swipes off`() {
        show()
        rule.onNodeWithTag("swipe").performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertEquals(1, playNext)
        rightState.value = RightAction.Disabled
        leftState.value = false
        rule.waitForIdle()
        rule.onNodeWithTag("swipe").performTouchInput { swipeRight() }
        rule.waitForIdle()
        rule.onNodeWithTag("swipe").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(1, playNext)
        assertEquals(0, removed)
    }

    // -------------------------------------------------------------------------------- a locked queue says so

    @Test
    fun `a swipe to the right on a locked queue runs nothing, ticks once and asks to unlock once`() {
        show(right = RightAction.Disabled, left = false, locked = true)
        rule.onNodeWithTag("swipe").performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertEquals(0, playNext)
        assertEquals(0, removed)
        assertEquals(emptyList<RightAction>(), rejected)
        assertEquals(1, hints)
        assertEquals(listOf(HapticFeedbackType.SegmentTick), haptics.log)
        assertEquals(0f, contentLeft(), 0.5f)
    }

    @Test
    fun `a swipe to the left on a locked queue runs nothing, ticks once and asks to unlock once`() {
        show(right = RightAction.Disabled, left = false, locked = true)
        rule.onNodeWithTag("swipe").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(0, playNext)
        assertEquals(0, removed)
        assertEquals(1, hints)
        assertEquals(listOf(HapticFeedbackType.SegmentTick), haptics.log)
        assertEquals(0f, contentLeft(), 0.5f)
    }

    @Test
    fun `on a locked queue the row stretches a little, the tick comes at 20 percent and nothing is asked while the finger is down`() {
        show(right = RightAction.Disabled, left = false, locked = true)
        val w = widthPx()
        rule.onNodeWithTag("swipe").performTouchInput {
            down(Offset(10f, centerY))
            moveBy(Offset(0.1f * w, 0f), delayMillis = 300)
        }
        rule.waitForIdle()
        assertTrue("moved: ${contentLeft()}", contentLeft() > 5f)
        assertTrue("no tick before 20 percent: ${haptics.log}", haptics.log.isEmpty())

        rule.onNodeWithTag("swipe").performTouchInput { moveBy(Offset(0.8f * w, 0f), delayMillis = 300) }
        rule.waitForIdle()
        val wDp = w / rule.density.density
        assertTrue("stays under 22 percent of the width: ${contentLeft()} of $wDp", contentLeft() < wDp * QueueSwipeLogic.RubberFraction)
        assertEquals(listOf(HapticFeedbackType.SegmentTick), haptics.log)
        assertEquals(0, hints)

        rule.onNodeWithTag("swipe").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(1, hints)
        assertEquals(0, playNext)
        assertEquals(0, removed)
        assertEquals(0f, contentLeft(), 0.5f)
    }

    @Test
    fun `a short drag on a locked queue is silent`() {
        show(right = RightAction.Disabled, left = false, locked = true)
        val w = widthPx()
        rule.onNodeWithTag("swipe").performTouchInput {
            down(Offset(10f, centerY))
            moveBy(Offset(0.15f * w, 0f), delayMillis = 500)
            up()
        }
        rule.waitForIdle()
        assertNothingHappened()
        assertTrue(haptics.log.isEmpty())
        assertEquals(0f, contentLeft(), 0.5f)
    }

    @Test
    fun `a slow drag past 20 percent on a locked queue asks, a flick too`() {
        show(right = RightAction.Disabled, left = false, locked = true)
        val w = widthPx()
        rule.onNodeWithTag("swipe").performTouchInput {
            down(Offset(10f, centerY))
            moveBy(Offset(0.25f * w, 0f), delayMillis = 900)
            up()
        }
        rule.waitForIdle()
        assertEquals(1, hints)
        rule.onNodeWithTag("swipe").performTouchInput {
            down(Offset(10f, centerY))
            repeat(4) { moveBy(Offset(0.08f * w, 0f), delayMillis = 8) }
            up()
        }
        rule.waitForIdle()
        assertEquals(2, hints)
        assertEquals(0, playNext)
        assertEquals(0, removed)
    }

    @Test
    fun `the swipes of an unlocked row never call the hint`() {
        show()
        rule.onNodeWithTag("swipe").performTouchInput { swipeRight() }
        rule.waitForIdle()
        rule.onNodeWithTag("swipe").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(0, hints)
        assertEquals(1, playNext)
        assertEquals(1, removed)
    }

    @Test
    fun `without the hint (guest, selection mode) a locked row does not move at all`() {
        show(right = RightAction.Disabled, left = false, locked = false)
        val w = widthPx()
        rule.onNodeWithTag("swipe").performTouchInput {
            down(Offset(10f, centerY))
            moveBy(Offset(0.5f * w, 0f), delayMillis = 200)
        }
        rule.waitForIdle()
        assertEquals(0f, contentLeft(), 0.5f)
        rule.onNodeWithTag("swipe").performTouchInput { up() }
        rule.waitForIdle()
        assertNothingHappened()
        assertTrue(haptics.log.isEmpty())
    }

    @Test
    fun `unlocking while the row is shown turns the hint into the real swipes`() {
        show(right = RightAction.Disabled, left = false, locked = true)
        rule.onNodeWithTag("swipe").performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertEquals(1, hints)
        assertEquals(0, playNext)
        rightState.value = RightAction.PlayNext
        leftState.value = true
        lockedState.value = false
        rule.waitForIdle()
        rule.onNodeWithTag("swipe").performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertEquals(1, hints)
        assertEquals(1, playNext)
    }

    // ------------------------------------------------------------------------------------- other gestures

    @Test
    fun `a tap on the row still reaches the row`() {
        show()
        rule.onNodeWithTag("content").performClick()
        rule.waitForIdle()
        assertEquals(1, taps)
        assertNothingHappened()
    }

    @Test
    fun `a vertical drag does not move the row or run anything`() {
        show()
        rule.onNodeWithTag("swipe").performTouchInput { swipeDown() }
        rule.waitForIdle()
        assertNothingHappened()
        assertEquals(0f, contentLeft(), 0.5f)
        assertTrue(haptics.log.isEmpty())
    }
}
