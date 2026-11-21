/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster.cutout

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// ====================================================================================================================
// The arithmetic of the cut-out network, plain Kotlin on float arrays (no native code, so it runs the same on every ABI and in the JVM
// tests). A tensor is C planes of H x W floats starting at [Tensor.off] of its array (a tensor can be a slice of a bigger one: the
// inputs of a concatenation are written straight into it).

internal class Tensor(val data: FloatArray, val off: Int, val c: Int, val h: Int, val w: Int) {
    val plane: Int get() = h * w
    val size: Int get() = c * h * w

    /** Channels [from, from + count) as a tensor over the same array. */
    fun channels(from: Int, count: Int): Tensor = Tensor(data, off + from * plane, count, h, w)
}

/** Splits work over the pool's threads: [body] gets the item and the index of the thread (0 until [threads]) for its scratch memory. */
internal interface Parallel {
    val threads: Int

    fun run(items: Int, body: (item: Int, worker: Int) -> Unit)

    /** True once the run should stop early (cancelled): long loops look at it between pieces of work. */
    val stopping: Boolean
}

/** Runs everything on the calling thread (tests, and a fallback). */
internal object Serial : Parallel {
    override val threads = 1
    override val stopping = false

    override fun run(items: Int, body: (item: Int, worker: Int) -> Unit) {
        for (i in 0 until items) body(i, 0)
    }
}

internal object CutoutKernels {
    /** Columns of the packed right-hand block (output pixels per piece of work) and rows of it (the depth of one pass). */
    const val NC = 64
    const val KC = 384

    fun outSize(n: Int, k: Int, stride: Int, pad: Int): Int = (n + 2 * pad - k) / stride + 1

    /**
     * Packs the weights of a standard convolution ([o] outputs, [kTotal] = inputs x k x k each, row-major) four outputs at a time:
     * packed[(q * kTotal + kk) * 4 + j] = w[(4q + j) * kTotal + kk], zero for the padding outputs.
     */
    fun packWeights(w: FloatArray, o: Int, kTotal: Int): FloatArray {
        val quads = (o + 3) / 4
        val out = FloatArray(quads * kTotal * 4)
        for (oc in 0 until o) {
            val q = oc ushr 2
            val j = oc and 3
            val src = oc * kTotal
            var d = q * kTotal * 4 + j
            for (kk in 0 until kTotal) {
                out[d] = w[src + kk]
                d += 4
            }
        }
        return out
    }

    /**
     * A standard (group 1) convolution as a matrix product: out[o][p] = bias[o] + sum_k W[o][k] * col[k][p], where col is the k x k
     * neighbourhood of the input around output pixel p (zero outside). The input neighbourhoods are packed a block at a time
     * (at most [KC] x [NC] floats per thread), never for the whole picture.
     */
    fun conv(
        inp: Tensor,
        out: Tensor,
        packed: FloatArray,
        bias: FloatArray?,
        k: Int,
        stride: Int,
        pad: Int,
        act: Int,
        par: Parallel,
        scratch: Array<FloatArray>,
        progress: ((Int) -> Unit)? = null,
    ) {
        val cin = inp.c
        val o = out.c
        val kTotal = cin * k * k
        val oh = out.h
        val ow = out.w
        val p = oh * ow
        val quads = (o + 3) / 4
        val blocks = (p + NC - 1) / NC
        val groups = convGroups(p, o, par.threads)
        val quadsPerGroup = (quads + groups - 1) / groups
        val items = blocks * groups
        par.run(items) { item, worker ->
            if (par.stopping) return@run
            val blk = item / groups
            val grp = item % groups
            val q0 = grp * quadsPerGroup
            val q1 = min(quads, q0 + quadsPerGroup)
            if (q0 >= q1) return@run
            val p0 = blk * NC
            val np = min(NC, p - p0)
            val npad = (np + 3) and 3.inv()
            val b = scratch[worker]
            var k0 = 0
            while (k0 < kTotal) {
                val kc = min(KC, kTotal - k0)
                packColumns(inp, b, k0, kc, p0, np, npad, k, stride, pad, ow)
                for (q in q0 until q1) {
                    gemmQuad(packed, b, kTotal, k0, kc, q, o, p0, np, npad, out, bias, first = k0 == 0, last = k0 + kc >= kTotal, act = act)
                }
                k0 += kc
            }
            progress?.invoke(1)
        }
    }

