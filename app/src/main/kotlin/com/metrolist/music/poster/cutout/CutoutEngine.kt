/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster.cutout

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

// ====================================================================================================================
// Runs the cut-out program on one picture. Before anything is computed the run is planned for the picture's size: the shape of every
// value, how much arithmetic each step is (for the progress), when each value is used for the last time (so its memory is let go right
// away), which values are written straight into the concatenation that reads them, and the most memory the run will hold at once.

internal class CutoutPlan(val model: CutoutModel, val h: Int, val w: Int) {
    val shapes = arrayOfNulls<IntArray>(model.numValues)

    /** For a value written straight into a concatenation: the concatenation's value and the channel it starts at; -1 otherwise. */
    val viewOf = IntArray(model.numValues) { -1 }
    val viewAt = IntArray(model.numValues)

    /** The index of the last step that reads a value (-1 = never read; the output is kept to the end). */
    val lastUse = IntArray(model.numValues) { -1 }

    /** Multiply-adds of each step. */
    val macs = LongArray(model.ops.size)
    val totalMacs: Long

    /** The most floats the values hold at the same time during the run. */
    val peakFloats: Long

    /** The biggest decoded weight of a convolution in floats (a Winograd layer holds the plain copy and its 16/9 bigger transform). */
    val maxWeightFloats: Long

    init {
        val ops = model.ops
        shapes[model.inputValue] = intArrayOf(3, h, w)
        // readers that are not in-place steps: a value that a batch norm or an excitation changes in place can still live inside a concatenation
        val readers = IntArray(model.numValues)
        val readerOp = IntArray(model.numValues) { -1 }
        for ((i, op) in ops.withIndex()) {
            fun read(v: Int) {
                if (op !is ModelOp.Ibn && op !is ModelOp.Se) {
                    readers[v]++
                    readerOp[v] = i
                }
                lastUse[v] = i
            }
            when (op) {
                is ModelOp.Conv -> {
                    read(op.input)
                    val s = shapes[op.input] ?: error("value ${op.input} has no shape")
                    val wshape = model.weights[op.weight].shape
                    val oh = CutoutKernels.outSize(s[1], op.k, op.stride, op.pad)
                    val ow = CutoutKernels.outSize(s[2], op.k, op.stride, op.pad)
                    shapes[op.output] = intArrayOf(wshape[0], oh, ow)
                    macs[i] = wshape[0].toLong() * oh * ow * (wshape.drop(1).fold(1) { a, b -> a * b })
                }
                is ModelOp.Ibn -> {
                    read(op.t)
                    macs[i] = shapes[op.t]!!.let { it[0].toLong() * it[1] * it[2] * 3 }
                }
                is ModelOp.Se -> {
                    read(op.t)
                    macs[i] = shapes[op.t]!!.let { it[0].toLong() * it[1] * it[2] * 2 }
                }
                is ModelOp.Resize -> {
                    read(op.input)
                    val s = shapes[op.input]!!
                    shapes[op.output] = intArrayOf(s[0], CutoutKernels.resizeOut(s[1], op.scaleH), CutoutKernels.resizeOut(s[2], op.scaleW))
                    macs[i] = shapes[op.output]!!.let { it[0].toLong() * it[1] * it[2] * 4 }
                }
                is ModelOp.Concat -> {
                    var c = 0
                    var hh = -1
                    var ww = -1
                    for (v in op.inputs) {
                        read(v)
                        val s = shapes[v]!!
                        if (hh < 0) {
                            hh = s[1]
                            ww = s[2]
                        }
                        require(s[1] == hh && s[2] == ww) { "concatenation of different sizes" }
                        c += s[0]
                    }
                    shapes[op.output] = intArrayOf(c, hh, ww)
                    macs[i] = c.toLong() * hh * ww
                }
                is ModelOp.Add -> {
                    read(op.a)
                    read(op.b)
                    shapes[op.output] = shapes[op.a]!!.clone()
                    macs[i] = shapes[op.a]!!.let { it[0].toLong() * it[1] * it[2] }
                }
            }
        }
        lastUse[model.outputValue] = ops.size
        // a value read only by one concatenation, made by a convolution, a resize or an addition, is written straight into it
        val producedBy = IntArray(model.numValues) { -1 }
        for ((i, op) in ops.withIndex()) {
            when (op) {
                is ModelOp.Conv -> producedBy[op.output] = i
                is ModelOp.Resize -> producedBy[op.output] = i
                is ModelOp.Add -> producedBy[op.output] = i
                else -> Unit
            }
        }
        for ((i, op) in ops.withIndex()) {
            if (op !is ModelOp.Concat) continue
            var at = 0
            for (v in op.inputs) {
                if (readers[v] == 1 && readerOp[v] == i && producedBy[v] >= 0 && v != model.inputValue && op.inputs.count { it == v } == 1) {
                    viewOf[v] = op.output
                    viewAt[v] = at
                }
                at += shapes[v]!![0]
            }
        }
        totalMacs = max(1L, macs.sum())
        // the memory: walk the run and add up what is alive after each step
        val alive = HashSet<Int>()
        var cur = 0L
        var peak = 0L
        fun floats(v: Int) = shapes[v]!!.let { it[0].toLong() * it[1] * it[2] }
        fun own(v: Int): Int = if (viewOf[v] >= 0) viewOf[v] else v
        fun alloc(v: Int) {
            val o = own(v)
            if (alive.add(o)) cur += floats(o)
        }
        alloc(model.inputValue)
        peak = cur
        var maxW = 0L
        for ((i, op) in ops.withIndex()) {
            when (op) {
                is ModelOp.Conv -> {
                    alloc(op.output)
                    val ws = model.weights[op.weight].size.toLong()
                    maxW = max(maxW, if (op.group == 1 && op.k == 3 && op.stride == 1) ws * 3 else ws + 4)
                }
                is ModelOp.Resize -> alloc(op.output)
                is ModelOp.Concat -> alloc(op.output)
                is ModelOp.Add -> alloc(op.output)
                else -> Unit
            }
            peak = max(peak, cur)
            // let go of what is no longer read (a concatenation's own buffer goes when the concatenation itself is done with)
            val it = alive.iterator()
            while (it.hasNext()) {
                val v = it.next()
                if (lastUse[v] <= i) {
                    cur -= floats(v)
                    it.remove()
                }
            }
        }
        peakFloats = peak
        maxWeightFloats = maxW
    }

