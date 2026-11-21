/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster

import android.graphics.Bitmap
import java.nio.IntBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ====================================================================================================================
// The pixel work of the picture done on the CPU, so it is the same on every Android version (RuntimeShader is API 33+, RenderEffect 31+
// and only on views, BlurMaskFilter is fine on a software canvas but blurs no images): Gaussian-like blur of bitmaps, the cut-out bitmap
// (1 px feather + edge-colour pull, ported from the Windows PosterAssets), the person's soft shadow, half-size copies for clean
// downscaling (the software canvas has no mip-maps), turning and mirroring the photo.

internal object PosterBlur {
    /** Three box sizes whose repeated box blur approximates a Gaussian of [sigma] (pixels). */
    fun boxes(sigma: Float, n: Int = 3): IntArray {
        val wIdeal = sqrt(12.0 * sigma * sigma / n + 1.0)
        var wl = floor(wIdeal).toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal = (12.0 * sigma * sigma - n * wl * wl - 4.0 * n * wl - 3.0 * n) / (-4.0 * wl - 4.0)
        val m = mIdeal.roundToInt()
        return IntArray(n) { if (it < m) wl else wu }
    }

    /** Work arrays a renderer keeps between frames, so a blur allocates nothing (review 2026-11-12, finding 24). Not thread-safe. */
    class Scratch {
        private var a: IntArray? = null
        private var b: IntArray? = null

        fun first(n: Int): IntArray = a?.takeIf { it.size >= n } ?: IntArray(n).also { a = it }

        fun second(n: Int): IntArray = b?.takeIf { it.size >= n } ?: IntArray(n).also { b = it }

        fun release() {
            a = null
            b = null
        }
    }

    /**
     * Blurs [px] ([w] x [h], four independent byte lanes per int: works on premultiplied pixels of any byte order) in place by about [sigma] pixels.
     * [clamp] true repeats the edge pixels (photos), false treats the outside as transparent (layers). [work] (at least w * h) is reused when given.
     */
    fun blur(px: IntArray, w: Int, h: Int, sigma: Float, clamp: Boolean, work: IntArray? = null) {
        if (sigma < 0.3f || w < 1 || h < 1) return
        val tmp = work?.takeIf { it.size >= w * h } ?: IntArray(w * h)
        for (b in boxes(sigma)) {
            val r = (b - 1) / 2
            if (r <= 0) continue
            boxH(px, tmp, w, h, r, clamp)
            boxV(tmp, px, w, h, r, clamp)
        }
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, clamp: Boolean) {
        val div = 2 * r + 1
        val inv = (1 shl 16) / div
        for (y in 0 until h) {
            val row = y * w
            var s0 = 0
            var s1 = 0
            var s2 = 0
            var s3 = 0
            for (k in -r..r) {
                if (!clamp && (k < 0 || k >= w)) continue
                val p = src[row + k.coerceIn(0, w - 1)]
                s0 += p and 255; s1 += (p ushr 8) and 255; s2 += (p ushr 16) and 255; s3 += p ushr 24
            }
            for (x in 0 until w) {
                dst[row + x] = ((s0 * inv + 0x8000) ushr 16) or (((s1 * inv + 0x8000) ushr 16) shl 8) or (((s2 * inv + 0x8000) ushr 16) shl 16) or (((s3 * inv + 0x8000) ushr 16) shl 24)
                val xo = x - r
                val xi = x + r + 1
                if (clamp || xo >= 0) {
                    val p = src[row + xo.coerceIn(0, w - 1)]
                    s0 -= p and 255; s1 -= (p ushr 8) and 255; s2 -= (p ushr 16) and 255; s3 -= p ushr 24
                }
                if (clamp || xi < w) {
                    val p = src[row + xi.coerceIn(0, w - 1)]
                    s0 += p and 255; s1 += (p ushr 8) and 255; s2 += (p ushr 16) and 255; s3 += p ushr 24
                }
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, clamp: Boolean) {
        val div = 2 * r + 1
        val inv = (1 shl 16) / div
        val s0 = IntArray(w)
        val s1 = IntArray(w)
        val s2 = IntArray(w)
        val s3 = IntArray(w)
        for (k in -r..r) {
            if (!clamp && (k < 0 || k >= h)) continue
            val row = k.coerceIn(0, h - 1) * w
            for (x in 0 until w) {
                val p = src[row + x]
                s0[x] += p and 255; s1[x] += (p ushr 8) and 255; s2[x] += (p ushr 16) and 255; s3[x] += p ushr 24
            }
        }
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                dst[row + x] = ((s0[x] * inv + 0x8000) ushr 16) or (((s1[x] * inv + 0x8000) ushr 16) shl 8) or (((s2[x] * inv + 0x8000) ushr 16) shl 16) or (((s3[x] * inv + 0x8000) ushr 16) shl 24)
            }
            val yo = y - r
            val yi = y + r + 1
            if (clamp || yo >= 0) {
                val ro = yo.coerceIn(0, h - 1) * w
                for (x in 0 until w) {
                    val p = src[ro + x]
                    s0[x] -= p and 255; s1[x] -= (p ushr 8) and 255; s2[x] -= (p ushr 16) and 255; s3[x] -= p ushr 24
                }
            }
            if (clamp || yi < h) {
                val ri = yi.coerceIn(0, h - 1) * w
                for (x in 0 until w) {
                    val p = src[ri + x]
                    s0[x] += p and 255; s1[x] += (p ushr 8) and 255; s2[x] += (p ushr 16) and 255; s3[x] += p ushr 24
                }
            }
        }
    }

