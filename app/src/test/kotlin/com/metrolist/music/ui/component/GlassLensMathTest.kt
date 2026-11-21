package com.metrolist.music.ui.component

import com.metrolist.music.ui.component.backdrop.internal.RoundedRectRefractionShaderString
import com.metrolist.music.ui.component.backdrop.internal.RoundedRectRefractionWithDispersionShaderString
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the formulas of the lens shader and its software copy ([SoftLens]) on sample points worked out by hand.
 * The AGSL text itself is checked for the lines the copy mirrors, so the copy cannot drift from the shipped shader.
 */
class GlassLensMathTest {
    private val eps = 1e-3f

    // a capsule of a 72 dp bar at 2 px per dp: 700 x 144 px, radius 72, bend 48 px deep, pulls 48 px
    private fun capsule(depth: Boolean = false, dispersion: Float = 0f) =
        SoftLens.Spec(w = 700f, h = 144f, radius = 72f, height = 48f, amount = 48f, depth = depth, dispersion = dispersion)

    // ---------------------------------------------------------------- the shipped AGSL still has the lines we mirror

    @Test
    fun `the plain shader still has the lines the software copy mirrors`() {
        val src = RoundedRectRefractionShaderString
        for (line in listOf(
            "float2 centeredCoord = (coord + offset) - halfSize;",
            "if (-sd >= refractionHeight) {",
            "sd = min(sd, 0.0);",
            "float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;",
            "float gradRadius = min(radius * 1.5, minHalf);",
            "grad = normalize(gradBase + normalize(centeredCoord));",
            "float2 refractedCoord = coord + d * grad;",
            "return 1.0 - sqrt(1.0 - x * x);",
            "return x * x;",
        )) assertTrue("missing in the AGSL: $line", src.contains(line))
    }

    @Test
    fun `the dispersion shader still has the lines the software copy mirrors`() {
        val src = RoundedRectRefractionWithDispersionShaderString
        for (line in listOf(
            "float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));",
            "float2 dispersedCoord = d * grad * dispersionIntensity;",
            "half4 refrRed = content.eval(refractedCoord + dispersedCoord);",
            "half4 refrBlue = content.eval(refractedCoord - dispersedCoord);",
            "return half4(refrRed.r, refr.g, refrBlue.b, refr.a);",
        )) assertTrue("missing in the AGSL: $line", src.contains(line))
    }

    // ---------------------------------------------------------------- the pieces

    @Test
    fun `circleMap is 0 at the inner end of the rim and 1 at the very edge`() {
        assertEquals(0f, SoftLens.circleMap(0f), eps)
        assertEquals(1f, SoftLens.circleMap(1f), eps)
        assertEquals(1f - sqrt(0.75f), SoftLens.circleMap(0.5f), eps)
        assertEquals(0.25f, SoftLens.circleMap(0.5f, fast = true), eps)
        // it only grows: the bend is gentle inside and steep at the edge
        var last = -1f
        for (i in 0..20) {
            val v = SoftLens.circleMap(i / 20f)
            assertTrue(v >= last)
            last = v
        }
    }

    @Test
    fun `signed distance of a capsule`() {
        // centre: 72 inside the top and bottom edge (half height 72, radius 72)
        assertEquals(-72f, SoftLens.sdRoundedRect(0f, 0f, 350f, 72f, 72f), eps)
        // on the top edge of the straight part
        assertEquals(0f, SoftLens.sdRoundedRect(0f, -72f, 350f, 72f, 72f), eps)
        // 10 px above it
        assertEquals(10f, SoftLens.sdRoundedRect(0f, -82f, 350f, 72f, 72f), eps)
        // the far tip of the right cap
        assertEquals(0f, SoftLens.sdRoundedRect(350f, 0f, 350f, 72f, 72f), eps)
    }

