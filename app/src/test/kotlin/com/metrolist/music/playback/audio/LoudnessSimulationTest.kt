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
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthetic music through the real rule ([LoudnessGain]) and the real sample engine ([LoudnessEngine]).
 *
 * The tracks are tonal bodies with kick drums; the loud ones are hard clipped at full scale like a loudness-war master.
 * Loudness is measured with the ITU-R BS.1770 K-weighting at 48 kHz (no gating: the signals are steady), which is the unit
 * YouTube's perceptualLoudnessDb is in, so the "measured" figure fed to the rule is a real LUFS value.
 */
class LoudnessSimulationTest {
    private val rate = 48_000
    private val default = LoudnessGain.DEFAULT_LEVEL.targetLufs.toDouble()

    // ---- tools ---------------------------------------------------------------------------------------------------------

    /** BS.1770 K-weighting (two biquads, 48 kHz coefficients) and mean square, summed over the channels (weights 1). */
    private fun lufs(samples: FloatArray, channels: Int = 2, fromFrame: Int = 0): Double {
        var sum = 0.0
        for (ch in 0 until channels) {
            var s1x1 = 0.0; var s1x2 = 0.0; var s1y1 = 0.0; var s1y2 = 0.0
            var s2x1 = 0.0; var s2x2 = 0.0; var s2y1 = 0.0; var s2y2 = 0.0
            var acc = 0.0
            var n = 0
            var f = 0
            val frames = samples.size / channels
            while (f < frames) {
                val x = samples[f * channels + ch].toDouble()
                val y1 = 1.53512485958697 * x - 2.69169618940638 * s1x1 + 1.19839281085285 * s1x2 +
                    1.69065929318241 * s1y1 - 0.73248077421585 * s1y2
                s1x2 = s1x1; s1x1 = x; s1y2 = s1y1; s1y1 = y1
                val y2 = y1 - 2.0 * s2x1 + s2x2 + 1.99004745483398 * s2y1 - 0.99007225036621 * s2y2
                s2x2 = s2x1; s2x1 = y1; s2y2 = s2y1; s2y1 = y2
                if (f >= fromFrame) {
                    acc += y2 * y2
                    n++
                }
                f++
            }
            sum += acc / n
        }
        return -0.691 + 10.0 * log10(sum)
    }

    /** A steady stereo track: tonal body with slow movement, kick drum every half second, a little hiss. */
    private fun rawTrack(seconds: Int, kick: Double, seed: Long): FloatArray {
        val random = Random(seed)
        val frames = rate * seconds
        val out = FloatArray(frames * 2)
        for (f in 0 until frames) {
            val t = f.toDouble() / rate
            val am = 1.0 + 0.3 * sin(2.0 * PI * 2.0 * t)
            val body = am * (0.5 * sin(2.0 * PI * 110.0 * t) + 0.3 * sin(2.0 * PI * 220.0 * t) +
                0.2 * sin(2.0 * PI * 330.0 * t) + 0.15 * sin(2.0 * PI * 880.0 * t))
            val tk = t % 0.5
            val attack = (tk / 0.002).coerceAtMost(1.0)
            val drum = kick * attack * exp(-tk / 0.06) * sin(2.0 * PI * 60.0 * tk)
            val hiss = (random.nextDouble() * 2.0 - 1.0) * 0.01
            out[f * 2] = (body + drum + hiss).toFloat()
            out[f * 2 + 1] = (0.9 * body + drum - hiss).toFloat()
        }
        return out
    }

    /** The track scaled to [target] LUFS; louder than full scale means clipped there (a loud master), and re-measured. */
    private fun masteredTrack(seconds: Int, kick: Double, target: Double, seed: Long): Pair<FloatArray, Double> {
        val raw = rawTrack(seconds, kick, seed)
        var scale = 10.0.pow((target - lufs(raw)) / 20.0)
        var x = FloatArray(raw.size)
        repeat(4) {
            x = FloatArray(raw.size) { (raw[it] * scale).toFloat().coerceIn(-1f, 1f) }
            scale *= 10.0.pow((target - lufs(x)) / 20.0)
        }
        x = FloatArray(raw.size) { (raw[it] * scale).toFloat().coerceIn(-1f, 1f) }
        return x to lufs(x)
    }

    private fun process(engine: LoudnessEngine, input: FloatArray, gainDb: Double, block: Int = 960): FloatArray {
        val out = FloatArray(input.size)
        val frames = input.size / 2
        val target = LoudnessGain.linearGain(gainDb)
        var done = 0
        while (done < frames) {
            val n = minOf(block, frames - done)
            val inBlock = input.copyOfRange(done * 2, (done + n) * 2)
            val outBlock = FloatArray(n * 2)
            engine.process(inBlock, outBlock, n, target, limit = true)
            outBlock.copyInto(out, done * 2)
            done += n
        }
        return out
    }

    private fun peak(a: FloatArray): Float = a.maxOf { abs(it) }

    private data class Result(val measured: Double, val gainDb: Double, val outLufs: Double, val outPeak: Float, val rawPeak: Float)

