/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster.sticker

import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ====================================================================================================================
// The pixel rules of the sticker editor ("Ubah stiker", round 4.3.5), pure JVM so the tests run them: which way to take the background
// off a picture, the background by colour (an edge-connected fill from the border with a tolerance and a soft edge), the brush ("Hapus" /
// "Pulihkan") as strokes of soft dabs, the undo history as deflated patches, and the finished sticker (edge colours cleaned, cropped).
// A picture is unpremultiplied ARGB ints, w x h; a mask is one byte per pixel (0 = gone, 255 = kept).

/** How the background is taken off. KEEP = the picture's own transparency (it already has one), PERSON = the cut-out network, COLOR = by colour. */
enum class StickerMethod { KEEP, PERSON, COLOR }

object StickerMask {
    /** A picture whose see-through pixels are at least this share of it (or of its border) already has a background removed. */
    private const val TRANSPARENT_SHARE = 0.005f

    /** Greedy clusters of the border colours closer than this (RGB distance) are one colour. */
    private const val CLUSTER = 26

    fun alphaOf(argb: IntArray, n: Int): ByteArray = ByteArray(n) { (argb[it] ushr 24).toByte() }

    /** True when the picture is already partly see-through (a PNG or WebP sticker cut by someone): then its own alpha is kept. */
    fun hasTransparency(argb: IntArray, w: Int, h: Int): Boolean {
        val n = w * h
        var clear = 0
        for (i in 0 until n) if ((argb[i] ushr 24) < 128) clear++
        if (clear >= max(1, (n * TRANSPARENT_SHARE).toInt())) return true
        var border = 0
        var clearBorder = 0
        forBorder(w, h) { i ->
            border++
            if ((argb[i] ushr 24) < 128) clearBorder++
        }
        return border > 0 && clearBorder >= max(1, (border * 0.05f).toInt())
    }

    private inline fun forBorder(w: Int, h: Int, f: (Int) -> Unit) {
        for (x in 0 until w) {
            f(x)
            if (h > 1) f((h - 1) * w + x)
        }
        for (y in 1 until h - 1) {
            f(y * w)
            if (w > 1) f(y * w + w - 1)
        }
    }

