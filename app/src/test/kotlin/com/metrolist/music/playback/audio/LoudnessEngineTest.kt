/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class LoudnessEngineTest {
    private val rate = 48_000

    private fun sine(frames: Int, channels: Int, hz: Double, amp: Float, fromFrame: Int = 0): FloatArray =
        FloatArray(frames * channels) { i ->
            val frame = fromFrame + i / channels
            (amp * sin(2.0 * PI * hz * frame / rate)).toFloat()
        }

    /** Runs [input] through [engine] in blocks of [block] frames, state carried between calls like in the player. */
    private fun run(
        engine: LoudnessEngine,
        input: FloatArray,
        target: Float,
        limit: Boolean,
        block: Int = 480,
    ): FloatArray {
        val channels = engine.channels
        val out = FloatArray(input.size)
        val frames = input.size / channels
        var done = 0
        while (done < frames) {
            val n = minOf(block, frames - done)
            val inBlock = input.copyOfRange(done * channels, (done + n) * channels)
            val outBlock = FloatArray(n * channels)
            engine.process(inBlock, outBlock, n, target, limit)
            outBlock.copyInto(out, done * channels)
            done += n
        }
        return out
    }

    private fun peak(a: FloatArray, from: Int = 0): Float = a.drop(from).maxOfOrNull { abs(it) } ?: 0f

    private fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Double {
        var sum = 0.0
        for (i in from until to) sum += a[i].toDouble() * a[i]
        return kotlin.math.sqrt(sum / (to - from))
    }

    // ---- sizes -------------------------------------------------------------------------------------------------------

    @Test
    fun `look-ahead is 5 ms at every common sample rate`() {
        assertEquals(240, LoudnessEngine(48_000, 2).lookaheadFrames)
        assertEquals(221, LoudnessEngine(44_100, 2).lookaheadFrames)
        assertEquals(480, LoudnessEngine(96_000, 2).lookaheadFrames)
        assertEquals(40, LoudnessEngine(8_000, 1).lookaheadFrames)
        assertEquals(1, LoudnessEngine(100, 1).lookaheadFrames.coerceAtLeast(1))
    }

    // ---- the guarantee ---------------------------------------------------------------------------------------------

    @Test
    fun `a boosted full-scale sine never passes the ceiling`() {
        val engine = LoudnessEngine(rate, 2)
        val out = run(engine, sine(rate * 2, 2, 440.0, 0.99f), LoudnessGain.linearGain(6.0), limit = true)
        assertTrue("peak ${peak(out)}", peak(out) <= LoudnessEngine.CEILING + 1e-5f)
        assertTrue(peak(out) > 0.9f) // it is limited, not squashed
    }

    @Test
    fun `a sudden loud burst after silence is caught from its very first sample`() {
        val engine = LoudnessEngine(rate, 2)
        val input = FloatArray(rate * 2) // 1 s of stereo silence...
        for (i in rate until input.size) input[i] = if ((i / 2) % 2 == 0) 1f else -1f // ...then a full-scale square wave
        val out = run(engine, input, LoudnessGain.linearGain(6.0), limit = true)
        assertTrue("peak ${peak(out)}", peak(out) <= LoudnessEngine.CEILING + 1e-5f)
    }

    @Test
    fun `random loud material with random bursts never passes the ceiling, at any gain`() {
        val random = Random(20261107)
        for (gainDb in listOf(0.0, 3.0, 6.0, 12.0, 20.0)) {
            val engine = LoudnessEngine(rate, 2)
            val frames = rate * 3
            val input = FloatArray(frames * 2)
            var level = 0.3f
            for (f in 0 until frames) {
                if (f % 2_000 == 0) level = if (random.nextInt(6) == 0) 1f else 0.05f + random.nextFloat() * 0.5f
                input[f * 2] = (random.nextFloat() * 2f - 1f) * level
                input[f * 2 + 1] = (random.nextFloat() * 2f - 1f) * level
            }
            val out = run(engine, input, LoudnessGain.linearGain(gainDb), limit = true, block = 1_024)
            assertTrue("gain $gainDb peak ${peak(out)}", peak(out) <= LoudnessEngine.CEILING + 1e-5f)
            assertFalse(out.any { it.isNaN() })
        }
    }

    @Test
    fun `one loud channel limits both channels by the same amount, the stereo image stays`() {
        val engine = LoudnessEngine(rate, 2)
        val frames = rate
        val input = FloatArray(frames * 2)
        for (f in 0 until frames) {
            val s = sin(2.0 * PI * 300.0 * f / rate).toFloat()
            input[f * 2] = s * 0.95f
            input[f * 2 + 1] = s * 0.19f
        }
        val out = run(engine, input, LoudnessGain.linearGain(6.0), limit = true)
        for (f in rate / 2 until frames step 97) {
            val l = out[f * 2]
            val r = out[f * 2 + 1]
            if (abs(l) > 0.1f) assertEquals("frame $f", 5f, l / r, 0.01f)
        }
        assertTrue(peak(out) <= LoudnessEngine.CEILING + 1e-5f)
    }

    // ---- transparency ------------------------------------------------------------------------------------------------

    @Test
    fun `under the knee the limiter does nothing and the gain is exact`() {
        val engine = LoudnessEngine(rate, 2)
        val input = sine(rate, 2, 1000.0, 0.2f)
        val out = run(engine, input, 2f, limit = true)
        val d = engine.lookaheadFrames * 2
        for (i in 0 until input.size - d) assertEquals("sample $i", input[i] * 2f, out[i + d], 1e-6f)
    }

    @Test
    fun `switched off the samples are the input, only 5 ms late`() {
        val engine = LoudnessEngine(rate, 2)
        val random = Random(7)
        val input = FloatArray(rate * 2) { random.nextFloat() * 2f - 1f }
        val out = run(engine, input, 1f, limit = false)
        val d = engine.lookaheadFrames * 2
        for (i in d until out.size) assertEquals(input[i - d], out[i], 0f) // bit for bit
        for (i in 0 until d) assertEquals(0f, out[i], 0f)
    }

    @Test
    fun `silence stays silent, exactly`() {
        val engine = LoudnessEngine(rate, 2)
        val out = run(engine, FloatArray(rate * 2), LoudnessGain.linearGain(6.0), limit = true)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun `silence after loud music becomes exactly silent again`() {
        val engine = LoudnessEngine(rate, 2)
        val loud = sine(rate, 2, 220.0, 0.99f)
        val input = loud + FloatArray(rate * 2)
        val out = run(engine, input, LoudnessGain.linearGain(6.0), limit = true)
        // the 5 ms delay, then the music is over: from there on every sample is exactly 0
        val from = rate * 2 + engine.lookaheadFrames * 2
        for (i in from until out.size) assertEquals("sample $i", 0f, out[i], 0f)
    }

    @Test
    fun `NaN and infinity from a broken decoder become silence and do not poison the limiter`() {
        val engine = LoudnessEngine(rate, 2)
        val input = sine(rate, 2, 440.0, 0.5f)
        input[1_000] = Float.NaN
        input[1_001] = Float.POSITIVE_INFINITY
        input[1_002] = Float.NEGATIVE_INFINITY
        val out = run(engine, input, LoudnessGain.linearGain(6.0), limit = true)
        assertFalse(out.any { it.isNaN() || it.isInfinite() })
        assertTrue(peak(out) <= LoudnessEngine.CEILING + 1e-5f)
        // and the music after the bad samples is back to normal level
        assertTrue(rms(out, rate, rate * 2) > 0.5)
    }

    // ---- smoothness --------------------------------------------------------------------------------------------------

    @Test
    fun `a gain step between two tracks is a ramp, there is no click`() {
        val engine = LoudnessEngine(rate, 2)
        val frames = rate * 2
        val input = sine(frames, 2, 220.0, 0.25f)
        val half = frames / 2
        val first = run(engine, input.copyOfRange(0, half * 2), LoudnessGain.linearGain(-8.0), limit = true)
        val second = run(engine, input.copyOfRange(half * 2, frames * 2), LoudnessGain.linearGain(6.0), limit = true)
        val out = first + second
        // the steepest a 220 Hz sine of 0.25 can be at the highest gain (2.0) is amp * 2*pi*f/fs * gain; the gain ramp adds little
        val bound = 0.25f * 2f * PI.toFloat() * 220f / rate * 2.0f * 1.15f
        var worst = 0f
        for (f in 1 until frames) worst = maxOf(worst, abs(out[f * 2] - out[(f - 1) * 2]))
        assertTrue("worst step $worst > $bound", worst <= bound)
    }

    @Test
    fun `the gain settles on its target within a few glide times`() {
        val engine = LoudnessEngine(rate, 2)
        run(engine, sine(100, 2, 100.0, 0.1f), 1f, limit = true) // the first block jumps to its target: 1.0
        run(engine, sine(480, 2, 100.0, 0.1f), 2f, limit = true) // 10 ms later it is on its way, not there
        assertTrue("after 10 ms ${engine.currentGain}", engine.currentGain in 1.1f..1.6f)
        run(engine, sine(rate / 2, 2, 100.0, 0.1f), 2f, limit = true)
        assertEquals(2f, engine.currentGain, 0.001f)
    }

    @Test
    fun `after a reset the next block starts at its target gain`() {
        val engine = LoudnessEngine(rate, 2)
        run(engine, sine(2_000, 2, 100.0, 0.1f), 0.1f, limit = true)
        engine.reset()
        val input = sine(2_000, 2, 100.0, 0.1f)
        val out = run(engine, input, 2f, limit = true)
        val d = engine.lookaheadFrames * 2
        // sample d+k of the output is input k at full gain 2.0 from the first sample: no glide from the old gain
        for (k in 0 until 200) assertEquals(input[k] * 2f, out[d + k], 1e-5f)
    }

    @Test
    fun `release is slow, a drum hit does not make the gain pump`() {
        val engine = LoudnessEngine(rate, 1)
        val quiet = sine(rate * 2, 1, 200.0, 0.3f)
        val input = quiet.copyOf()
        for (i in 0 until 300) input[rate / 2 + i] = if (i % 2 == 0) 1.0f else -1.0f // one hot burst at 0.5 s
        val out = run(engine, input, LoudnessGain.linearGain(6.0), limit = true)
        // envelope of the sine after the burst: recovers monotonically, never jumps
        val windows = 200
        val env = DoubleArray(windows) { w ->
            val from = rate / 2 + 2_000 + w * 240
            var m = 0f
            for (i in from until from + 240) m = maxOf(m, abs(out[i]))
            m.toDouble()
        }
        for (w in 1 until windows) assertTrue("window $w: ${env[w]} < ${env[w - 1]}", env[w] >= env[w - 1] - 0.005)
        assertTrue(env[windows - 1] > env[0])
    }

    @Test
    fun `a sine held in the limiter has a steady level, the gain ripple is below 1 percent`() {
        val engine = LoudnessEngine(rate, 2)
        val out = run(engine, sine(rate * 2, 2, 500.0, 0.99f), LoudnessGain.linearGain(6.0), limit = true)
        // after settling, the peak of every 2 ms window of the left channel is within 1 percent of the ceiling
        for (w in 0 until 400) {
            val from = rate + 4_800 + w * 96
            var m = 0f
            for (f in from until from + 96) m = maxOf(m, abs(out[f * 2]))
            assertEquals("window $w", LoudnessEngine.CEILING, m, LoudnessEngine.CEILING * 0.01f)
        }
    }

    // ---- stream edges --------------------------------------------------------------------------------------------------

    @Test
    fun `drain returns the tail, nothing is lost at the end of a stream`() {
        val engine = LoudnessEngine(rate, 2)
        val random = Random(99)
        val input = FloatArray(10_000) { random.nextFloat() - 0.5f }
        val body = run(engine, input, 1f, limit = false)
        val tail = FloatArray(engine.lookaheadFrames * 2)
        val frames = engine.drain(tail, 1f, false)
        assertEquals(engine.lookaheadFrames, frames)
        val all = body + tail
        val d = engine.lookaheadFrames * 2
        for (i in input.indices) assertEquals(input[i], all[i + d], 0f)
    }

    @Test
    fun `mono and surround work`() {
        for (channels in listOf(1, 2, 6)) {
            val engine = LoudnessEngine(rate, channels)
            val out = run(engine, sine(rate, channels, 330.0, 0.99f), LoudnessGain.linearGain(6.0), limit = true)
            assertTrue("channels $channels", peak(out) <= LoudnessEngine.CEILING + 1e-5f)
        }
    }

    @Test
    fun `block size does not change the result`() {
        val random = Random(5)
        val input = FloatArray(rate * 2) { (random.nextFloat() * 2f - 1f) * 0.8f }
        val a = run(LoudnessEngine(rate, 2), input, LoudnessGain.linearGain(5.0), true, block = 64)
        val b = run(LoudnessEngine(rate, 2), input, LoudnessGain.linearGain(5.0), true, block = 4_096)
        for (i in a.indices) assertEquals(a[i], b[i], 0f)
    }

    @Test
    fun `switching the limiter off while still boosted cannot let a peak through`() {
        val engine = LoudnessEngine(rate, 2)
        val boosted = run(engine, sine(rate, 2, 440.0, 0.99f), LoudnessGain.linearGain(6.0), limit = true)
        // normalisation is turned off now: the gain glides back to 1, and the limiter has to stay on until it has
        val after = run(engine, sine(rate, 2, 440.0, 0.99f, fromFrame = rate), 1f, limit = false)
        assertTrue(peak(boosted) <= LoudnessEngine.CEILING + 1e-5f)
        assertTrue("peak ${peak(after)}", peak(after) <= 1.0f)
    }

    @Test
    fun `soft ceiling is the identity under the knee and never passes the ceiling`() {
        var p = 0f
        while (p <= LoudnessEngine.KNEE_START) {
            assertEquals(p, LoudnessEngine.softCeiling(p), 0f)
            p += 0.01f
        }
        var previous = 0f
        p = 0f
        while (p <= 50f) {
            val y = LoudnessEngine.softCeiling(p)
            assertTrue("$p -> $y", y <= LoudnessEngine.CEILING + 1e-6f)
            assertTrue("monotonic at $p", y >= previous - 1e-6f)
            previous = y
            p += 0.003f
        }
        assertEquals(LoudnessEngine.CEILING, LoudnessEngine.softCeiling(1000f), 1e-5f)
    }

    @Test
    fun `a peak of exactly full scale is bent by well under a decibel`() {
        val y = LoudnessEngine.softCeiling(1.0f)
        val reductionDb = 20.0 * kotlin.math.log10(1.0 / y)
        assertTrue("reduction $reductionDb dB", reductionDb in 0.0..0.6)
    }
}