    private fun runTrack(kick: Double, target: Double, seed: Long): Result {
        val (track, measured) = masteredTrack(8, kick, target, seed)
        val gain = LoudnessGain.gainDb(measured, default)
        val out = process(LoudnessEngine(rate, 2), track, gain)
        return Result(measured, gain, lufs(out, 2, fromFrame = rate), peak(out), peak(track))
    }

    // ---- the simulation --------------------------------------------------------------------------------------------------

    @Test
    fun `the meter reads a known level`() {
        // BS.1770: a full-scale 997 Hz sine in both channels reads -0.691 + 10*log10(2 * 0.5 * 1.0 gain at 1 kHz ~ 1.0) ~ 0 LUFS
        val sine = FloatArray(rate * 4) { (sin(2.0 * PI * 997.0 * (it / 2) / rate)).toFloat() }
        assertEquals(0.0, lufs(sine, 2, rate), 0.3)
        // 10 dB lower reads 10 dB lower
        val quieter = FloatArray(sine.size) { sine[it] * 0.316228f }
        assertEquals(-10.0, lufs(quieter, 2, rate), 0.3)
    }

    @Test
    fun `tracks from loud to quiet end up close together and nothing clips`() {
        val targets = listOf(-6.0, -9.0, -12.0, -15.0, -20.0)
        val results = targets.mapIndexed { i, t -> runTrack(kick = 1.1, target = t, seed = 100L + i) }
        val report = results.joinToString("\n") {
            "measured %6.2f LUFS  gain %+5.2f dB  out %6.2f LUFS  out peak %.3f (%.2f dBFS)  in peak %.3f".format(
                it.measured, it.gainDb, it.outLufs, it.outPeak, 20.0 * log10(it.outPeak.toDouble()), it.rawPeak,
            )
        }
        println("---- loudness simulation, default level (-7 LUFS), cap +6 / -15 dB ----\n$report")

        for (r in results) {
            assertTrue("peak ${r.outPeak}", r.outPeak <= LoudnessEngine.CEILING + 1e-5f) // peak <= -0.5 dBFS <= 0 dBFS
            assertTrue(r.outLufs.isFinite())
        }
        // the four tracks the cap can reach (-6 .. -15 LUFS) end within 4 dB of each other; raw they were 9 dB apart
        val reachable = results.take(4)
        val spread = reachable.maxOf { it.outLufs } - reachable.minOf { it.outLufs }
        val rawSpread = reachable.maxOf { it.measured } - reachable.minOf { it.measured }
        assertTrue("spread $spread raw $rawSpread", spread <= 4.0)
        assertTrue(spread < rawSpread - 4.0)
        // the loudest track is not louder than the quietest reachable one by more than a few dB
        assertTrue(results[0].outLufs - results[3].outLufs <= 4.0)
        // nobody is made quieter than the cut the rule asked for, minus what the limiter takes off a boosted peak
        for (r in results) assertTrue("${r.outLufs} vs ${r.measured + r.gainDb}", r.outLufs >= r.measured + r.gainDb - 3.5)
    }

    @Test
    fun `the default rule makes loud tracks as loud as, or louder than, they were`() {
        // the point of the change: a track at -9 LUFS or quieter is never turned down, so it is at least as loud as the
        // rival app plays it on the phone speaker (untouched), and louder where the rival boosts by only 3 dB at most
        for (t in listOf(-9.0, -12.0, -15.0)) {
            val r = runTrack(kick = 1.1, target = t, seed = 7)
            assertTrue("gain ${r.gainDb}", r.gainDb >= 0.0)
            assertTrue("out ${r.outLufs} measured ${r.measured}", r.outLufs >= r.measured - 0.3)
        }
    }

    @Test
    fun `heavy limiting on a hot boosted track stays moderate, no pumping`() {
        val (track, measured) = masteredTrack(10, kick = 1.1, target = -14.0, seed = 5L)
        val gainDb = LoudnessGain.gainDb(measured, default) // +6 dB: the largest boost the rule allows
        assertEquals(6.0, gainDb, 1e-9)
        val engine = LoudnessEngine(rate, 2)
        val out = process(engine, track, gainDb)
        val d = engine.lookaheadFrames
        val g = LoudnessGain.linearGain(gainDb)
        // gain reduction per frame where the signal is big enough to measure it
        val grDb = ArrayList<Double>()
        val envelope = DoubleArray((track.size / 2 - d) / 480)
        envelope.fill(0.0)
        for (f in rate until track.size / 2 - d) {
            val pre = track[f * 2] * g
            if (abs(pre) > 0.3f) {
                val applied = out[(f + d) * 2] / pre
                val gr = -20.0 * log10(applied.toDouble().coerceIn(1e-3, 1.0))
                grDb += gr
                val w = f / 480
                if (w < envelope.size) envelope[w] = maxOf(envelope[w], gr)
            }
        }
        val meanGr = grDb.average()
        val maxGr = grDb.max()
        // how fast the (10 ms) gain reduction envelope moves between neighbouring windows: pumping would show as swings
        var biggestSwing = 0.0
        for (w in (rate / 480 + 1) until envelope.size) {
            if (envelope[w] > 0 && envelope[w - 1] > 0) biggestSwing = maxOf(biggestSwing, abs(envelope[w] - envelope[w - 1]))
        }
        println("---- hot track +6 dB: mean GR %.2f dB, max GR %.2f dB, biggest 10 ms swing %.2f dB, out peak %.3f".format(meanGr, maxGr, biggestSwing, peak(out)))
        assertTrue(peak(out) <= LoudnessEngine.CEILING + 1e-5f)
        assertTrue("max GR $maxGr", maxGr <= 8.0)
        assertTrue("mean GR $meanGr", meanGr <= 4.0)
        assertTrue("swing $biggestSwing", biggestSwing <= 5.0)
        assertFalse(out.any { it.isNaN() })
    }