    fun dist2(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 255) - ((b shr 16) and 255)
        val dg = ((a shr 8) and 255) - ((b shr 8) and 255)
        val db = (a and 255) - (b and 255)
        return dr * dr + dg * dg + db * db
    }

    /**
     * The border's main colours (RGB ints, most frequent first): the opaque border pixels in greedy clusters of [CLUSTER], the clusters with at
     * least 3 percent of them, at most six. [share] gets the share of the opaque border the returned colours cover (0..1).
     */
    fun borderPalette(argb: IntArray, w: Int, h: Int, share: FloatArray? = null): IntArray {
        val sums = ArrayList<LongArray>()
        val centres = ArrayList<Int>()
        var total = 0
        forBorder(w, h) { i ->
            val c = argb[i]
            if ((c ushr 24) < 128) return@forBorder
            total++
            var best = -1
            var bd = CLUSTER * CLUSTER + 1
            for (k in centres.indices) {
                val d = dist2(c, centres[k])
                if (d < bd) {
                    bd = d
                    best = k
                }
            }
            if (best < 0 && centres.size < 16) {
                sums.add(longArrayOf(0, 0, 0, 0))
                centres.add(c)
                best = centres.lastIndex
            }
            if (best >= 0) {
                val s = sums[best]
                s[0] += ((c shr 16) and 255).toLong(); s[1] += ((c shr 8) and 255).toLong(); s[2] += (c and 255).toLong(); s[3]++
                centres[best] = (((s[0] / s[3]).toInt()) shl 16) or (((s[1] / s[3]).toInt()) shl 8) or (s[2] / s[3]).toInt()
            }
        }
        if (total == 0) {
            share?.set(0, 0f)
            return IntArray(0)
        }
        val order = sums.indices.filter { sums[it][3] >= max(1, (total * 0.03f).toInt()) }.sortedByDescending { sums[it][3] }.take(6)
        share?.set(0, order.sumOf { sums[it][3] }.toFloat() / total)
        return IntArray(order.size) { centres[order[it]] }
    }

    /**
     * What "Hapus latar" does when the user has not picked a way: a picture that is already see-through keeps its own alpha (KEEP); a border
     * of one or two flat colours (a logo, a screenshot, an object on a plain wall: at least 80 percent of the border within reach of the two
     * main colours) is cut by colour; anything else (a photo) goes to the cut-out network (PERSON).
     */
    fun chooseMethod(argb: IntArray, w: Int, h: Int): StickerMethod {
        if (hasTransparency(argb, w, h)) return StickerMethod.KEEP
        val pal = borderPalette(argb, w, h)
        if (pal.isEmpty()) return StickerMethod.PERSON
        val main = pal.take(2)
        var n = 0
        var near = 0
        forBorder(w, h) { i ->
            n++
            if (main.any { dist2(argb[i], it) <= 30 * 30 }) near++
        }
        return if (n > 0 && near >= n * 0.8f) StickerMethod.COLOR else StickerMethod.PERSON
    }

    /** The tolerance slider (0..1) as an RGB distance. */
    fun toleranceDistance(tolerance: Float): Float = 8f + tolerance.coerceIn(0f, 1f) * 92f

    /**
     * The background by colour: every pixel connected to the border through pixels close to the border's colours ([tolerance], see
     * [toleranceDistance]; a smooth gradient is followed step by step) is taken off; an enclosed area of the same colour (the inside of an
     * "O") stays, as in any edge fill. The edge is soft (a pixel between the object and the background gets the share of the object its colour
     * says), specks of noise left in the background are dropped, and [smooth] (0..1) softens the edge inward up to about four pixels without
     * a halo of the old background. The picture's own alpha is kept (min). Returns the mask.
     */
    fun colorCut(argb: IntArray, w: Int, h: Int, tolerance: Float, smooth: Float, isCancelled: () -> Boolean = { false }): ByteArray {
        val n = w * h
        val pal = borderPalette(argb, w, h)
        val t = toleranceDistance(tolerance)
        val t2 = (t * t).toInt()
        val step2 = ((t * 0.32f) * (t * 0.32f)).toInt()
        fun palD2(c: Int): Int {
            var best = Int.MAX_VALUE
            for (p in pal) best = min(best, dist2(c, p))
            return best
        }
        val bg = ByteArray(n)
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        fun candidate(i: Int, from: Int): Boolean {
            val c = argb[i]
            if ((c ushr 24) < 16) return true
            if (pal.isEmpty()) return false
            val d = palD2(c)
            if (d <= t2) return true
            // a smooth gradient of the background is followed: close to the pixel it was reached from, not too far from the border's colours
            return from >= 0 && d <= 6 * t2 && dist2(c, argb[from]) <= step2
        }
        forBorder(w, h) { i ->
            if (bg[i].toInt() == 0 && candidate(i, -1)) {
                bg[i] = 1
                queue[tail++] = i
            }
        }
        while (head < tail) {
            if ((head and 0xFFFF) == 0 && isCancelled()) break
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) tail = visit(i - 1, i, bg, queue, tail, ::candidate)
            if (x < w - 1) tail = visit(i + 1, i, bg, queue, tail, ::candidate)
            if (y > 0) tail = visit(i - w, i, bg, queue, tail, ::candidate)
            if (y < h - 1) tail = visit(i + w, i, bg, queue, tail, ::candidate)
        }
        val a = ByteArray(n)
        // the rim: kept pixels that touch the background, and the ring just inside them (an anti-aliased edge is two pixels wide)
        val rim = ByteArray(n)
        for (i in 0 until n) {
            if (bg[i].toInt() != 0) continue
            val x = i % w
            val y = i / w
            if ((x > 0 && bg[i - 1].toInt() != 0) || (x < w - 1 && bg[i + 1].toInt() != 0) || (y > 0 && bg[i - w].toInt() != 0) || (y < h - 1 && bg[i + w].toInt() != 0)) rim[i] = 1
        }
        for (i in 0 until n) {
            if (rim[i].toInt() != 1) continue
            val x = i % w
            val y = i / w
            if (x > 0 && bg[i - 1].toInt() == 0 && rim[i - 1].toInt() == 0) rim[i - 1] = 2
            if (x < w - 1 && bg[i + 1].toInt() == 0 && rim[i + 1].toInt() == 0) rim[i + 1] = 2
            if (y > 0 && bg[i - w].toInt() == 0 && rim[i - w].toInt() == 0) rim[i - w] = 2
            if (y < h - 1 && bg[i + w].toInt() == 0 && rim[i + w].toInt() == 0) rim[i + w] = 2
        }
        for (i in 0 until n) {
            if (bg[i].toInt() != 0) continue
            a[i] = if (rim[i].toInt() != 0 && pal.isNotEmpty()) edgeAlpha(argb, bg, rim, w, h, i, pal).toByte() else 255.toByte()
        }
        dropSpecks(a, w, h, max(24, n / 2500), queue, bg)
        if (smooth > 0.01f) soften(a, w, h, smooth)
        for (i in 0 until n) {
            val own = argb[i] ushr 24
            if (own < 255) a[i] = min(a[i].toInt() and 255, own).toByte()
        }
        return a
    }

    /**
     * The alpha of a rim pixel by unmixing: an anti-aliased edge pixel P is the object's colour F and the background's B mixed, P = a F + (1 - a) B.
     * B = the border colour nearest to P, F = the mean of the solid object pixels within two pixels (not background, not rim); a = how far P lies
     * from B towards F. Without solid pixels near (a thin line), the pixel is kept whole.
     */
    private fun edgeAlpha(argb: IntArray, bg: ByteArray, rim: ByteArray, w: Int, h: Int, i: Int, pal: IntArray): Int {
        val x = i % w
        val y = i / w
        var fr = 0
        var fg = 0
        var fb = 0
        var k = 0
        for (dy in -2..2) {
            val yy = y + dy
            if (yy < 0 || yy >= h) continue
            for (dx in -2..2) {
                val xx = x + dx
                if (xx < 0 || xx >= w) continue
                val j = yy * w + xx
                if (bg[j].toInt() != 0 || rim[j].toInt() != 0) continue
                val q = argb[j]
                fr += (q shr 16) and 255
                fg += (q shr 8) and 255
                fb += q and 255
                k++
            }
        }
        if (k == 0) return 255
        val p = argb[i]
        var b = pal[0]
        for (c in pal) if (dist2(p, c) < dist2(p, b)) b = c
        val br = (b shr 16) and 255
        val bgc = (b shr 8) and 255
        val bb = b and 255
        val ex = fr.toFloat() / k - br
        val ey = fg.toFloat() / k - bgc
        val ez = fb.toFloat() / k - bb
        val len2 = ex * ex + ey * ey + ez * ez
        if (len2 < 400f) return 255
        val t = ((((p shr 16) and 255) - br) * ex + (((p shr 8) and 255) - bgc) * ey + ((p and 255) - bb) * ez) / len2
        // a little slack at both ends: grain in a solid pixel does not make it see-through, nor a near-background pixel a speck
        return (((t - 0.04f) / 0.92f).coerceIn(0f, 1f) * 255f).roundToInt()
    }

    private inline fun visit(j: Int, from: Int, bg: ByteArray, queue: IntArray, tail: Int, candidate: (Int, Int) -> Boolean): Int {
        if (bg[j].toInt() != 0 || !candidate(j, from)) return tail
        bg[j] = 1
        queue[tail] = j
        return tail + 1
    }

    /** Pieces of the kept part smaller than [minSize] pixels (noise the fill left in the background) are taken off. [queue], [seen] = work arrays of w * h. */
    fun dropSpecks(a: ByteArray, w: Int, h: Int, minSize: Int, queue: IntArray, seen: ByteArray) {
        val n = w * h
        java.util.Arrays.fill(seen, 0, n, 0)
        for (s in 0 until n) {
            if (seen[s].toInt() != 0 || (a[s].toInt() and 255) < 128) continue
            var head = 0
            var tail = 0
            queue[tail++] = s
            seen[s] = 1
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                if (x > 0) { val j = i - 1; if (seen[j].toInt() == 0 && (a[j].toInt() and 255) >= 128) { seen[j] = 1; queue[tail++] = j } }
                if (x < w - 1) { val j = i + 1; if (seen[j].toInt() == 0 && (a[j].toInt() and 255) >= 128) { seen[j] = 1; queue[tail++] = j } }
                if (y > 0) { val j = i - w; if (seen[j].toInt() == 0 && (a[j].toInt() and 255) >= 128) { seen[j] = 1; queue[tail++] = j } }
                if (y < h - 1) { val j = i + w; if (seen[j].toInt() == 0 && (a[j].toInt() and 255) >= 128) { seen[j] = 1; queue[tail++] = j } }
            }
            if (tail < minSize) for (k in 0 until tail) a[queue[k]] = 0
        }
    }

    /** Softens the mask's edge inward (no halo outward): the mask blurred by up to about four pixels, never above what it was. */
    fun soften(a: ByteArray, w: Int, h: Int, smooth: Float) {
        val r = (smooth.coerceIn(0f, 1f) * 4f).roundToInt()
        if (r < 1) return
        val n = w * h
        val src = IntArray(n) { a[it].toInt() and 255 }
        val tmp = IntArray(n)
        // two box passes (a tent) per axis
        repeat(2) {
            boxH(src, tmp, w, h, r)
            boxV(tmp, src, w, h, r)
        }
        for (i in 0 until n) {
            val o = a[i].toInt() and 255
            val b = min(255, src[i] * 3 / 2)
            a[i] = min(o, b).toByte()
        }
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var s = 0
            for (k in -r..r) s += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[row + x] = s / div
                s += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var s = 0
            for (k in -r..r) s += src[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = s / div
                s += src[(y + r + 1).coerceAtMost(h - 1) * w + x] - src[(y - r).coerceAtLeast(0) * w + x]
            }
        }
    }

    /** The share of the picture the mask keeps (0..1). */
    fun coverage(mask: ByteArray): Float {
        var k = 0L
        for (b in mask) k += b.toInt() and 255
        return if (mask.isEmpty()) 0f else k / (255f * mask.size)
    }

    // ------------------------------------------------------------------------------------------------ the finished sticker

    /** The box (x0, y0, x1, y1 exclusive) of what the mask keeps (at least [min] alpha), widened by [margin]; null when nothing is kept. */
    fun bounds(mask: ByteArray, w: Int, h: Int, min: Int = 8, margin: Int = 2): IntArray? {
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((mask[row + x].toInt() and 255) >= min) {
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
        }
        if (x1 < 0) return null
        return intArrayOf(max(0, x0 - margin), max(0, y0 - margin), min(w, x1 + 1 + margin), min(h, y1 + 1 + margin))
    }

    /**
     * The sticker as it is saved: the picture's colours with the mask as alpha (unpremultiplied ARGB), cropped to [box]. The colour of a
     * half-see-through edge pixel is pulled towards the solid pixels next to it (the old background does not show as a pale or dark fringe).
     */
    fun compose(argb: IntArray, mask: ByteArray, w: Int, h: Int, box: IntArray): IntArray {
        val bw = box[2] - box[0]
        val bh = box[3] - box[1]
        val out = IntArray(bw * bh)
        for (y in box[1] until box[3]) {
            for (x in box[0] until box[2]) {
                val i = y * w + x
                val a = mask[i].toInt() and 255
                val o = (y - box[1]) * bw + (x - box[0])
                if (a == 0) {
                    out[o] = 0
                    continue
                }
                val p = argb[i]
                if (a >= 250) {
                    out[o] = (a shl 24) or (p and 0xFFFFFF)
                    continue
                }
                var sw = 0L
                var sr = 0L
                var sg = 0L
                var sb = 0L
                for (dy in -2..2) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -2..2) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        val j = yy * w + xx
                        val m = mask[j].toInt() and 255
                        if (m < 230) continue
                        val q = argb[j]
                        val wt = (m * m).toLong()
                        sw += wt
                        sr += wt * ((q shr 16) and 255)
                        sg += wt * ((q shr 8) and 255)
                        sb += wt * (q and 255)
                    }
                }
                var r = (p shr 16) and 255
                var g = (p shr 8) and 255
                var b = p and 255
                if (sw > 0) {
                    r = ((r + 3 * (sr / sw)) / 4).toInt()
                    g = ((g + 3 * (sg / sw)) / 4).toInt()
                    b = ((b + 3 * (sb / sw)) / 4).toInt()
                }
                out[o] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }
}

