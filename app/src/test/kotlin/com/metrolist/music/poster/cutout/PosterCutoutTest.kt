package com.metrolist.music.poster.cutout

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * The cut-out end to end with the real model file. The fixture (test resources, poster/modnet_fixture.bin) is a 96 x 128 copy of a CC0 photo
 * ("Hooded woman in snow (Unsplash)", Wikimedia Commons, CC0) with the network outputs ONNX Runtime 1.31 computed for it: once with the
 * app's compressed weights (what this engine must reproduce) and once with the original float weights (how far the compression moves it).
 */
class PosterCutoutTest {
    private val modelFile = File("src/main/assets/poster/modnet_q.bin")
    private val modelBytes: ByteArray by lazy { modelFile.readBytes() }

    private class Fixture(val w: Int, val h: Int, val argb: IntArray, val quantized: FloatArray, val original: FloatArray)

    private val fixture: Fixture by lazy {
        val bytes = javaClass.classLoader!!.getResourceAsStream("poster/modnet_fixture.bin")!!.use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { bb.get(it) }
        assertEquals("MFX1", String(magic))
        val w = bb.int
        val h = bb.int
        val argb = IntArray(w * h) {
            val r = bb.get().toInt() and 255
            val g = bb.get().toInt() and 255
            val b = bb.get().toInt() and 255
            (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        val q = FloatArray(w * h) { bb.float }
        val o = FloatArray(w * h) { bb.float }
        Fixture(w, h, argb, q, o)
    }

    /**
     * A fixed, roomy heap for every test that is not about memory: the result must not depend on what the tests before this one left
     * on the JVM heap (review 2026-11-12: in the full suite two tests here failed with "memory" while they passed alone).
     */
    @Before
    fun fixedProbe() {
        PosterCutout.heapProbe = { 8L shl 30 }
        PosterCutout.gcHint = {}
    }

    @After
    fun resetProbe() {
        PosterCutout.heapProbe = { PosterCutout.defaultHeapProbe() }
        PosterCutout.gcHint = { Runtime.getRuntime().gc() }
    }

    @Test
    fun memoryCheck_collectsTheGarbageBeforeTakingASmallerSize() = runBlocking {
        // the heap looks full only because of garbage (the decoded photo's copies): one collection later the full size fits
        val f = fixture
        var collected = 0
        PosterCutout.heapProbe = { if (collected > 0) 8L shl 30 else 1L }
        PosterCutout.gcHint = { collected++ }
        val r = PosterCutout.cut(ByteArrayInputStream(modelBytes), f.argb, f.w, f.h, threads = 2, maxShortSide = 96)
        assertTrue("outcome $r", r is CutoutOutcome.Done)
        assertEquals(96, (r as CutoutOutcome.Done).inputW)
        assertEquals(1, collected)
        // a heap with room asks for no collection at all
        collected = 0
        PosterCutout.heapProbe = { 8L shl 30 }
        PosterCutout.gcHint = { collected += 10 }
        PosterCutout.cut(ByteArrayInputStream(modelBytes), f.argb, f.w, f.h, threads = 2, maxShortSide = 96)
        assertEquals(0, collected)
    }

    @Test
    fun memoryCheck_realProbe_onTheCrowdedTestHeap_stillRunsTheSmallFixture() = runBlocking {
        // the real probe and a real collection, whatever the tests before this one left behind
        PosterCutout.heapProbe = { PosterCutout.defaultHeapProbe() }
        PosterCutout.gcHint = { Runtime.getRuntime().gc() }
        val f = fixture
        val r = PosterCutout.cut(ByteArrayInputStream(modelBytes), f.argb, f.w, f.h, threads = 2, maxShortSide = 96)
        assertTrue("outcome $r", r is CutoutOutcome.Done)
    }

    @Test
    fun modelFile_parsesIntoTheWholeProgram() {
        val m = CutoutModel.parse(modelBytes)
        assertEquals(111, m.ops.size)
        assertEquals(204, m.weights.size)
        assertTrue("asset under 5 MB, was ${modelBytes.size}", modelBytes.size < 5_000_000)
        // every weight decodes to finite numbers
        for (w in m.weights) {
            val v = w.decode()
            assertEquals(w.size, v.size)
            assertTrue(v.all { it.isFinite() })
        }
        // three kinds of storage are in use
        assertEquals(setOf(0, 1, 2), m.weights.map { it.kind }.toSet())
        // the packed decode is the plain decode, rearranged
        for (w in m.weights.filter { it.shape.size == 4 }.take(12) + m.weights.maxBy { it.size }) {
            val per = w.size / w.shape[0]
            val ref = CutoutKernels.packWeights(w.decode(), w.shape[0], per)
            assertTrue(ref.contentEquals(w.decodeQuads()))
        }
    }

    @Test
    fun plan_givesTheOutputThePictureSize_andCountsMemory() {
        val m = CutoutModel.parse(modelBytes)
        for ((h, w) in listOf(128 to 96, 512 to 384, 96 to 160, 32 to 32)) {
            val plan = CutoutPlan(m, h, w)
            val s = plan.shapes[m.outputValue]!!
            assertEquals(listOf(1, h, w), s.toList())
            assertTrue(plan.peakFloats > 3L * h * w)
        }
        val p = CutoutPlan(m, 512, 384)
        println("cutout plan 384x512: ${p.totalMacs / 1e9} GMAC, peak values ${p.peakFloats * 4 / 1e6} MB, biggest weight ${p.maxWeightFloats * 4 / 1e6} MB, need with 4 threads ${p.bytesNeeded(4) / 1e6} MB")
        // the big resize of the last stage is written straight into its concatenation
        assertTrue(p.viewOf.any { it >= 0 })
    }

    private fun maxAbs(a: FloatArray, b: FloatArray): Float {
        var m = 0f
        for (i in a.indices) m = max(m, abs(a[i] - b[i]))
        return m
    }

    @Test
    fun engine_reproducesOnnxRuntime_onTheFixture() {
        val f = fixture
        val m = CutoutModel.parse(modelBytes)
        val input = PosterCutout.toNetworkInput(f.argb, f.w, f.h, f.w, f.h)
        for (threads in listOf(1, 3)) {
            val out = CutoutEngine(m).run(input.copyOf(), f.h, f.w, threads)
            val d = maxAbs(out, f.quantized)
            println("cutout engine vs ONNX Runtime (same weights), threads $threads: max abs diff $d")
            assertTrue("max diff $d", d < 2e-3f)
        }
    }

    @Test
    fun compressedWeights_stayCloseToTheOriginalNetwork() {
        val f = fixture
        var mae = 0.0
        var inter = 0
        var union = 0
        for (i in f.quantized.indices) {
            mae += abs(f.quantized[i] - f.original[i])
            val a = f.quantized[i] > 0.5f
            val b = f.original[i] > 0.5f
            if (a && b) inter++
            if (a || b) union++
        }
        mae /= f.quantized.size
        val iou = inter.toDouble() / union
        println("compressed vs original weights on the fixture: MAE $mae IoU $iou")
        assertTrue(mae < 0.03)
        assertTrue(iou > 0.95)
    }

    @Test
    fun cut_endToEnd_findsThePerson() = runBlocking {
        val f = fixture
        val progress = ArrayList<Float>()
        val r = PosterCutout.cut(ByteArrayInputStream(modelBytes), f.argb, f.w, f.h, threads = 2, maxShortSide = 96) { progress.add(it) }
        assertTrue("outcome $r", r is CutoutOutcome.Done)
        r as CutoutOutcome.Done
        assertEquals(96, r.inputW)
        assertEquals(128, r.inputH)
        assertEquals(f.w * f.h, r.alpha.size)
        // same size in and out: the bytes are the network's values
        var worst = 0
        for (i in r.alpha.indices) worst = max(worst, abs((r.alpha[i].toInt() and 255) - (f.quantized[i] * 255f + 0.5f).toInt()))
        assertTrue("worst byte diff $worst", worst <= 1)
        assertTrue(r.coverage in 0.3f..0.95f)
        assertTrue(progress.isNotEmpty())
        for (i in 1 until progress.size) assertTrue(progress[i] >= progress[i - 1])
        assertEquals(1f, progress.last(), 1e-6f)
    }

    @Test
    fun cut_ofAPlainPicture_findsNobody() = runBlocking {
        val w = 96
        val h = 128
        val grey = IntArray(w * h) { (255 shl 24) or 0x808080 }
        val r = PosterCutout.cut(ByteArrayInputStream(modelBytes), grey, w, h, threads = 2, maxShortSide = 96)
        assertEquals(CutoutOutcome.NoSubject, r)
    }

    @Test
    fun cut_withoutMemory_failsCleanly_andShrinksFirst() = runBlocking {
        val f = fixture
        PosterCutout.heapProbe = { 1L }
        val r = PosterCutout.cut(ByteArrayInputStream(modelBytes), f.argb, f.w, f.h, threads = 2, maxShortSide = 512)
        assertTrue(r is CutoutOutcome.Failed && r.reason == "memory")
        // enough for a 256 picture but not for 512: the run goes on at a smaller size
        val m = CutoutModel.parse(modelBytes)
        val (w256, h256) = PosterCutout.networkSize(300, 400, 256)
        val need256 = CutoutPlan(m, h256, w256).bytesNeeded(2) + w256.toLong() * h256 * 12 + 300L * 400 + 24L * 1024 * 1024
        PosterCutout.heapProbe = { need256 + 1 }
        val big = IntArray(300 * 400) { i -> if ((i % 300) in 100..200 && (i / 300) in 80..360) 0xFF2A3A4A.toInt() else 0xFFE8E2D8.toInt() }
        val r2 = PosterCutout.cut(ByteArrayInputStream(modelBytes), big, 300, 400, threads = 2, maxShortSide = 512)
        if (r2 is CutoutOutcome.Done) assertEquals(256, minOf(r2.inputW, r2.inputH)) else assertTrue("got $r2", r2 is CutoutOutcome.NoSubject)
    }

    @Test
    fun cut_isCancellable_whileTheNetworkRuns() = runBlocking {
        val w = 384
        val h = 512
        val argb = IntArray(w * h) { i -> 0xFF000000.toInt() or ((i * 2654435761L).toInt() and 0xFFFFFF) }
        val started = java.util.concurrent.atomic.AtomicLong(0)
        val job = async(Dispatchers.Default) {
            PosterCutout.cut(ByteArrayInputStream(modelBytes), argb, w, h, threads = 2, maxShortSide = 384) { p ->
                if (p > 0.05f) started.compareAndSet(0L, System.nanoTime())
            }
        }
        withTimeout(120_000) {
            while (started.get() == 0L && job.isActive) kotlinx.coroutines.delay(5)
        }
        val cancelledAt = System.nanoTime()
        job.cancel()
        try {
            job.await()
            fail("should have been cancelled")
        } catch (e: CancellationException) {
            // expected
        }
        val ms = (System.nanoTime() - cancelledAt) / 1_000_000
        println("cutout stopped $ms ms after the cancel")
        assertTrue("took $ms ms to stop", ms < 3000)
    }

    @Test
    fun networkSize_isAMultipleOf32_withTheShortSideAsAsked() {
        assertEquals(384 to 512, PosterCutout.networkSize(1536, 2048, 384))
        assertEquals(512 to 384, PosterCutout.networkSize(4000, 3000, 384))
        val (w, h) = PosterCutout.networkSize(1080, 2400, 512)
        assertEquals(0, w % 32)
        assertEquals(0, h % 32)
        // the long side is capped, and the short side shrinks with it (one scale: the picture is not squashed)
        assertEquals(1024, h)
        assertEquals(448, w)
        // a panorama keeps its shape (each side used to be clamped on its own: 1024 x 384 for a 4:1 photo)
        assertEquals(1024 to 256, PosterCutout.networkSize(4000, 1000, 384))
        assertEquals(32 to 32, PosterCutout.networkSize(10, 12, 16))
    }

    @Test
    fun toNetworkInput_averagesAndNormalises() {
        // 4 x 2 photo: left half black, right half white, scaled to 2 x 1
        val argb = intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), -1, -1, 0xFF000000.toInt(), 0xFF000000.toInt(), -1, -1)
        val x = PosterCutout.toNetworkInput(argb, 4, 2, 2, 1)
        assertEquals(6, x.size)
        assertEquals(-1f, x[0], 1e-6f)
        assertEquals(1f, x[1], 1e-6f)
        assertEquals(-1f, x[2], 1e-6f)
        assertEquals(1f, x[5], 1e-6f)
        // a mid grey: 128 -> (128 / 255 - 0.5) / 0.5
        val g = PosterCutout.toNetworkInput(IntArray(4) { 0xFF808080.toInt() }, 2, 2, 2, 2)
        assertEquals((128f / 255f - 0.5f) / 0.5f, g[3], 1e-6f)
    }

