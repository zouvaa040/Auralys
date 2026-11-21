/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The numbers of the playlist / album / artist header (HeroMath) and how the top bar fade reads them. */
class HeroHeaderMathTest {
    @Test
    fun `the cover is as tall as the screen is wide on a phone and stops growing on a tablet`() {
        assertEquals(411f, HeroMath.coverHeightDp(411f), 0f)
        assertEquals(360f, HeroMath.coverHeightDp(360f), 0f)
        assertEquals(HeroMath.MaxCoverDp, HeroMath.coverHeightDp(840f), 0f)
    }

    @Test
    fun `the title climbs into the fade but never above the middle of the smallest cover`() {
        assertTrue(HeroMath.TitleOverlapDp > 0f)
        // the smallest phone cover is 320 dp wide: the title must start in the faded lower part (from 40 percent), not on the picture
        assertTrue(320f - HeroMath.TitleOverlapDp > 320f * 0.4f)
        assertTrue(HeroMath.WashDp > HeroMath.TitleOverlapDp)
    }

    @Test
    fun `the title in the bar and the scrim behind it read the same number (REVIEW-2026-11-07 F8)`() {
        val s = HeroMath.BarFadeStartDp
        val r = HeroMath.BarFadeRampDp
        // at the top: neither
        assertEquals(0f, heroBarFade(false, 0, 0f, s, r), 0f)
        // the old gap: scrim already half on at 235 dp of scroll, the title used to wait for the whole header item to leave
        val mid = heroBarFade(false, 0, s + r / 2f, s, r)
        assertEquals(0.5f, mid, 0.001f)
        // the same number is what both use, so there is no scroll position with a scrim and no title
        var previous = 0f
        var offset = 0f
        while (offset <= 600f) {
            val fade = heroBarFade(false, 0, offset, s, r)
            assertTrue("never goes back while scrolling down", fade >= previous)
            previous = fade
            offset += 5f
        }
        assertEquals(1f, previous, 0f)
        // the header item is gone: full; searching: full at any offset
        assertEquals(1f, heroBarFade(false, 1, 0f, s, r), 0f)
        assertEquals(1f, heroBarFade(true, 0, 0f, s, r), 0f)
    }

    @Test
    fun `the title is fully on when the ramp of the scrim is done`() {
        val s = HeroMath.BarFadeStartDp
        val r = HeroMath.BarFadeRampDp
        assertEquals(1f, heroBarFade(false, 0, s + r, s, r), 0f)
    }

    @Test
    fun `the top bar scrim starts after the status bar and the bar are over the page, not over the cover`() {
        // not at the first pixel of scroll
        assertEquals(0f, topBarFade(0f, HeroMath.BarFadeStartDp, HeroMath.BarFadeRampDp), 0f)
        assertEquals(0f, topBarFade(HeroMath.BarFadeStartDp, HeroMath.BarFadeStartDp, HeroMath.BarFadeRampDp), 0f)
        // full once the ramp is done
        assertEquals(1f, topBarFade(HeroMath.BarFadeStartDp + HeroMath.BarFadeRampDp, HeroMath.BarFadeStartDp, HeroMath.BarFadeRampDp), 0f)
        // half way in between
        assertEquals(0.5f, topBarFade(HeroMath.BarFadeStartDp + HeroMath.BarFadeRampDp / 2f, HeroMath.BarFadeStartDp, HeroMath.BarFadeRampDp), 0.001f)
    }
}