/**
 * One brush stroke ("Hapus" or "Pulihkan") on [mask]: soft round dabs every quarter radius along the finger's path. The stroke keeps the
 * mask as it was when it began and its own coverage (the strongest dab per pixel), so going over the same spot twice in one stroke does not
 * make it stronger, and [cancel] puts everything back (a second finger came: it was a pinch). Erase moves a pixel towards 0, restore towards
 * the picture's own alpha [original].
 */
class StickerStroke(private val w: Int, private val h: Int, private val mask: ByteArray, private val original: ByteArray, val erase: Boolean, val radius: Float, val softness: Float) {
    private val start = mask.copyOf()
    private val cov = ByteArray(w * h)
    private var lx = 0f
    private var ly = 0f
    private var any = false

    /** The box touched so far (x0, y0, x1, y1 exclusive), or empty. */
    var x0 = w
        private set
    var y0 = h
        private set
    var x1 = 0
        private set
    var y1 = 0
        private set

    val touched: Boolean get() = x1 > x0 && y1 > y0

    /** Moves the stroke to ([x], [y]) (picture pixels). Returns the box changed by this step (x0, y0, x1, y1), or null. */
    fun to(x: Float, y: Float): IntArray? {
        if (!any) {
            any = true
            lx = x
            ly = y
            return dab(x, y)
        }
        val dx = x - lx
        val dy = y - ly
        val d = sqrt(dx * dx + dy * dy)
        val step = max(1f, radius * 0.25f)
        if (d < step) return null
        val n = (d / step).toInt()
        var box: IntArray? = null
        for (k in 1..n) {
            val t = k * step / d
            box = union(box, dab(lx + dx * t, ly + dy * t))
        }
        lx += dx * (n * step / d)
        ly += dy * (n * step / d)
        return box
    }