    /** Blurs a mutable bitmap in place (its raw premultiplied pixels); [scratch] keeps the work arrays between calls. */
    fun blurBitmap(b: Bitmap, sigma: Float, clamp: Boolean, scratch: Scratch? = null) {
        if (sigma < 0.3f) return
        val n = b.width * b.height
        val px = scratch?.first(n) ?: IntArray(n)
        val buf = IntBuffer.wrap(px, 0, n)
        b.copyPixelsToBuffer(buf)
        blur(px, b.width, b.height, sigma, clamp, scratch?.second(n))
        buf.rewind()
        b.copyPixelsFromBuffer(buf)
    }
}

/** Half-size copies of a bitmap, made when first asked for, so a big picture can be drawn small without the shimmer of plain bilinear sampling. */
internal class MipChain(base: Bitmap) {
    private val levels = ArrayList<Bitmap>().apply { add(base) }

    val base: Bitmap get() = levels[0]

    /** The copy to draw when the picture is shown at [scale] of its size (device pixels per bitmap pixel): the smallest level still at least as big. */
    @Synchronized
    fun forScale(scale: Float): Bitmap {
        var level = 0
        var s = scale
        while (s < 0.5f && level < 6) {
            s *= 2f
            level++
        }
        while (levels.size <= level) {
            val last = levels.last()
            if (last.width < 4 || last.height < 4) return last
            levels.add(Bitmap.createScaledBitmap(last, max(1, last.width / 2), max(1, last.height / 2), true))
        }
        return levels[level]
    }

    fun recycleCopies() {
        for (i in 1 until levels.size) levels[i].recycle()
        while (levels.size > 1) levels.removeAt(levels.lastIndex)
    }
}

internal object CutoutPixels {
    /** Alpha after the 1 px feather and the edge tightening, indexed by 16 x the smoothed alpha (0..4080). */
    private val shape = IntArray(4081).also { t -> for (s in 0..4080) t[s] = (((s - 320f) / (3920f - 320f)).coerceIn(0f, 1f) * 255f + 0.5f).toInt() }