    // ------------------------------------------------------------------------------------------------ 3 x 3 convolutions, Winograd F(2x2, 3x3)
    // A 3 x 3 convolution (stride 1, padding 1) computed on 2 x 2 output tiles: 16 products per tile and input/output pair instead of 36,
    // with the transforms of Lavin and Gray, "Fast Algorithms for Convolutional Neural Networks" (2016). Exact in exact arithmetic; in
    // floats it differs from the direct sum by rounding only.

    /** Output tiles per piece of work. */
    const val WT = 32

    fun winogradFits(k: Int, stride: Int, pad: Int, cin: Int): Boolean = k == 3 && stride == 1 && pad == 1 && cin <= KC

    /** The transformed filters U = G g G^T, packed per position p (16) four outputs at a time: [((p * quads + q) * cin + ic) * 4 + j]. */
    fun winogradWeights(w: FloatArray, o: Int, cin: Int): FloatArray {
        val quads = (o + 3) / 4
        val out = FloatArray(16 * quads * cin * 4)
        val t = FloatArray(12)
        val u = FloatArray(16)
        for (oc in 0 until o) {
            val q = oc ushr 2
            val j = oc and 3
            for (ic in 0 until cin) {
                val g = (oc * cin + ic) * 9
                for (c in 0 until 3) {
                    val g0 = w[g + c]
                    val g1 = w[g + 3 + c]
                    val g2 = w[g + 6 + c]
                    t[c] = g0
                    t[3 + c] = 0.5f * (g0 + g1 + g2)
                    t[6 + c] = 0.5f * (g0 - g1 + g2)
                    t[9 + c] = g2
                }
                for (r in 0 until 4) {
                    val a0 = t[r * 3]
                    val a1 = t[r * 3 + 1]
                    val a2 = t[r * 3 + 2]
                    u[r * 4] = a0
                    u[r * 4 + 1] = 0.5f * (a0 + a1 + a2)
                    u[r * 4 + 2] = 0.5f * (a0 - a1 + a2)
                    u[r * 4 + 3] = a2
                }
                for (p in 0 until 16) out[((p * quads + q) * cin + ic) * 4 + j] = u[p]
            }
        }
        return out
    }

    fun winogradItems(oh: Int, ow: Int): Int {
        val tiles = ((oh + 1) / 2) * ((ow + 1) / 2)
        return (tiles + WT - 1) / WT
    }