    private fun union(a: IntArray?, b: IntArray?): IntArray? {
        if (a == null) return b
        if (b == null) return a
        return intArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))
    }

    /** The weight of a dab at distance [d] from its centre: 1 inside the hard core, a smooth fall to 0 at the radius (at least a one-pixel edge). */
    fun weight(d: Float): Float {
        val r = radius
        val core = min(r - 1f, r * (1f - softness.coerceIn(0f, 1f))).coerceAtLeast(0f)
        if (d <= core) return 1f
        if (d >= r) return 0f
        val t = (r - d) / (r - core)
        return t * t * (3f - 2f * t)
    }

    private fun dab(cx: Float, cy: Float): IntArray? {
        val r = radius
        val bx0 = max(0, (cx - r).toInt())
        val by0 = max(0, (cy - r).toInt())
        val bx1 = min(w, (cx + r).toInt() + 2)
        val by1 = min(h, (cy + r).toInt() + 2)
        if (bx1 <= bx0 || by1 <= by0) return null
        for (y in by0 until by1) {
            val dy = y + 0.5f - cy
            val row = y * w
            for (x in bx0 until bx1) {
                val dx = x + 0.5f - cx
                val wt = weight(sqrt(dx * dx + dy * dy))
                if (wt <= 0f) continue
                val i = row + x
                val c = (wt * 255f).roundToInt()
                val old = cov[i].toInt() and 255
                if (c <= old) continue
                cov[i] = c.toByte()
                val s = start[i].toInt() and 255
                val target = if (erase) 0 else original[i].toInt() and 255
                // rounded to the nearest step both ways (an integer division would leave an erased pixel at 1)
                val delta = (target - s) * c
                mask[i] = (s + if (delta >= 0) (delta + 127) / 255 else (delta - 127) / 255).toByte()
            }
        }
        x0 = min(x0, bx0)
        y0 = min(y0, by0)
        x1 = max(x1, bx1)
        y1 = max(y1, by1)
        return intArrayOf(bx0, by0, bx1, by1)
    }

    /** Puts the mask back as it was before the stroke (in the touched box). Returns that box, or null. */
    fun cancel(): IntArray? {
        if (!touched) return null
        for (y in y0 until y1) System.arraycopy(start, y * w + x0, mask, y * w + x0, x1 - x0)
        return intArrayOf(x0, y0, x1, y1)
    }

    /** The undo step of the stroke (its box before and after), or null when it changed nothing. */
    fun patch(): StickerPatch? {
        if (!touched) return null
        return StickerPatch.of(start, mask, w, x0, y0, x1, y1)
    }
}

