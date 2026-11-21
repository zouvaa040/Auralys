package com.metrolist.music.poster.cutout

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** Each kernel of the cut-out engine against a plain, obviously correct version of the same arithmetic. */
class CutoutKernelsTest {
    private fun rnd(n: Int, seed: Long): FloatArray {
        val r = Random(seed)
        return FloatArray(n) { r.nextFloat() * 2f - 1f }
    }

    private fun naiveConv(x: FloatArray, c: Int, h: Int, w: Int, wt: FloatArray, o: Int, k: Int, s: Int, p: Int, bias: FloatArray?, act: Int): FloatArray {
        val oh = (h + 2 * p - k) / s + 1
        val ow = (w + 2 * p - k) / s + 1
        val out = FloatArray(o * oh * ow)
        for (oc in 0 until o) for (oy in 0 until oh) for (ox in 0 until ow) {
            var sum = (bias?.get(oc) ?: 0f).toDouble()
            for (ic in 0 until c) for (ky in 0 until k) for (kx in 0 until k) {
                val iy = oy * s + ky - p
                val ix = ox * s + kx - p
                if (iy in 0 until h && ix in 0 until w) sum += wt[((oc * c + ic) * k + ky) * k + kx].toDouble() * x[(ic * h + iy) * w + ix]
            }
            out[(oc * oh + oy) * ow + ox] = CutoutKernels.activate(sum.toFloat(), act)
        }
        return out
    }

    private fun maxDiff(a: FloatArray, b: FloatArray): Float {
        assertEquals(a.size, b.size)
        var m = 0f
        for (i in a.indices) m = max(m, abs(a[i] - b[i]))
        return m
    }

    private fun pool(threads: Int): Parallel = if (threads == 1) Serial else CutoutPool(threads)

    @Test
    fun conv_matchesNaive_forEveryKernelStrideAndSize() {
        // odd sizes on purpose: outputs not a multiple of 4, pixels not a multiple of the block, depth beyond one pass (KC)
        val cases = listOf(
            intArrayOf(3, 13, 11, 5, 3, 2, 1, 2),
            intArrayOf(16, 9, 7, 6, 1, 1, 0, 0),
            intArrayOf(35, 17, 10, 16, 3, 1, 1, 1),
            intArrayOf(64, 6, 5, 9, 5, 1, 2, 0),
            intArrayOf(7, 70, 3, 1, 1, 1, 0, 3),
            intArrayOf(90, 5, 5, 4, 3, 2, 1, 2),
        )
        for ((n, cs) in cases.withIndex()) {
            val c = cs[0]
            val h = cs[1]
            val w = cs[2]
            val o = cs[3]
            val k = cs[4]
            val s = cs[5]
            val p = cs[6]
            val act = cs[7]
            val x = rnd(c * h * w, 10L + n)
            val wt = rnd(o * c * k * k, 20L + n)
            val bias = rnd(o, 30L + n)
            val ref = naiveConv(x, c, h, w, wt, o, k, s, p, bias, act)
            for (threads in listOf(1, 3)) {
                val par = pool(threads)
                val oh = CutoutKernels.outSize(h, k, s, p)
                val ow = CutoutKernels.outSize(w, k, s, p)
                // the output sits in the middle of a bigger array, the way a slice of a concatenation does
                val big = FloatArray(o * oh * ow + 37)
                val out = Tensor(big, 37, o, oh, ow)
                val scratch = Array(par.threads) { FloatArray(CutoutKernels.KC * CutoutKernels.NC) }
                CutoutKernels.conv(Tensor(x, 0, c, h, w), out, CutoutKernels.packWeights(wt, o, c * k * k), bias, k, s, p, act, par, scratch)
                val got = big.copyOfRange(37, big.size)
                assertTrue("case $n threads $threads diff ${maxDiff(got, ref)}", maxDiff(got, ref) < 2e-4f * sqrt((c * k * k).toFloat()))
                for (i in 0 until 37) assertEquals(0f, big[i])
                (par as? CutoutPool)?.close()
            }
        }
    }

