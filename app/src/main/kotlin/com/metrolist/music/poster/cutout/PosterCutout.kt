/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster.cutout

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.InputStream
import java.lang.ref.SoftReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ====================================================================================================================
// The automatic cut-out of the poster ("Poster lagu"): which part of the user's photo is the person. MODNet (portrait matting, Apache-2.0,
// see assets/licenses/NOTICE-poster.txt) runs on the phone itself, offline, in plain Kotlin: no native library, no Google Play services,
// the same on every ABI. The photo never leaves the phone.

sealed interface CutoutOutcome {
    /** [alpha] = one byte per photo pixel (w*h, row-major), 0 background .. 255 subject, already at the photo's size. */
    class Done(val w: Int, val h: Int, val alpha: ByteArray, val millis: Long, val coverage: Float, val inputW: Int, val inputH: Int) : CutoutOutcome

    /** The network found (almost) no person: under 2 percent coverage. */
    data object NoSubject : CutoutOutcome

    class Failed(val reason: String, val error: Throwable? = null) : CutoutOutcome
}

object PosterCutout {
    /** The converted network in the app's assets. */
    const val ASSET = "poster/modnet_q.bin"

    /** Under this share of the picture the network found no person. */
    const val MIN_COVERAGE = 0.02f

    /** The shorter side the network sees on a phone (both sides are multiples of 32, the longer one at most [MAX_SIDE]). */
    const val PHONE_SHORT_SIDE = 384

    /** On a phone that says it has little memory. */
    const val LOW_RAM_SHORT_SIDE = 320

    const val MAX_SIDE = 1024

    /** Smaller sizes tried, in order, when the memory is short. */
    private val FALLBACK_SIDES = intArrayOf(448, 384, 320, 256)

    private var cached: SoftReference<CutoutModel>? = null
    private val modelLock = Any()

    /** Runs MODNet on the photo (opaque ARGB ints, w*h). Heavy: call from Dispatchers.Default. Cancellable (throws CancellationException). */
    suspend fun cut(context: Context, argb: IntArray, w: Int, h: Int, onProgress: (Float) -> Unit = {}): CutoutOutcome {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lowRam = am?.isLowRamDevice == true
        val model =
            try {
                loadModel { context.assets.open(ASSET) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return CutoutOutcome.Failed("model", t)
            }
        return run(model, argb, w, h, defaultThreads(), if (lowRam) LOW_RAM_SHORT_SIDE else PHONE_SHORT_SIDE, onProgress)
    }

    /** Same, model bytes from a stream (JVM tests). */
    suspend fun cut(model: InputStream, argb: IntArray, w: Int, h: Int, threads: Int = 2, maxShortSide: Int = 512, onProgress: (Float) -> Unit = {}): CutoutOutcome {
        val m =
            try {
                CutoutModel.read(model)
            } catch (t: Throwable) {
                return CutoutOutcome.Failed("model", t)
            }
        return run(m, argb, w, h, threads, maxShortSide, onProgress)
    }

    /** One fewer than the cores, between 1 and 4: the phone (and the music) stays responsive. */
    fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)

    private inline fun loadModel(open: () -> InputStream): CutoutModel {
        synchronized(modelLock) {
            cached?.get()?.let { return it }
            val m = open().use { CutoutModel.read(it) }
            cached = SoftReference(m)
            return m
        }
    }

