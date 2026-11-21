/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import com.metrolist.music.ui.player.QueueSwipeLogic.Pull
import com.metrolist.music.ui.player.QueueSwipeLogic.RightAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure decisions of the queue swipe: which action a row gets, how the row follows the finger, when it commits, and the new play order. */
class QueueSwipeLogicTest {
    // ------------------------------------------------------------------------------------------ the right action

    private fun right(
        index: Int,
        current: Int,
        guest: Boolean = false,
        locked: Boolean = false,
        selecting: Boolean = false,
    ) = QueueSwipeLogic.rightAction(index, current, guest, locked, selecting)

    @Test
    fun `a row further down plays next`() {
        assertEquals(RightAction.PlayNext, right(index = 5, current = 1))
        assertEquals(RightAction.PlayNext, right(index = 2, current = 0))
    }

    @Test
    fun `a row above the playing song plays next too`() {
        assertEquals(RightAction.PlayNext, right(index = 0, current = 3))
        assertEquals(RightAction.PlayNext, right(index = 2, current = 4))
    }

    @Test
    fun `the playing song and the song right under it get a note instead`() {
        assertEquals(RightAction.IsCurrent, right(index = 3, current = 3))
        assertEquals(RightAction.AlreadyNext, right(index = 4, current = 3))
        // the song right ABOVE the playing one is not next
        assertEquals(RightAction.PlayNext, right(index = 2, current = 3))
    }

    @Test
    fun `a listen together guest, a locked queue and selection mode switch it off`() {
        assertEquals(RightAction.Disabled, right(5, 1, guest = true))
        assertEquals(RightAction.Disabled, right(5, 1, locked = true))
        assertEquals(RightAction.Disabled, right(5, 1, selecting = true))
        // also for the rows that would only get a note
        assertEquals(RightAction.Disabled, right(1, 1, locked = true))
        assertEquals(RightAction.Disabled, right(2, 1, guest = true))
    }

    @Test
    fun `nothing playing means nothing to play next after`() {
        assertEquals(RightAction.Disabled, right(index = 2, current = -1))
        assertEquals(RightAction.Disabled, right(index = -1, current = 1))
    }

    @Test
    fun `removing is switched off by the same three conditions`() {
        assertTrue(QueueSwipeLogic.leftEnabled(guest = false, locked = false, selecting = false))
        assertFalse(QueueSwipeLogic.leftEnabled(guest = true, locked = false, selecting = false))
        assertFalse(QueueSwipeLogic.leftEnabled(guest = false, locked = true, selecting = false))
        assertFalse(QueueSwipeLogic.leftEnabled(guest = false, locked = false, selecting = true))
    }

    @Test
    fun `the positions are the ones shown, so the answer does not depend on shuffle`() {
        // The screen lists the songs in play order. Shuffled queue [A E C F B D] with C playing: C is position 2, so
        // F (3) is next and B (4) is a row further down, whatever the timeline order of the songs is.
        val shown = listOf("A", "E", "C", "F", "B", "D")
        val current = shown.indexOf("C")
        assertEquals(RightAction.IsCurrent, right(shown.indexOf("C"), current))
        assertEquals(RightAction.AlreadyNext, right(shown.indexOf("F"), current))
        assertEquals(RightAction.PlayNext, right(shown.indexOf("B"), current))
        assertEquals(RightAction.PlayNext, right(shown.indexOf("A"), current))
    }

    // ------------------------------------------------------------------------------------------ the locked queue hint

    @Test
    fun `a locked queue asks to be unlocked`() {
        assertTrue(QueueSwipeLogic.lockedHint(guest = false, locked = true, selecting = false))
    }

    @Test
    fun `an unlocked queue has no hint`() {
        assertFalse(QueueSwipeLogic.lockedHint(guest = false, locked = false, selecting = false))
    }

    @Test
    fun `a guest and selection mode get no hint even when the queue is locked`() {
        assertFalse(QueueSwipeLogic.lockedHint(guest = true, locked = true, selecting = false))
        assertFalse(QueueSwipeLogic.lockedHint(guest = false, locked = true, selecting = true))
        assertFalse(QueueSwipeLogic.lockedHint(guest = true, locked = true, selecting = true))
        assertFalse(QueueSwipeLogic.lockedHint(guest = true, locked = false, selecting = false))
        assertFalse(QueueSwipeLogic.lockedHint(guest = false, locked = false, selecting = true))
    }