    @Test
    fun winograd3x3_matchesNaive_includingOddSizesAndPartialTiles() {
        // odd heights and widths (half-filled tiles), tile counts that are not a multiple of the piece size, a single row, a single pixel
        val cases = listOf(
            intArrayOf(5, 13, 11, 6, 2),
            intArrayOf(35, 17, 10, 16, 1),
            intArrayOf(64, 9, 70, 32, 1),
            intArrayOf(3, 1, 7, 5, 0),
            intArrayOf(8, 1, 1, 3, 3),
            intArrayOf(99, 12, 8, 64, 1),
        )
        for ((n, cs) in cases.withIndex()) {
            val c = cs[0]
            val h = cs[1]
            val w = cs[2]
            val o = cs[3]
            val act = cs[4]
            assertTrue(CutoutKernels.winogradFits(3, 1, 1, c))
            val x = rnd(c * h * w, 110L + n)
            val wt = rnd(o * c * 9, 120L + n)
            val bias = rnd(o, 130L + n)
            val ref = naiveConv(x, c, h, w, wt, o, 3, 1, 1, bias, act)
            for (threads in listOf(1, 3)) {
                val par = pool(threads)
                val big = FloatArray(o * h * w + 11)
                val out = Tensor(big, 11, o, h, w)
                CutoutKernels.winogradConv(Tensor(x, 0, c, h, w), out, CutoutKernels.winogradWeights(wt, o, c), bias, act, par)
                val got = big.copyOfRange(11, big.size)
                assertTrue("case $n threads $threads diff ${maxDiff(got, ref)}", maxDiff(got, ref) < 3e-4f * sqrt((c * 9).toFloat()))
                (par as? CutoutPool)?.close()
            }
        }
        assertTrue(!CutoutKernels.winogradFits(3, 2, 1, 32))
        assertTrue(!CutoutKernels.winogradFits(5, 1, 2, 32))
    }

    @Test
    fun depthwise_matchesNaive_includingTinyInputs() {
        val cases = listOf(intArrayOf(5, 9, 7, 1), intArrayOf(4, 8, 8, 2), intArrayOf(3, 1, 1, 2), intArrayOf(2, 2, 3, 2), intArrayOf(6, 3, 2, 1), intArrayOf(3, 12, 5, 2))
        for ((n, cs) in cases.withIndex()) {
            val c = cs[0]
            val h = cs[1]
            val w = cs[2]
            val s = cs[3]
            val x = rnd(c * h * w, 40L + n)
            val wt = rnd(c * 9, 50L + n)
            val bias = rnd(c, 60L + n)
            val oh = CutoutKernels.outSize(h, 3, s, 1)
            val ow = CutoutKernels.outSize(w, 3, s, 1)
            val ref = FloatArray(c * oh * ow)
            for (ch in 0 until c) {
                val one = naiveConv(x.copyOfRange(ch * h * w, (ch + 1) * h * w), 1, h, w, wt.copyOfRange(ch * 9, ch * 9 + 9), 1, 3, s, 1, floatArrayOf(bias[ch]), 2)
                System.arraycopy(one, 0, ref, ch * oh * ow, oh * ow)
            }
            val out = Tensor(FloatArray(c * oh * ow), 0, c, oh, ow)
            CutoutKernels.depthwise(Tensor(x, 0, c, h, w), out, wt, bias, 3, s, 1, 2, Serial)
            assertTrue("case $n", maxDiff(out.data, ref) < 1e-5f)
        }
    }

    @Test
    fun resize_matchesOnnxRuntimeNumbers() {
        // reference values from the numpy resize that matches ONNX Runtime's Resize (linear, pytorch_half_pixel) to 1e-6
        val a = FloatArray(12) { Math.pow(it.toDouble(), 1.5).toFloat() }
        val up = Tensor(FloatArray(48), 0, 1, 6, 8)
        CutoutKernels.resize(Tensor(a, 0, 1, 3, 4), up, 2f, 2f, Serial)
        val upRef = floatArrayOf(
            0.0f, 0.25f, 0.75f, 1.45711f, 2.37132f, 3.42036f, 4.60422f, 5.19615f, 2.0f, 2.38627f, 3.15881f, 4.1077f, 5.23294f, 6.47846f, 7.84427f, 8.52718f,
            6.0f, 6.65881f, 7.97644f, 9.40889f, 10.95617f, 12.59467f, 14.32438f, 15.18923f, 11.65686f, 12.52645f, 14.26566f, 16.08354f, 17.98011f, 19.94903f, 21.99028f, 23.01092f,
            18.97056f, 19.98919f, 22.02646f, 24.13164f, 26.30476f, 28.54154f, 30.84199f, 31.99222f, 22.62742f, 23.72056f, 25.90686f, 28.15569f, 30.46708f, 32.8378f, 35.26785f, 36.48287f,
        )
        assertTrue(maxDiff(up.data, upRef) < 1e-3f)
        assertEquals(1, CutoutKernels.resizeOut(3, 0.5f))
        val down = Tensor(FloatArray(2), 0, 1, 1, 2)
        CutoutKernels.resize(Tensor(a, 0, 1, 3, 4), down, 0.5f, 0.5f, Serial)
        assertTrue(maxDiff(down.data, floatArrayOf(0.5f, 4.01229f)) < 1e-4f)
        val b = FloatArray(64) { Math.pow(it.toDouble(), 1.3).toFloat() }
        val q = Tensor(FloatArray(4), 0, 1, 2, 2)
        CutoutKernels.resize(Tensor(b, 0, 1, 8, 8), q, 0.25f, 0.25f, Serial)
        assertTrue(maxDiff(q.data, floatArrayOf(29.99107f, 41.7297f, 143.24501f, 159.78955f)) < 1e-3f)
    }