    @Test
    fun `gradient of a capsule points straight out on the flat parts and radially on the caps`() {
        val top = SoftLens.gradSdRoundedRect(0f, -60f, 350f, 72f, 72f)
        assertEquals(0f, top[0], eps); assertEquals(-1f, top[1], eps)
        val bottom = SoftLens.gradSdRoundedRect(0f, 60f, 350f, 72f, 72f)
        assertEquals(1f, bottom[1], eps)
        val tip = SoftLens.gradSdRoundedRect(340f, 0f, 350f, 72f, 72f)
        assertEquals(1f, tip[0], eps); assertEquals(0f, tip[1], eps)
        // 45 degrees on the cap
        val d = SoftLens.gradSdRoundedRect(278f + 50f, -50f, 350f, 72f, 72f)
        assertEquals(1f, hypot(d[0], d[1]), eps)
        assertTrue(d[0] > 0f && d[1] < 0f)
    }

    // ---------------------------------------------------------------- the refraction at sample points

    @Test
    fun `the middle of the bar is not bent`() {
        val s = SoftLens.sample(capsule(), 350f, 72f)
        assertFalse(s.inside)
        assertEquals(350f, s.refractedX, 0f)
        assertEquals(72f, s.refractedY, 0f)
    }

    @Test
    fun `a pixel exactly one rim-depth inside the edge is not bent`() {
        // top edge at y = 0; 48 px deep = y 48 on the straight part
        val s = SoftLens.sample(capsule(), 350f, 48f)
        assertFalse(s.inside)
    }

    @Test
    fun `at the very edge the picture is pulled by the full amount from inside the glass`() {
        // y = 0.0 is on the edge; half a pixel in is the first drawn row. Use y = 0 exactly: sd = 0
        val s = SoftLens.sample(capsule(), 350f, 0f)
        assertTrue(s.inside)
        assertEquals(0f, s.sd, eps)
        assertEquals(-48f, s.d, eps) // circleMap(1) * -amount
        // the top edge's gradient points up (-y); d is negative, so the sample moves DOWN, into the glass
        assertEquals(0f, s.gradX, eps); assertEquals(-1f, s.gradY, eps)
        assertEquals(350f, s.refractedX, eps)
        assertEquals(48f, s.refractedY, eps)
    }

    @Test
    fun `half way into the rim the pull follows the circle map`() {
        // sd = -24: x = 1 - 24/48 = 0.5 -> circleMap = 1 - sqrt(0.75)
        val s = SoftLens.sample(capsule(), 350f, 24f)
        val want = (1f - sqrt(0.75f)) * -48f
        assertEquals(want, s.d, eps)
        assertEquals(24f - want, s.refractedY, eps) // grad.y = -1, so y + d * -1
    }

    @Test
    fun `the bottom edge pulls up, the caps pull towards the middle`() {
        val bottom = SoftLens.sample(capsule(), 350f, 144f)
        assertEquals(144f - 48f, bottom.refractedY, eps)
        val left = SoftLens.sample(capsule(), 0f, 72f)
        assertEquals(48f, left.refractedX, eps)
        val right = SoftLens.sample(capsule(), 700f, 72f)
        assertEquals(700f - 48f, right.refractedX, eps)
    }

    @Test
    fun `without dispersion the three samples coincide`() {
        val s = SoftLens.sample(capsule(dispersion = 0f), 90f, 20f)
        assertEquals(0f, s.dispX, 0f)
        assertEquals(0f, s.dispY, 0f)
    }

    @Test
    fun `dispersion is zero on the axes and opposite in the opposite corners`() {
        // on the horizontal axis centeredCoord.y = 0, so intensity = 0 (no fringe in the middle row)
        val axis = SoftLens.sample(capsule(dispersion = 1f), 60f, 72f)
        assertEquals(0f, axis.dispX, eps); assertEquals(0f, axis.dispY, eps)
        // upper-left and lower-right quadrants have centeredCoord.x * y > 0, the other two < 0
        val ul = SoftLens.sample(capsule(dispersion = 1f), 60f, 10f)
        val ur = SoftLens.sample(capsule(dispersion = 1f), 640f, 10f)
        assertTrue(ul.dispX != 0f || ul.dispY != 0f)
        // the displacement vector of the red sample flips sign between the left and right half
        assertTrue(ul.dispY * ur.dispY < 0f || ul.dispX * ur.dispX < 0f)
    }