    @Test
    fun `the lock never turns a swipe on, it only adds the hint`() {
        // the actions stay off for every locked combination, hint or not
        for (guest in listOf(false, true)) {
            for (selecting in listOf(false, true)) {
                assertEquals(RightAction.Disabled, right(5, 1, guest = guest, locked = true, selecting = selecting))
                assertFalse(QueueSwipeLogic.leftEnabled(guest, locked = true, selecting = selecting))
            }
        }
    }

    @Test
    fun `the hint needs 20 percent of the width in either direction, slow or fast`() {
        assertFalse(QueueSwipeLogic.hintCommit(199f, width))
        assertTrue(QueueSwipeLogic.hintCommit(200f, width))
        assertTrue(QueueSwipeLogic.hintCommit(-200f, width))
        assertFalse(QueueSwipeLogic.hintCommit(-199f, width))
        assertTrue(QueueSwipeLogic.hintCommit(900f, width))
        assertFalse(QueueSwipeLogic.hintCommit(0f, width))
        assertFalse(QueueSwipeLogic.hintCommit(300f, 0f))
    }

    @Test
    fun `the tick comes exactly where the hint would commit, once`() {
        // the row stretches like a rubber band, so the offset at 20 percent of finger travel is the tick point
        val tick = QueueSwipeLogic.hintOffsetPx(width)
        assertEquals(QueueSwipeLogic.offsetFor(200f, width, Pull.Rubber), tick, 0.001f)
        assertTrue(tick in 50f..100f)
        assertFalse(QueueSwipeLogic.isHintArmed(tick - 1f, width))
        assertTrue(QueueSwipeLogic.isHintArmed(tick, width))
        assertTrue(QueueSwipeLogic.isHintArmed(-tick, width))
        assertFalse(QueueSwipeLogic.isHintArmed(0f, width))
        // every offset a rubber-banded row reaches after the finger passed 20 percent is armed, below it never
        var armedAt = -1f
        for (travel in 0..1000 step 10) {
            val o = QueueSwipeLogic.offsetFor(travel.toFloat(), width, Pull.Rubber)
            val armed = QueueSwipeLogic.isHintArmed(o, width)
            assertEquals("travel $travel", travel >= 200, armed)
            if (armed && armedAt < 0f) armedAt = travel.toFloat()
        }
        assertEquals(200f, armedAt, 0f)
        assertFalse(QueueSwipeLogic.isHintArmed(50f, 0f))
    }

    @Test
    fun `the hint is shown at most once in three seconds`() {
        assertEquals(3000L, QueueSwipeLogic.HintIntervalMs)
        assertTrue("the first one", QueueSwipeLogic.hintAllowed(nowMs = 10_000L, lastShownMs = null))
        assertFalse(QueueSwipeLogic.hintAllowed(nowMs = 10_000L, lastShownMs = 10_000L))
        assertFalse(QueueSwipeLogic.hintAllowed(nowMs = 12_999L, lastShownMs = 10_000L))
        assertTrue(QueueSwipeLogic.hintAllowed(nowMs = 13_000L, lastShownMs = 10_000L))
        assertTrue(QueueSwipeLogic.hintAllowed(nowMs = 60_000L, lastShownMs = 10_000L))
        // a clock that went back must not lock the hint away
        assertTrue(QueueSwipeLogic.hintAllowed(nowMs = 5_000L, lastShownMs = 10_000L))
    }

    @Test
    fun `a user who keeps swiping sees the hint every third second and not more`() {
        var last: Long? = null
        var shown = 0
        // a swipe every 700 ms for 10 seconds
        for (t in 0..10_000 step 700) {
            if (QueueSwipeLogic.hintAllowed(t.toLong(), last)) {
                last = t.toLong()
                shown++
            }
        }
        // shown at 0, 3500 (the first swipe from 3000 on) and 7000 (the first from 6500 on)
        assertEquals(3, shown)
    }

    @Test
    fun `pulls`() {
        assertEquals(Pull.Action, QueueSwipeLogic.rightPull(RightAction.PlayNext))
        assertEquals(Pull.Rubber, QueueSwipeLogic.rightPull(RightAction.IsCurrent))
        assertEquals(Pull.Rubber, QueueSwipeLogic.rightPull(RightAction.AlreadyNext))
        assertEquals(Pull.None, QueueSwipeLogic.rightPull(RightAction.Disabled))
        assertEquals(Pull.Action, QueueSwipeLogic.leftPull(true))
        assertEquals(Pull.None, QueueSwipeLogic.leftPull(false))
    }

    // ------------------------------------------------------------------------------------ the row follows the finger

    private val width = 1000f

