package com.metrolist.music.ui.component

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A pure-JVM software copy of the lens maths in `backdrop/internal/Shaders.kt` (AGSL `RoundedRectRefractionShaderString`
 * and `RoundedRectRefractionWithDispersionShaderString`, the 3-sample dispersion), line by line. The JVM cannot run a
 * RuntimeShader, so this is how the numbers are tuned by eye and how the formulas are pinned by tests. It is NOT the
 * real GPU result: filtering, the half-resolution capture and the real blur kernel differ.
 *
 * Coordinates are pixels of the surface, origin at its top-left corner, like the shader's `coord`.
 */
internal object SoftLens {
    class Spec(
        val w: Float,
        val h: Float,
        /** Same corner radius on the four corners (a capsule: min(w, h) / 2). */
        val radius: Float,
        /** `refractionHeight` of lens(): how deep the bent rim reaches. */
        val height: Float,
        /** `refractionAmount` of lens() as the caller passes it (positive; the library negates it). */
        val amount: Float,
        val depth: Boolean,
        /** `chromaticAberration` uniform: 0 = the plain shader. */
        val dispersion: Float,
        val fastCircle: Boolean = false,
    ) {
        val halfW get() = w * 0.5f
        val halfH get() = h * 0.5f
        val minHalf get() = min(w, h) * 0.5f
    }

    /** What the shader computes for one pixel. [inside] is false where it returns `content.eval(coord)` untouched. */
    class Sample(
        val inside: Boolean,
        val sd: Float,
        /** The (negative) displacement length d. */
        val d: Float,
        val gradX: Float,
        val gradY: Float,
        val refractedX: Float,
        val refractedY: Float,
        /** Offset of the red sample (the blue one is the opposite). */
        val dispX: Float,
        val dispY: Float,
    )

    fun circleMap(x: Float, fast: Boolean = false): Float = if (fast) x * x else 1f - sqrt(1f - x * x)

    fun sdRoundedRect(px: Float, py: Float, hx: Float, hy: Float, radius: Float): Float {
        val cx = abs(px) - (hx - radius)
        val cy = abs(py) - (hy - radius)
        val outside = hypot(max(cx, 0f), max(cy, 0f)) - radius
        val inside = min(max(cx, cy), 0f)
        return outside + inside
    }

    private fun sign(v: Float): Float = if (v > 0f) 1f else if (v < 0f) -1f else 0f

    private fun norm(x: Float, y: Float): FloatArray {
        val l = hypot(x, y)
        return if (l == 0f) floatArrayOf(0f, 0f) else floatArrayOf(x / l, y / l)
    }

    fun gradSdRoundedRect(px: Float, py: Float, hx: Float, hy: Float, radius: Float): FloatArray {
        val cx = abs(px) - (hx - radius)
        val cy = abs(py) - (hy - radius)
        return if (cx >= 0f || cy >= 0f) {
            val n = norm(max(cx, 0f), max(cy, 0f))
            floatArrayOf(sign(px) * n[0], sign(py) * n[1])
        } else {
            val gradX = if (cx < cy) 0f else 1f // step(cy, cx)
            floatArrayOf(sign(px) * gradX, sign(py) * (1f - gradX))
        }
    }

    fun sample(s: Spec, x: Float, y: Float): Sample {
        val px = x - s.halfW
        val py = y - s.halfH
        var sd = sdRoundedRect(px, py, s.halfW, s.halfH, s.radius)
        if (-sd >= s.height) return Sample(false, sd, 0f, 0f, 0f, x, y, 0f, 0f)
        sd = min(sd, 0f)
        val d = circleMap(1f - -sd / s.height, s.fastCircle) * -s.amount
        val gradRadius = min(s.radius * 1.5f, s.minHalf)
        val base = gradSdRoundedRect(px, py, s.halfW, s.halfH, gradRadius)
        val g: FloatArray = if (s.depth) {
            val c = norm(px, py)
            norm(base[0] + c[0], base[1] + c[1])
        } else {
            base
        }
        val rx = x + d * g[0]
        val ry = y + d * g[1]
        val intensity = s.dispersion * ((px * py) / (s.halfW * s.halfH))
        return Sample(true, sd, d, g[0], g[1], rx, ry, d * g[0] * intensity, d * g[1] * intensity)
    }

    /** A float RGB image, premultiplied-free, 0..1. */
    class Img(val w: Int, val h: Int) {
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)

        fun fill(cr: Float, cg: Float, cb: Float) { r.fill(cr); g.fill(cg); b.fill(cb) }

        fun copy(): Img = Img(w, h).also { r.copyInto(it.r); g.copyInto(it.g); b.copyInto(it.b) }

