package com.metrolist.music.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerHeroMathTest {
    @Test
    fun `wide thumbnail in a square box shows the centre square`() {
        val r = heroCropRect(1280f, 720f, 400f, 400f)
        assertEquals(720f, r.width, 0.01f)
        assertEquals(720f, r.height, 0.01f)
        assertEquals(280f, r.left, 0.01f)
        assertEquals(0f, r.top, 0.01f)
    }

    @Test
    fun `square image in a taller box loses a little width`() {
        val r = heroCropRect(1000f, 1000f, 400f, 500f)
        assertEquals(800f, r.width, 0.01f)
        assertEquals(1000f, r.height, 0.01f)
        assertEquals(100f, r.left, 0.01f)
    }

    @Test
    fun `tall image in a wide box loses height`() {
        val r = heroCropRect(500f, 1000f, 500f, 250f)
        assertEquals(500f, r.width, 0.01f)
        assertEquals(250f, r.height, 0.01f)
        assertEquals(375f, r.top, 0.01f)
    }

    @Test
    fun `bad sizes fall back to the whole image`() {
        val r = heroCropRect(100f, 50f, 0f, 10f)
        assertEquals(100f, r.width, 0f)
        assertEquals(50f, r.height, 0f)
    }

    @Test
    fun `wide detection uses the 0_85 to 1_15 band`() {
        assertTrue(isWideOrTall(1280f, 720f))
        assertFalse(isWideOrTall(544f, 544f))
        assertFalse(isWideOrTall(544f, 500f))
        assertTrue(isWideOrTall(300f, 500f))
        assertFalse(isWideOrTall(0f, 0f))
    }

    @Test
    fun `luminance of white black and mixed bands`() {
        val w = 10
        val h = 10
        val white = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        val black = IntArray(w * h) { 0xFF000000.toInt() }
        assertEquals(1f, heroTopBandLuminance(white, w, h), 0.01f)
        assertEquals(0f, heroTopBandLuminance(black, w, h), 0.01f)
        // top row white, the rest black: a 15 % band is one row -> white
        val mixed = IntArray(w * h) { if (it < w) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        assertEquals(1f, heroTopBandLuminance(mixed, w, h), 0.01f)
        // only the bottom is white: the top band is black
        val bottomWhite = IntArray(w * h) { if (it >= w * 5) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        assertEquals(0f, heroTopBandLuminance(bottomWhite, w, h), 0.01f)
    }

    @Test
    fun `luminance only looks at the visible crop of a wide image`() {
        // 16 x 9 image: left and right edges white, centre black. Square box shows the centre only.
        val w = 16
        val h = 9
        val px = IntArray(w * h) { i ->
            val x = i % w
            if (x < 3 || x >= 13) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        assertEquals(0f, heroTopBandLuminance(px, w, h, boxAspect = 1f), 0.05f)
        assertTrue(heroTopBandLuminance(px, w, h, boxAspect = 0f) > 0.3f)
    }

    @Test
    fun `transparent pixels count as black and bad input is neutral`() {
        val clear = IntArray(4) { 0 }
        assertEquals(0f, heroTopBandLuminance(clear, 2, 2), 0.001f)
        assertEquals(0.5f, heroTopBandLuminance(IntArray(1), 2, 2), 0.001f)
        assertEquals(0.5f, heroTopBandLuminance(IntArray(0), 0, 0), 0.001f)
    }

    @Test
    fun `scrim is darker over bright covers`() {
        assertEquals(0.16f, heroScrimAlpha(0f), 0.001f)
        assertEquals(0.65f, heroScrimAlpha(1f), 0.001f)
        assertTrue(heroScrimAlpha(0.8f) > heroScrimAlpha(0.2f))
        assertEquals(0.65f, heroScrimAlpha(5f), 0.001f)
    }

    @Test
    fun `scrim alphas fall from the peak to zero`() {
        val a = heroScrimAlphas(0.5f, 8)
        assertEquals(8, a.size)
        assertEquals(0.5f, a.first(), 0.001f)
        assertEquals(0f, a.last(), 0.001f)
        assertTrue(a.zipWithNext().all { (x, y) -> x >= y })
    }

    @Test
    fun `mask is opaque at the top and clear at the bottom and never increases`() {
        val stops = heroMaskStops(0.4f)
        assertEquals(0f to 1f, stops.first())
        assertEquals(1f, stops.last().first, 0.001f)
        assertEquals(0f, stops.last().second, 0.001f)
        assertEquals(0.6f, stops[1].first, 0.001f)
        assertEquals(1f, stops[1].second, 0.001f)
        assertTrue(stops.zipWithNext().all { (a, b) -> b.first >= a.first && b.second <= a.second })
    }

    @Test
    fun `mask fraction is kept inside a sane range`() {
        val stops = heroMaskStops(5f)
        assertTrue(stops[1].first >= 0.04f)
        val none = heroMaskStops(-1f)
        assertTrue(none[1].first <= 0.96f)
    }
}