    @Test
    fun `the row follows the finger one to one up to the threshold`() {
        assertEquals(0f, QueueSwipeLogic.offsetFor(0f, width, Pull.Action), 0f)
        assertEquals(100f, QueueSwipeLogic.offsetFor(100f, width, Pull.Action), 0.001f)
        assertEquals(400f, QueueSwipeLogic.offsetFor(400f, width, Pull.Action), 0.001f)
        assertEquals(-250f, QueueSwipeLogic.offsetFor(-250f, width, Pull.Action), 0.001f)
    }

    @Test
    fun `past the threshold it slows down and stops at 70 percent of the width`() {
        val at500 = QueueSwipeLogic.offsetFor(500f, width, Pull.Action)
        assertEquals(400f + 100f * QueueSwipeLogic.OvershootResistance, at500, 0.001f)
        assertTrue(at500 > 400f && at500 < 500f)
        assertEquals(700f, QueueSwipeLogic.offsetFor(5000f, width, Pull.Action), 0.001f)
        assertEquals(-700f, QueueSwipeLogic.offsetFor(-5000f, width, Pull.Action), 0.001f)
    }

    @Test
    fun `a direction that is switched off does not move the row`() {
        assertEquals(0f, QueueSwipeLogic.offsetFor(300f, width, Pull.None), 0f)
        assertEquals(0f, QueueSwipeLogic.offsetFor(-300f, width, Pull.None), 0f)
    }

    @Test
    fun `a swipe that does nothing stretches like a rubber band and never reaches the threshold`() {
        var last = 0f
        for (travel in listOf(50f, 150f, 400f, 800f, 1000f, 5000f)) {
            val o = QueueSwipeLogic.offsetFor(travel, width, Pull.Rubber)
            assertTrue("grows: $travel -> $o", o > last)
            assertTrue("stays under the limit: $o", o < width * QueueSwipeLogic.RubberFraction)
            assertFalse("never armed", QueueSwipeLogic.isArmed(o, width))
            last = o
        }
        // starts at about half the finger speed
        assertTrue(QueueSwipeLogic.offsetFor(20f, width, Pull.Rubber) in 8f..14f)
    }

    @Test
    fun `an unmeasured row does not move`() {
        assertEquals(0f, QueueSwipeLogic.offsetFor(100f, 0f, Pull.Action), 0f)
        assertFalse(QueueSwipeLogic.isArmed(100f, 0f))
        assertEquals(0f, QueueSwipeLogic.progress(100f, 0f), 0f)
    }

    // ------------------------------------------------------------------------------------------ armed and progress

    @Test
    fun `armed from 40 percent of the width in either direction`() {
        assertFalse(QueueSwipeLogic.isArmed(399f, width))
        assertTrue(QueueSwipeLogic.isArmed(400f, width))
        assertTrue(QueueSwipeLogic.isArmed(-400f, width))
        assertFalse(QueueSwipeLogic.isArmed(-399f, width))
        assertFalse(QueueSwipeLogic.isArmed(0f, width))
    }

    @Test
    fun `progress runs from 0 to 1 at the threshold and stays there`() {
        assertEquals(0f, QueueSwipeLogic.progress(0f, width), 0f)
        assertEquals(0.5f, QueueSwipeLogic.progress(200f, width), 0.001f)
        assertEquals(1f, QueueSwipeLogic.progress(400f, width), 0.001f)
        assertEquals(1f, QueueSwipeLogic.progress(-700f, width), 0.001f)
    }

    @Test
    fun `progress can be measured against another limit for the rows that only get a note`() {
        val limit = QueueSwipeLogic.RubberFraction
        assertEquals(0.5f, QueueSwipeLogic.progress(0.11f * width, width, limit), 0.001f)
        assertEquals(1f, QueueSwipeLogic.progress(0.22f * width, width, limit), 0.001f)
        // the same offset is only halfway to the threshold
        assertEquals(0.275f, QueueSwipeLogic.progress(0.11f * width, width), 0.001f)
    }

    @Test
    fun `the label fades in between a quarter and 85 percent of the way`() {
        assertEquals(0f, QueueSwipeLogic.revealAlpha(0f), 0f)
        assertEquals(0f, QueueSwipeLogic.revealAlpha(0.25f), 0f)
        assertEquals(1f, QueueSwipeLogic.revealAlpha(0.85f), 0.001f)
        assertEquals(1f, QueueSwipeLogic.revealAlpha(1f), 0f)
        var last = 0f
        for (p in listOf(0.3f, 0.45f, 0.6f, 0.75f, 0.85f)) {
            val a = QueueSwipeLogic.revealAlpha(p)
            assertTrue("monotone at $p", a >= last)
            last = a
        }
        // the 20 percent picture is still empty, the 60 percent one mostly there
        assertEquals(0f, QueueSwipeLogic.revealAlpha(0.2f), 0f)
        assertTrue(QueueSwipeLogic.revealAlpha(0.6f) > 0.5f)
    }

