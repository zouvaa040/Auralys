package com.metrolist.music.ui.component

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandGlassTest {
    // ------------------------------------------------------------------ covers (128 px, like the controller's artwork)

    private fun cover(kind: String, n: Int = 128): IntArray = IntArray(n * n) { i ->
        val x = (i % n) / n.toFloat()
        val y = (i / n) / n.toFloat()
        var r: Float
        var g: Float
        var b: Float
        when (kind) {
            "sunset" -> { // warm orange to magenta, a pale sun, a dark figure
                r = 235f - 90f * y; g = 90f - 50f * y; b = 30f + 110f * y
                if (hypot(x - 0.5f, y - 0.38f) < 0.17f) { r = 255f; g = 214f; b = 150f }
                if (hypot(x - 0.5f, y - 0.95f) < 0.34f) { r = 40f; g = 14f; b = 24f }
            }
            "pastel" -> { // a bright cover
                r = 250f - 20f * x; g = 226f - 30f * y; b = 214f + 20f * x
                if (hypot(x - 0.3f, y - 0.6f) < 0.22f) { r = 255f; g = 250f; b = 240f }
                if (hypot(x - 0.75f, y - 0.3f) < 0.14f) { r = 246f; g = 170f; b = 190f }
            }
            "night" -> { // dark navy with a cyan glow
                r = 8f + 12f * y; g = 14f + 20f * y; b = 40f + 40f * y
                val d = hypot(x - 0.7f, y - 0.3f)
                if (d < 0.3f) { val f = (1f - d / 0.3f); r += 20f * f; g += 190f * f; b += 200f * f }
            }
            "stripes" -> { // black and white stripes with a red blob: the harshest case for the ink
                val s = ((x * 8f).toInt() % 2 == 0)
                r = if (s) 245f else 18f; g = r; b = r
                if (hypot(x - 0.6f, y - 0.55f) < 0.2f) { r = 220f; g = 30f; b = 40f }
            }
            else -> { r = 128f; g = 128f; b = 128f }
        }
        (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
    }

    private val covers = listOf("sunset", "pastel", "night", "stripes", "none")

    private fun dimsFor(wDp: Float, hDp: Float, cornerDp: Float, density: Float = 2f) = IslandGlass.bakeDims(wDp, hDp, cornerDp, density)

    private fun bake(kind: String, wDp: Float, hDp: Float, cornerDp: Float, dark: Boolean, blur: Int = 70): IslandGlass.Baked {
        val art = if (kind == "none") null else cover(kind)
        return IslandGlass.bake(art, 128, 128, dimsFor(wDp, hDp, cornerDp), hDp, dark, blur)!!
    }

    // ------------------------------------------------------------------ pure logic

    @Test
    fun `bake size follows the screen but stays small and never empty`() {
        for (density in listOf(1f, 2f, 2.625f, 3f, 3.5f, 4f)) {
            for ((w, h) in listOf(190f to 38f, 80f to 24f, 320f to 64f, 360f to 152f, 483f to 175f, 484f to 252f)) {
                val d = IslandGlass.bakeDims(w, h, 30f, density)
                assertTrue("long side ${d.w}x${d.h} at $density", max(d.w, d.h) <= IslandGlass.MAX_BAKE_SIDE + 1)
                assertTrue(d.w >= IslandGlass.MIN_BAKE_SIDE && d.h >= IslandGlass.MIN_BAKE_SIDE)
                assertTrue("radius fits", d.radius <= min(d.w, d.h) / 2f + 0.001f)
                // same shape as the island, within a pixel of rounding
                assertEquals(w / h, d.w.toFloat() / d.h, w / h * 0.06f + 0.05f)
            }
        }
        // at 3x a 360 dp card bakes at half the screen resolution
        val card = IslandGlass.bakeDims(360f, 152f, 40f, 3f)
        assertEquals(540, card.w)
        assertEquals(1.5f, card.pxPerDp, 0.001f)
    }

    @Test
    fun `lens and frost scale with the island the way the nav pill's do`() {
        assertEquals(24f, IslandGlass.lensHeightDp(72f), 0.001f)
        assertEquals(24f, IslandGlass.lensHeightDp(152f), 0.001f)
        assertEquals(40f / 3f, IslandGlass.lensHeightDp(40f), 0.001f)
        assertEquals(18f, IslandGlass.frostSigmaDp(72f, 70), 0.001f)
        assertTrue(IslandGlass.frostSigmaDp(40f, 70) < IslandGlass.frostSigmaDp(152f, 70))
        assertTrue(IslandGlass.frostSigmaDp(152f, 100) > IslandGlass.frostSigmaDp(152f, 0))
    }

    @Test
    fun `the veil takes the nav pill's numbers at most and grows with how hard the ink is to read`() {
        var prevDark = 0f
        var prevLight = 1f
        for (i in 0..20) {
            val l = i / 20f
            val d = IslandGlass.veilAlpha(true, l)
            val w = IslandGlass.veilAlpha(false, l)
            assertTrue("dark veil in range at $l: $d", d in 0.30f..0.68f)
            assertTrue("light veil in range at $l: $w", w in 0.34f..0.60f)
            assertTrue("a brighter cover needs more dark veil", d >= prevDark - 1e-6f)
            assertTrue("a darker cover needs more light veil", w <= prevLight + 1e-6f)
            prevDark = d
            prevLight = w
        }
        assertEquals(0.68f, IslandGlass.veilAlpha(true, 1f), 0.02f)
        assertEquals(0.60f, IslandGlass.veilAlpha(false, 0f), 0.001f)
    }

    @Test
    fun `rim and sheen follow glassRim and glassSheen and the shine setting`() {
        val full = IslandGlass.rim(dark = true, shine = 100)
        assertEquals(0.48f, full.hi, 0.001f)
        assertEquals(0.22f, full.glint, 0.001f)
        val light = IslandGlass.rim(dark = false, shine = 100)
        assertEquals(0.95f, light.hi, 0.001f)
        assertEquals(0.65f, light.glint, 0.001f)
        assertTrue(IslandGlass.rim(true, 0).hi in 0.05f..full.hi)
        assertTrue(IslandGlass.sheen(false, 100) > IslandGlass.sheen(true, 100))
        assertEquals(0.35f, IslandGlass.sheen(false, 100), 0.001f)
        assertEquals(0.10f, IslandGlass.sheen(true, 100), 0.001f)
    }

    @Test
    fun `ink is chosen by the better worst case contrast`() {
        assertFalse("dark glass gets white ink", IslandGlass.darkInkWins(0.01f, 0.08f))
        assertTrue("light glass gets dark ink", IslandGlass.darkInkWins(0.6f, 0.9f))
        assertFalse("mid glass with a bright patch keeps white", IslandGlass.darkInkWins(0.02f, 0.3f))
        assertTrue(IslandGlass.darkInkWins(0.4f, 0.75f))
    }

    @Test
    fun `the cache key changes with every input and with nothing else`() {
        val a = dimsFor(200f, 40f, 20f)
        val b = dimsFor(360f, 152f, 40f)
        val base = IslandGlass.cacheKey("url1", a, b, true, 70)
        assertEquals(base, IslandGlass.cacheKey("url1", dimsFor(200f, 40f, 20f), dimsFor(360f, 152f, 40f), true, 70))
        assertNotEquals(base, IslandGlass.cacheKey("url2", a, b, true, 70))
        assertNotEquals(base, IslandGlass.cacheKey("url1", dimsFor(220f, 40f, 20f), b, true, 70))
        assertNotEquals(base, IslandGlass.cacheKey("url1", a, dimsFor(360f, 120f, 40f), true, 70))
        assertNotEquals(base, IslandGlass.cacheKey("url1", a, b, false, 70))
        assertNotEquals(base, IslandGlass.cacheKey("url1", a, b, true, 71))
        assertNotEquals(base, IslandGlass.cacheKey("url1", dimsFor(200f, 40f, 12f), b, true, 70))
    }

    @Test
    fun `the cover is cropped from the middle like ContentScale Crop`() {
        // left half red, right half blue, 40 x 40: a 120 x 20 pane shows the middle band, so both halves
        val art = IntArray(40 * 40) { if (it % 40 < 20) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val out = IslandGlass.cropScene(art, 40, 40, 120, 20)
        assertEquals(0xFFFF0000.toInt(), out[10 * 120 + 5])
        assertEquals(0xFF0000FF.toInt(), out[10 * 120 + 115])
        // a tall pane crops the sides: the middle column of a 2-colour cover stays split in the middle
        val tall = IslandGlass.cropScene(art, 40, 40, 20, 120)
        assertEquals(0xFFFF0000.toInt(), tall[60 * 20 + 2])
        assertEquals(0xFF0000FF.toInt(), tall[60 * 20 + 17])
    }

    @Test
    fun `every pixel of a baked skin is opaque, corners included`() {
        for ((w, h, c) in listOf(Triple(200f, 40f, 20f), Triple(360f, 152f, 40f), Triple(190f, 38f, 8f), Triple(80f, 24f, 12f))) {
            val baked = bake("sunset", w, h, c, dark = true)
            assertEquals(baked.w * baked.h, baked.pixels.size)
            assertTrue("opaque everywhere ($w x $h)", baked.pixels.all { (it ushr 24) == 0xFF })
            // a corner pixel takes a colour of the glass next to it, not a hole or the raw scene
            val corner = baked.pixels[0]
            val near = baked.pixels[(baked.h / 4) * baked.w + baked.w / 8]
            assertTrue("corner is glass-coloured", hypot((corner shr 16 and 0xFF) - (near shr 16 and 0xFF) + 0f, ((corner shr 8) and 0xFF) - ((near shr 8) and 0xFF) + 0f) < 140f)
        }
    }

    @Test
    fun `the shadow is soft, sits lower than the pane and stays inside its frame`() {
        for (dark in listOf(true, false)) {
            val d = dimsFor(200f, 40f, 20f, 3f)
            val pad = IslandGlass.shadowPadPx(14f, d)
            val sw = d.w + pad * 2
            val sh = d.h + pad * 2
            val shadow = IslandGlass.bakeShadow(d, pad, dark)
            assertEquals(sw * sh, shadow.size)
            fun alpha(x: Int, y: Int) = shadow[y * sw + x] ushr 24
            assertTrue("black, the alpha is the picture", shadow.all { (it and 0xFFFFFF) == 0 })
            val strength = ((if (dark) IslandGlass.SHADOW_DARK else IslandGlass.SHADOW_LIGHT) * 255f).toInt()
            val centre = alpha(sw / 2, sh / 2)
            assertTrue("centre near full strength: $centre of $strength", centre in (strength * 0.85f).toInt()..strength + 1)
            assertTrue("the frame edge is clear", alpha(0, 0) <= 2 && alpha(sw - 1, sh / 2) <= 8 && alpha(sw / 2, 0) <= 2 && alpha(sw / 2, sh - 1) <= 8)
            assertTrue("lower than the pane: below ${alpha(sw / 2, pad + d.h + 3)} > above ${alpha(sw / 2, pad - 3)}", alpha(sw / 2, pad + d.h + 3) > alpha(sw / 2, pad - 3))
            // soft: the alpha falls off over several pixels instead of cutting at the outline
            assertTrue("soft edge", (pad + d.h until pad + d.h + 12).map { alpha(sw / 2, it) }.distinct().size >= 4)
        }
    }

    @Test
    fun `the morph weight goes from the compact skin to the expanded one without a step`() {
        assertEquals(0f, IslandGlass.expandedWeight(0f), 0f)
        assertEquals(1f, IslandGlass.expandedWeight(1f), 0f)
        assertEquals(0f, IslandGlass.expandedWeight(-0.08f), 0f)
        assertEquals(1f, IslandGlass.expandedWeight(1.08f), 0f)
        var prev = 0f
        for (i in 0..100) {
            val w = IslandGlass.expandedWeight(i / 100f)
            assertTrue(w >= prev - 1e-6f && w - prev < 0.04f)
            prev = w
        }
    }

    @Test
    fun `a stale bake ends early`() {
        val d = dimsFor(360f, 152f, 40f)
        assertNull(IslandGlass.bake(cover("sunset"), 128, 128, d, 152f, true, 70) { true })
        assertNotNull(IslandGlass.bake(cover("sunset"), 128, 128, d, 152f, true, 70) { false })
    }

    @Test
    fun `the cover colour shows through the glass, it is not a dark slab`() {
        for (dark in listOf(true, false)) {
            val red = bake("sunset", 360f, 152f, 40f, dark)
            val blue = bake("night", 360f, 152f, 40f, dark)
            fun meanRgb(b: IslandGlass.Baked): IntArray {
                var r = 0L; var g = 0L; var bl = 0L; var n = 0
                for (y in b.h / 4 until b.h * 3 / 4) for (x in b.w / 4 until b.w * 3 / 4) {
                    val c = b.pixels[y * b.w + x]
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; bl += c and 0xFF; n++
                }
                return intArrayOf((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
            }
            val a = meanRgb(red)
            val b = meanRgb(blue)
            assertTrue("sunset glass is warm (dark=$dark): ${a.toList()}", a[0] > a[2] + 12)
            assertTrue("night glass is cool (dark=$dark): ${b.toList()}", b[2] > b[0] + 6)
            if (dark) assertTrue("dark glass is not black: ${a.toList()}", a[0] + a[1] + a[2] > 120)
        }
    }

    @Test
    fun `the ink reads on every cover in both appearances`() {
        for (kind in covers) for (dark in listOf(true, false)) {
            val c = bake(kind, 200f, 40f, 20f, dark)
            val e = bake(kind, 360f, 152f, 40f, dark)
            val p10 = min(c.p10, e.p10)
            val p90 = max(c.p90, e.p90)
            val darkInk = IslandGlass.darkInkWins(p10, p90)
            val ratio = if (darkInk) (p10 + 0.05f) / 0.058f else 1.05f / (p90 + 0.05f)
            assertTrue("$kind dark=$dark ink=${if (darkInk) "dark" else "white"} contrast $ratio (p10 $p10, p90 $p90)", ratio >= 3.0f)
            // and the appearance decides the ink: a dark glass takes white ink, a light glass dark ink
            assertEquals("$kind dark=$dark", !dark, darkInk)
        }
    }

    @Test
    fun `the lens bends the edge and leaves the middle to the frost`() {
        val d = dimsFor(360f, 152f, 40f)
        val art = cover("stripes")
        val glass = IslandGlass.bake(art, 128, 128, d, 152f, true, 70)!!
        // same bake with the lens turned off by a tiny pane height would differ: compare the edge to the middle gradient
        fun lum(x: Int, y: Int) = IslandGlass.luminance(glass.pixels[y * glass.w + x])
        val midRow = glass.h / 2
        var edgeSpread = 0f
        for (x in 2 until glass.w / 10) edgeSpread += abs(lum(x, midRow) - lum(x - 1, midRow))
        var midSpread = 0f
        for (x in glass.w / 2 until glass.w / 2 + glass.w / 10) midSpread += abs(lum(x, midRow) - lum(x - 1, midRow))
        assertTrue("the edge shows more detail than the frosted middle ($edgeSpread vs $midSpread)", edgeSpread >= midSpread)
    }

    // ------------------------------------------------------------------ contact sheet (not a check: look at the PNG)

    private val px = 2f

    private enum class Shape { PILL, CARD, ROW, MID }

    private fun smooth(k: Float): Float = IslandGlass.expandedWeight(k)

    private fun toImage(b: IslandGlass.Baked): BufferedImage {
        val img = BufferedImage(b.w, b.h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, b.w, b.h, b.pixels, 0, b.w)
        return img
    }

    /** Draws one island with the live parts (shadow, sheen, rim) the way the Compose code draws them. */
    private fun drawIsland(
        g: Graphics2D,
        ox: Float,
        oy: Float,
        shape: Shape,
        kind: String,
        dark: Boolean,
        shine: Int = 85,
        blur: Int = 70,
    ) {
        val cover = cover(kind)
        val pillW = 200f
        val pillH = 40f
        val cardW = 360f
        val cardH = 152f
        val rowH = 72f
        val (wDp, hDp, cornerDp) = when (shape) {
            Shape.PILL -> Triple(pillW, pillH, pillH / 2f)
            Shape.CARD -> Triple(cardW, cardH, 40f)
            Shape.ROW -> Triple(cardW, rowH, 36f)
            Shape.MID -> {
                val k = 0.5f
                val h = pillH + (cardH - pillH) * k
                val f = ((h - pillH) / (cardH - pillH)).coerceIn(0f, 1f)
                Triple(pillW + (cardW - pillW) * k, h, pillH / 2f + (40f - pillH / 2f) * f)
            }
        }
        val w = wDp * px
        val h = hDp * px
        val corner = cornerDp * px

        // skins: compact + (expanded or row)
        val full = if (shape == Shape.ROW) Triple(cardW, rowH, 36f) else Triple(cardW, cardH, 40f)
        val compactBake = bake(kind, pillW, pillH, pillH / 2f, dark, blur)
        val fullBake = bake(kind, full.first, full.second, full.third, dark, blur)
        val darkInk = IslandGlass.darkInkWins(min(compactBake.p10, fullBake.p10), max(compactBake.p90, fullBake.p90))
        val ink = if (darkInk) Color(0x16, 0x15, 0x1A) else Color.WHITE

        // soft shadow: baked with the glass; the compact and the full one cross-fade like the live canvas does
        val e = when (shape) {
            Shape.PILL -> 0f
            Shape.MID -> smooth(0.5f)
            else -> 1f
        }
        val padDp = 14f
        val padLive = padDp * px
        fun shadowImage(d: IslandGlass.Dims): BufferedImage {
            val pad = IslandGlass.shadowPadPx(padDp, d)
            val img = BufferedImage(d.w + pad * 2, d.h + pad * 2, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, img.width, img.height, IslandGlass.bakeShadow(d, pad, dark), 0, img.width)
            return img
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val shadowBox = intArrayOf((ox - padLive).roundToInt(), (oy - padLive).roundToInt(), (w + padLive * 2).roundToInt(), (h + padLive * 2).roundToInt())
        if (e < 0.999f) {
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f - e)
            g.drawImage(shadowImage(dimsFor(pillW, pillH, pillH / 2f)), shadowBox[0], shadowBox[1], shadowBox[2], shadowBox[3], null)
        }
        if (e > 0.001f) {
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, e)
            g.drawImage(shadowImage(dimsFor(full.first, full.second, full.third)), shadowBox[0], shadowBox[1], shadowBox[2], shadowBox[3], null)
        }
        g.composite = AlphaComposite.SrcOver

        val clip = RoundRectangle2D.Float(ox, oy, w, h, corner * 2, corner * 2)
        val oldClip = g.clip
        g.clip = clip
        // compact skin opaque underneath, the big one fades in on top (see IslandGlass.expandedWeight)
        g.composite = AlphaComposite.SrcOver
        g.drawImage(toImage(compactBake), ox.roundToInt(), oy.roundToInt(), w.roundToInt(), h.roundToInt(), null)
        if (e > 0f) {
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, e)
            g.drawImage(toImage(fullBake), ox.roundToInt(), oy.roundToInt(), w.roundToInt(), h.roundToInt(), null)
            g.composite = AlphaComposite.SrcOver
        }
        // top sheen
        val sheenA = IslandGlass.sheen(dark, shine)
        g.paint = GradientPaint(0f, oy, Color(1f, 1f, 1f, sheenA), 0f, oy + h * 0.5f, Color(1f, 1f, 1f, 0f))
        g.fill(Rectangle2D.Float(ox, oy, w, h * 0.5f))

        drawContent(g, ox, oy, shape, kind, ink, dark)
        g.clip = oldClip

        // rim: two-sided, 45 degrees, equal stops corner to corner
        val rim = IslandGlass.rim(dark, shine)
        val stroke = 0.9f * px
        val rimPaint = LinearGradientPaint(
            Point2D.Float(ox, oy), Point2D.Float(ox + w, oy + h),
            floatArrayOf(0f, 1f / 3f, 2f / 3f, 1f),
            arrayOf(Color(1f, 1f, 1f, rim.hi), Color(1f, 1f, 1f, rim.lo), Color(1f, 1f, 1f, rim.lo), Color(1f, 1f, 1f, rim.glint)),
        )
        g.paint = rimPaint
        g.stroke = BasicStroke(stroke)
        g.draw(RoundRectangle2D.Float(ox + stroke / 2, oy + stroke / 2, w - stroke, h - stroke, (corner - stroke / 2) * 2, (corner - stroke / 2) * 2))
    }

    private fun drawContent(g: Graphics2D, ox: Float, oy: Float, shape: Shape, kind: String, ink: Color, dark: Boolean) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val soft = Color(ink.red, ink.green, ink.blue, 166)
        val art = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, 128, 128, cover(kind), 0, 128) }

        fun thumb(xDp: Float, yDp: Float, sDp: Float) {
            val s = sDp * px
            val r = s * 0.27f * 2
            val rr = RoundRectangle2D.Float(ox + xDp * px, oy + yDp * px, s, s, r, r)
            val old = g.clip
            g.clip = rr
            g.drawImage(art, rr.x.roundToInt(), rr.y.roundToInt(), s.roundToInt(), s.roundToInt(), null)
            g.clip = old
        }

        fun eq(xDp: Float, yDp: Float, wDp: Float, hDp: Float) {
            g.color = ink
            val barW = wDp / 9f
            val levels = floatArrayOf(0.5f, 1f, 0.7f, 0.9f, 0.45f)
            for (i in 0 until 5) {
                val bh = hDp * levels[i]
                g.fill(RoundRectangle2D.Float(ox + (xDp + i * barW * 2) * px, oy + (yDp + (hDp - bh) / 2) * px, barW * px, bh * px, barW * px, barW * px))
            }
        }

        fun text(s: String, xDp: Float, baselineDp: Float, sizeDp: Float, bold: Boolean, color: Color) {
            g.font = Font("SansSerif", if (bold) Font.BOLD else Font.PLAIN, (sizeDp * px).roundToInt())
            g.color = color
            g.drawString(s, ox + xDp * px, oy + baselineDp * px)
        }

        fun glyph(cxDp: Float, cyDp: Float, sizeDp: Float, kindName: String) {
            val s = sizeDp * px * 0.5f
            val cx = ox + cxDp * px
            val cy = oy + cyDp * px
            g.color = ink
            when (kindName) {
                "play" -> {
                    val bar = s * 0.4f
                    g.fill(Rectangle2D.Float(cx - bar * 1.5f, cy - s * 0.85f, bar, s * 1.7f))
                    g.fill(Rectangle2D.Float(cx + bar * 0.5f, cy - s * 0.85f, bar, s * 1.7f))
                }
                "next" -> {
                    g.fill(Path2D.Float().apply { moveTo(cx - s * 0.7f, cy - s * 0.8f); lineTo(cx + s * 0.5f, cy); lineTo(cx - s * 0.7f, cy + s * 0.8f); closePath() })
                    g.fill(Rectangle2D.Float(cx + s * 0.62f, cy - s * 0.8f, s * 0.18f, s * 1.6f))
                }
                else -> {
                    g.fill(Path2D.Float().apply { moveTo(cx + s * 0.7f, cy - s * 0.8f); lineTo(cx - s * 0.5f, cy); lineTo(cx + s * 0.7f, cy + s * 0.8f); closePath() })
                    g.fill(Rectangle2D.Float(cx - s * 0.8f, cy - s * 0.8f, s * 0.18f, s * 1.6f))
                }
            }
        }

        when (shape) {
            Shape.PILL -> {
                thumb(6f, 7f, 26f)
                eq(200f - 14f - 20f, 13f, 20f, 14f)
            }
            Shape.CARD, Shape.MID -> {
                val k = if (shape == Shape.MID) 0.5f else 1f
                if (shape == Shape.MID) {
                    // half way: only the shared cover and the equaliser are there
                    thumb(6f + (20f - 6f) * k, 7f + (16f - 7f) * k, 26f + (52f - 26f) * k)
                    eq(166f + (318f - 166f) * k, 13f + (34f - 13f) * k, 20f + 2f * k, 14f + 2f * k)
                    return
                }
                thumb(20f, 16f, 52f)
                text("Blinding Lights", 84f, 16f + 24f, 16f, true, ink)
                text("The Weeknd", 84f, 16f + 42f, 13f, false, soft)
                eq(318f, 34f, 22f, 16f)
                // progress row
                val y = 16f + 52f + 10f
                text("0:42", 20f, y + 13f, 11f, false, soft)
                val barL = 20f + 40f
                val barR = 360f - 20f - 44f
                g.color = Color(ink.red, ink.green, ink.blue, 51)
                g.fill(RoundRectangle2D.Float(ox + barL * px, oy + (y + 7f) * px, (barR - barL) * px, 4f * px, 4f * px, 4f * px))
                g.color = Color(ink.red, ink.green, ink.blue, 235)
                g.fill(RoundRectangle2D.Float(ox + barL * px, oy + (y + 7f) * px, (barR - barL) * 0.21f * px, 4f * px, 4f * px, 4f * px))
                text("-2:38", 360f - 20f - 36f, y + 13f, 11f, false, soft)
                // buttons
                val by = y + 18f + 4f + 20f
                glyph(180f - 70f, by, 30f, "prev")
                glyph(180f, by, 34f, "play")
                glyph(180f + 70f, by, 30f, "next")
            }
            Shape.ROW -> {
                thumb(12f, 12f, 48f)
                text("Blinding Lights", 72f, 36f - 4f, 15f, true, ink)
                text("The Weeknd", 72f, 36f + 14f, 12.5f, false, soft)
                glyph(360f - 12f - 90f + 18f, 36f, 24f, "prev")
                glyph(360f - 12f - 90f + 54f, 36f, 28f, "play")
                glyph(360f - 12f - 90f + 90f - 18f, 36f, 24f, "next")
            }
        }
    }

    private fun page(w: Int, h: Int, dark: Boolean): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = if (dark) Color(0x16, 0x15, 0x1B) else Color(0xF3, 0xF1, 0xEE)
        g.fillRect(0, 0, w, h)
        // a busy page: rows of text and tiles
        var y = 12
        var i = 0
        while (y < h - 20) {
            g.color = if (dark) Color(255, 255, 255, 40 + (i % 3) * 20) else Color(0, 0, 0, 30 + (i % 3) * 25)
            g.fillRoundRect(16, y, (w * (0.35f + 0.1f * (i % 4))).toInt(), 12, 10, 10)
            g.color = if (dark) Color(120 + (i * 30) % 120, 80, 160, 90) else Color(200, 110 + (i * 25) % 100, 90, 110)
            g.fillRoundRect(w - 90, y - 6, 70, 40, 14, 14)
            y += 36
            i++
        }
        g.dispose()
        return img
    }

    @Test
    fun `render the contact sheet`() {
        val dir = File("build/island-glass").also { it.mkdirs() }
        val colW = (360f * px).roundToInt() + 40
        val rowH = (152f * px).roundToInt() + 50
        val gap = 28
        val sheets = mutableListOf<BufferedImage>()
        for (dark in listOf(true, false)) {
            val sheet = page(colW * 2 + gap, rowH * covers.size + 40, dark)
            val g = sheet.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            covers.forEachIndexed { row, kind ->
                val y = 26 + row * rowH
                drawIsland(g, 20f, y.toFloat(), Shape.CARD, kind, dark)
                // right column: pill on top, the compact row, and the half-open card
                val x2 = colW + gap + 8f
                drawIsland(g, x2 + (360f - 200f) * px / 2f, y.toFloat(), Shape.PILL, kind, dark)
                drawIsland(g, x2, y + 40f * px + 14f, Shape.ROW, kind, dark)
            }
            g.dispose()
            ImageIO.write(sheet, "png", File(dir, if (dark) "sheet-dark.png" else "sheet-light.png"))
            sheets += sheet
        }
        // both appearances side by side
        val both = BufferedImage(sheets.sumOf { it.width }, sheets.maxOf { it.height }, BufferedImage.TYPE_INT_ARGB)
        val gb = both.createGraphics()
        var bx = 0
        for (s in sheets) { gb.drawImage(s, bx, 0, null); bx += s.width }
        gb.dispose()
        ImageIO.write(both, "png", File(dir, "sheet.png"))
        // one close-up of the transition frame and a card per mode at 2x
        val mid = page(colW, (152f * px).roundToInt() + 60, true)
        val gm = mid.createGraphics()
        drawIsland(gm, 20f + 40f, 20f, Shape.MID, "sunset", true)
        gm.dispose()
        ImageIO.write(mid, "png", File(dir, "mid-morph.png"))
        assertTrue(File(dir, "sheet-dark.png").length() > 5000)
    }
}
