/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The Android side of the loudness gain: ExoPlayer's byte buffers in and out of the pure [LoudnessEngine]. */
@Suppress("DEPRECATION")
class VolumeNormalizationProcessorTest {
    private val rate = 48_000
    private val lookahead = LoudnessEngine(rate, 2).lookaheadFrames

    private fun processor(encoding: Int, channels: Int = 2): VolumeNormalizationAudioProcessor =
        VolumeNormalizationAudioProcessor().also {
            it.configure(AudioProcessor.AudioFormat(rate, channels, encoding))
            it.flush()
        }

    private fun pcm16(samples: ShortArray): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach { putShort(it) }
            flip()
        }

    private fun drainOutput(p: AudioProcessor): ByteArray {
        val out = p.output
        return ByteArray(out.remaining()).also(out::get)
    }

    private fun shorts(bytes: ByteArray): ShortArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.size / 2) { b.getShort() }
    }

    /** All the audio a stream produces: every queueInput result, then the tail after queueEndOfStream. */
    private fun runStream16(p: VolumeNormalizationAudioProcessor, input: ShortArray, block: Int = 960): ShortArray {
        val out = ArrayList<Short>()
        var i = 0
        while (i < input.size) {
            val n = minOf(block, input.size - i)
            p.queueInput(pcm16(input.copyOfRange(i, i + n)))
            out += shorts(drainOutput(p)).toList()
            i += n
        }
        p.queueEndOfStream()
        out += shorts(drainOutput(p)).toList()
        assertTrue(p.isEnded)
        return out.toShortArray()
    }

    private fun stereoSine16(frames: Int, amp: Double, hz: Double = 440.0): ShortArray =
        ShortArray(frames * 2) { (amp * 32767.0 * sin(2.0 * PI * hz * (it / 2) / rate)).toInt().toShort() }

    @Test
    fun `switched off, 16-bit audio comes out unchanged, only 5 ms later, and the tail is not lost`() {
        val p = processor(C.ENCODING_PCM_16BIT)
        val input = stereoSine16(20_000, 0.99)
        val out = runStream16(p, input)
        assertEquals(input.size + lookahead * 2, out.size)
        for (i in 0 until lookahead * 2) assertEquals(0, out[i].toInt())
        for (i in input.indices) assertEquals("sample $i", input[i], out[i + lookahead * 2])
    }

    @Test
    fun `every call returns exactly as many bytes as it was given`() {
        val p = processor(C.ENCODING_PCM_16BIT)
        for (frames in listOf(1, 7, 960, 4_000, 2_049)) {
            p.queueInput(pcm16(stereoSine16(frames, 0.5)))
            assertEquals(frames * 4, drainOutput(p).size)
        }
    }

    @Test
    fun `with a boost a loud track is limited, never wrapped or clipped`() {
        val p = processor(C.ENCODING_PCM_16BIT)
        p.enabled = true
        p.setTargetGain(600)
        val input = stereoSine16(rate, 0.99, 200.0)
        val out = runStream16(p, input)
        val ceiling = (LoudnessEngine.CEILING * 32768f).toInt() + 2
        var peak = 0
        for (s in out) peak = maxOf(peak, abs(s.toInt()))
        assertTrue("peak $peak > $ceiling", peak <= ceiling)
        assertTrue("limited, not squashed: $peak", peak > 30_000)
        // a 200 Hz sine changes sign exactly twice per period: no wrap-around jumps (a wrap would flip it many times)
        var flips = 0
        for (f in lookahead until rate - 1) if ((out[f * 2] >= 0) != (out[(f + 1) * 2] >= 0)) flips++
        assertTrue("sign flips $flips", flips in 380..420)
    }

    @Test
    fun `enabled with a gain of 0 dB leaves a quiet track alone`() {
        val p = processor(C.ENCODING_PCM_16BIT)
        p.enabled = true
        p.setTargetGain(0)
        val input = stereoSine16(10_000, 0.3)
        val out = runStream16(p, input)
        for (i in input.indices) assertEquals("sample $i", input[i], out[i + lookahead * 2])
    }

    @Test
    fun `the gain is the one that was set`() {
        val p = processor(C.ENCODING_PCM_16BIT)
        p.enabled = true
        p.setTargetGain(-600) // -6 dB
        val input = stereoSine16(10_000, 0.5)
        val out = runStream16(p, input)
        for (i in 2_000 until 4_000) {
            val expected = input[i] * LoudnessGain.linearGainFromMb(-600)
            assertEquals("sample $i", expected, out[i + lookahead * 2].toFloat(), 1f)
        }
    }

    @Test
    fun `turning it off puts the track back to its own level`() {
        val p = processor(C.ENCODING_PCM_16BIT)
        p.enabled = true
        p.setTargetGain(-800)
        p.queueInput(pcm16(stereoSine16(4_800, 0.5)))
        drainOutput(p)
        p.enabled = false
        // after the glide (about 0.7 s) the samples are the input again, bit for bit
        val tail = stereoSine16(72_000, 0.5)
        p.queueInput(pcm16(tail))
        val out = shorts(drainOutput(p))
        for (i in 100_000 until 130_000) assertEquals("sample $i", tail[i - lookahead * 2], out[i])
    }

    @Test
    fun `a flush starts the next stream from silence again`() {
        val p = processor(C.ENCODING_PCM_16BIT)
        p.queueInput(pcm16(stereoSine16(2_000, 0.9)))
        drainOutput(p)
        p.flush()
        p.queueInput(pcm16(stereoSine16(2_000, 0.9)))
        val out = shorts(drainOutput(p))
        for (i in 0 until lookahead * 2) assertEquals(0, out[i].toInt())
        assertTrue(out[lookahead * 2 + 2_000] != 0.toShort())
    }

    @Test
    fun `end of stream right after a flush adds nothing`() {
        val p = processor(C.ENCODING_PCM_16BIT)
        p.queueEndOfStream()
        assertEquals(0, drainOutput(p).size)
        assertTrue(p.isEnded)
    }

    @Test
    fun `24-bit, 32-bit and float audio get the gain too`() {
        // 0.2 of full scale, +6 dB => 0.399 (no limiting under the knee)
        for (encoding in listOf(C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)) {
            val p = processor(encoding, channels = 1)
            p.enabled = true
            p.setTargetGain(600)
            val frames = 4_000
            val bytes = when (encoding) {
                C.ENCODING_PCM_24BIT -> 3
                else -> 4
            }
            val input = ByteBuffer.allocateDirect(frames * bytes).order(ByteOrder.LITTLE_ENDIAN)
            val values = DoubleArray(frames) { 0.2 * sin(2.0 * PI * 300.0 * it / rate) }
            for (v in values) {
                when (encoding) {
                    C.ENCODING_PCM_24BIT -> {
                        val s = Math.round(v * 8388607.0).toInt()
                        input.put((s and 0xFF).toByte()).put(((s shr 8) and 0xFF).toByte()).put(((s shr 16) and 0xFF).toByte())
                    }
                    C.ENCODING_PCM_32BIT -> input.putInt(Math.round(v * 2147483647.0).toInt())
                    else -> input.putFloat(v.toFloat())
                }
            }
            input.flip()
            p.queueInput(input)
            val out = ByteBuffer.wrap(drainOutput(p)).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(frames * bytes, out.remaining())
            val decoded = DoubleArray(frames) {
                when (encoding) {
                    C.ENCODING_PCM_24BIT -> {
                        val b0 = out.get().toInt() and 0xFF
                        val b1 = out.get().toInt() and 0xFF
                        val b2 = out.get().toInt()
                        (((b2 shl 16) or (b1 shl 8) or b0) / 8388608.0)
                    }
                    C.ENCODING_PCM_32BIT -> out.getInt() / 2147483648.0
                    else -> out.getFloat().toDouble()
                }
            }
            val d = lookahead
            val gain = LoudnessGain.linearGainFromMb(600).toDouble()
            for (i in 500 until 3_000) {
                assertEquals("encoding $encoding sample $i", values[i - d] * gain, decoded[i], 2e-5)
            }
        }
    }

    @Test
    fun `an unsupported format is refused, not played wrong`() {
        val p = VolumeNormalizationAudioProcessor()
        try {
            p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_8BIT))
            fail("8-bit audio must be refused")
        } catch (expected: AudioProcessor.UnhandledAudioFormatException) {
            // fine: the sink then simply leaves this processor out
        }
    }

    @Test
    fun `reconfiguring for another sample rate changes the delay`() {
        val p = VolumeNormalizationAudioProcessor()
        p.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        p.queueInput(pcm16(ShortArray(2_000) { 1000 }))
        val out = shorts(drainOutput(p))
        val silent = out.indexOfFirst { it.toInt() != 0 }
        assertEquals(221 * 2, silent)
        assertFalse(out.all { it.toInt() == 0 })
    }
}