    // ------------------------------------------------------------------------------------------------ the release

    @Test
    fun `lets go past 40 percent commits, slowly or fast`() {
        assertTrue(QueueSwipeLogic.shouldCommit(400f, width, 0f))
        assertTrue(QueueSwipeLogic.shouldCommit(-400f, width, 0f))
        assertTrue(QueueSwipeLogic.shouldCommit(900f, width, -2000f))
    }

    @Test
    fun `lets go before 20 percent never commits`() {
        assertFalse(QueueSwipeLogic.shouldCommit(150f, width, 0f))
        assertFalse(QueueSwipeLogic.shouldCommit(199f, width, 5000f))
        assertFalse(QueueSwipeLogic.shouldCommit(0f, width, 5000f))
    }

    @Test
    fun `between 20 and 40 percent only a quick flick commits`() {
        assertFalse(QueueSwipeLogic.shouldCommit(300f, width, 300f))
        assertFalse(QueueSwipeLogic.shouldCommit(300f, width, QueueSwipeLogic.FlingVelocityDp - 1f))
        assertTrue(QueueSwipeLogic.shouldCommit(300f, width, QueueSwipeLogic.FlingVelocityDp))
        assertTrue(QueueSwipeLogic.shouldCommit(-300f, width, 1500f))
        // a finger that is already coming back does not commit
        assertFalse(QueueSwipeLogic.shouldCommit(300f, width, -1500f))
    }

    @Test
    fun `an unmeasured row never commits`() {
        assertFalse(QueueSwipeLogic.shouldCommit(500f, 0f, 5000f))
    }

    // ----------------------------------------------------------------------------------------------- the new order

    private val abcde = listOf("A", "B", "C", "D", "E")

    @Test
    fun `placeAfter moves a row down to right after the anchor`() {
        assertEquals(listOf("A", "C", "D", "B", "E"), QueueSwipeLogic.placeAfter(abcde, "B", "D"))
        assertEquals(listOf("A", "B", "C", "D", "E"), QueueSwipeLogic.placeAfter(abcde, "E", "D"))
    }

    @Test
    fun `placeAfter moves a row up to right after the anchor`() {
        assertEquals(listOf("A", "B", "D", "C", "E"), QueueSwipeLogic.placeAfter(abcde, "D", "B"))
        assertEquals(listOf("A", "B", "E", "C", "D"), QueueSwipeLogic.placeAfter(abcde, "E", "B"))
    }

    @Test
    fun `play next of a row above the playing song puts it right after the playing song`() {
        // B is playing; A (above it) is swiped right
        assertEquals(listOf("B", "A", "C", "D", "E"), QueueSwipeLogic.placeAfter(abcde, "A", "B"))
        // D is playing; B (two above) is swiped right
        assertEquals(listOf("A", "C", "D", "B", "E"), QueueSwipeLogic.placeAfter(abcde, "B", "D"))
    }

    @Test
    fun `placeAfter with no anchor puts the row first and refuses what cannot be done`() {
        assertEquals(listOf("D", "A", "B", "C", "E"), QueueSwipeLogic.placeAfter(abcde, "D", null))
        assertNull(QueueSwipeLogic.placeAfter(abcde, "X", "B"))
        assertNull(QueueSwipeLogic.placeAfter(abcde, "B", "X"))
        assertNull(QueueSwipeLogic.placeAfter(abcde, "B", "B"))
        assertNull(QueueSwipeLogic.placeAfter(emptyList<String>(), "B", "A"))
    }

    @Test
    fun `an already-next row comes out unchanged`() {
        assertEquals(abcde, QueueSwipeLogic.placeAfter(abcde, "C", "B"))
    }

    @Test
    fun `placeBefore`() {
        assertEquals(listOf("D", "A", "B", "C", "E"), QueueSwipeLogic.placeBefore(abcde, "D", "A"))
        assertEquals(listOf("A", "C", "D", "B", "E"), QueueSwipeLogic.placeBefore(abcde, "B", "E"))
        assertNull(QueueSwipeLogic.placeBefore(abcde, "B", "B"))
        assertNull(QueueSwipeLogic.placeBefore(abcde, "B", "X"))
    }

