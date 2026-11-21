package com.metrolist.music.poster

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The CPU pixel work (pure JVM): blur, the cut-out's feather and edge colour, the colour matrix, turning and mirroring the photo. */
class PosterPixelsTest {
    @Test
    fun blur_keepsAFlatPictureFlat_andSpreadsAnImpulseSymmetrically() {
        val w = 40
        val h = 30
        val flat = IntArray(w * h) { 0x80604020.toInt() }
        PosterBlur.blur(flat, w, h, 5f, clamp = true)
        assertTrue(flat.all { it == 0x80604020.toInt() })
        val px = IntArray(w * h)
        px[15 * w + 20] = 0xFFFFFFFF.toInt()
        PosterBlur.blur(px, w, h, 2f, clamp = false)
        val c = px[15 * w + 20] and 255
        assertTrue(c in 1..254)
        assertEquals(px[15 * w + 18] and 255, px[15 * w + 22] and 255)
        assertEquals(px[13 * w + 20] and 255, px[17 * w + 20] and 255)
        assertEquals(0, px[0])
    }

    @Test
    fun boxes_approximateTheGaussian() {
        for (sigma in listOf(1f, 3f, 8f, 20f)) {
            val b = PosterBlur.boxes(sigma)
            // the variance of three boxes of width w is sum((w^2 - 1) / 12)
            val v = b.sumOf { ((it * it - 1) / 12.0) }
            assertTrue("sigma $sigma -> ${Math.sqrt(v)}", abs(Math.sqrt(v) - sigma) < 0.6)
        }
    }

    @Test
    fun cutout_solidInside_clearOutside_softEdge_andNoPaleFringe() {
        val w = 20
        val h = 10
        // a bright wall (white) on the left, the subject (brown) on the right; the mask edge sits at x = 10
        val px = IntArray(w * h) { i -> if (i % w < 10) 0xFFFFFFFF.toInt() else 0xFF6A3B22.toInt() }
        val alpha = ByteArray(w * h) { i -> if (i % w < 10) 0 else -1 }
        // an edge pixel that still carries some of the wall's colour
        for (y in 0 until h) {
            px[y * w + 10] = 0xFFB89D8E.toInt()
            alpha[y * w + 10] = 128.toByte()
        }
        val out = IntArray(w * h)
        CutoutPixels.convertRows(px, alpha, w, h, 0, h, out)
        assertEquals(0, out[5 * w + 2])
        assertEquals(0xFF6A3B22.toInt(), out[5 * w + 16])
        val edge = out[5 * w + 10]
        val a = edge ushr 24
        assertTrue("edge alpha $a", a in 1..254)
        // its colour is pulled towards the subject's brown, away from the white wall
        val r = (edge shr 16) and 255
        assertTrue("edge red $r", r < 0xB8)
    }

    @Test
    fun colourMatrix_onTheCpu_identityKeepsThePixels() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        val px = intArrayOf(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF808080.toInt())
        val copy = px.copyOf()
        PosterPost.matrix(px, px.size, PosterPost.fixedMatrix(identity))
        assertArrayEquals(copy, px)
    }

    @Test
    fun photoOps_turnAndMirror_moveTheMaskWithThePixels() {
        val w = 3
        val h = 2
        val px = intArrayOf(1, 2, 3, 4, 5, 6)
        val mask = byteArrayOf(10, 20, 30, 40, 50, 60)
        val p = PosterPhoto(PosterImage(w, h, px), mask)
        val r = PosterPhotoOps.rotateRight(p)
        assertEquals(2, r.w)
        assertEquals(3, r.h)
        // the left column, bottom to top, becomes the top row
        assertArrayEquals(intArrayOf(4, 1, 5, 2, 6, 3), r.image.argb)
        assertArrayEquals(byteArrayOf(40, 10, 50, 20, 60, 30), r.alpha)
        var q = p
        repeat(4) { q = PosterPhotoOps.rotateRight(q) }
        assertArrayEquals(px, q.image.argb)
        val m = PosterPhotoOps.mirror(p)
        assertArrayEquals(intArrayOf(3, 2, 1, 6, 5, 4), m.image.argb)
        assertArrayEquals(px, PosterPhotoOps.mirror(m).image.argb)
        assertArrayEquals(mask, PosterPhotoOps.mirror(m).alpha)
        assertTrue(r.uid != p.uid)
    }

    @Test
    fun photoLoad_sizes() {
        assertEquals(1, PosterPhotoLoad.sampleSize(2000, 1500, 2048))
        assertEquals(2, PosterPhotoLoad.sampleSize(4096, 3072, 2048))
        assertEquals(4, PosterPhotoLoad.sampleSize(8192, 6144, 2048))
        // review item 32: a 50 MP (8160 px) photo decodes at 2040 px, a 12 MP (4000 px) one at 2000 px, not at twice that
        assertEquals(4, PosterPhotoLoad.sampleSize(8160, 6120, 2048))
        assertEquals(2, PosterPhotoLoad.sampleSize(4000, 3000, 2048))
        assertEquals(2, PosterPhotoLoad.sampleSize(6000, 4000, 2048))
        assertEquals(2, PosterPhotoLoad.sampleSize(3200, 2400, 1600))
        assertEquals(2048 to 1536, PosterPhotoLoad.fitted(4096, 3072, 2048))
        assertEquals(800 to 600, PosterPhotoLoad.fitted(800, 600, 2048))
        assertEquals(1200 to 1600, PosterPhotoLoad.fitted(3000, 4000, 1600))
    }

    @Test
    fun meanColour_ofAFlatPhoto() {
        val img = PosterImage(10, 10, IntArray(100) { 0xFF336699.toInt() })
        val c = PosterFx.meanColor(img)
        assertTrue(abs(c.red - 0x33 / 255f) < 0.01f)
        assertTrue(abs(c.blue - 0x99 / 255f) < 0.01f)
    }
}