    /**
     * The cut-out as UNPREMULTIPLIED ARGB ints (for Bitmap.setPixels) of the rows [y0, y1): alpha = the mask smoothed with its 8 neighbours, tightened;
     * an edge pixel's colour is pulled towards the solid pixels around it (a 5 x 5 window weighted by the square of their mask), so the old background does
     * not show as a pale fringe. Same maths as the Windows app.
     */
    fun convertRows(px: IntArray, alpha: ByteArray, w: Int, h: Int, y0: Int, y1: Int, out: IntArray) {
        var r0 = IntArray(w)
        var r1 = IntArray(w)
        var r2 = IntArray(w)
        fun fill(row: IntArray, y: Int) {
            val base = y.coerceIn(0, h - 1) * w
            for (x in 0 until w) {
                val xl = if (x > 0) x - 1 else 0
                val xr = if (x < w - 1) x + 1 else w - 1
                row[x] = (alpha[base + xl].toInt() and 255) + 2 * (alpha[base + x].toInt() and 255) + (alpha[base + xr].toInt() and 255)
            }
        }
        fill(r0, y0 - 1)
        fill(r1, y0)
        var o = 0
        for (y in y0 until y1) {
            fill(r2, y + 1)
            val prow = y * w
            for (x in 0 until w) {
                val a = shape[r0[x] + 2 * r1[x] + r2[x]]
                if (a == 0) {
                    out[o++] = 0
                    continue
                }
                val p = px[prow + x]
                if (a == 255) {
                    out[o++] = p or (255 shl 24)
                    continue
                }
                var r = (p shr 16) and 255
                var g = (p shr 8) and 255
                var b = p and 255
                var sw = 0
                var sr = 0
                var sg = 0
                var sb = 0
                for (dy in -2..2) {
                    val yy = (y + dy).coerceIn(0, h - 1) * w
                    for (dx in -2..2) {
                        val idx = yy + (x + dx).coerceIn(0, w - 1)
                        val m = alpha[idx].toInt() and 255
                        if (m < 96) continue
                        val q = px[idx]
                        val wgt = m * m
                        sw += wgt
                        sr += wgt * ((q shr 16) and 255)
                        sg += wgt * ((q shr 8) and 255)
                        sb += wgt * (q and 255)
                    }
                }
                if (sw > 0) {
                    r = (r + 4 * (sr / sw)) / 5
                    g = (g + 4 * (sg / sw)) / 5
                    b = (b + 4 * (sb / sw)) / 5
                }
                out[o++] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            val t = r0; r0 = r1; r1 = r2; r2 = t
        }
    }

    /** The whole cut-out as a bitmap of the photo's size (heavy: call off the main thread). */
    fun buildBitmap(photo: PosterPhoto): Bitmap {
        val w = photo.w
        val h = photo.h
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val band = 64
        val rows = IntArray(w * min(band, h))
        var y = 0
        while (y < h) {
            val bh = min(band, h - y)
            convertRows(photo.image.argb, photo.alpha, w, h, y, y + bh, rows)
            bmp.setPixels(rows, 0, w, 0, y, w, bh)
            y += bh
        }
        return bmp
    }
}

/** The soft dark copy of the subject's outline for the shadow the person throws on the cards behind (one pixel = [cell] poster pixels). */
internal object SilhouetteShadow {
    fun build(photo: PosterPhoto, zoom: Float, panX: Float, panY: Float, cell: Int, ratio: PosterRatio = PosterRatio.DEFAULT): Bitmap? {
        val gw = (ratio.w + cell - 1) / cell
        val gh = (ratio.h + cell - 1) / cell
        val pw = photo.w
        val ph = photo.h
        val scale = PosterPhotoGeometry.scale(pw, ph, zoom, ratio)
        val px = IntArray(gw * gh)
        var any = false
        val sub = floatArrayOf(1f / 6f, 3f / 6f, 5f / 6f)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                var sum = 0
                for (sy in sub) {
                    val fy = ph / 2f + ((gy + sy) * cell - ratio.h / 2f - panY) / scale
                    val iy = fy.toInt()
                    if (fy < 0f || iy >= ph) continue
                    for (sx in sub) {
                        val fx = pw / 2f + ((gx + sx) * cell - ratio.w / 2f - panX) / scale
                        val ix = fx.toInt()
                        if (fx < 0f || ix >= pw) continue
                        sum += photo.alpha[iy * pw + ix].toInt() and 255
                    }
                }
                val a = sum / 9
                if (a > 0) any = true
                px[gy * gw + gx] = a shl 24 // black, premultiplied = only the alpha byte
            }
        }
        if (!any) return null
        // the raw buffer of an ARGB_8888 bitmap is premultiplied RGBA bytes: black with alpha a is (a shl 24) in little-endian lanes
        PosterBlur.blur(px, gw, gh, 22f / cell, clamp = false)
        val bmp = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(IntBuffer.wrap(px))
        return bmp
    }
}

