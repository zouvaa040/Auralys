package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class ReverbAudioProcessorTest {
    private val rate = 44_100

    @After
    fun reset() {
        ReverbAudioProcessor.enabled = false
        ReverbAudioProcessor.amount = 1f
        ReverbAudioProcessor.lofi = false
        ReverbAudioProcessor.spatial = false
    }

    private fun tone(frames: Int, hz: Double, amp: Int = 8000): ShortArray =
        ShortArray(frames * 2) { i -> (amp * kotlin.math.sin(2 * Math.PI * hz * (i / 2) / rate)).toInt().toShort() }

    private fun channelRms(data: ShortArray, channel: Int, fromFrame: Int, toFrame: Int): Double =
        sqrt((fromFrame until toFrame).sumOf { data[it * 2 + channel].toDouble() * data[it * 2 + channel] } / (toFrame - fromFrame))

    /** Pushes interleaved [samples] through the processor in chunks and returns what came out. */
    private fun process(samples: ShortArray, channels: Int = 2, chunk: Int = 4_000): ShortArray {
        val processor = ReverbAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val out = ArrayList<Short>()
        var pos = 0
        while (pos < samples.size) {
            val n = minOf(chunk * channels, samples.size - pos)
            val input = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in pos until pos + n) input.putShort(samples[i])
            input.flip()
            processor.queueInput(input)
            val buffer = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            while (buffer.remaining() >= 2) out.add(buffer.short)
            pos += n
        }
        return out.toShortArray()
    }

    private fun rms(data: ShortArray, from: Int = 0, to: Int = data.size): Double =
        sqrt((from until to).sumOf { data[it].toDouble() * data[it] } / (to - from))

    private fun noise(frames: Int, amp: Int = 6000): ShortArray {
        val random = Random(1)
        return ShortArray(frames * 2) { (random.nextInt(-amp, amp)).toShort() }
    }

    @Test
    fun `a long silent tail stays finite and decays to exact zero`() {
        ReverbAudioProcessor.enabled = true
        val burst = noise(2_000)
        val silence = ShortArray(rate * 2 * 40)
        val out = process(burst + silence)
        val tail = out.takeLast(rate * 2).map { it.toInt() }
        assertTrue("tail should have died out", tail.all { it == 0 })
    }

    @Test
    fun `reverb runs far faster than real time`() {
        ReverbAudioProcessor.enabled = true
        val input = noise(rate * 10)
        process(input.copyOf(rate * 2)) // warm up the JIT
        val start = System.nanoTime()
        process(input)
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue("10 s of audio took $millis ms", millis < 2_500)
    }

    @Test
    fun `disabled mode passes audio through unchanged`() {
        val input = noise(5_000)
        val out = process(input)
        assertEquals(input.size, out.size)
        for (i in input.indices) assertEquals(input[i], out[i])
    }

    @Test
    fun `output keeps the length of the input`() {
        ReverbAudioProcessor.enabled = true
        val input = noise(12_345)
        assertEquals(input.size, process(input).size)
    }

    @Test
    fun `a click leaves a tail after it`() {
        ReverbAudioProcessor.enabled = true
        val input = ShortArray(rate * 2) // one second of stereo frames
        input[0] = 20_000
        input[1] = 20_000
        val out = process(input)
        val tail = rms(out, 2 * (rate / 10), 2 * (rate / 2))
        assertTrue("tail only $tail", tail > 5)
    }

    @Test
    fun `silence stays silent`() {
        ReverbAudioProcessor.enabled = true
        val out = process(ShortArray(rate * 2))
        assertEquals(0, out.maxOf { abs(it.toInt()) })
    }

    @Test
    fun `the reverb is audible but does not bury the song`() {
        ReverbAudioProcessor.enabled = true
        val input = noise(rate * 3)
        val out = process(input)
        val before = rms(input, rate, input.size)
        val after = rms(out, rate, out.size)
        val change = 20 * kotlin.math.log10(after / before)
        assertTrue("level changed by $change dB", change in 0.5..6.0)
    }

    @Test
    fun `loud audio is bent not clipped`() {
        ReverbAudioProcessor.enabled = true
        val input = ShortArray(rate * 2) { if ((it / 2) % 100 < 50) 32_000 else -32_000 }
        val out = process(input)
        val atFullScale = out.count { abs(it.toInt()) >= 32_767 }
        assertEquals("$atFullScale samples hit full scale", 0, atFullScale)
    }

    @Test
    fun `mono audio works too`() {
        ReverbAudioProcessor.enabled = true
        val mono = ShortArray(rate) { (4000 * kotlin.math.sin(it * 0.05)).toInt().toShort() }
        val out = process(mono, channels = 1)
        assertEquals(mono.size, out.size)
        assertTrue(rms(out) > 0)
    }

    @Test
    fun `switching it off and on starts without an old tail`() {
        ReverbAudioProcessor.enabled = true
        val processor = ReverbAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        fun push(data: ShortArray): ShortArray {
            val input = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            data.forEach { input.putShort(it) }
            input.flip()
            processor.queueInput(input)
            val buffer = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            return ShortArray(buffer.remaining() / 2) { buffer.short }
        }
        push(noise(rate))
        ReverbAudioProcessor.enabled = false
        push(ShortArray(2_000))
        ReverbAudioProcessor.enabled = true
        val quiet = push(ShortArray(rate / 2 * 2))
        assertEquals(0, quiet.maxOf { abs(it.toInt()) })
    }

    @Test
    fun `lofi cuts the highs and keeps the lows`() {
        val high = tone(rate / 2, 15_000.0)
        val low = tone(rate / 2, 200.0)
        val plainHigh = rms(process(high))
        ReverbAudioProcessor.lofi = true
        val lofiHigh = rms(process(high))
        val lofiLow = rms(process(low))
        assertTrue("15 kHz should be mostly gone ($lofiHigh of $plainHigh)", lofiHigh < plainHigh * 0.45)
        assertTrue("200 Hz should stay ($lofiLow of ${rms(low)})", lofiLow > rms(low) * 0.8)
    }

    @Test
    fun `8D swings the sound from one ear to the other`() {
        ReverbAudioProcessor.spatial = true
        val out = process(tone(rate * 9, 440.0))
        // a quarter of the 8 s turn (2 s) is fully to the right, three quarters (6 s) fully to the left
        val at2 = (rate * 19 / 10) to (rate * 21 / 10)
        val at6 = (rate * 59 / 10) to (rate * 61 / 10)
        assertTrue("at 2 s the left ear should be nearly silent", channelRms(out, 0, at2.first, at2.second) < channelRms(out, 1, at2.first, at2.second) * 0.2)
        assertTrue("at 6 s the right ear should be nearly silent", channelRms(out, 1, at6.first, at6.second) < channelRms(out, 0, at6.first, at6.second) * 0.2)
    }

    @Test
    fun `a smaller reverb amount leaves a quieter tail`() {
        val burst = noise(rate / 5)
        val silence = ShortArray(rate * 2)
        fun tailRms(amount: Float): Double {
            ReverbAudioProcessor.enabled = true
            ReverbAudioProcessor.amount = amount
            val out = process(burst + silence)
            return rms(out, burst.size + rate / 2, out.size)
        }
        val light = tailRms(0.3f)
        val full = tailRms(1f)
        assertTrue("light tail $light should be quieter than full $full", light < full * 0.6)
    }

    @Test
    fun `applying a style sets every colour switch`() {
        ReverbAudioProcessor.apply(SoundVibe.LOFI)
        assertTrue(ReverbAudioProcessor.lofi)
        assertTrue(ReverbAudioProcessor.enabled)
        assertEquals(0.45f, ReverbAudioProcessor.amount, 0f)
        ReverbAudioProcessor.apply(SoundVibe.SPATIAL_8D)
        assertTrue(ReverbAudioProcessor.spatial)
        assertTrue(!ReverbAudioProcessor.lofi)
        ReverbAudioProcessor.apply(SoundVibe.NORMAL)
        assertTrue(!ReverbAudioProcessor.enabled && !ReverbAudioProcessor.lofi && !ReverbAudioProcessor.spatial)
    }
}