        /** Bilinear, clamped at the edges (the library records a padded layer, so edges are not hit in practice). */
        fun at(x: Float, y: Float, out: FloatArray) {
            val fx = (x - 0.5f).coerceIn(0f, w - 1f)
            val fy = (y - 0.5f).coerceIn(0f, h - 1f)
            val x0 = fx.toInt()
            val y0 = fy.toInt()
            val x1 = min(x0 + 1, w - 1)
            val y1 = min(y0 + 1, h - 1)
            val tx = fx - x0
            val ty = fy - y0
            fun mix(a: FloatArray): Float {
                val top = a[y0 * w + x0] * (1 - tx) + a[y0 * w + x1] * tx
                val bot = a[y1 * w + x0] * (1 - tx) + a[y1 * w + x1] * tx
                return top * (1 - ty) + bot * ty
            }
            out[0] = mix(r); out[1] = mix(g); out[2] = mix(b)
        }
    }

    /** `colorControls(saturation = s)` of the library (luma weights 0.213, 0.715, 0.072). */
    fun saturate(img: Img, s: Float) {
        val inv = 1f - s
        for (i in img.r.indices) {
            val lr = 0.213f * inv
            val lg = 0.715f * inv
            val lb = 0.072f * inv
            val r = img.r[i]
            val g = img.g[i]
            val b = img.b[i]
            val l = lr * r + lg * g + lb * b
            img.r[i] = (l + s * r).coerceIn(0f, 1f)
            img.g[i] = (l + s * g).coerceIn(0f, 1f)
            img.b[i] = (l + s * b).coerceIn(0f, 1f)
        }
    }

    /** Gaussian-like blur (three box passes) with a standard deviation of [sigma] px. */
    fun blur(img: Img, sigma: Float) {
        if (sigma < 0.3f) return
        val boxW = max(1, sqrt(12f * sigma * sigma / 3f + 1f).toInt())
        repeat(3) {
            boxPass(img.r, img.w, img.h, boxW, true); boxPass(img.r, img.w, img.h, boxW, false)
            boxPass(img.g, img.w, img.h, boxW, true); boxPass(img.g, img.w, img.h, boxW, false)
            boxPass(img.b, img.w, img.h, boxW, true); boxPass(img.b, img.w, img.h, boxW, false)
        }
    }

    private fun boxPass(a: FloatArray, w: Int, h: Int, box: Int, horizontal: Boolean) {
        val rad = box / 2
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val tmp = FloatArray(len)
        for (line in 0 until lines) {
            fun idx(i: Int) = if (horizontal) line * w + i else i * w + line
            var sum = 0f
            for (k in -rad..rad) sum += a[idx(k.coerceIn(0, len - 1))]
            val n = (2 * rad + 1).toFloat()
            for (i in 0 until len) {
                tmp[i] = sum / n
                sum += a[idx((i + rad + 1).coerceIn(0, len - 1))] - a[idx((i - rad).coerceIn(0, len - 1))]
            }
            for (i in 0 until len) a[idx(i)] = tmp[i]
        }
    }

    /**
     * Runs the lens over the rectangle ([x0], [y0], spec.w x spec.h) of [src] and writes the result into [dst]
     * (3 samples when dispersion > 0, like the shipped shader). Pixels outside the bent rim are copied.
     */
    fun apply(src: Img, dst: Img, spec: Spec, x0: Int, y0: Int) {
        val c = FloatArray(3)
        val cr = FloatArray(3)
        val cb = FloatArray(3)
        for (yy in 0 until spec.h.toInt()) {
            for (xx in 0 until spec.w.toInt()) {
                val fx = xx + 0.5f
                val fy = yy + 0.5f
                val s = sample(spec, fx, fy)
                val o = (y0 + yy) * dst.w + (x0 + xx)
                if (!s.inside) {
                    dst.r[o] = src.r[o]; dst.g[o] = src.g[o]; dst.b[o] = src.b[o]
                    continue
                }
                src.at(x0 + s.refractedX, y0 + s.refractedY, c)
                if (spec.dispersion > 0f) {
                    src.at(x0 + s.refractedX + s.dispX, y0 + s.refractedY + s.dispY, cr)
                    src.at(x0 + s.refractedX - s.dispX, y0 + s.refractedY - s.dispY, cb)
                    dst.r[o] = cr[0]; dst.g[o] = c[1]; dst.b[o] = cb[2]
                } else {
                    dst.r[o] = c[0]; dst.g[o] = c[1]; dst.b[o] = c[2]
                }
            }
        }
    }

    /** `HighlightStyle.Default` rim: white * alpha * |dot(grad, light)|^falloff in a band of [band] px inside the edge. */
    fun rim(img: Img, spec: Spec, x0: Int, y0: Int, band: Float, alpha: Float, angleDeg: Float = 45f, falloff: Float = 1f) {
        val lx = kotlin.math.cos(Math.toRadians(angleDeg.toDouble())).toFloat()
        val ly = kotlin.math.sin(Math.toRadians(angleDeg.toDouble())).toFloat()
        for (yy in 0 until spec.h.toInt()) {
            for (xx in 0 until spec.w.toInt()) {
                val px = xx + 0.5f - spec.halfW
                val py = yy + 0.5f - spec.halfH
                val sd = sdRoundedRect(px, py, spec.halfW, spec.halfH, spec.radius)
                if (sd > 0f || -sd > band) continue
                val gr = min(spec.radius * 1.5f, spec.minHalf)
                val g = gradSdRoundedRect(px, py, spec.halfW, spec.halfH, gr)
                val d = abs(g[0] * lx + g[1] * ly)
                val k = alpha * d.pow(falloff) * (1f - (-sd / band)).coerceIn(0f, 1f).let { 0.35f + 0.65f * it }
                val o = (y0 + yy) * img.w + (x0 + xx)
                img.r[o] = (img.r[o] + k).coerceAtMost(1f)
                img.g[o] = (img.g[o] + k).coerceAtMost(1f)
                img.b[o] = (img.b[o] + k).coerceAtMost(1f)
            }
        }
    }

    /** The shape mask (1 inside the rounded rect, anti-aliased by one pixel). */
    fun coverage(spec: Spec, xx: Int, yy: Int): Float {
        val sd = sdRoundedRect(xx + 0.5f - spec.halfW, yy + 0.5f - spec.halfH, spec.halfW, spec.halfH, spec.radius)
        return (0.5f - sd).coerceIn(0f, 1f)
    }
}
