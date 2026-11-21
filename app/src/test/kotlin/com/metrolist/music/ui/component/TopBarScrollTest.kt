/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Test

class TopBarScrollTest {
    @Test
    fun `fade is zero at the top and one when content is well under the bar`() {
        assertEquals(0f, topBarFade(0f, 20f, 80f), 0.0001f)
        assertEquals(0f, topBarFade(20f, 20f, 80f), 0.0001f)
        assertEquals(0.5f, topBarFade(60f, 20f, 80f), 0.0001f)
        assertEquals(1f, topBarFade(100f, 20f, 80f), 0.0001f)
        assertEquals(1f, topBarFade(5000f, 20f, 80f), 0.0001f)
    }

    @Test
    fun `fade never leaves 0 to 1 and tolerates a zero ramp`() {
        assertEquals(0f, topBarFade(-50f, 20f, 80f), 0.0001f)
        assertEquals(1f, topBarFade(30f, 20f, 0f), 0.0001f)
        assertEquals(0f, topBarFade(10f, 20f, 0f), 0.0001f)
    }

    @Test
    fun `scrolling on adds to the offset and scrolling back removes it`() {
        var o = 0f
        o = nextScrollOffset(o, consumedY = -40f, availableY = 0f)
        assertEquals(40f, o, 0.0001f)
        o = nextScrollOffset(o, consumedY = -10f, availableY = 0f)
        assertEquals(50f, o, 0.0001f)
        o = nextScrollOffset(o, consumedY = 30f, availableY = 0f)
        assertEquals(20f, o, 0.0001f)
        o = nextScrollOffset(o, consumedY = 100f, availableY = 0f)
        assertEquals(0f, o, 0.0001f)
    }

    @Test
    fun `pulling down at the very top resets the offset`() {
        assertEquals(0f, nextScrollOffset(75f, consumedY = 0f, availableY = 12f), 0.0001f)
        assertEquals(75f, nextScrollOffset(75f, consumedY = 0f, availableY = 0f), 0.0001f)
    }

    @Test
    fun `each page keeps its own offset`() {
        val state = TopBarScrollState()
        state.switchTo("a")
        state.connection.onPostScroll(Offset(0f, -60f), Offset.Zero, NestedScrollSource.UserInput)
        assertEquals(60f, state.offsetPx, 0.0001f)
        state.switchTo("b")
        assertEquals(0f, state.offsetPx, 0.0001f)
        state.connection.onPostScroll(Offset(0f, -10f), Offset.Zero, NestedScrollSource.UserInput)
        state.switchTo("a")
        assertEquals(60f, state.offsetPx, 0.0001f)
        state.switchTo("b")
        assertEquals(10f, state.offsetPx, 0.0001f)
    }

    private fun restored(state: TopBarScrollState): TopBarScrollState {
        // what rememberSaveable does: save, then restore into a new instance (a recreated Activity)
        val scope = object : androidx.compose.runtime.saveable.SaverScope {
            override fun canBeSaved(value: Any) = true
        }
        val saved = with(scope) { with(TopBarScrollStateSaver) { save(state) } }
        return TopBarScrollStateSaver.restore(saved!!)!!
    }

    @Test
    fun `the offsets survive a recreated Activity`() {
        val state = TopBarScrollState()
        state.switchTo("a")
        state.connection.onPostScroll(Offset(0f, -60f), Offset.Zero, NestedScrollSource.UserInput)
        state.switchTo("b")
        state.connection.onPostScroll(Offset(0f, -25f), Offset.Zero, NestedScrollSource.UserInput)

        val again = restored(state)
        // the page on screen keeps its tint at once, without any new scroll
        assertEquals(25f, again.offsetPx, 0.0001f)
        // the same back-stack entry id arrives again: nothing is reset
        again.switchTo("b")
        assertEquals(25f, again.offsetPx, 0.0001f)
        // and the parked page is still known
        again.switchTo("a")
        assertEquals(60f, again.offsetPx, 0.0001f)
    }

    @Test
    fun `an empty tracker is saved and restored`() {
        val again = restored(TopBarScrollState())
        assertEquals(0f, again.offsetPx, 0.0001f)
        again.switchTo("x")
        assertEquals(0f, again.offsetPx, 0.0001f)
    }

    @Test
    fun `a saved list with broken entries is read as far as it is sound`() {
        val state = TopBarScrollState()
        state.restoreFrom(listOf("k", 12f, "p", 5f, 7, "q"))
        assertEquals(12f, state.offsetPx, 0.0001f)
        state.switchTo("p")
        assertEquals(5f, state.offsetPx, 0.0001f)
        state.restoreFrom(emptyList())
        assertEquals(0f, state.offsetPx, 0.0001f)
    }
}