    @Test
    fun upscaleMatte_keepsTheEndsAndInterpolates() {
        val m = floatArrayOf(0f, 1f)
        val up = PosterCutout.upscaleMatte(m, 2, 1, 8, 2)
        val row = up.take(8).map { it.toInt() and 255 }
        assertEquals(0, row.first())
        assertEquals(255, row.last())
        for (i in 1 until 8) assertTrue(row[i] >= row[i - 1])
        assertEquals(row, up.drop(8).map { it.toInt() and 255 })
        assertEquals(0.5f, PosterCutout.coverage(floatArrayOf(0.2f, 0.9f, 0.4f, 0.6f)), 0f)
    }

    @Test
    fun halfFloats_andPalettes_decodeExactly() {
        assertEquals(1f, ModelWeight.halfToFloat(0x3C00))
        assertEquals(-2f, ModelWeight.halfToFloat(0xC000))
        assertEquals(65504f, ModelWeight.halfToFloat(0x7BFF))
        assertEquals(5.9604645E-8f, ModelWeight.halfToFloat(0x0001))
        assertEquals(0f, ModelWeight.halfToFloat(0x0000))
        assertEquals(0.33325195f, ModelWeight.halfToFloat(0x3555))
        // a 3-bit palette for a [2][5] weight: tables (2 x 8 halves), then 10 indices packed LSB first
        val lut = IntArray(16) { 0x3C00 + it * 0x40 } // 1.0, 1.0625, ...
        val idx = intArrayOf(0, 7, 3, 5, 1, 6, 2, 4, 7, 0)
        val bytes = ArrayList<Byte>()
        for (v in lut) {
            bytes.add((v and 255).toByte())
            bytes.add((v shr 8).toByte())
        }
        var acc = 0L
        for ((i, v) in idx.withIndex()) acc = acc or (v.toLong() shl (3 * i))
        for (i in 0 until 4) bytes.add(((acc shr (8 * i)) and 255).toByte())
        val w = ModelWeight(bytes.toByteArray(), 0, ModelWeight.KIND_PALETTE, intArrayOf(2, 5), 3)
        val v = w.decode()
        for (i in 0 until 10) {
            val table = if (i < 5) 0 else 8
            assertEquals(ModelWeight.halfToFloat(lut[table + idx[i]]), v[i])
        }
        assertNotNull(v)
    }
}
