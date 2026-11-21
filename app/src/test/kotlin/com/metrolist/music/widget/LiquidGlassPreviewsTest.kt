package com.metrolist.music.widget

import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.min
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Not a check on behaviour: draws the pictures the widget list shows for each size (res/drawable-nodpi/aw_preview_*.png)
 * with the live widget's own glass and layout numbers, so the picker shows what the widget really looks like. The files
 * land in build/liquid-glass and are copied to the resources by hand when the look changes.
 */
class LiquidGlassPreviewsTest {
    private val px = 2f

    @Test
    fun `draw the widget list previews`() {
        val dir = File("build/liquid-glass").also { it.mkdirs() }
        for ((name, size) in listOf(
            "wide" to (320 to 150),
            "square" to (160 to 160),
            "strip" to (320 to 72),
            "tall" to (150 to 300),
            "tiny" to (72 to 72),
        )) {
            val file = File(dir, "aw_preview_$name.png")
            draw(file, size.first, size.second)
            assertTrue("$name written", file.length() > 1000)
        }
    }

    private fun draw(file: File, wDp: Int, hDp: Int) {
        val w = (wDp * px).toInt()
        val h = (hDp * px).toInt()
        val raw = LiquidGlass.syntheticScene(w, h, 0xFF6A4FD0.toInt())
        val scene = LiquidGlass.blur(raw, w, h, 12f)
        val sharp = LiquidGlass.blur(raw, w, h, 2.4f)
        val cornerDp = (4f + 36f * 0.66f).coerceAtMost(min(wDp, hDp) / 2f)
        val glass = LiquidGlass.render(scene, LiquidGlass.paramsFor(w, h, px, cornerDp, 0.6f, 0.7f, 1f, LiquidGlass.luma(scene), false), sharp)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, w, h, glass, 0, w)

        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val spec = AuralysWidgetLayout.compute(wDp.toFloat(), hDp.toFloat(), WidgetLayoutInput())
        val white = Color.WHITE
        val soft = Color(255, 255, 255, 179)

        fun text(rect: WRect?, s: String, sizeDp: Float, bold: Boolean, color: Color) {
            rect ?: return
            val font = Font("SansSerif", if (bold) Font.BOLD else Font.PLAIN, (sizeDp * px).toInt())
            g.font = font
            g.color = color
            val fm = g.fontMetrics
            var t = s
            val room = (rect.w * px).toInt()
            while (t.length > 1 && fm.stringWidth(t) > room) t = t.dropLast(2) + "…"
            val baseline = (rect.y + rect.h / 2f) * px + (fm.ascent - fm.descent) / 2f
            g.drawString(t, rect.x * px, baseline)
        }

        // cover: the corners follow the glass's, like the live widget
        spec.cover?.let { c ->
            val gap = min(min(c.x, c.y), min(wDp - c.right, hDp - c.bottom)).coerceAtLeast(0f)
            val inner = (cornerDp - gap).coerceAtLeast(4f) * px * 2f
            val shape = RoundRectangle2D.Float(c.x * px, c.y * px, c.w * px, c.h * px, inner, inner)
            g.paint = GradientPaint(c.x * px, c.y * px, Color(0xB0, 0x20, 0x6A), (c.x + c.w) * px, (c.y + c.h) * px, Color(0x3A, 0x2A, 0x5A))
            g.fill(shape)
            g.clip = shape
            g.color = Color(255, 200, 87)
            val sun = c.w * px * 0.42f
            g.fill(Ellipse2D.Float((c.x + c.w * 0.5f) * px, c.y * px + c.h * px * 0.08f, sun, sun))
            g.clip = null
        }

        text(spec.title, "Blinding Lights", spec.titleSize, true, white)
        text(spec.artist, "The Weeknd", spec.artistSize, false, soft)

        spec.like?.let { b ->
            val s = spec.likeIcon * px
            val cx = (b.x + b.w / 2f) * px
            val cy = (b.y + b.h / 2f) * px
            val heart = Path2D.Float().apply {
                moveTo(cx, cy + s * 0.42f)
                curveTo(cx - s * 0.75f, cy - s * 0.05f, cx - s * 0.38f, cy - s * 0.62f, cx, cy - s * 0.2f)
                curveTo(cx + s * 0.38f, cy - s * 0.62f, cx + s * 0.75f, cy - s * 0.05f, cx, cy + s * 0.42f)
                closePath()
            }
            g.color = Color(0xFF, 0x5C, 0x7A)
            g.fill(heart)
        }

        fun skip(b: WRect?, forward: Boolean) {
            b ?: return
            val s = spec.skipIcon * px * 0.5f
            val cx = (b.x + b.w / 2f) * px
            val cy = (b.y + b.h / 2f) * px
            val tri = Path2D.Float().apply {
                if (forward) {
                    moveTo(cx - s * 0.7f, cy - s); lineTo(cx + s * 0.5f, cy); lineTo(cx - s * 0.7f, cy + s)
                } else {
                    moveTo(cx + s * 0.7f, cy - s); lineTo(cx - s * 0.5f, cy); lineTo(cx + s * 0.7f, cy + s)
                }
                closePath()
            }
            g.color = white
            g.fill(tri)
            val barX = if (forward) cx + s * 0.62f else cx - s * 0.62f - s * 0.18f
            g.fill(java.awt.geom.Rectangle2D.Float(barX, cy - s, s * 0.18f, s * 2f))
        }
        skip(spec.prev, false)
        skip(spec.next, true)

        spec.play?.let { b ->
            val d = spec.playDisc * px
            val cx = (b.x + b.w / 2f) * px
            val cy = (b.y + b.h / 2f) * px
            g.color = Color(255, 255, 255, 242)
            g.fill(Ellipse2D.Float(cx - d / 2f, cy - d / 2f, d, d))
            g.color = Color(0x1B, 0x1B, 0x1F)
            val bar = spec.playIcon * px * 0.2f
            val tall = spec.playIcon * px * 0.62f
            g.fill(java.awt.geom.Rectangle2D.Float(cx - bar * 1.5f, cy - tall / 2f, bar, tall))
            g.fill(java.awt.geom.Rectangle2D.Float(cx + bar * 0.5f, cy - tall / 2f, bar, tall))
        }

        spec.progress?.let { b ->
            val r = b.h * px
            g.color = Color(255, 255, 255, 70)
            g.fill(RoundRectangle2D.Float(b.x * px, b.y * px, b.w * px, r, r, r))
            g.color = white
            g.fill(RoundRectangle2D.Float(b.x * px, b.y * px, b.w * px * 0.35f, r, r, r))
        }
        text(spec.timeNow, "1:10", spec.timeSize, false, soft)
        text(spec.timeTotal, "3:20", spec.timeSize, false, soft)
        g.dispose()
        ImageIO.write(img, "png", file)
    }
}
