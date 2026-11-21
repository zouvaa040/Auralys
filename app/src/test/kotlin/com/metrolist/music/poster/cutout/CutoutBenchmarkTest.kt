package com.metrolist.music.poster.cutout

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Timing of the whole cut-out on this machine's JVM (not a phone). Runs only when the environment variable POSTER_BENCH is set, so the normal
 * suite stays quick. Prints the time per thread count and the most heap the run held.
 */
class CutoutBenchmarkTest {
    @Test
    fun benchmark() = runBlocking {
        assumeTrue(System.getenv("POSTER_BENCH") != null)
        val bytes = File("src/main/assets/poster/modnet_q.bin").readBytes()
        val w = 768
        val h = 1024
        // a made-up photo: soft gradients and a dark figure (the content does not change the arithmetic)
        val argb = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val inFigure = (x - 384) * (x - 384) / 160f / 160f + (y - 600) * (y - 600) / 380f / 380f < 1f || (x - 384) * (x - 384) + (y - 250) * (y - 250) < 110 * 110
            if (inFigure) 0xFF000000.toInt() or (60 + (y / 16 % 40) shl 16) or (45 shl 8) or 50
            else 0xFF000000.toInt() or ((180 + x / 12 % 60) shl 16) or ((200 - y / 16 % 50) shl 8) or (210 + (x + y) / 30 % 40)
        }
        for (short in listOf(384, 512)) {
            for (threads in listOf(1, 2, 4)) {
                System.gc()
                Thread.sleep(200)
                val rt = Runtime.getRuntime()
                val base = rt.totalMemory() - rt.freeMemory()
                val peakRef = java.util.concurrent.atomic.AtomicLong(base)
                val running = java.util.concurrent.atomic.AtomicBoolean(true)
                val sampler = Thread {
                    while (running.get()) {
                        val used = rt.totalMemory() - rt.freeMemory()
                        if (used > peakRef.get()) peakRef.set(used)
                        Thread.sleep(3)
                    }
                }.apply { isDaemon = true; start() }
                val t0 = System.nanoTime()
                val r = PosterCutout.cut(ByteArrayInputStream(bytes), argb, w, h, threads = threads, maxShortSide = short)
                val ms = (System.nanoTime() - t0) / 1_000_000
                running.set(false)
                sampler.join()
                val peak = peakRef.get()
                val desc = when (r) {
                    is CutoutOutcome.Done -> "input ${r.inputW}x${r.inputH} coverage ${r.coverage}"
                    else -> r.toString()
                }
                val plan = CutoutPlan(CutoutModel.parse(bytes), PosterCutout.networkSize(w, h, short).second, PosterCutout.networkSize(w, h, short).first)
                println("BENCH short $short threads $threads: $ms ms, $desc, heap peak +${(peak - base) / 1_000_000} MB over ${base / 1_000_000} MB, plan ${plan.totalMacs / 1e9} GMAC, values peak ${plan.peakFloats * 4 / 1_000_000} MB, cores ${rt.availableProcessors()}")
            }
        }
    }

    /** Time per kind of step at 384 x 512 (cold run, warm run, one thread). Same switch as above. */
    @Test
    fun profile() {
        assumeTrue(System.getenv("POSTER_BENCH") != null)
        val m = CutoutModel.parse(File("src/main/assets/poster/modnet_q.bin").readBytes())
        val h = 512
        val w = 384
        val r = java.util.Random(3)
        val input = FloatArray(3 * h * w) { r.nextFloat() * 2f - 1f }
        for (threads in listOf(4, 4, 1)) {
            val prof = LongArray(m.ops.size)
            val t0 = System.nanoTime()
            CutoutEngine(m).run(input.copyOf(), h, w, threads, profile = prof)
            val total = (System.nanoTime() - t0) / 1e6
            val plan = CutoutPlan(m, h, w)
            val byKind = HashMap<String, Long>()
            for ((i, op) in m.ops.withIndex()) {
                val k = when (op) {
                    is ModelOp.Conv -> if (op.group > 1) "dw" else "conv${op.k}x${op.k}"
                    else -> op.javaClass.simpleName
                }
                byKind[k] = (byKind[k] ?: 0L) + prof[i]
            }
            println("PROFILE threads $threads total $total ms: " + byKind.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value / 1_000_000} ms" })
            val top = m.ops.indices.sortedByDescending { prof[it] }.take(8)
            for (i in top) println("  step $i ${m.ops[i].javaClass.simpleName} ${(m.ops[i] as? ModelOp.Conv)?.let { "k${it.k} s${it.stride} out ${plan.shapes[it.output]!!.toList()} in ${plan.shapes[it.input]!!.toList()}" } ?: ""} ${prof[i] / 1_000_000} ms, ${plan.macs[i] / 1e6} MMAC -> ${if (prof[i] > 0) plan.macs[i].toDouble() / prof[i] else 0.0} GMAC/s")
        }
    }
}