    @Test
    fun `a queue of three songs in a row ramps the gain at each change and nothing clicks`() {
        // quiet tonal songs (no clipping, no hiss) joined through ONE engine like the gapless chain; each fades in and out
        // over 30 ms so the source itself has no jump at a join, whatever the processing does
        fun song(seconds: Int, amp: Double, hz: Double): FloatArray {
            val frames = rate * seconds
            val fade = rate * 30 / 1000
            return FloatArray(frames * 2) { i ->
                val f = i / 2
                val edge = minOf(f, frames - 1 - f)
                val env = if (edge < fade) edge.toDouble() / fade else 1.0
                (amp * env * sin(2.0 * PI * hz * f / rate)).toFloat()
            }
        }
        val songs = listOf(song(2, 0.8, 330.0) to -4.0, song(2, 0.15, 220.0) to 6.0, song(2, 0.5, 440.0) to 1.0)
        val engine = LoudnessEngine(rate, 2)
        val pieces = songs.map { (s, gainDb) -> process(engine, s, gainDb) }
        val out = pieces.reduce { a, b -> a + b }
        assertFalse(out.any { it.isNaN() })
        assertTrue(peak(out) <= LoudnessEngine.CEILING + 1e-5f)
        // No limiter acts here (every peak is under the knee), so out[f + delay] = in[f] * gain[f]: the gain curve that was
        // really applied can be read back. It may only move by what a 40 ms glide allows per sample (about 7e-4 at the
        // biggest step of 4 to 2.0), never jump: a jump is a click.
        val input = songs.map { it.first }.reduce { a, b -> a + b }
        val d = engine.lookaheadFrames
        var previous = Double.NaN
        var worstStep = 0.0
        var seen = 0
        var smallest = Double.MAX_VALUE
        var largest = 0.0
        for (f in 0 until input.size / 2 - d) {
            val x = input[f * 2]
            if (abs(x) < 0.1f) {
                previous = Double.NaN
                continue
            }
            val g = out[(f + d) * 2] / x.toDouble()
            if (!previous.isNaN()) worstStep = maxOf(worstStep, abs(g - previous))
            previous = g
            smallest = minOf(smallest, g)
            largest = maxOf(largest, g)
            seen++
        }
        assertTrue("frames checked $seen", seen > rate)
        assertTrue("worst gain step $worstStep", worstStep <= 1.2e-3)
        // and it really moved between the three levels (-4 dB, +6 dB, +1 dB), it was not simply stuck
        assertEquals(LoudnessGain.linearGain(-4.0).toDouble(), smallest, 0.02)
        assertEquals(LoudnessGain.linearGain(6.0).toDouble(), largest, 0.02)
    }

    @Test
    fun `silence between songs stays silent and a song after it starts clean`() {
        val engine = LoudnessEngine(rate, 2)
        val silence = FloatArray(rate * 2)
        val first = process(engine, silence, 6.0)
        assertTrue(first.all { it == 0f })
        val loud = process(engine, rawTrack(1, 1.1, 3L), 6.0)
        assertTrue(peak(loud) <= LoudnessEngine.CEILING + 1e-5f)
        val after = process(engine, silence, -3.0)
        // 5 ms of delay, then the exact zero again
        for (i in engine.lookaheadFrames * 2 until after.size) assertEquals(0f, after[i], 0f)
    }

    @Test
    fun `white noise is gained exactly when it fits under the knee, no distortion added`() {
        val random = Random(42)
        val noise = FloatArray(rate * 4) { (random.nextGaussian() * 0.05).toFloat().coerceIn(-0.12f, 0.12f) }
        val engine = LoudnessEngine(rate, 2)
        val out = process(engine, noise, 6.0)
        val g = LoudnessGain.linearGain(6.0)
        val d = engine.lookaheadFrames * 2
        for (i in noise.indices) if (i + d < out.size) assertEquals(noise[i] * g, out[i + d], 1e-6f)
        val inRms = sqrt(noise.sumOf { it.toDouble() * it } / noise.size)
        val outRms = sqrt(out.drop(d).sumOf { it.toDouble() * it } / (out.size - d))
        assertEquals(6.0, 20.0 * log10(outRms / inRms), 0.05)
    }
}