/**
 * The person on a coarse poster grid ([CELL] poster pixels per cell, the mapping of [SilhouetteShadow.build]): where the flare streak and the
 * stars must not land (review 2026-11-12, finding 13). [at] = the mask's mean in a cell, grown by one cell for loose hair; [head] = the box
 * of the head and face (left, top, right, bottom, poster pixels, with a margin), or null when no head could be found.
 */
internal class SubjectGrid(val gw: Int, val gh: Int, private val cells: ByteArray, val head: FloatArray?) {
    fun at(x: Float, y: Float): Int {
        if (x < 0f || y < 0f) return 0
        val gx = (x / CELL).toInt()
        val gy = (y / CELL).toInt()
        if (gx >= gw || gy >= gh) return 0
        return cells[gy * gw + gx].toInt() and 255
    }

    fun inHead(x: Float, y: Float): Boolean {
        val b = head ?: return false
        return x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3]
    }

    companion object {
        const val CELL = 16

        /** A cell at least this solid is the person (for the head search). */
        private const val SOLID = 96

        /** The grid of [photo]'s mask at this zoom and pan, or null when the mask shows nobody on the poster. About 70 x 120 cells. */
        fun build(photo: PosterPhoto, zoom: Float, panX: Float, panY: Float, ratio: PosterRatio = PosterRatio.DEFAULT): SubjectGrid? {
            val gw = (ratio.w + CELL - 1) / CELL
            val gh = (ratio.h + CELL - 1) / CELL
            val pw = photo.w
            val ph = photo.h
            val scale = PosterPhotoGeometry.scale(pw, ph, zoom, ratio)
            val raw = ByteArray(gw * gh)
            var any = false
            val sub = floatArrayOf(1f / 6f, 3f / 6f, 5f / 6f)
            for (gy in 0 until gh) {
                for (gx in 0 until gw) {
                    var sum = 0
                    for (sy in sub) {
                        val fy = ph / 2f + ((gy + sy) * CELL - ratio.h / 2f - panY) / scale
                        val iy = fy.toInt()
                        if (fy < 0f || iy >= ph) continue
                        for (sx in sub) {
                            val fx = pw / 2f + ((gx + sx) * CELL - ratio.w / 2f - panX) / scale
                            val ix = fx.toInt()
                            if (fx < 0f || ix >= pw) continue
                            sum += photo.alpha[iy * pw + ix].toInt() and 255
                        }
                    }
                    val a = sum / 9
                    if (a > 32) any = true
                    raw[gy * gw + gx] = a.toByte()
                }
            }
            if (!any) return null
            // grown by one cell (the 3 x 3 maximum): loose hair and the soft edge of the mask
            val grown = ByteArray(gw * gh)
            for (gy in 0 until gh) for (gx in 0 until gw) {
                var m = 0
                for (dy in -1..1) {
                    val y = gy + dy
                    if (y < 0 || y >= gh) continue
                    for (dx in -1..1) {
                        val x = gx + dx
                        if (x < 0 || x >= gw) continue
                        m = max(m, raw[y * gw + x].toInt() and 255)
                    }
                }
                grown[gy * gw + gx] = m.toByte()
            }
            return SubjectGrid(gw, gh, grown, headBox(raw, gw, gh))
        }

        /**
         * The head: from the top of the person down the run of solid cells under it. A head is taller than wide, so going down from the
         * crown the depth catches up with the width (at the eyes or the mouth); the head ends 1.4 times that depth below the crown (the
         * chin). Shoulders that widen smoothly (no jump to look for) do not stretch it. Works for a full-length photo (a small head) and a
         * selfie (a big one); loose hair makes the box bigger, never smaller.
         */
        internal fun headBox(raw: ByteArray, gw: Int, gh: Int): FloatArray? {
            fun solid(x: Int, y: Int) = (raw[y * gw + x].toInt() and 255) >= SOLID
            var top = -1
            for (gy in 0 until gh) {
                if ((0 until gw).any { solid(it, gy) }) {
                    top = gy
                    break
                }
            }
            if (top < 0) return null
            var cx = (0 until gw).filter { solid(it, top) }.average().toFloat()
            var widest = 0
            var minX = gw
            var maxX = -1
            var bottom = top
            var square = -1
            for (gy in top until gh) {
                // the run of solid cells nearest to the head's centre
                val reach = max(3, widest)
                var seed = -1
                for (d in 0..reach) {
                    val l = cx.toInt() - d
                    val r = cx.toInt() + d
                    if (l in 0 until gw && solid(l, gy)) { seed = l; break }
                    if (r in 0 until gw && solid(r, gy)) { seed = r; break }
                }
                if (seed < 0) break
                var l = seed
                var r = seed
                while (l > 0 && solid(l - 1, gy)) l--
                while (r < gw - 1 && solid(r + 1, gy)) r++
                val w = r - l + 1
                val depth = gy - top + 1
                if (square > 0 && depth > ceil(1.4f * square).toInt()) break
                widest = max(widest, w)
                minX = min(minX, l)
                maxX = max(maxX, r)
                cx = (l + r) / 2f
                bottom = gy
                if (square < 0 && depth >= 3 && depth >= w) square = depth
            }
            if (maxX < 0) return null
            val m = 2
            return floatArrayOf(((minX - m) * CELL).toFloat(), ((top - m) * CELL).toFloat(), ((maxX + 1 + m) * CELL).toFloat(), ((bottom + 1 + m) * CELL).toFloat())
        }
    }
}