    fun winogradConv(inp: Tensor, out: Tensor, u: FloatArray, bias: FloatArray?, act: Int, par: Parallel) {
        val cin = inp.c
        val o = out.c
        val ih = inp.h
        val iw = inp.w
        val oh = out.h
        val ow = out.w
        val tw = (ow + 1) / 2
        val tiles = ((oh + 1) / 2) * tw
        val items = (tiles + WT - 1) / WT
        val quads = (o + 3) / 4
        val vBufs = arrayOfNulls<FloatArray>(par.threads)
        val mBufs = arrayOfNulls<FloatArray>(par.threads)
        val src = inp.data
        val dst = out.data
        par.run(items) { item, worker ->
            if (par.stopping) return@run
            val t0 = item * WT
            val nt = min(WT, tiles - t0)
            val ntPad = (nt + 3) and 3.inv()
            val vs = vBufs[worker] ?: FloatArray(16 * cin * WT).also { vBufs[worker] = it }
            val ms = mBufs[worker] ?: FloatArray(16 * o * WT).also { mBufs[worker] = it }
            val vStride = cin * ntPad
            // the input tiles, transformed: V[p][ic][tile] = (B^T d B)[p]
            for (ic in 0 until cin) {
                val cb = inp.off + ic * ih * iw
                for (n in 0 until ntPad) {
                    var at0 = ic * ntPad + n
                    if (n >= nt) {
                        for (p in 0 until 16) {
                            vs[at0] = 0f
                            at0 += vStride
                        }
                        continue
                    }
                    val tile = t0 + n
                    val ty = tile / tw
                    val tx = tile - ty * tw
                    val y0 = ty * 2 - 1
                    val x0 = tx * 2 - 1
                    val d00: Float; val d01: Float; val d02: Float; val d03: Float
                    val d10: Float; val d11: Float; val d12: Float; val d13: Float
                    val d20: Float; val d21: Float; val d22: Float; val d23: Float
                    val d30: Float; val d31: Float; val d32: Float; val d33: Float
                    if (y0 >= 0 && x0 >= 0 && y0 + 3 < ih && x0 + 3 < iw) {
                        var r = cb + y0 * iw + x0
                        d00 = src[r]; d01 = src[r + 1]; d02 = src[r + 2]; d03 = src[r + 3]
                        r += iw
                        d10 = src[r]; d11 = src[r + 1]; d12 = src[r + 2]; d13 = src[r + 3]
                        r += iw
                        d20 = src[r]; d21 = src[r + 1]; d22 = src[r + 2]; d23 = src[r + 3]
                        r += iw
                        d30 = src[r]; d31 = src[r + 1]; d32 = src[r + 2]; d33 = src[r + 3]
                    } else {
                        d00 = px(src, cb, ih, iw, y0, x0); d01 = px(src, cb, ih, iw, y0, x0 + 1); d02 = px(src, cb, ih, iw, y0, x0 + 2); d03 = px(src, cb, ih, iw, y0, x0 + 3)
                        d10 = px(src, cb, ih, iw, y0 + 1, x0); d11 = px(src, cb, ih, iw, y0 + 1, x0 + 1); d12 = px(src, cb, ih, iw, y0 + 1, x0 + 2); d13 = px(src, cb, ih, iw, y0 + 1, x0 + 3)
                        d20 = px(src, cb, ih, iw, y0 + 2, x0); d21 = px(src, cb, ih, iw, y0 + 2, x0 + 1); d22 = px(src, cb, ih, iw, y0 + 2, x0 + 2); d23 = px(src, cb, ih, iw, y0 + 2, x0 + 3)
                        d30 = px(src, cb, ih, iw, y0 + 3, x0); d31 = px(src, cb, ih, iw, y0 + 3, x0 + 1); d32 = px(src, cb, ih, iw, y0 + 3, x0 + 2); d33 = px(src, cb, ih, iw, y0 + 3, x0 + 3)
                    }
                    // B^T d: rows
                    val a00 = d00 - d20; val a01 = d01 - d21; val a02 = d02 - d22; val a03 = d03 - d23
                    val a10 = d10 + d20; val a11 = d11 + d21; val a12 = d12 + d22; val a13 = d13 + d23
                    val a20 = d20 - d10; val a21 = d21 - d11; val a22 = d22 - d12; val a23 = d23 - d13
                    val a30 = d10 - d30; val a31 = d11 - d31; val a32 = d12 - d32; val a33 = d13 - d33
                    // (B^T d) B: columns
                    vs[at0] = a00 - a02; at0 += vStride
                    vs[at0] = a01 + a02; at0 += vStride
                    vs[at0] = a02 - a01; at0 += vStride
                    vs[at0] = a01 - a03; at0 += vStride
                    vs[at0] = a10 - a12; at0 += vStride
                    vs[at0] = a11 + a12; at0 += vStride
                    vs[at0] = a12 - a11; at0 += vStride
                    vs[at0] = a11 - a13; at0 += vStride
                    vs[at0] = a20 - a22; at0 += vStride
                    vs[at0] = a21 + a22; at0 += vStride
                    vs[at0] = a22 - a21; at0 += vStride
                    vs[at0] = a21 - a23; at0 += vStride
                    vs[at0] = a30 - a32; at0 += vStride
                    vs[at0] = a31 + a32; at0 += vStride
                    vs[at0] = a32 - a31; at0 += vStride
                    vs[at0] = a31 - a33
                }
            }
            // sixteen products: M[p] = U[p] . V[p]
            for (p in 0 until 16) {
                val m = Tensor(ms, p * o * ntPad, o, 1, ntPad)
                for (q in 0 until quads) {
                    gemmQuad(u, vs, cin, 0, cin, q, o, 0, ntPad, ntPad, m, null, first = true, last = true, act = 0, aOff = p * quads * cin * 4, bOff = p * vStride)
                }
            }
            // back to pixels: Y = A^T M A, 2 x 2 outputs per tile
            val ps = o * ntPad
            for (oc in 0 until o) {
                val bv = bias?.get(oc) ?: 0f
                val ob = out.off + oc * oh * ow
                for (n in 0 until nt) {
                    var at0 = oc * ntPad + n
                    val m00 = ms[at0]; at0 += ps
                    val m01 = ms[at0]; at0 += ps
                    val m02 = ms[at0]; at0 += ps
                    val m03 = ms[at0]; at0 += ps
                    val m10 = ms[at0]; at0 += ps
                    val m11 = ms[at0]; at0 += ps
                    val m12 = ms[at0]; at0 += ps
                    val m13 = ms[at0]; at0 += ps
                    val m20 = ms[at0]; at0 += ps
                    val m21 = ms[at0]; at0 += ps
                    val m22 = ms[at0]; at0 += ps
                    val m23 = ms[at0]; at0 += ps
                    val m30 = ms[at0]; at0 += ps
                    val m31 = ms[at0]; at0 += ps
                    val m32 = ms[at0]; at0 += ps
                    val m33 = ms[at0]
                    // A^T M, then (A^T M) A
                    val b0 = m00 + m10 + m20; val b1 = m01 + m11 + m21; val b2 = m02 + m12 + m22; val b3 = m03 + m13 + m23
                    val c0 = m10 - m20 - m30; val c1 = m11 - m21 - m31; val c2 = m12 - m22 - m32; val c3 = m13 - m23 - m33
                    val tile = t0 + n
                    val ty = tile / tw
                    val tx = tile - ty * tw
                    val oy = ty * 2
                    val ox = tx * 2
                    val r0 = ob + oy * ow + ox
                    dst[r0] = activate(b0 + b1 + b2 + bv, act)
                    if (ox + 1 < ow) dst[r0 + 1] = activate(b1 - b2 - b3 + bv, act)
                    if (oy + 1 < oh) {
                        dst[r0 + ow] = activate(c0 + c1 + c2 + bv, act)
                        if (ox + 1 < ow) dst[r0 + ow + 1] = activate(c1 - c2 - c3 + bv, act)
                    }
                }
            }
        }
    }

