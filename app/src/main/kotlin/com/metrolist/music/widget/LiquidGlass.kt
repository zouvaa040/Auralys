package com.metrolist.music.widget

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * "Liquid glass" for the home-screen widget, baked into pixels.
 *
 * A widget is a RemoteViews, so it cannot run a live shader the way the in-app glass does. This is the same recipe
 * (the lens and rim light of Kyant0/AndroidLiquidGlass, Apache-2.0) done once on the CPU for the widget's background:
 *
 *  1. shadow under the pane, cut out where the pane is
 *  2. what is behind the glass (the cover), made more vivid ("vibrancy")
 *  3. the lens: near the edge the picture is pulled in from further inside, so it bends like a thick pane of glass
 *  4. the surface: a veil that keeps the white text readable
 *  5. the highlight: a thin rim that is bright where light hits (top-left) and where it leaves (bottom-right)
 *
 * It only touches int arrays, so it runs (and is tested) without Android.
 */
internal object LiquidGlass {

    class Params(
        val width: Int,
        val height: Int,
        /** Space kept free around the pane for the shadow, in px. */
        val inset: Float,
        /** Corner radius of the pane, in px. */
        val radius: Float,
        /** How far in from the edge the lens works, in px. 0 turns the lens off. */
        val refractionHeight: Float,
        /** How far the picture is pulled in at the very edge, in px. */
        val refractionAmount: Float,
        val saturation: Float = 1.5f,
        /** Drawn over the scene in order, ARGB. */
        val veils: IntArray = IntArray(0),
        /** 0..1, how opaque the pane is overall. */
        val bodyAlpha: Float = 1f,
        val highlightAlpha: Float = 0.5f,
        val highlightWidth: Float = 2f,
        /** A wider, softer light just inside the rim (0 = none). */
        val glowAlpha: Float = 0f,
        val glowWidth: Float = 0f,
        val shadowAlpha: Float = 0.25f,
        val shadowRadius: Float = 4f,
        val shadowOffsetY: Float = 2f,
    )

    /**
     * The glass for a widget of [w] x [h] px, where [px] is pixels per dp. [edge], [shine] and [opacity] are the
     * widget's 0..1 settings, [luma] the brightness of the scene (a bright cover gets a darker veil so the white text
     * stays readable), [clear] the see-through style that has no scene of its own.
     */
    fun paramsFor(w: Int, h: Int, px: Float, cornerDp: Float, edge: Float, shine: Float, opacity: Float, luma: Float, clear: Boolean): Params {
        // No shadow outside the pane: a launcher clips the widget to its box (and some pad or round it), which turned
        // a soft shadow into a dirty rectangle. Only a hair of margin is kept so the rim is not cut by the box.
        val inset = 1.5f
        val pane = min(w, h) - 2f * inset
        val refH = if (clear || edge < 0.02f) 0f else (pane * (0.08f + 0.14f * edge)).coerceIn(5f * px, 30f * px).coerceAtMost(pane / 2f)
        return Params(
            width = w,
            height = h,
            inset = inset,
            radius = min(cornerDp * px, pane / 2f),
            refractionHeight = refH,
            refractionAmount = min(refH * (0.8f + 2.4f * edge), pane * 0.4f),
            veils = if (clear) {
                // for the see-through style the opacity slider is how frosted the pane is
                val frostK = opacity.coerceIn(0.3f, 1f) * 0.9f
                intArrayOf(argb(0.07f * frostK, 255, 255, 255), argb(0.16f * frostK, 0, 0, 0))
            } else {
                intArrayOf(argb(0.10f + 0.32f * luma.coerceIn(0f, 1f), 0, 0, 0), argb(0.05f, 255, 255, 255))
            },
            // a see-through pane over a busy wallpaper only looks messy (a widget cannot blur the wallpaper), so the
            // glass style is always solid and the see-through look is its own style
            bodyAlpha = 1f,
            highlightAlpha = 0.18f + 0.62f * shine,
            highlightWidth = (1.1f + 0.7f * edge) * px,
            glowAlpha = (if (clear) 0.26f else 0.18f) * (0.3f + 0.7f * shine),
            glowWidth = if (refH > 0f) refH else (3f + 5f * edge) * px,
            shadowAlpha = 0f,
        )
    }

