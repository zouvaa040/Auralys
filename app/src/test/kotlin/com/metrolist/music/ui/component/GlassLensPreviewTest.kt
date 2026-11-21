package com.metrolist.music.ui.component

import com.metrolist.music.ui.component.SoftLens.Img
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Software preview of the nav bar and the mini player glass (before / after / BitChord numbers) over a colourful page.
 * It runs the pure-Kotlin port of the lens shader ([SoftLens]); the real GPU look can only be judged on a phone.
 * PNGs go to `build/glass-lens/`.
 */
class GlassLensPreviewTest {
    private val dpr = 2.75f // px per dp of a 1080 px wide phone

    private fun dp(v: Float) = (v * dpr)

    // ------------------------------------------------------------------ the page behind the glass

    private val covers = listOf(
        Triple(232, 74, 60), Triple(246, 170, 40), Triple(70, 170, 120), Triple(60, 120, 220),
        Triple(150, 90, 210), Triple(235, 90, 160), Triple(30, 30, 40), Triple(240, 235, 225),
    )

    private fun page(dark: Boolean, wDp: Float, hDp: Float): Img {
        val w = dp(wDp).roundToInt()
        val h = dp(hDp).roundToInt()
        val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = bi.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = if (dark) Color(0x0E, 0x0F, 0x12) else Color(0xF6, 0xF6, 0xF8)
        g.fillRect(0, 0, w, h)
        // a shelf of big covers, then song rows: what scrolls under the bar
        for (i in 0 until 6) {
            val c = covers[i % covers.size]
            g.color = Color(c.first, c.second, c.third)
            val x = dp(14f + i * 66f).roundToInt()
            g.fillRoundRect(x, dp(8f).roundToInt(), dp(58f).roundToInt(), dp(58f).roundToInt(), dp(8f).roundToInt(), dp(8f).roundToInt())
        }
        var y = 72f
        var i = 0
        while (y < hDp) {
            val c = covers[(i + 3) % covers.size]
            g.color = Color(c.first, c.second, c.third)
            g.fillRoundRect(dp(16f).roundToInt(), dp(y).roundToInt(), dp(44f).roundToInt(), dp(44f).roundToInt(), dp(6f).roundToInt(), dp(6f).roundToInt())
            g.color = if (dark) Color(235, 235, 240) else Color(25, 26, 30)
            g.fillRoundRect(dp(70f).roundToInt(), dp(y + 6f).roundToInt(), dp(150f - (i % 3) * 22f).roundToInt(), dp(10f).roundToInt(), 6, 6)
            g.color = if (dark) Color(130, 132, 140) else Color(120, 122, 130)
            g.fillRoundRect(dp(70f).roundToInt(), dp(y + 24f).roundToInt(), dp(100f + (i % 2) * 30f).roundToInt(), dp(8f).roundToInt(), 6, 6)
            g.color = if (dark) Color(80, 82, 90) else Color(190, 192, 198)
            g.fillOval(dp(352f).roundToInt(), dp(y + 16f).roundToInt(), dp(12f).roundToInt(), dp(12f).roundToInt())
            y += 56f
            i++
        }
        g.dispose()
        return fromBuffered(bi)
    }

    private fun fromBuffered(bi: BufferedImage): Img {
        val img = Img(bi.width, bi.height)
        for (y in 0 until bi.height) for (x in 0 until bi.width) {
            val p = bi.getRGB(x, y)
            val o = y * bi.width + x
            img.r[o] = ((p shr 16) and 255) / 255f
            img.g[o] = ((p shr 8) and 255) / 255f
            img.b[o] = (p and 255) / 255f
        }
        return img
    }

    private fun toBuffered(img: Img): BufferedImage {
        val bi = BufferedImage(img.w, img.h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.h) for (x in 0 until img.w) {
            val o = y * img.w + x
            val r = (img.r[o].coerceIn(0f, 1f) * 255f).roundToInt()
            val g = (img.g[o].coerceIn(0f, 1f) * 255f).roundToInt()
            val b = (img.b[o].coerceIn(0f, 1f) * 255f).roundToInt()
            bi.setRGB(x, y, (r shl 16) or (g shl 8) or b)
        }
        return bi
    }