    @Test
    fun ibn_isBatchNormThenInstanceNormThenRelu() {
        val c = 6
        val n = 50
        val x = rnd(c * n, 70)
        val orig = x.copyOf()
        val scale = floatArrayOf(1.5f, -0.5f, 2f)
        val shift = floatArrayOf(0.1f, 0.2f, -0.3f)
        val gamma = floatArrayOf(0.9f, 1.1f, -1f)
        val beta = floatArrayOf(0.05f, -0.4f, 0.3f)
        CutoutKernels.ibn(Tensor(x, 0, c, 5, 10), 3, scale, shift, gamma, beta, 1e-5f, Serial)
        for (ch in 0 until c) {
            val base = ch * n
            if (ch < 3) {
                for (i in 0 until n) assertEquals(max(0f, orig[base + i] * scale[ch] + shift[ch]), x[base + i], 1e-5f)
            } else {
                var mean = 0.0
                for (i in 0 until n) mean += orig[base + i]
                mean /= n
                var v = 0.0
                for (i in 0 until n) v += (orig[base + i] - mean) * (orig[base + i] - mean)
                v /= n
                for (i in 0 until n) {
                    val e = ((orig[base + i] - mean) / sqrt(v + 1e-5) * gamma[ch - 3] + beta[ch - 3]).toFloat()
                    assertEquals(max(0f, e), x[base + i], 1e-4f)
                }
            }
        }
    }

    @Test
    fun se_scalesEveryChannelByItsGate() {
        val c = 8
        val mid = 3
        val n = 12
        val x = rnd(c * n, 80)
        val orig = x.copyOf()
        val w1 = rnd(mid * c, 81)
        val w2 = rnd(c * mid, 82)
        CutoutKernels.se(Tensor(x, 0, c, 3, 4), w1, w2, mid, Serial)
        val mean = DoubleArray(c) { ch -> (0 until n).sumOf { orig[ch * n + it].toDouble() } / n }
        val hidden = DoubleArray(mid) { j -> max(0.0, (0 until c).sumOf { w1[j * c + it] * mean[it] }) }
        for (ch in 0 until c) {
            val g = 1.0 / (1.0 + exp(-(0 until mid).sumOf { w2[ch * mid + it] * hidden[it] }))
            for (i in 0 until n) assertEquals((orig[ch * n + i] * g).toFloat(), x[ch * n + i], 1e-5f)
        }
    }

    @Test
    fun add_andCopy_respectOffsets() {
        val a = Tensor(FloatArray(20) { it.toFloat() }, 4, 2, 2, 2)
        val b = Tensor(FloatArray(10) { 100f + it }, 2, 2, 2, 2)
        val out = Tensor(FloatArray(9), 1, 2, 2, 2)
        CutoutKernels.add(a, b, out, Serial)
        assertArrayEquals(FloatArray(8) { (4 + it) + (102f + it) }, out.data.copyOfRange(1, 9), 0f)
        val dst = Tensor(FloatArray(16), 0, 4, 2, 2)
        CutoutKernels.copy(a, dst.channels(2, 2))
        assertArrayEquals(FloatArray(8) { 4f + it }, dst.data.copyOfRange(8, 16), 0f)
    }

    @Test
    fun pool_runsEveryItemOnce_andPropagatesErrors() {
        CutoutPool(3).use { p ->
            repeat(5) {
                val hits = IntArray(1000)
                p.run(1000) { i, wkr ->
                    assertTrue(wkr in 0 until 3)
                    synchronized(hits) { hits[i]++ }
                }
                assertTrue(hits.all { it == 1 })
            }
            var thrown = false
            try {
                p.run(50) { i, _ -> if (i == 7) throw IllegalStateException("boom") }
            } catch (e: IllegalStateException) {
                thrown = true
            }
            assertTrue(thrown)
            // still usable afterwards
            val count = java.util.concurrent.atomic.AtomicInteger()
            p.run(64) { _, _ -> count.incrementAndGet() }
            assertEquals(64, count.get())
        }
    }
}