    /** Bytes the run needs on top of what the app already uses: the values at the peak, one decoded weight, the threads' scratch. */
    fun bytesNeeded(threads: Int): Long = peakFloats * 4 + maxWeightFloats * 4 + threads.toLong() * CutoutKernels.KC * CutoutKernels.NC * 4 + model.fileBytes
}

/** A few background threads for the arithmetic. Low priority: the music keeps playing in the same process. */
internal class CutoutPool(override val threads: Int) : Parallel, AutoCloseable {
    @Volatile private var stopFlag = false
    override val stopping: Boolean get() = stopFlag

    private val workers: Array<Thread>
    private val lock = Object()
    private var job: Job? = null
    private var generation = 0L

    @Volatile private var closed = false

    private class Job(val items: Int, val body: (Int, Int) -> Unit) {
        val next = AtomicInteger(0)
        val done = CountDownLatch(1)
        val finished = AtomicInteger(0)
        @Volatile var error: Throwable? = null
    }

    /** Called (from a worker) after each finished item; the run reports progress from it. */
    @Volatile var onItem: (() -> Unit)? = null

    init {
        workers =
            Array(threads) { idx ->
                Thread({ loop(idx) }, "poster-cutout-$idx").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                    start()
                }
            }
    }

    private fun loop(idx: Int) {
        // Android: below every normal thread, but one step above THREAD_PRIORITY_BACKGROUND: from 10 on, Android also moves the thread into
        // the background CPU set, which on most phones means the slow cores only. The audio thread (-16) and the UI (-10 and 0) always win.
        // (The JVM tests have no Process class to call.)
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND - 1) }
        var seen = 0L
        while (true) {
            var j: Job? = null
            synchronized(lock) {
                while (!closed && generation == seen) lock.wait()
                if (!closed) {
                    seen = generation
                    j = job
                }
            }
            if (closed) return
            j?.let { work(it, idx) }
        }
    }

    private fun work(j: Job, idx: Int) {
        while (true) {
            val i = j.next.getAndIncrement()
            if (i >= j.items) break
            if (j.error == null && !stopFlag) {
                try {
                    j.body(i, idx)
                } catch (t: Throwable) {
                    j.error = t
                }
            }
            onItem?.invoke()
            if (j.finished.incrementAndGet() == j.items) j.done.countDown()
        }
    }

    /** [waitTick] is called about every 50 ms while the job runs (the caller checks for cancellation there). */
    var waitTick: (() -> Unit)? = null

    override fun run(items: Int, body: (item: Int, worker: Int) -> Unit) {
        if (items <= 0) return
        if (threads == 1 || items == 1) {
            // not worth waking the pool
            for (i in 0 until items) {
                if (stopFlag) break
                body(i, 0)
                onItem?.invoke()
                waitTick?.invoke()
            }
            return
        }
        val j = Job(items, body)
        synchronized(lock) {
            job = j
            generation++
            lock.notifyAll()
        }
        while (!j.done.await(50, TimeUnit.MILLISECONDS)) waitTick?.invoke()
        synchronized(lock) { job = null }
        j.error?.let { throw it }
    }

    fun stop() {
        stopFlag = true
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }
}