/** A change of the mask in a box: the bytes before and after, deflated (a mask is mostly runs of 0 and 255: a full 1024 x 1024 step is tens of KB). */
class StickerPatch private constructor(val x0: Int, val y0: Int, val x1: Int, val y1: Int, private val before: ByteArray, private val after: ByteArray) {
    val bytes: Int get() = before.size + after.size

    fun undo(mask: ByteArray, w: Int) = put(before, mask, w)

    fun redo(mask: ByteArray, w: Int) = put(after, mask, w)

    private fun put(z: ByteArray, mask: ByteArray, w: Int) {
        val bw = x1 - x0
        val box = inflate(z, bw * (y1 - y0))
        for (y in y0 until y1) System.arraycopy(box, (y - y0) * bw, mask, y * w + x0, bw)
    }

    companion object {
        /** The patch of the box (x0, y0)-(x1, y1) between [old] and [new] masks of width [w]. */
        fun of(old: ByteArray, new: ByteArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): StickerPatch {
            val bw = x1 - x0
            val bh = y1 - y0
            val a = ByteArray(bw * bh)
            val b = ByteArray(bw * bh)
            for (y in 0 until bh) {
                System.arraycopy(old, (y0 + y) * w + x0, a, y * bw, bw)
                System.arraycopy(new, (y0 + y) * w + x0, b, y * bw, bw)
            }
            return StickerPatch(x0, y0, x1, y1, deflate(a), deflate(b))
        }

        fun deflate(src: ByteArray): ByteArray {
            val d = Deflater(Deflater.BEST_SPEED)
            try {
                d.setInput(src)
                d.finish()
                val out = java.io.ByteArrayOutputStream(max(64, src.size / 16))
                val buf = ByteArray(16 * 1024)
                while (!d.finished()) {
                    val k = d.deflate(buf)
                    out.write(buf, 0, k)
                }
                return out.toByteArray()
            } finally {
                d.end()
            }
        }

        fun inflate(src: ByteArray, size: Int): ByteArray {
            val f = Inflater()
            try {
                f.setInput(src)
                val out = ByteArray(size)
                var off = 0
                while (off < size && !f.finished()) {
                    val k = f.inflate(out, off, size - off)
                    if (k == 0 && (f.needsInput() || f.needsDictionary())) break
                    off += k
                }
                return out
            } finally {
                f.end()
            }
        }
    }
}