    @Test
    fun `the neighbours of a row`() {
        val slot = QueueSwipeLogic.slotOf(abcde, 2)
        assertEquals("B", slot.before)
        assertEquals("D", slot.after)
        assertNull(QueueSwipeLogic.slotOf(abcde, 0).before)
        assertEquals("B", QueueSwipeLogic.slotOf(abcde, 0).after)
        assertNull(QueueSwipeLogic.slotOf(abcde, 4).after)
        // a row that is not there has no neighbours (not "the first row")
        val none = QueueSwipeLogic.slotOf(abcde, -1)
        assertNull(none.before)
        assertNull(none.after)
        assertNull(QueueSwipeLogic.slotOf(abcde, 9).before)
    }

    // --------------------------------------------------------------------------------- undo and the shifted indices

    @Test
    fun `undo of a play next puts the row back between its old neighbours`() {
        // D is swiped right while B plays: A B C D E -> A B D C E
        val slot = QueueSwipeLogic.slotOf(abcde, abcde.indexOf("D"))
        val moved = QueueSwipeLogic.placeAfter(abcde, "D", "B")!!
        assertEquals(listOf("A", "B", "D", "C", "E"), moved)
        assertEquals(abcde, QueueSwipeLogic.restore(moved, "D", slot))
    }

    @Test
    fun `undo still works when other rows were removed or added in between`() {
        val slot = QueueSwipeLogic.slotOf(abcde, abcde.indexOf("D")) // before C, after E
        val moved = QueueSwipeLogic.placeAfter(abcde, "D", "B")!! // A B D C E
        // the user removed A and added F at the end
        val later = moved.filter { it != "A" } + "F" // B D C E F
        assertEquals(listOf("B", "C", "D", "E", "F"), QueueSwipeLogic.restore(later, "D", slot))
        // the user also removed the old upper neighbour C: the lower neighbour E is used
        val laterStill = later.filter { it != "C" } // B D E F
        assertEquals(listOf("B", "D", "E", "F"), QueueSwipeLogic.restore(laterStill, "D", slot))
    }

    @Test
    fun `undo with both neighbours gone leaves the row where it is`() {
        val slot = QueueSwipeLogic.slotOf(abcde, abcde.indexOf("D"))
        val gone = listOf("A", "B", "D")
        assertNull(QueueSwipeLogic.restore(gone, "D", slot))
    }

    @Test
    fun `undo of the first row goes back to the front, or before its old neighbour`() {
        val slot = QueueSwipeLogic.slotOf(abcde, 0) // before nothing, after B
        val moved = QueueSwipeLogic.placeAfter(abcde, "A", "C")!! // B C A D E
        assertEquals(abcde, QueueSwipeLogic.restore(moved, "A", slot))
        // B was removed meanwhile: the row was first, so it goes first
        assertEquals(listOf("A", "C", "D", "E"), QueueSwipeLogic.restore(listOf("C", "A", "D", "E"), "A", slot))
    }

    @Test
    fun `undo of the last row goes back after its old neighbour`() {
        val slot = QueueSwipeLogic.slotOf(abcde, 4) // before D, after nothing
        val moved = QueueSwipeLogic.placeAfter(abcde, "E", "A")!! // A E B C D
        assertEquals(abcde, QueueSwipeLogic.restore(moved, "E", slot))
        // D is gone and the row was last: nothing to anchor to, leave it
        assertNull(QueueSwipeLogic.restore(listOf("A", "E", "B", "C"), "E", slot))
    }

    @Test
    fun `restoring a removed row that was appended puts it in its slot`() {
        // C is removed (neighbours B and D), others shifted; Undo appends C and asks for its slot back
        val slot = QueueSwipeLogic.slotOf(abcde, 2)
        val afterRemoval = listOf("A", "B", "D", "E")
        val appended = afterRemoval + "C"
        assertEquals(abcde, QueueSwipeLogic.restore(appended, "C", slot))
        // B was swiped to play next in between (order A D B E): C goes after B again, wherever B is now
        val reshuffled = listOf("A", "D", "B", "E", "C")
        assertEquals(listOf("A", "D", "B", "C", "E"), QueueSwipeLogic.restore(reshuffled, "C", slot))
    }

    @Test
    fun `a single row queue has nothing to restore`() {
        val slot = QueueSwipeLogic.slotOf(listOf("A"), 0)
        assertNull(slot.before)
        assertNull(slot.after)
        assertNull(QueueSwipeLogic.restore(listOf("A"), "A", slot))
        assertNotNull(slot)
    }
}