    private fun px(src: FloatArray, cb: Int, ih: Int, iw: Int, y: Int, x: Int): Float = if (y in 0 until ih && x in 0 until iw) src[cb + y * iw + x] else 0f

    /** Enough pieces of work for every thread: when the picture is small, the outputs are split as well. */
    fun convGroups(p: Int, o: Int, threads: Int): Int {
        val quads = (o + 3) / 4
        val blocks = (p + NC - 1) / NC
        var groups = 1
        while (blocks * groups < threads * 3 && groups * 2 <= quads) groups *= 2
        return groups
    }

    fun convItems(p: Int, o: Int, threads: Int): Int = ((p + NC - 1) / NC) * convGroups(p, o, threads)

    /** col[kk - k0][i] for kk in [k0, k0 + kc) and pixels p0 .. p0 + np (columns np .. npad zero). */
    private fun packColumns(inp: Tensor, b: FloatArray, k0: Int, kc: Int, p0: Int, np: Int, npad: Int, k: Int, stride: Int, pad: Int, ow: Int) {
        val src = inp.data
        val ih = inp.h
        val iw = inp.w
        val plane = ih * iw
        val kk2 = k * k
        if (k == 1 && stride == 1 && pad == 0) {
            for (r in 0 until kc) {
                val c = k0 + r
                System.arraycopy(src, inp.off + c * plane + p0, b, r * npad, np)
                for (i in np until npad) b[r * npad + i] = 0f
            }
            return
        }
        for (r in 0 until kc) {
            val kk = k0 + r
            val c = kk / kk2
            val rem = kk - c * kk2
            val ky = rem / k
            val kx = rem - ky * k
            val cbase = inp.off + c * plane
            val row = r * npad
            var i = 0
            var oy = p0 / ow
            var ox = p0 - oy * ow
            while (i < np) {
                // one stretch of an output row at a time
                val run = min(np - i, ow - ox)
                val iy = oy * stride + ky - pad
                if (iy < 0 || iy >= ih) {
                    for (j in 0 until run) b[row + i + j] = 0f
                } else {
                    val rbase = cbase + iy * iw
                    var ix = ox * stride + kx - pad
                    for (j in 0 until run) {
                        b[row + i + j] = if (ix in 0 until iw) src[rbase + ix] else 0f
                        ix += stride
                    }
                }
                i += run
                ox = 0
                oy++
            }
            for (j in np until npad) b[row + j] = 0f
        }
    }