/** Thrown inside the engine when the run was stopped from outside. */
internal class CutoutStopped : RuntimeException("stopped")

internal class CutoutEngine(private val model: CutoutModel) {
    /** Values computed so far, by the progress (0..1). */
    fun run(
        input: FloatArray,
        h: Int,
        w: Int,
        threads: Int,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
        /** When given (size = number of steps), the nanoseconds each step took are added to it (the benchmark reads it). */
        profile: LongArray? = null,
    ): FloatArray {
        val plan = CutoutPlan(model, h, w)
        val pool = CutoutPool(threads.coerceAtLeast(1))
        try {
            return execute(plan, pool, input, isCancelled, onProgress, profile)
        } finally {
            pool.stop()
            pool.close()
        }
    }

    private fun execute(plan: CutoutPlan, pool: CutoutPool, input: FloatArray, isCancelled: () -> Boolean, onProgress: (Float) -> Unit, profile: LongArray?): FloatArray {
        val n = model.numValues
        val tensors = arrayOfNulls<Tensor>(n)
        tensors[model.inputValue] = Tensor(input, 0, 3, plan.h, plan.w)
        val scratch = Array(pool.threads) { FloatArray(CutoutKernels.KC * CutoutKernels.NC) }
        val done = AtomicLong(0)
        var lastReport = 0L
        var reported = -1f
        val total = plan.totalMacs.toDouble()

        fun report(extra: Long) {
            val f = ((done.get() + extra) / total).toFloat().coerceIn(0f, 1f)
            if (f - reported >= 0.005f || f >= 1f) {
                reported = f
                onProgress(f)
            }
        }

        fun checkCancel() {
            if (isCancelled()) {
                pool.stop()
                throw CutoutStopped()
            }
        }

        // a big convolution reports how far it is while the caller waits for it
        val itemsDone = AtomicLong(0)
        var itemsTotal = 1L
        var opMacs = 0L
        pool.onItem = { itemsDone.incrementAndGet() }
        pool.waitTick = {
            if (isCancelled()) pool.stop()
            val now = System.nanoTime()
            if (now - lastReport > 100_000_000L) {
                lastReport = now
                report(opMacs * itemsDone.get() / itemsTotal.coerceAtLeast(1))
            }
        }

        fun tensor(v: Int): Tensor = tensors[v] ?: error("value $v is not computed")

        fun allocate(v: Int): Tensor {
            tensors[v]?.let { return it }
            val s = plan.shapes[v]!!
            val owner = plan.viewOf[v]
            val t =
                if (owner >= 0) {
                    val big = tensors[owner] ?: plan.shapes[owner]!!.let { os -> Tensor(FloatArray(os[0] * os[1] * os[2]), 0, os[0], os[1], os[2]) }.also { tensors[owner] = it }
                    big.channels(plan.viewAt[v], s[0])
                } else {
                    Tensor(FloatArray(s[0] * s[1] * s[2]), 0, s[0], s[1], s[2])
                }
            tensors[v] = t
            return t
        }

        for ((i, op) in model.ops.withIndex()) {
            checkCancel()
            val stepStart = if (profile != null) System.nanoTime() else 0L
            itemsDone.set(0)
            opMacs = plan.macs[i]
            when (op) {
                is ModelOp.Conv -> {
                    val inp = tensor(op.input)
                    val out = allocate(op.output)
                    val wt = model.weights[op.weight]
                    val bias = if (op.bias >= 0) model.weights[op.bias].decode() else null
                    if (op.group == 1 && CutoutKernels.winogradFits(op.k, op.stride, op.pad, inp.c)) {
                        val u = CutoutKernels.winogradWeights(wt.decode(), out.c, inp.c)
                        itemsTotal = CutoutKernels.winogradItems(out.h, out.w).toLong()
                        CutoutKernels.winogradConv(inp, out, u, bias, op.act, pool)
                    } else if (op.group == 1) {
                        val packed = wt.decodeQuads()
                        itemsTotal = CutoutKernels.convItems(out.plane, out.c, pool.threads).toLong()
                        CutoutKernels.conv(inp, out, packed, bias, op.k, op.stride, op.pad, op.act, pool, scratch)
                    } else {
                        require(op.group == inp.c && op.group == out.c) { "only depthwise grouped convolutions" }
                        itemsTotal = inp.c.toLong()
                        CutoutKernels.depthwise(inp, out, wt.decode(), bias, op.k, op.stride, op.pad, op.act, pool)
                    }
                }
                is ModelOp.Ibn -> {
                    val t = tensor(op.t)
                    CutoutKernels.ibn(t, op.half, model.weights[op.scale].decode(), model.weights[op.shift].decode(), model.weights[op.gamma].decode(), model.weights[op.beta].decode(), op.eps, pool)
                }
                is ModelOp.Se -> {
                    val t = tensor(op.t)
                    val w1 = model.weights[op.w1]
                    CutoutKernels.se(t, w1.decode(), model.weights[op.w2].decode(), w1.shape[0], pool)
                }
                is ModelOp.Resize -> {
                    val inp = tensor(op.input)
                    val out = allocate(op.output)
                    CutoutKernels.resize(inp, out, op.scaleH, op.scaleW, pool)
                }
                is ModelOp.Concat -> {
                    val out = allocate(op.output)
                    var at = 0
                    for (v in op.inputs) {
                        val src = tensor(v)
                        // values that were written straight into it are already there
                        if (plan.viewOf[v] != op.output) CutoutKernels.copy(src, out.channels(at, src.c))
                        at += src.c
                    }
                }
                is ModelOp.Add -> {
                    val a = tensor(op.a)
                    val b = tensor(op.b)
                    val out = allocate(op.output)
                    CutoutKernels.add(a, b, out, pool)
                }
            }
            if (pool.stopping) throw CutoutStopped()
            // free what is read no more (views go with their concatenation)
            for (v in 0 until n) {
                if (tensors[v] != null && plan.lastUse[v] <= i && v != model.outputValue) {
                    tensors[v] = null
                }
            }
            if (profile != null) profile[i] += System.nanoTime() - stepStart
            done.addAndGet(plan.macs[i])
            report(0)
        }
        val out = tensor(model.outputValue)
        return if (out.off == 0 && out.data.size == out.size) out.data else out.data.copyOfRange(out.off, out.off + out.size)
    }
}