    // ------------------------------------------------------------------ one glass surface

    class Look(
        val name: String,
        val blurDp: Float,
        val sat: Float,
        val heightDp: Float,
        val amountDp: Float,
        val depth: Boolean,
        val dispersion: Float,
        val veilRgb: Int,
        val veilAlpha: Float,
        val rimAlpha: Float = 0.5f,
    )

    private fun surface(
        pageImg: Img,
        look: Look,
        xDp: Float,
        yDp: Float,
        wDp: Float,
        hDp: Float,
        radiusDp: Float,
    ): Img {
        val out = pageImg.copy()
        val base = pageImg.copy()
        SoftLens.saturate(base, look.sat)
        SoftLens.blur(base, dp(look.blurDp))
        val spec = SoftLens.Spec(
            w = dp(wDp), h = dp(hDp), radius = dp(radiusDp),
            height = dp(look.heightDp), amount = dp(look.amountDp),
            depth = look.depth, dispersion = look.dispersion,
        )
        val x0 = dp(xDp).roundToInt()
        val y0 = dp(yDp).roundToInt()
        val lensed = base.copy()
        if (look.heightDp > 0f && look.amountDp > 0f) SoftLens.apply(base, lensed, spec, x0, y0)
        // veil, then the rim, then the mask
        val vr = ((look.veilRgb shr 16) and 255) / 255f
        val vg = ((look.veilRgb shr 8) and 255) / 255f
        val vb = (look.veilRgb and 255) / 255f
        for (yy in 0 until spec.h.toInt()) for (xx in 0 until spec.w.toInt()) {
            val o = (y0 + yy) * out.w + (x0 + xx)
            val a = look.veilAlpha
            lensed.r[o] = lensed.r[o] * (1 - a) + vr * a
            lensed.g[o] = lensed.g[o] * (1 - a) + vg * a
            lensed.b[o] = lensed.b[o] * (1 - a) + vb * a
        }
        SoftLens.rim(lensed, spec, x0, y0, band = dp(1.0f), alpha = look.rimAlpha)
        for (yy in 0 until spec.h.toInt()) for (xx in 0 until spec.w.toInt()) {
            val k = SoftLens.coverage(spec, xx, yy)
            if (k <= 0f) continue
            val o = (y0 + yy) * out.w + (x0 + xx)
            out.r[o] = out.r[o] * (1 - k) + lensed.r[o] * k
            out.g[o] = out.g[o] * (1 - k) + lensed.g[o] * k
            out.b[o] = out.b[o] * (1 - k) + lensed.b[o] * k
        }
        return out
    }

    // ------------------------------------------------------------------ the looks

    private fun navLooks(dark: Boolean): List<Look> {
        val veilRgb = if (dark) 0x101216 else 0xFFFFFF
        val g1 = if (dark) 0.62f else 0.58f
        val n = GlassLensRecipes.NavBar
        val bc = GlassLensRecipes.BitChord
        val bcRgb = if (dark) 0x121212 else 0xFAFAFA
        return listOf(
            Look("BEFORE (blur 20, plain lens 24/24, veil G1)", 20f, 1.5f, 24f, 24f, false, 0f, veilRgb, g1),
            Look("AFTER Auralys (NavBar recipe)", n.blurDp, n.saturation, n.lensHeightDp, n.lensAmountDp, n.depth, n.dispersion, veilRgb, g1 * n.veilScale),
            Look("BitChord (blur 8, lens 24/24 depth + dispersion, veil 0.4)", bc.blurDp, bc.saturation, bc.lensHeightDp, bc.lensAmountDp, bc.depth, bc.dispersion, bcRgb, 0.4f),
            Look("BLUR mode (no lens)", 20f, 1.5f, 0f, 0f, false, 0f, veilRgb, g1),
        )
    }