    private fun argb(alpha: Float, r: Int, g: Int, b: Int): Int =
        ((alpha * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * [scene] is what sits behind the glass, opaque ARGB, `width * height` pixels, or null for a glass that shows only
     * its own veil (the see-through style). [sharp] is the same picture with little blur: the lens ring at the edge reads
     * from it, so the edge of the glass shows the cover bending while the middle stays frosted. Returns non-premultiplied ARGB ready for `Bitmap.setPixels`.
     */
    fun render(scene: IntArray?, p: Params, sharp: IntArray? = null): IntArray {
        val w = p.width
        val h = p.height
        val out = IntArray(w * h)
        val gw = w - 2f * p.inset
        val gh = h - 2f * p.inset
        if (gw < 2f || gh < 2f) return out

        val cx = w / 2f
        val cy = h / 2f
        val hx = gw / 2f
        val hy = gh / 2f
        val radius = p.radius.coerceIn(0f, min(hx, hy))
        val gradRadius = min(radius * 1.5f, min(hx, hy))
        val refH = p.refractionHeight
        val amount = p.refractionAmount
        val lens = scene != null && refH > 0.5f && amount > 0.5f
        val hw = max(0.5f, p.highlightWidth)
        val glowW = p.glowWidth
        val ringReach = refH * 1.15f

        val shadow = shadowMask(p, cx, cy, hx, hy, radius)
        val grad = FloatArray(2)
        val lensGrad = FloatArray(2)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val px = x + 0.5f - cx
                val py = y + 0.5f - cy
                val sd = sdRoundedRect(px, py, hx, hy, radius)
                val cover = (0.5f - sd).coerceIn(0f, 1f)

                var pr = 0f
                var pg = 0f
                var pb = 0f
                var pa = 0f
                if (cover > 0f) {
                    if (scene != null) {
                        var sx = x.toFloat()
                        var sy = y.toFloat()
                        if (lens && -sd < refH) {
                            val t = 1f - (-min(sd, 0f)) / refH
                            val d = -(1f - sqrt(max(0f, 1f - t * t))) * amount
                            gradSdRoundedRect(px, py, hx, hy, gradRadius, lensGrad)
                            // depthEffect: lean the surface normal towards the centre line so the middle bulges too
                            val len = hypot(px, py)
                            var gx = lensGrad[0]
                            var gy = lensGrad[1]
                            if (len > 1e-4f) {
                                gx += px / len
                                gy += py / len
                            }
                            val gl = hypot(gx, gy)
                            if (gl > 1e-4f) {
                                sx += d * gx / gl
                                sy += d * gy / gl
                            }
                        }
                        var c = sample(scene, w, h, sx, sy)
                        if (sharp != null && lens && -sd < ringReach) {
                            val k = (1f - (-sd) / ringReach).coerceIn(0f, 1f)
                            c = mixRgb(c, sample(sharp, w, h, sx, sy), k * k * (3f - 2f * k))
                        }
                        pr = ((c shr 16) and 0xFF).toFloat()
                        pg = ((c shr 8) and 0xFF).toFloat()
                        pb = (c and 0xFF).toFloat()
                        pa = 1f
                        if (p.saturation != 1f) {
                            val lum = 0.213f * pr + 0.715f * pg + 0.072f * pb
                            pr = (lum + (pr - lum) * p.saturation).coerceIn(0f, 255f)
                            pg = (lum + (pg - lum) * p.saturation).coerceIn(0f, 255f)
                            pb = (lum + (pb - lum) * p.saturation).coerceIn(0f, 255f)
                        }
                    }
                    for (veil in p.veils) {
                        val va = (veil ushr 24) / 255f
                        if (va <= 0f) continue
                        pr = ((veil shr 16) and 0xFF) * va + pr * (1f - va)
                        pg = ((veil shr 8) and 0xFF) * va + pg * (1f - va)
                        pb = (veil and 0xFF) * va + pb * (1f - va)
                        pa = va + pa * (1f - va)
                    }
                    val k = p.bodyAlpha * cover
                    pr *= k
                    pg *= k
                    pb *= k
                    pa *= k

                    // rim light, added on top (Plus blend)
                    val depth = -sd
                    if (depth < max(hw, glowW) + 1f) {
                        gradSdRoundedRect(px, py, hx, hy, gradRadius, grad)
                        val intensity = abs(grad[0] * INV_SQRT2 + grad[1] * INV_SQRT2)
                        var light = 0f
                        if (depth < hw + 1f) {
                            light += p.highlightAlpha * intensity * ((hw - depth) / (hw * 0.5f + 0.5f)).coerceIn(0f, 1f)
                        }
                        if (glowW > 0f && p.glowAlpha > 0f && depth < glowW) {
                            val f = 1f - depth / glowW
                            light += p.glowAlpha * intensity * f * f
                        }
                        light *= cover
                        if (light > 0f) {
                            pr += 255f * light
                            pg += 255f * light
                            pb += 255f * light
                            pa += light
                        }
                    }
                }

                val sa = shadow[i] * p.shadowAlpha * (1f - cover)
                val a = min(1f, pa + sa * (1f - min(1f, pa)))
                if (a <= 0f) continue
                val rr = min(255f, pr) / max(a, 1e-4f)
                val gg = min(255f, pg) / max(a, 1e-4f)
                val bb = min(255f, pb) / max(a, 1e-4f)
                out[i] = ((a * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or
                    (rr.toInt().coerceIn(0, 255) shl 16) or
                    (gg.toInt().coerceIn(0, 255) shl 8) or
                    bb.toInt().coerceIn(0, 255)
            }
        }
        return out
    }

    /** Soft shadow of the pane: its outline shifted down and blurred. 0..1 per pixel. */
    private fun shadowMask(p: Params, cx: Float, cy: Float, hx: Float, hy: Float, radius: Float): FloatArray {
        val w = p.width
        val h = p.height
        val mask = FloatArray(w * h)
        if (p.shadowAlpha <= 0f || p.shadowRadius <= 0f) return mask
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sd = sdRoundedRect(x + 0.5f - cx, y + 0.5f - cy - p.shadowOffsetY, hx, hy, radius)
                mask[y * w + x] = (0.5f - sd).coerceIn(0f, 1f)
            }
        }
        val box = max(1, (p.shadowRadius * 0.6f).toInt())
        repeat(3) { blurPlane(mask, w, h, box) }
        return mask
    }

