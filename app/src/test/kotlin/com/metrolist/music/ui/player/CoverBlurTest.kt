package com.metrolist.music.ui.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverBlurTest {
    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun `uniform image stays uniform`() {
        val src = IntArray(7 * 5) { gray(120) }
        assertArrayEquals(src, boxBlurArgb(src, 7, 5, 3))
    }

    @Test
    fun `radius zero is a copy`() {
        val src = IntArray(12) { gray(it * 10) }
        val out = boxBlurArgb(src, 4, 3, 0)
        assertArrayEquals(src, out)
        assertTrue(out !== src)
    }

    @Test
    fun `a white dot spreads out and stays inside the 0 to 255 range`() {
        val w = 15
        val h = 15
        val src = IntArray(w * h) { gray(0) }
        src[7 * w + 7] = gray(255)
        val out = boxBlurArgb(src, w, h, 2)
        val centre = out[7 * w + 7] and 0xFF
        val neighbour = out[7 * w + 8] and 0xFF
        assertTrue(centre in 1..254)
        assertTrue(neighbour > 0)
        assertTrue(centre >= neighbour)
        // light never appears out of nothing far from the dot
        assertEquals(0, out[0] and 0xFF)
    }

    @Test
    fun `alpha is kept opaque`() {
        val src = IntArray(9) { gray(200) }
        assertTrue(boxBlurArgb(src, 3, 3, 1).all { (it ushr 24) == 0xFF })
    }

    @Test
    fun `big radius on a small image does not crash and averages`() {
        val src = IntArray(4) { gray(if (it % 2 == 0) 0 else 200) }
        val out = boxBlurArgb(src, 2, 2, 20)
        assertTrue(out.all { (it and 0xFF) in 50..150 })
    }
}