    private fun miniLooks(dark: Boolean): List<Look> {
        val veilRgb = if (dark) 0x101216 else 0xFFFFFF
        val g1 = if (dark) 0.62f else 0.58f
        val m = GlassLensRecipes.MiniPlayer
        return listOf(
            Look("BEFORE (blur 8, plain lens 16/22, veil G1)", 8f, 1.6f, 16f, 22f, false, 0f, veilRgb, g1),
            Look("AFTER Auralys (MiniPlayer recipe)", m.blurDp, m.saturation, m.lensHeightDp, m.lensAmountDp, m.depth, m.dispersion, veilRgb, g1 * m.veilScale),
            Look("BLUR mode (no lens)", 8f, 1.6f, 0f, 0f, false, 0f, veilRgb, g1),
        )
    }

    private fun sheet(dark: Boolean, looks: List<Look>, hDp: Float, barY: Float, barH: Float, barRadius: Float, barX: Float, barW: Float): BufferedImage {
        val pg = page(dark, 393f, hDp)
        val frames = looks.map { toBuffered(surface(pg, it, barX, barY, barW, barH, barRadius)) }
        val labelH = 26
        val w = frames[0].width
        val h = frames[0].height
        val sheet = BufferedImage(w, (h + labelH) * frames.size, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = if (dark) Color(0x20, 0x20, 0x24) else Color(0xE0, 0xE0, 0xE4)
        g.fillRect(0, 0, sheet.width, sheet.height)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
        frames.forEachIndexed { i, f ->
            g.color = if (dark) Color.WHITE else Color.BLACK
            g.drawString((if (dark) "DARK  " else "LIGHT  ") + looks[i].name, 12, i * (h + labelH) + 18)
            g.drawImage(f, 0, i * (h + labelH) + labelH, null)
        }
        g.dispose()
        return sheet
    }

    @Test
    fun `render the nav bar and mini player previews`() {
        val dir = File("build/glass-lens").also { it.mkdirs() }
        for (dark in listOf(false, true)) {
            val nav = sheet(dark, navLooks(dark), 150f, 40f, 72f, 36f, 22f, 349f)
            ImageIO.write(nav, "png", File(dir, "nav-${if (dark) "dark" else "light"}.png"))
            val mini = sheet(dark, miniLooks(dark), 140f, 36f, 64f, 28f, 12f, 369f)
            ImageIO.write(mini, "png", File(dir, "mini-${if (dark) "dark" else "light"}.png"))
        }
        assertTrue(File(dir, "nav-light.png").length() > 10_000)
    }

    @Test
    fun `the lens changes the picture at the rim and leaves the middle alone`() {
        val pg = page(false, 393f, 150f)
        val n = GlassLensRecipes.NavBar
        val none = surface(pg, Look("a", n.blurDp, 1.5f, 0f, 0f, false, 0f, 0xFFFFFF, 0f), 22f, 40f, 349f, 72f, 36f)
        val lens = surface(pg, Look("b", n.blurDp, 1.5f, n.lensHeightDp, n.lensAmountDp, n.depth, n.dispersion, 0xFFFFFF, 0f), 22f, 40f, 349f, 72f, 36f)
        // the middle row of the bar, far from the edge: identical
        val cx = dp(22f + 349f / 2f).roundToInt()
        val cy = dp(40f + 36f).roundToInt()
        val mid = cy * pg.w + cx
        assertTrue(abs(none.r[mid] - lens.r[mid]) < 1e-4f)
        // a pixel on the rim row, one dp inside the top edge: it has to differ somewhere along the bar
        var maxDiff = 0f
        val ry = dp(41.5f).roundToInt()
        for (x in dp(60f).roundToInt()..dp(330f).roundToInt()) {
            val o = ry * pg.w + x
            maxDiff = maxOf(maxDiff, abs(none.r[o] - lens.r[o]), abs(none.g[o] - lens.g[o]), abs(none.b[o] - lens.b[o]))
        }
        assertTrue("the rim row did not change (max diff $maxDiff)", maxDiff > 0.02f)
    }
}