/**
 * Undo and redo of the mask ("Urungkan" / "Ulangi"): patches in order, at most [maxSteps] and [maxBytes] of deflated data (the oldest go
 * first). A new change after an undo drops the redo steps.
 */
class StickerHistory(private val maxSteps: Int = 60, private val maxBytes: Int = 16 * 1024 * 1024) {
    private val done = ArrayDeque<StickerPatch>()
    private val undone = ArrayDeque<StickerPatch>()
    private var bytes = 0

    val canUndo: Boolean get() = done.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()
    val size: Int get() = done.size
    val totalBytes: Int get() = bytes

    fun push(p: StickerPatch) {
        undone.forEach { bytes -= it.bytes }
        undone.clear()
        done.addLast(p)
        bytes += p.bytes
        while (done.size > maxSteps || (bytes > maxBytes && done.size > 1)) bytes -= done.removeFirst().bytes
    }

    /** Undoes the last step on [mask]; returns its box (x0, y0, x1, y1) or null. */
    fun undo(mask: ByteArray, w: Int): IntArray? {
        val p = done.removeLastOrNull() ?: return null
        p.undo(mask, w)
        undone.addLast(p)
        return intArrayOf(p.x0, p.y0, p.x1, p.y1)
    }

    fun redo(mask: ByteArray, w: Int): IntArray? {
        val p = undone.removeLastOrNull() ?: return null
        p.redo(mask, w)
        done.addLast(p)
        return intArrayOf(p.x0, p.y0, p.x1, p.y1)
    }

    fun clear() {
        done.clear()
        undone.clear()
        bytes = 0
    }
}