    /** Four outputs (quad [q]) times the block's pixels, four pixels at a time, sixteen sums held in locals. */
    private fun gemmQuad(
        a: FloatArray,
        b: FloatArray,
        kTotal: Int,
        k0: Int,
        kc: Int,
        q: Int,
        o: Int,
        p0: Int,
        np: Int,
        npad: Int,
        out: Tensor,
        bias: FloatArray?,
        first: Boolean,
        last: Boolean,
        act: Int,
        aOff: Int = 0,
        bOff: Int = 0,
    ) {
        val dst = out.data
        val plane = out.plane
        val oc0 = q * 4
        val n = min(4, o - oc0)
        val aBase = aOff + (q * kTotal + k0) * 4
        val r0 = out.off + oc0 * plane + p0
        val r1 = r0 + plane
        val r2 = r1 + plane
        val r3 = r2 + plane
        val b0 = if (bias != null) bias[oc0] else 0f
        val b1 = if (bias != null && n > 1) bias[oc0 + 1] else 0f
        val b2 = if (bias != null && n > 2) bias[oc0 + 2] else 0f
        val b3 = if (bias != null && n > 3) bias[oc0 + 3] else 0f
        var i = 0
        while (i < npad) {
            var c00: Float; var c01: Float; var c02: Float; var c03: Float
            var c10: Float; var c11: Float; var c12: Float; var c13: Float
            var c20: Float; var c21: Float; var c22: Float; var c23: Float
            var c30: Float; var c31: Float; var c32: Float; var c33: Float
            val full = i + 4 <= np
            if (first) {
                c00 = b0; c01 = b0; c02 = b0; c03 = b0
                c10 = b1; c11 = b1; c12 = b1; c13 = b1
                c20 = b2; c21 = b2; c22 = b2; c23 = b2
                c30 = b3; c31 = b3; c32 = b3; c33 = b3
            } else {
                c00 = ld(dst, r0, i, np); c01 = ld(dst, r0, i + 1, np); c02 = ld(dst, r0, i + 2, np); c03 = ld(dst, r0, i + 3, np)
                if (n > 1) { c10 = ld(dst, r1, i, np); c11 = ld(dst, r1, i + 1, np); c12 = ld(dst, r1, i + 2, np); c13 = ld(dst, r1, i + 3, np) } else { c10 = 0f; c11 = 0f; c12 = 0f; c13 = 0f }
                if (n > 2) { c20 = ld(dst, r2, i, np); c21 = ld(dst, r2, i + 1, np); c22 = ld(dst, r2, i + 2, np); c23 = ld(dst, r2, i + 3, np) } else { c20 = 0f; c21 = 0f; c22 = 0f; c23 = 0f }
                if (n > 3) { c30 = ld(dst, r3, i, np); c31 = ld(dst, r3, i + 1, np); c32 = ld(dst, r3, i + 2, np); c33 = ld(dst, r3, i + 3, np) } else { c30 = 0f; c31 = 0f; c32 = 0f; c33 = 0f }
            }
            var ai = aBase
            var bi = bOff + i
            for (kk in 0 until kc) {
                val w0 = a[ai]
                val w1 = a[ai + 1]
                val w2 = a[ai + 2]
                val w3 = a[ai + 3]
                val x0 = b[bi]
                val x1 = b[bi + 1]
                val x2 = b[bi + 2]
                val x3 = b[bi + 3]
                c00 += w0 * x0; c01 += w0 * x1; c02 += w0 * x2; c03 += w0 * x3
                c10 += w1 * x0; c11 += w1 * x1; c12 += w1 * x2; c13 += w1 * x3
                c20 += w2 * x0; c21 += w2 * x1; c22 += w2 * x2; c23 += w2 * x3
                c30 += w3 * x0; c31 += w3 * x1; c32 += w3 * x2; c33 += w3 * x3
                ai += 4
                bi += npad
            }
            if (last && act != 0) {
                c00 = activate(c00, act); c01 = activate(c01, act); c02 = activate(c02, act); c03 = activate(c03, act)
                c10 = activate(c10, act); c11 = activate(c11, act); c12 = activate(c12, act); c13 = activate(c13, act)
                c20 = activate(c20, act); c21 = activate(c21, act); c22 = activate(c22, act); c23 = activate(c23, act)
                c30 = activate(c30, act); c31 = activate(c31, act); c32 = activate(c32, act); c33 = activate(c33, act)
            }
            if (full) {
                dst[r0 + i] = c00; dst[r0 + i + 1] = c01; dst[r0 + i + 2] = c02; dst[r0 + i + 3] = c03
                if (n > 1) { dst[r1 + i] = c10; dst[r1 + i + 1] = c11; dst[r1 + i + 2] = c12; dst[r1 + i + 3] = c13 }
                if (n > 2) { dst[r2 + i] = c20; dst[r2 + i + 1] = c21; dst[r2 + i + 2] = c22; dst[r2 + i + 3] = c23 }
                if (n > 3) { dst[r3 + i] = c30; dst[r3 + i + 1] = c31; dst[r3 + i + 2] = c32; dst[r3 + i + 3] = c33 }
            } else {
                st(dst, r0, i, np, c00, c01, c02, c03)
                if (n > 1) st(dst, r1, i, np, c10, c11, c12, c13)
                if (n > 2) st(dst, r2, i, np, c20, c21, c22, c23)
                if (n > 3) st(dst, r3, i, np, c30, c31, c32, c33)
            }
            i += 4
        }
    }

