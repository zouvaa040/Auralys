/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The animated side of "Efek berbayang" with a hand-stepped clock: the first cover appears in its own colour, a change of
 * cover cross-fades in 700 ms, a stopped song fades the wash out and keeps its colour while it does, the shade steps
 * aside under a page header in 350 ms and comes back, and a change of level moves the strength and the reach.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h860dp-xhdpi")
class CoverShadeMotionTest {
    @get:Rule
    val rule = createComposeRule()

    private val red = Color(0xFFE53935)
    private val blue = Color(0xFF1E4FD8)

    private var level by mutableStateOf(CoverShadeLevel.SOFT)
    private var cover by mutableStateOf<Color?>(null)
    private var shade: CoverShade? = null

    private fun start(initialCover: Color? = null, initialLevel: CoverShadeLevel = CoverShadeLevel.SOFT) {
        level = initialLevel
        cover = initialCover
        rule.mainClock.autoAdvance = false
        rule.setContent {
            shade = rememberCoverShade(level, cover, dark = false, pureBlack = false, flat = false)
        }
        step(50)
    }

    /** Frame by frame: one big jump is one frame, and an animation that starts in that frame has not moved yet. */
    /**
     * A state written from the test body while the test is idle is not seen by the recomposer until the apply
     * notifications are sent (the app does it from its main loop; the stepped test clock does not).
     */
    private fun change(block: () -> Unit) {
        block()
        Snapshot.sendApplyNotifications()
    }

    private fun step(ms: Long) {
        repeat(((ms + 15) / 16).toInt()) { rule.mainClock.advanceTimeByFrame() }
    }

    private val s get() = shade!!

    @Test
    fun `nothing plays, nothing is drawn`() {
        start(initialCover = null)
        step(1000)
        assertEquals(0f, s.strengthNow, 1e-4f)
        assertTrue(s.active)
        assertEquals(null, s.sourceCover)
    }

    @Test
    fun `the first cover fades in, in its own colour from the first frame`() {
        start(initialCover = null)
        change { cover = red }
        step(60)
        val tint = CoverShadeMath.tint(red, dark = false)
        assertEquals("the colour does not slide up from grey", tint.red, s.tintNow.red, 0.02f)
        assertEquals(tint.green, s.tintNow.green, 0.02f)
        val early = s.strengthNow
        assertTrue("the strength starts below the goal: $early", early < 0.16f)
        step(CoverShadeMath.CrossfadeMs.toLong())
        assertEquals(0.16f, s.strengthNow, 1e-3f)
        assertEquals(red, s.sourceCover)
    }

    @Test
    fun `a new cover cross-fades the colour in 700 ms`() {
        start(initialCover = red)
        step(1000)
        val from = CoverShadeMath.tint(red, dark = false)
        val to = CoverShadeMath.tint(blue, dark = false)
        assertEquals(from.red, s.tintNow.red, 0.01f)
        change { cover = blue }
        step(16)
        step(CoverShadeMath.CrossfadeMs / 2L)
        val mid = s.tintNow
        assertTrue("red channel is between: ${mid.red}", mid.red < from.red - 0.02f && mid.red > to.red + 0.02f)
        assertEquals("the strength does not dip between two covers", 0.16f, s.strengthNow, 1e-3f)
        step(CoverShadeMath.CrossfadeMs.toLong())
        assertEquals(to.red, s.tintNow.red, 0.01f)
        assertEquals(to.blue, s.tintNow.blue, 0.01f)
    }

    @Test
    fun `when the song stops the wash fades out and keeps its colour while it does`() {
        start(initialCover = red)
        step(1000)
        val tint = CoverShadeMath.tint(red, dark = false)
        change { cover = null }
        step(16)
        step(CoverShadeMath.CrossfadeMs / 2L)
        assertTrue("fading: ${s.strengthNow}", s.strengthNow in 0.001f..0.159f)
        assertEquals("still the same colour", tint.red, s.tintNow.red, 0.01f)
        step(CoverShadeMath.CrossfadeMs.toLong())
        assertEquals(0f, s.strengthNow, 1e-3f)
    }

    @Test
    fun `a page with its own header takes over in 350 ms and the shade comes back`() {
        start(initialCover = red)
        step(1000)
        assertEquals(0.16f, s.strengthNow, 1e-3f)
        change { s.setPageOwnsHeader(true) }
        step(16)
        step(CoverShadeMath.HeaderYieldMs.toLong() + 60)
        assertEquals("no second wash under the header", 0f, s.strengthNow, 1e-3f)
        change { s.setPageOwnsHeader(false) }
        step(16)
        step(CoverShadeMath.CrossfadeMs.toLong() + 60)
        assertEquals(0.16f, s.strengthNow, 1e-3f)
    }

    @Test
    fun `a change of level moves the strength and the reach`() {
        start(initialCover = red, initialLevel = CoverShadeLevel.SOFT)
        step(1000)
        assertEquals(0.48f, s.reachNow, 1e-3f)
        change { level = CoverShadeLevel.STRONG }
        step(16)
        step(CoverShadeMath.CrossfadeMs.toLong() + 60)
        assertEquals(0.24f, s.strengthNow, 1e-3f)
        assertEquals(0.58f, s.reachNow, 1e-3f)
        assertEquals(CoverShadeLevel.STRONG, s.level)
        change { level = CoverShadeLevel.OFF }
        step(16)
        step(CoverShadeMath.CrossfadeMs.toLong() + 60)
        assertEquals(0f, s.strengthNow, 1e-3f)
        assertTrue("Mati is not active", !s.active)
    }
}