    @Test
    fun `dispersion intensity is the product of the centred coordinates over the product of the half sizes`() {
        val spec = capsule(dispersion = 1f)
        val x = 60f
        val y = 10f
        val s = SoftLens.sample(spec, x, y)
        val intensity = ((x - 350f) * (y - 72f)) / (350f * 72f)
        assertEquals(s.d * s.gradX * intensity, s.dispX, eps)
        assertEquals(s.d * s.gradY * intensity, s.dispY, eps)
        // and the strength scales linearly
        val half = SoftLens.sample(capsule(dispersion = 0.5f), x, y)
        assertEquals(s.dispX * 0.5f, half.dispX, eps)
    }

    @Test
    fun `depth effect turns the gradient towards the radial direction at the corners`() {
        // near the top of the left cap: the plain gradient and the radial direction differ, depth blends them
        val plain = SoftLens.sample(capsule(depth = false), 40f, 20f)
        val deep = SoftLens.sample(capsule(depth = true), 40f, 20f)
        assertEquals(1f, hypot(deep.gradX, deep.gradY), eps)
        // radial (from the middle outward) at this point is up and to the left
        assertTrue(deep.gradX < 0f && deep.gradY < 0f)
        assertTrue(abs(deep.refractedX - plain.refractedX) > 1e-3f || abs(deep.refractedY - plain.refractedY) > 1e-3f)
        // on the flat top both agree on "up" at x = middle
        val flatPlain = SoftLens.sample(capsule(depth = false), 350f, 10f)
        val flatDeep = SoftLens.sample(capsule(depth = true), 350f, 10f)
        assertEquals(flatPlain.gradY, flatDeep.gradY, eps)
    }

    @Test
    fun `a bar that is not a capsule keeps its flat middle`() {
        // 16 dp card radius on a 64 dp x 300 dp mini player at 2 px per dp
        val spec = SoftLens.Spec(300f * 2f, 64f * 2f, 16f * 2f, 18f * 2f, 22f * 2f, true, 1f)
        assertFalse(SoftLens.sample(spec, 300f, 64f).inside)
        assertTrue(SoftLens.sample(spec, 300f, 4f).inside)
    }

    // ---------------------------------------------------------------- the image pipeline

    @Test
    fun `saturation 1 leaves a colour alone and 0 makes it grey`() {
        val img = SoftLens.Img(1, 1)
        img.r[0] = 0.9f; img.g[0] = 0.2f; img.b[0] = 0.1f
        val same = img.copy()
        SoftLens.saturate(same, 1f)
        assertEquals(0.9f, same.r[0], 1e-4f)
        val grey = img.copy()
        SoftLens.saturate(grey, 0f)
        assertEquals(grey.r[0], grey.g[0], 1e-4f)
        assertEquals(grey.g[0], grey.b[0], 1e-4f)
    }

    @Test
    fun `blur keeps the mean and flattens a point`() {
        val img = SoftLens.Img(41, 41)
        img.r[20 * 41 + 20] = 1f
        SoftLens.blur(img, 4f)
        assertTrue(img.r[20 * 41 + 20] < 0.2f)
        assertEquals(1f, img.r.sum(), 0.02f)
    }

    @Test
    fun `the lens moves colour from inside the glass to the rim`() {
        // left half red, right half blue; the bar crosses the seam. Just inside the left end cap the picture must
        // come from further right (towards the middle), so the red there turns purple/blue only if the seam is near
        val img = SoftLens.Img(800, 200)
        for (y in 0 until 200) for (x in 0 until 800) {
            val o = y * 800 + x
            if (x < 70) { img.r[o] = 1f } else { img.b[o] = 1f }
        }
        val spec = SoftLens.Spec(w = 700f, h = 144f, radius = 72f, height = 48f, amount = 48f, depth = false, dispersion = 0f)
        val out = SoftLens.Img(800, 200)
        out.r.indices.forEach { out.r[it] = img.r[it]; out.g[it] = img.g[it]; out.b[it] = img.b[it] }
        SoftLens.apply(img, out, spec, 50, 28)
        // the bar starts at x = 50; at its left tip (x = 52) the sample comes from about 33 px further in (x 85): blue
        val o = (28 + 72) * 800 + 52
        assertTrue("left tip should now show the colour from inside (blue), r=${out.r[o]} b=${out.b[o]}", out.b[o] > 0.5f)
        // without the lens that pixel is red
        assertTrue(img.r[o] > 0.5f && img.b[o] < 0.5f)
    }
}