    private fun ld(d: FloatArray, row: Int, i: Int, np: Int): Float = if (i < np) d[row + i] else 0f

    private fun st(d: FloatArray, row: Int, i: Int, np: Int, a: Float, b: Float, c: Float, e: Float) {
        if (i < np) d[row + i] = a
        if (i + 1 < np) d[row + i + 1] = b
        if (i + 2 < np) d[row + i + 2] = c
        if (i + 3 < np) d[row + i + 3] = e
    }

    fun activate(v: Float, act: Int): Float =
        when (act) {
            1 -> if (v > 0f) v else 0f
            2 -> if (v < 0f) 0f else if (v > 6f) 6f else v
            3 -> (1.0 / (1.0 + exp(-v.toDouble()))).toFloat()
            else -> v
        }

    /** A depthwise convolution (one k x k filter per channel, [w] is [c][k][k]). */
    fun depthwise(inp: Tensor, out: Tensor, w: FloatArray, bias: FloatArray?, k: Int, stride: Int, pad: Int, act: Int, par: Parallel) {
        val c = inp.c
        val ih = inp.h
        val iw = inp.w
        val oh = out.h
        val ow = out.w
        val src = inp.data
        val dst = out.data
        val kk2 = k * k
        par.run(c) { ch, _ ->
            if (par.stopping) return@run
            val sb = inp.off + ch * ih * iw
            val db = out.off + ch * oh * ow
            val wb = ch * kk2
            val bv = bias?.get(ch) ?: 0f
            if (k == 3) {
                val w00 = w[wb]; val w01 = w[wb + 1]; val w02 = w[wb + 2]
                val w10 = w[wb + 3]; val w11 = w[wb + 4]; val w12 = w[wb + 5]
                val w20 = w[wb + 6]; val w21 = w[wb + 7]; val w22 = w[wb + 8]
                // the outputs whose 3 x 3 window lies fully inside the picture need no checks
                val xa = ceilDiv(pad, stride)
                val xb = min(ow, Math.floorDiv(iw - 3 + pad, stride) + 1)
                for (oy in 0 until oh) {
                    val iy = oy * stride - pad
                    val inside = iy >= 0 && iy + 2 < ih
                    val drow = db + oy * ow
                    if (inside && xa < xb) {
                        val r0 = sb + iy * iw
                        val r1 = r0 + iw
                        val r2 = r1 + iw
                        for (ox in 0 until xa) dst[drow + ox] = activate(dwAt(src, sb, ih, iw, iy, ox * stride - pad, w, wb, 3) + bv, act)
                        var ix = xa * stride - pad
                        for (ox in xa until xb) {
                            var s = bv
                            s += w00 * src[r0 + ix] + w01 * src[r0 + ix + 1] + w02 * src[r0 + ix + 2]
                            s += w10 * src[r1 + ix] + w11 * src[r1 + ix + 1] + w12 * src[r1 + ix + 2]
                            s += w20 * src[r2 + ix] + w21 * src[r2 + ix + 1] + w22 * src[r2 + ix + 2]
                            dst[drow + ox] = if (act == 0) s else activate(s, act)
                            ix += stride
                        }
                        for (ox in xb until ow) dst[drow + ox] = activate(dwAt(src, sb, ih, iw, iy, ox * stride - pad, w, wb, 3) + bv, act)
                    } else {
                        for (ox in 0 until ow) dst[drow + ox] = activate(dwAt(src, sb, ih, iw, iy, ox * stride - pad, w, wb, 3) + bv, act)
                    }
                }
            } else {
                for (oy in 0 until oh) {
                    for (ox in 0 until ow) {
                        dst[db + oy * ow + ox] = activate(dwAt(src, sb, ih, iw, oy * stride - pad, ox * stride - pad, w, wb, k) + bv, act)
                    }
                }
            }
        }
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    private fun dwAt(src: FloatArray, sb: Int, ih: Int, iw: Int, iy0: Int, ix0: Int, w: FloatArray, wb: Int, k: Int): Float {
        var s = 0f
        for (ky in 0 until k) {
            val iy = iy0 + ky
            if (iy < 0 || iy >= ih) continue
            val r = sb + iy * iw
            for (kx in 0 until k) {
                val ix = ix0 + kx
                if (ix < 0 || ix >= iw) continue
                s += w[wb + ky * k + kx] * src[r + ix]
            }
        }
        return s
    }

    /** The sampling of one axis for a bilinear resize with half-pixel centres: the two source indices and the weight of the second. */
    class Axis(val i0: IntArray, val i1: IntArray, val f: FloatArray)

    fun axis(outN: Int, inN: Int, scale: Float): Axis {
        val i0 = IntArray(outN)
        val i1 = IntArray(outN)
        val f = FloatArray(outN)
        for (o in 0 until outN) {
            var src = if (outN == 1) 0f else ((o + 0.5f) / scale - 0.5f)
            if (src < 0f) src = 0f
            if (src > inN - 1) src = (inN - 1).toFloat()
            val a = floor(src).toInt()
            i0[o] = a
            i1[o] = min(a + 1, inN - 1)
            f[o] = src - a
        }
        return Axis(i0, i1, f)
    }

    fun resizeOut(n: Int, scale: Float): Int = max(1, floor(n * scale.toDouble() + 1e-6).toInt())

    fun resize(inp: Tensor, out: Tensor, scaleH: Float, scaleW: Float, par: Parallel) {
        val ay = axis(out.h, inp.h, scaleH)
        val ax = axis(out.w, inp.w, scaleW)
        val src = inp.data
        val dst = out.data
        val ow = out.w
        val iw = inp.w
        par.run(inp.c) { ch, _ ->
            if (par.stopping) return@run
            val sb = inp.off + ch * inp.plane
            val db = out.off + ch * out.plane
            for (oy in 0 until out.h) {
                val ra = sb + ay.i0[oy] * iw
                val rb = sb + ay.i1[oy] * iw
                val fy = ay.f[oy]
                val d = db + oy * ow
                for (ox in 0 until ow) {
                    val x0 = ax.i0[ox]
                    val x1 = ax.i1[ox]
                    val fx = ax.f[ox]
                    val top = src[ra + x0] + (src[ra + x1] - src[ra + x0]) * fx
                    val bot = src[rb + x0] + (src[rb + x1] - src[rb + x0]) * fx
                    dst[d + ox] = top + (bot - top) * fy
                }
            }
        }
    }

    /** In place: per-channel affine below [half], instance norm from [half], then ReLU. */
    fun ibn(t: Tensor, half: Int, scale: FloatArray, shift: FloatArray, gamma: FloatArray, beta: FloatArray, eps: Float, par: Parallel) {
        val d = t.data
        val n = t.plane
        par.run(t.c) { ch, _ ->
            if (par.stopping) return@run
            val base = t.off + ch * n
            if (ch < half) {
                val s = scale[ch]
                val b = shift[ch]
                for (i in base until base + n) {
                    val v = d[i] * s + b
                    d[i] = if (v > 0f) v else 0f
                }
            } else {
                var sum = 0.0
                for (i in base until base + n) sum += d[i]
                val mean = sum / n
                var sq = 0.0
                for (i in base until base + n) {
                    val e = d[i] - mean
                    sq += e * e
                }
                val inv = 1.0 / sqrt(sq / n + eps)
                val g = (gamma[ch - half] * inv).toFloat()
                val b = (beta[ch - half] - mean * gamma[ch - half] * inv).toFloat()
                for (i in base until base + n) {
                    val v = d[i] * g + b
                    d[i] = if (v > 0f) v else 0f
                }
            }
        }
    }

    /** In place squeeze and excitation; [w1] is [mid][c], [w2] is [c][mid]. */
    fun se(t: Tensor, w1: FloatArray, w2: FloatArray, mid: Int, par: Parallel) {
        val c = t.c
        val n = t.plane
        val d = t.data
        val mean = FloatArray(c)
        par.run(c) { ch, _ ->
            var s = 0.0
            val base = t.off + ch * n
            for (i in base until base + n) s += d[i]
            mean[ch] = (s / n).toFloat()
        }
        val hidden = FloatArray(mid)
        for (j in 0 until mid) {
            var s = 0f
            val r = j * c
            for (i in 0 until c) s += w1[r + i] * mean[i]
            hidden[j] = if (s > 0f) s else 0f
        }
        val gate = FloatArray(c)
        for (i in 0 until c) {
            var s = 0f
            val r = i * mid
            for (j in 0 until mid) s += w2[r + j] * hidden[j]
            gate[i] = (1.0 / (1.0 + exp(-s.toDouble()))).toFloat()
        }
        par.run(c) { ch, _ ->
            val g = gate[ch]
            val base = t.off + ch * n
            for (i in base until base + n) d[i] *= g
        }
    }

    fun add(a: Tensor, b: Tensor, out: Tensor, par: Parallel) {
        val n = a.size
        val chunk = 16384
        val pieces = (n + chunk - 1) / chunk
        par.run(pieces) { p, _ ->
            val s = p * chunk
            val e = min(n, s + chunk)
            val ad = a.data
            val bd = b.data
            val od = out.data
            val ao = a.off
            val bo = b.off
            val oo = out.off
            for (i in s until e) od[oo + i] = ad[ao + i] + bd[bo + i]
        }
    }

    fun copy(src: Tensor, dst: Tensor) {
        System.arraycopy(src.data, src.off, dst.data, dst.off, src.size)
    }
}