    internal suspend fun run(model: CutoutModel, argb: IntArray, w: Int, h: Int, threads: Int, maxShortSide: Int, onProgress: (Float) -> Unit): CutoutOutcome {
        if (w < 8 || h < 8 || argb.size < w * h) return CutoutOutcome.Failed("photo")
        val t0 = System.nanoTime()
        val ctx = currentCoroutineContext()
        // the sizes that may fit, biggest first: the first one whose memory need fits the heap that is really free
        val sizes = (listOf(maxShortSide) + FALLBACK_SIDES.filter { it < maxShortSide }).distinct().map { s ->
            val (nw, nh) = networkSize(w, h, s)
            Triple(nw, nh, CutoutPlan(model, nh, nw).bytesNeeded(threads) + nw.toLong() * nh * 3 * 4 + w.toLong() * h)
        }
        var start = firstFitting(sizes.map { it.third }) ?: return CutoutOutcome.Failed("memory")
        var lastOom: OutOfMemoryError? = null
        while (start < sizes.size) {
            val (nw, nh, _) = sizes[start]
            try {
                val input = toNetworkInput(argb, w, h, nw, nh)
                ctx.ensureActive()
                val engine = CutoutEngine(model)
                // one thread works on the caller (a normal-priority pool thread): lower it like the engine's own workers for the run
                val matte = atWorkerPriority(threads <= 1) { engine.run(input, nh, nw, threads, isCancelled = { !ctx.isActive }, onProgress = onProgress) }
                ctx.ensureActive()
                val coverage = coverage(matte)
                return if (coverage < MIN_COVERAGE) {
                    CutoutOutcome.NoSubject
                } else {
                    val alpha = upscaleMatte(matte, nw, nh, w, h)
                    CutoutOutcome.Done(w, h, alpha, (System.nanoTime() - t0) / 1_000_000, coverage, nw, nh)
                }
            } catch (e: CutoutStopped) {
                ctx.ensureActive()
                return CutoutOutcome.Failed("stopped", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                // the estimate was too low for this phone: free what the attempt left and try the next smaller size
                lastOom = e
                start++
                gcHint()
            } catch (t: Throwable) {
                return CutoutOutcome.Failed("error", t)
            }
        }
        return CutoutOutcome.Failed("memory", lastOom)
    }

    /**
     * Index of the biggest size in [needs] (bytes, biggest first) that fits the free heap, or null. "Free" counts what is really
     * free: `maxMemory - (totalMemory - freeMemory)` also counts garbage that was not collected yet (right after the photo decode
     * there are tens of MB of it), so when the biggest size does not fit, one collection is asked for and the heap measured again
     * before a smaller (worse) size is taken (review 2026-11-12, finding 21).
     */
    private fun firstFitting(needs: List<Long>): Int? {
        var heap = availableHeap()
        if (needs.isEmpty()) return null
        if (needs[0] + SAFETY_BYTES > heap) {
            gcHint()
            heap = availableHeap()
        }
        val i = needs.indexOfFirst { it + SAFETY_BYTES <= heap }
        return if (i >= 0) i else null
    }

    /**
     * Runs [block] with the calling thread one step above THREAD_PRIORITY_BACKGROUND (the priority of the engine's workers, see
     * CutoutPool) and puts the old priority back. Review 2026-11-12 item 32: with one thread the network ran on the caller at normal
     * priority and competed with the screen and the music. No-op in the JVM tests (no android.os.Process there).
     */
    private inline fun <T> atWorkerPriority(enabled: Boolean, block: () -> T): T {
        val old = if (enabled) runCatching { android.os.Process.getThreadPriority(android.os.Process.myTid()) }.getOrNull() else null
        if (old != null) runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND - 1) }
        try {
            return block()
        } finally {
            if (old != null) runCatching { android.os.Process.setThreadPriority(old) }
        }
    }

    /** Head room left for the rest of the app while the network runs. */
    private const val SAFETY_BYTES = 24L * 1024 * 1024

    /** The memory still free for the run; a seam for the tests. */
    internal var heapProbe: () -> Long = { defaultHeapProbe() }

    internal fun defaultHeapProbe(): Long {
        val rt = Runtime.getRuntime()
        return rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
    }

    /**
     * Asks for a garbage collection; a seam for the tests. Runtime.gc(), not System.gc(): on Android System.gc() only collects
     * when a finalization ran just before it, so it is often a no-op.
     */
    internal var gcHint: () -> Unit = { Runtime.getRuntime().gc() }

    private fun availableHeap(): Long = heapProbe()

    // ------------------------------------------------------------------------------------------------ before and after the network

    /**
     * The network's input size for a [w] x [h] photo: the shorter side to [shortSide] unless that would make the longer side bigger than
     * [MAX_SIDE], both sides multiples of 32. One scale for both sides, so a panorama is not squashed (it used to be: each side was clamped
     * on its own, review 2026-11-12 item 32).
     */
    fun networkSize(w: Int, h: Int, shortSide: Int): Pair<Int, Int> {
        val ratio = min(shortSide.toFloat() / min(w, h), MAX_SIDE.toFloat() / max(w, h))
        val nw = max(32, (w * ratio / 32f).roundToInt() * 32).coerceAtMost(MAX_SIDE)
        val nh = max(32, (h * ratio / 32f).roundToInt() * 32).coerceAtMost(MAX_SIDE)
        return nw to nh
    }

    /**
     * The photo scaled to [nw] x [nh] (an average over each output pixel's area, at most 4 x 4 reads, so thin details do not alias) as the
     * network wants it: three planes R, G, B, each value (x / 255 - 0.5) / 0.5.
     */
    fun toNetworkInput(argb: IntArray, w: Int, h: Int, nw: Int, nh: Int): FloatArray {
        val plane = nw * nh
        val out = FloatArray(3 * plane)
        val sx = w.toFloat() / nw
        val sy = h.toFloat() / nh
        for (y in 0 until nh) {
            val y0 = (y * sy).toInt().coerceIn(0, h - 1)
            val y1 = ((y + 1) * sy).toInt().coerceIn(y0 + 1, h)
            val stepY = max(1, (y1 - y0) / 4)
            for (x in 0 until nw) {
                val x0 = (x * sx).toInt().coerceIn(0, w - 1)
                val x1 = ((x + 1) * sx).toInt().coerceIn(x0 + 1, w)
                val stepX = max(1, (x1 - x0) / 4)
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                var yy = y0
                while (yy < y1) {
                    var xx = x0
                    val row = yy * w
                    while (xx < x1) {
                        val p = argb[row + xx]
                        r += (p shr 16) and 255
                        g += (p shr 8) and 255
                        b += p and 255
                        n++
                        xx += stepX
                    }
                    yy += stepY
                }
                val i = y * nw + x
                out[i] = ((r / n) / 255f - 0.5f) / 0.5f
                out[plane + i] = ((g / n) / 255f - 0.5f) / 0.5f
                out[2 * plane + i] = ((b / n) / 255f - 0.5f) / 0.5f
            }
        }
        return out
    }

    /** Share of the matte above one half. */
    fun coverage(matte: FloatArray): Float {
        var n = 0
        for (v in matte) if (v > 0.5f) n++
        return n.toFloat() / max(1, matte.size)
    }

    /** The network's matte ([mw] x [mh], 0..1) scaled to the photo with bilinear sampling at pixel centres, as bytes 0..255. */
    fun upscaleMatte(matte: FloatArray, mw: Int, mh: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        val ax = CutoutKernels.axis(w, mw, w.toFloat() / mw)
        val ay = CutoutKernels.axis(h, mh, h.toFloat() / mh)
        for (y in 0 until h) {
            val ra = ay.i0[y] * mw
            val rb = ay.i1[y] * mw
            val fy = ay.f[y]
            val o = y * w
            for (x in 0 until w) {
                val x0 = ax.i0[x]
                val x1 = ax.i1[x]
                val fx = ax.f[x]
                val top = matte[ra + x0] + (matte[ra + x1] - matte[ra + x0]) * fx
                val bot = matte[rb + x0] + (matte[rb + x1] - matte[rb + x0]) * fx
                val v = (top + (bot - top) * fy).coerceIn(0f, 1f)
                out[o + x] = (v * 255f + 0.5f).toInt().toByte()
            }
        }
        return out
    }
}