/** Turning and mirroring the photo: the pixels and the mask move together, so these return a NEW [PosterPhoto]. */
object PosterPhotoOps {
    fun rotateRight(p: PosterPhoto): PosterPhoto {
        val w = p.w
        val h = p.h
        val pixels = IntArray(w * h)
        val mask = ByteArray(w * h)
        val src = p.image.argb
        for (y in 0 until h) {
            val nx = h - 1 - y
            for (x in 0 until w) {
                val d = x * h + nx
                val s = y * w + x
                pixels[d] = src[s]
                mask[d] = p.alpha[s]
            }
        }
        return PosterPhoto(PosterImage(h, w, pixels), mask)
    }

    fun mirror(p: PosterPhoto): PosterPhoto {
        val w = p.w
        val h = p.h
        val pixels = IntArray(w * h)
        val mask = ByteArray(w * h)
        val src = p.image.argb
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val d = row + (w - 1 - x)
                val s = row + x
                pixels[d] = src[s]
                mask[d] = p.alpha[s]
            }
        }
        return PosterPhoto(PosterImage(w, h, pixels), mask)
    }
}

/** The colour matrix of the halation threshold on the CPU: the same as Android's ColorMatrixColorFilter on opaque pixels, on every version. */
internal object PosterPost {
    /** A 4 x 5 colour matrix (Android units: offsets 0..255; the alpha row is ignored) as 12 fixed-point numbers (x 1024). */
    fun fixedMatrix(m: FloatArray): IntArray =
        intArrayOf(
            (m[0] * 1024f).roundToInt(), (m[1] * 1024f).roundToInt(), (m[2] * 1024f).roundToInt(), (m[4] * 1024f).roundToInt(),
            (m[5] * 1024f).roundToInt(), (m[6] * 1024f).roundToInt(), (m[7] * 1024f).roundToInt(), (m[9] * 1024f).roundToInt(),
            (m[10] * 1024f).roundToInt(), (m[11] * 1024f).roundToInt(), (m[12] * 1024f).roundToInt(), (m[14] * 1024f).roundToInt(),
        )

    /** Applies a colour matrix (see [fixedMatrix]) to [n] opaque ARGB ints in place; the same as Android's ColorMatrixColorFilter on opaque pixels. */
    fun matrix(px: IntArray, n: Int, m: IntArray) {
        for (i in 0 until n) {
            val p = px[i]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            val nr = ((m[0] * r + m[1] * g + m[2] * b + m[3] + 512) shr 10).coerceIn(0, 255)
            val ng = ((m[4] * r + m[5] * g + m[6] * b + m[7] + 512) shr 10).coerceIn(0, 255)
            val nb = ((m[8] * r + m[9] * g + m[10] * b + m[11] + 512) shr 10).coerceIn(0, 255)
            px[i] = (p and -0x1000000) or (nr shl 16) or (ng shl 8) or nb
        }
    }
}