    /** Box-blurs the picture three times, which is close enough to a gaussian. [radius] is in px. */
    fun blur(scene: IntArray, w: Int, h: Int, radius: Float): IntArray {
        val box = (radius * 0.6f).toInt()
        if (box < 1) return scene
        val r = FloatArray(w * h) { ((scene[it] shr 16) and 0xFF).toFloat() }
        val g = FloatArray(w * h) { ((scene[it] shr 8) and 0xFF).toFloat() }
        val b = FloatArray(w * h) { (scene[it] and 0xFF).toFloat() }
        for (plane in arrayOf(r, g, b)) repeat(3) { blurPlane(plane, w, h, box) }
        return IntArray(w * h) {
            (0xFF shl 24) or
                (r[it].toInt().coerceIn(0, 255) shl 16) or
                (g[it].toInt().coerceIn(0, 255) shl 8) or
                b[it].toInt().coerceIn(0, 255)
        }
    }

    /** Average brightness of the picture, 0..1 (rough relative luminance). */
    fun luma(scene: IntArray): Float {
        if (scene.isEmpty()) return 0f
        var sum = 0.0
        val step = max(1, scene.size / 4000)
        var n = 0
        var i = 0
        while (i < scene.size) {
            val c = scene[i]
            sum += 0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)
            n++
            i += step
        }
        return (sum / n / 255.0).toFloat()
    }

    /**
     * A picture to put behind the glass when there is no cover: a vertical wash of [base] with two soft blobs, so the
     * lens has something to bend.
     */
    fun syntheticScene(w: Int, h: Int, base: Int): IntArray {
        val br = (base shr 16) and 0xFF
        val bg = (base shr 8) and 0xFF
        val bb = base and 0xFF
        val reach = max(w, h).toFloat()
        return IntArray(w * h) { i ->
            val x = (i % w) / w.toFloat()
            val y = (i / w) / h.toFloat()
            // wash: lighter at the top, darker at the bottom
            val wash = 1.28f - 0.62f * y
            var r = br * wash
            var g = bg * wash
            var b = bb * wash
            // blob 1: bright, top-left
            val d1 = hypot((x - 0.18f) * w, (y - 0.22f) * h) / (reach * 0.55f)
            val f1 = (1f - d1).coerceIn(0f, 1f)
            r += (255f - r) * f1 * f1 * 0.45f
            g += (255f - g) * f1 * f1 * 0.45f
            b += (255f - b) * f1 * f1 * 0.45f
            // blob 2: a shifted hue, bottom-right
            val d2 = hypot((x - 0.85f) * w, (y - 0.85f) * h) / (reach * 0.5f)
            val f2 = (1f - d2).coerceIn(0f, 1f)
            r += (bb - r) * f2 * f2 * 0.55f
            g += (br - g) * f2 * f2 * 0.55f
            b += (bg - b) * f2 * f2 * 0.55f
            (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }
    }

    // ----------------------------------------------------------------- geometry (the AGSL of the library, in Kotlin)

    private const val INV_SQRT2 = 0.70710678f

    private fun sdRoundedRect(x: Float, y: Float, hx: Float, hy: Float, r: Float): Float {
        val qx = abs(x) - (hx - r)
        val qy = abs(y) - (hy - r)
        val outside = hypot(max(qx, 0f), max(qy, 0f)) - r
        val inside = min(max(qx, qy), 0f)
        return outside + inside
    }

    private fun gradSdRoundedRect(x: Float, y: Float, hx: Float, hy: Float, r: Float, out: FloatArray) {
        val qx = abs(x) - (hx - r)
        val qy = abs(y) - (hy - r)
        val sx = if (x > 0f) 1f else if (x < 0f) -1f else 0f
        val sy = if (y > 0f) 1f else if (y < 0f) -1f else 0f
        if (qx >= 0f || qy >= 0f) {
            val mx = max(qx, 0f)
            val my = max(qy, 0f)
            val len = hypot(mx, my)
            if (len < 1e-6f) {
                out[0] = 0f
                out[1] = 0f
            } else {
                out[0] = sx * mx / len
                out[1] = sy * my / len
            }
        } else {
            val gx = if (qx >= qy) 1f else 0f
            out[0] = sx * gx
            out[1] = sy * (1f - gx)
        }
    }

    // ----------------------------------------------------------------- sampling and blur

    private fun mixRgb(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = ((((a shr shift) and 0xFF) * (1f - t) + ((b shr shift) and 0xFF) * t) + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Bilinear read at a position given in pixel units (pixel `i` covers `i .. i+1`, its centre is `i + 0.5`). */
    private fun sample(scene: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val fx = x - 0.5f
        val fy = y - 0.5f
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        val xa = x0.coerceIn(0, w - 1)
        val xb = (x0 + 1).coerceIn(0, w - 1)
        val ya = y0.coerceIn(0, h - 1)
        val yb = (y0 + 1).coerceIn(0, h - 1)
        val c00 = scene[ya * w + xa]
        val c10 = scene[ya * w + xb]
        val c01 = scene[yb * w + xa]
        val c11 = scene[yb * w + xb]
        fun channel(shift: Int): Int {
            val top = ((c00 shr shift) and 0xFF) * (1f - tx) + ((c10 shr shift) and 0xFF) * tx
            val bottom = ((c01 shr shift) and 0xFF) * (1f - tx) + ((c11 shr shift) and 0xFF) * tx
            return (top * (1f - ty) + bottom * ty + 0.5f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** One box-blur pass over a plane, horizontally then vertically, repeating the edge pixels outwards. */
    private fun blurPlane(plane: FloatArray, w: Int, h: Int, r: Int) {
        val size = 2 * r + 1
        val line = FloatArray(max(w, h))
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (k in -r..r) sum += plane[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                line[x] = sum / size
                sum += plane[row + (x + r + 1).coerceIn(0, w - 1)] - plane[row + (x - r).coerceIn(0, w - 1)]
            }
            System.arraycopy(line, 0, plane, row, w)
        }
        for (x in 0 until w) {
            var sum = 0f
            for (k in -r..r) sum += plane[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                line[y] = sum / size
                sum += plane[(y + r + 1).coerceIn(0, h - 1) * w + x] - plane[(y - r).coerceIn(0, h - 1) * w + x]
            }
            for (y in 0 until h) plane[y * w + x] = line[y]
        }
    }
}
