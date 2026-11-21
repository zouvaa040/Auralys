package com.metrolist.music.widget

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassTest {
    private val px = 2.5f

    /** A stand-in for album art: rings, stripes and a sun, so the lens has plenty to bend. */
    private fun cover(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
        val x = (i % w) / w.toFloat()
        val y = (i / w) / h.toFloat()
        val d = hypot(x - 0.5f, y - 0.5f)
        var r = 40f + 120f * y
        var g = 30f + 60f * x
        var b = 120f + 100f * (1f - y)
        if ((d * 9f).toInt() % 2 == 0) { r += 60f; g += 40f }
        if (hypot(x - 0.3f, y - 0.3f) < 0.12f) { r = 255f; g = 200f; b = 60f }
        if (x > 0.62f && ((y * 12f).toInt() % 2 == 0)) { r = 20f; g = 20f; b = 40f }
        (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
    }

    private fun params(w: Int, h: Int, edge: Float = 0.6f, clear: Boolean = false, scene: IntArray? = null) =
        LiquidGlass.paramsFor(w, h, px, 4f + 36f * 0.66f, edge, 0.7f, 1f, if (scene != null) LiquidGlass.luma(scene) else 0f, clear)

    private fun sceneFor(w: Int, h: Int, frost: Float = 0.4f): IntArray {
        val raw = cover(w, h)
        return LiquidGlass.blur(raw, w, h, (1.5f + 12.5f * frost) * px)
    }

    private fun paint(img: BufferedImage, pix: IntArray, w: Int, h: Int, ox: Int, oy: Int) {
        for (y in 0 until h) for (x in 0 until w) {
            val c = pix[y * w + x]
            val a = (c ushr 24) / 255f
            if (a <= 0f) continue
            val under = img.getRGB(ox + x, oy + y)
            fun mix(s: Int) = (((c shr s) and 0xFF) * a + ((under shr s) and 0xFF) * (1f - a)).toInt().coerceIn(0, 255)
            img.setRGB(ox + x, oy + y, (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0))
        }
    }

    @Test
    fun `contact sheet of every shape`() {
        val shapes = listOf(320 to 150, 170 to 170, 320 to 72, 150 to 270, 72 to 72)
        val styles = listOf("cover", "clear", "plain")
        val sheetW = (shapes.sumOf { (it.first * px).toInt() + 24 } + 24)
        val rowH = (shapes.maxOf { (it.second * px).toInt() } + 24)
        val img = BufferedImage(sheetW, rowH * styles.size + 24, BufferedImage.TYPE_INT_ARGB)
        // a busy "wallpaper" so the see-through style has something to show
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val t = x / img.width.toFloat()
            val s = y / img.height.toFloat()
            val stripe = if (((x + y) / 40) % 2 == 0) 25 else 0
            img.setRGB(x, y, (0xFF shl 24) or ((30 + 90 * t + stripe).toInt() shl 16) or ((60 + 80 * s).toInt() shl 8) or (140 + (80 * (1f - t)).toInt()))
        }
        styles.forEachIndexed { row, style ->
            var ox = 24
            for ((wDp, hDp) in shapes) {
                val w = (wDp * px).toInt()
                val h = (hDp * px).toInt()
                val scene = when (style) {
                    "cover" -> sceneFor(w, h)
                    "plain" -> LiquidGlass.blur(LiquidGlass.syntheticScene(w, h, 0xFF6B5A33.toInt()), w, h, 6f * px)
                    else -> null
                }
                val pix = LiquidGlass.render(scene, params(w, h, clear = style == "clear", scene = scene))
                paint(img, pix, w, h, ox, 24 + row * rowH)
                ox += w + 24
            }
        }
        val dir = File("build/liquid-glass").also { it.mkdirs() }
        ImageIO.write(img, "png", File(dir, "sheet.png"))
    }

    @Test
    fun `corners are cut and the middle is filled`() {
        val w = 800
        val h = 380
        val scene = sceneFor(w, h)
        val out = LiquidGlass.render(scene, params(w, h, scene = scene))
        assertEquals("outer corner is clear", 0, out[0] ushr 24)
        assertTrue("middle is solid", (out[(h / 2) * w + w / 2] ushr 24) >= 250)
    }

    @Test
    fun `the lens bends the picture near the edge and leaves the middle alone`() {
        val w = 800
        val h = 380
        val scene = sceneFor(w, h, frost = 0.1f)
        val flat = LiquidGlass.render(scene, LiquidGlass.paramsFor(w, h, px, 28f, 0f, 0f, 1f, 0f, false))
        val lensed = LiquidGlass.render(scene, LiquidGlass.paramsFor(w, h, px, 28f, 0.8f, 0f, 1f, 0f, false))
        val mid = (h / 2) * w + w / 2
        assertEquals("middle unchanged", flat[mid], lensed[mid])
        var differing = 0
        for (x in 12 until 60) if (flat[(h / 2) * w + x] != lensed[(h / 2) * w + x]) differing++
        assertTrue("edge pixels change ($differing)", differing > 10)
    }

    @Test
    fun `the rim is brighter at the top-left than on the left-middle, and nothing is NaN`() {
        val w = 640
        val h = 300
        val scene = sceneFor(w, h)
        val on = LiquidGlass.render(scene, params(w, h, scene = scene))
        val off = LiquidGlass.render(
            scene,
            LiquidGlass.Params(w, h, 4f, 60f, 0f, 0f, highlightAlpha = 0f, glowAlpha = 0f, shadowAlpha = 0f),
        )
        fun lum(c: Int) = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
        // along the top edge, just inside the pane, the rim adds light
        val i = 12 * w + w / 2
        assertTrue("rim adds light on the top edge", lum(on[i]) > lum(off[i]) - 40)
        assertNotEquals(0, on.count { it != 0 })
    }

    @Test
    fun `tiny and extreme sizes do not break`() {
        for ((w, h) in listOf(24 to 24, 60 to 60, 900 to 40, 40 to 900, 5 to 5)) {
            val scene = LiquidGlass.syntheticScene(w, h, 0xFF336699.toInt())
            val out = LiquidGlass.render(scene, params(w, h, scene = scene))
            assertEquals(w * h, out.size)
            val clear = LiquidGlass.render(null, params(w, h, clear = true))
            assertEquals(w * h, clear.size)
        }
    }

    /**
     * Not a check: draws the stand-in pictures the widget shows before its first real draw (widget list, first frame),
     * with the same glass as the live widget, into build/liquid-glass. They are copied to res/drawable-nodpi by hand when
     * the look of the glass changes.
     */
    @Test
    fun `render the first-frame pictures`() {
        val dir = File("build/liquid-glass").also { it.mkdirs() }
        val w = 640
        val h = 300
        val scene = LiquidGlass.blur(LiquidGlass.syntheticScene(w, h, 0xFF6A4FD0.toInt()), w, h, 12f)
        val sharp = LiquidGlass.blur(LiquidGlass.syntheticScene(w, h, 0xFF6A4FD0.toInt()), w, h, 2.4f)
        val glass = LiquidGlass.render(scene, LiquidGlass.paramsFor(w, h, 2f, 27.8f, 0.6f, 0.7f, 1f, LiquidGlass.luma(scene), false), sharp)
        writePng(File(dir, "aw_preview_glass.png"), glass, w, h)

        val s = 192
        val tile = IntArray(s * s) { i ->
            val x = (i % s) / s.toFloat()
            val y = (i / s) / s.toFloat()
            var r = 176 - 118 * (x + y) / 2f
            var g = 32 - 0 * x
            var b = 106 - 16 * (x + y) / 2f
            if (hypot(x - 0.7f, y - 0.3f) < 0.22f) { r = 255f; g = 200f; b = 87f }
            val sd = kotlin.math.min(kotlin.math.min(i % s, s - 1 - i % s), kotlin.math.min(i / s, s - 1 - i / s)).toFloat()
            val corner = 0.2f * s
            val cx = kotlin.math.max(corner - (i % s), kotlin.math.max((i % s) - (s - 1 - corner), 0f)).coerceAtLeast(0f)
            val cy = kotlin.math.max(corner - (i / s), kotlin.math.max((i / s) - (s - 1 - corner), 0f)).coerceAtLeast(0f)
            val inside = hypot(cx, cy) <= corner
            val a = if (inside) 255 else 0
            (a shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }
        writePng(File(dir, "aw_preview_cover.png"), tile, s, s)
    }

    private fun writePng(file: File, pixels: IntArray, w: Int, h: Int) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, w, h, pixels, 0, w)
        ImageIO.write(img, "png", file)
    }
}
