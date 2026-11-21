/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.eq.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.metrolist.music.eq.data.GraphicEq
import com.metrolist.music.eq.data.ParametricEQ
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Moving a slider while the song plays must change the sound without a click. */
class EqualizerLiveUpdateTest {
    private val sampleRate = 48_000
    private val buffer = 960 // 20 ms
    private var phase = 0

    private fun eq(gains: List<Double>) = ParametricEQ(preamp = GraphicEq.autoPreamp(gains), bands = GraphicEq.bands(gains))

    private fun processor(): CustomEqualizerAudioProcessor {
        val p = CustomEqualizerAudioProcessor()
        @Suppress("DEPRECATION")
        p.configure(AudioProcessor.AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT))
        @Suppress("DEPRECATION")
        p.flush()
        return p
    }

    /** One buffer of a steady 1 kHz tone, the same on both channels, continuing where the last buffer ended. */
    private fun tone(amplitude: Double): ByteBuffer {
        val b = ByteBuffer.allocateDirect(buffer * 4).order(ByteOrder.nativeOrder())
        repeat(buffer) {
            val v = (sin(2.0 * PI * 1_000.0 * phase / sampleRate) * amplitude * 32767.0).toInt().toShort()
            phase++
            b.putShort(v)
            b.putShort(v)
        }
        b.flip()
        return b
    }

    /** The left channel of what the processor made from one buffer. */
    @Suppress("DEPRECATION")
    private fun run(p: CustomEqualizerAudioProcessor, amplitude: Double): DoubleArray {
        p.queueInput(tone(amplitude))
        val out = p.output.order(ByteOrder.nativeOrder())
        val samples = DoubleArray(buffer)
        for (i in 0 until buffer) {
            samples[i] = out.getShort().toDouble() / 32768.0
            out.getShort()
        }
        return samples
    }

    private fun rms(x: DoubleArray) = sqrt(x.sumOf { it * it } / x.size)

    @Test
    fun `a gain moved in place changes the level and never clicks`() {
        val p = processor()
        val flat = GraphicEq.FLAT
        val a = GraphicEq.withBand(flat, 5, 6.0) // +6 dB at 1 kHz
        val b = GraphicEq.withBand(flat, 5, 3.0) // dragged down to +3 dB
        p.applyProfile(eq(a))
        repeat(25) { run(p, 0.2) } // let the filters settle

        val before = run(p, 0.2)
        p.applyProfile(eq(b))
        val around = run(p, 0.2) + run(p, 0.2)
        val after = run(p, 0.2)

        // the tone is a steady sine, so the largest step between two samples is known; a click is far above it
        val maxNaturalStep = 2.0 * PI * 1_000.0 / sampleRate * rms(before) * sqrt(2.0)
        val steps = (before.toList() + around.toList() + after.toList()).zipWithNext { x, y -> abs(y - x) }
        assertTrue("largest step ${steps.max()} against $maxNaturalStep", steps.max() <= maxNaturalStep * 1.3)

        // both settings take 6 and 3 dB off at the top (the automatic preamp), so what is left at 1 kHz is the same
        // wanted level (0 dB after the preamp): the level has to stay equal, not jump
        assertEquals(rms(before), rms(after), rms(before) * 0.08)
    }

    @Test
    fun `a bigger boost is louder after the move than before it when the preamp is left alone`() {
        val p = processor()
        val low = ParametricEQ(preamp = 0.0, bands = GraphicEq.bands(GraphicEq.withBand(GraphicEq.FLAT, 5, 2.0)))
        val high = ParametricEQ(preamp = 0.0, bands = GraphicEq.bands(GraphicEq.withBand(GraphicEq.FLAT, 5, 8.0)))
        p.applyProfile(low)
        repeat(25) { run(p, 0.05) }
        val before = rms(run(p, 0.05))
        p.applyProfile(high)
        repeat(25) { run(p, 0.05) }
        val after = rms(run(p, 0.05))
        // +2 dB to +8 dB at the centre of the band: 6 dB more, about twice the level
        assertEquals(2.0, after / before, 0.2)
    }

    @Test
    fun `a new preamp glides in over a buffer instead of jumping`() {
        val p = processor()
        p.applyProfile(ParametricEQ(preamp = 0.0, bands = GraphicEq.bands(GraphicEq.FLAT)))
        repeat(10) { run(p, 0.5) }
        p.applyProfile(ParametricEQ(preamp = -12.0, bands = GraphicEq.bands(GraphicEq.FLAT)))
        val glide = run(p, 0.5)
        val steady = run(p, 0.5)
        // the first quarter of the glide is still near the old level, the end is at the new one
        val start = rms(glide.copyOfRange(0, 100))
        val end = rms(glide.copyOfRange(buffer - 100, buffer))
        assertTrue("start $start end $end", start > end * 1.8)
        assertEquals(rms(steady), end, rms(steady) * 0.25)
    }

    @Test
    fun `a different set of bands is a full change and still plays`() {
        val p = processor()
        p.applyProfile(eq(GraphicEq.withBand(GraphicEq.FLAT, 2, 4.0)))
        repeat(5) { run(p, 0.2) }
        p.applyProfile(ParametricEQ(preamp = -3.0, bands = listOf(com.metrolist.music.eq.data.ParametricEQBand(1_000.0, 5.0))))
        val out = run(p, 0.2)
        assertTrue(rms(out) > 0.01)
        assertTrue(out.all { it.isFinite() })
    }
}
