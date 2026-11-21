package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The colour of the sound styles, in one pass over the audio: a warm low-pass for "Lo-fi", a room reverb (eight parallel
 * damped comb filters feeding four all-pass filters, the classic Schroeder/Freeverb layout, one set per channel with
 * slightly different delays so the tail is wide in stereo), and a slow circling pan for "8D". Each part switches on by
 * itself: with reverb off, lo-fi off and 8D off the audio goes through unchanged. It costs a few percent of one core.
 */
@UnstableApi
class ReverbAudioProcessor : BaseAudioProcessor() {
    private var channels = 0
    private var active = false
    private var processing = false
    private var banks: Array<Bank> = emptyArray()
    private var lowCoeff = 0f
    private var panStep = 0.0
    private var lowLeft = 0f
    private var lowRight = 0f
    private var phase = 0.0
    private var panLeft = 1f
    private var panRight = 1f
    private var panCounter = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        active = channels in 1..2 && inputAudioFormat.sampleRate > 0
        banks = if (active) Array(channels) { Bank(inputAudioFormat.sampleRate, spread = it * STEREO_SPREAD) } else emptyArray()
        if (active) {
            lowCoeff = (1.0 - exp(-2.0 * PI * LOFI_CUTOFF / inputAudioFormat.sampleRate)).toFloat()
            panStep = 2.0 * PI / (PAN_SECONDS * inputAudioFormat.sampleRate)
        }
        resetState()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val reverbOn = enabled
        val lofiOn = lofi
        val spatialOn = spatial && channels == 2
        if (!active || !(reverbOn || lofiOn || spatialOn)) {
            if (processing) {
                resetState()
                processing = false
            }
            replaceOutputBuffer(size).apply {
                put(inputBuffer)
                flip()
            }
            return
        }
        processing = true
        val amountNow = amount.coerceIn(0f, 1.5f)
        val wet = WET * amountNow
        val dry = 1f - (1f - DRY) * amountNow

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val output = replaceOutputBuffer(size).order(ByteOrder.LITTLE_ENDIAN)
        val frames = size / (2 * channels)
        repeat(frames) {
            var left = inputBuffer.short / 32768f
            var right = if (channels == 2) inputBuffer.short / 32768f else left
            if (lofiOn) {
                lowLeft += lowCoeff * (left - lowLeft)
                lowRight += lowCoeff * (right - lowRight)
                left = lowLeft * LOFI_GAIN
                right = lowRight * LOFI_GAIN
            }
            if (reverbOn) {
                val mono = (left + right) * 0.5f
                val tailLeft = banks[0].process(mono * INPUT_GAIN) * wet
                val tailRight = if (channels == 2) banks[1].process(mono * INPUT_GAIN) * wet else 0f
                left = left * dry + tailLeft
                right = right * dry + tailRight
            }
            if (spatialOn) {
                if (panCounter == 0) {
                    // equal-power pan that sweeps left to right and back; gains are refreshed every 32 frames, not per sample
                    val angle = (0.5 + 0.5 * sin(phase)) * (PI / 2.0)
                    panLeft = (cos(angle) * SQRT2).toFloat()
                    panRight = (sin(angle) * SQRT2).toFloat()
                }
                panCounter = (panCounter + 1) and 31
                phase += panStep
                if (phase > 2.0 * PI) phase -= 2.0 * PI
                left *= panLeft
                right *= panRight
            }
            output.putShort(toPcm(softLimit(left)))
            if (channels == 2) output.putShort(toPcm(softLimit(right)))
        }
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        resetState()
    }

    override fun onReset() {
        active = false
        processing = false
        banks = emptyArray()
    }

    private fun resetState() {
        banks.forEach { it.clear() }
        lowLeft = 0f
        lowRight = 0f
        phase = 0.0
        panCounter = 0
        panLeft = 1f
        panRight = 1f
    }

    private fun toPcm(value: Float): Short = (value * 32767f).toInt().coerceIn(-32768, 32767).toShort()

    /** Bends peaks above 0.8 smoothly towards [CEILING] (just under full scale) instead of clipping them. */
    private fun softLimit(x: Float): Float {
        val size = abs(x)
        if (size <= KNEE) return x
        val bent = KNEE + (CEILING - KNEE) * tanh((size - KNEE) / (CEILING - KNEE))
        return if (x < 0f) -bent else bent
    }

    /** One channel's filter network. Delay lengths are the Freeverb ones for 44.1 kHz, scaled to the real rate. */
    private class Bank(sampleRate: Int, spread: Int) {
        private val scale = sampleRate / 44_100f
        // plain arrays and index loops: this runs per sample on the audio thread, so nothing here may allocate
        private val combs = Array(COMB_DELAYS.size) { Comb(((COMB_DELAYS[it] + spread) * scale).toInt().coerceAtLeast(1)) }
        private val allpasses = Array(ALLPASS_DELAYS.size) { AllPass(((ALLPASS_DELAYS[it] + spread) * scale).toInt().coerceAtLeast(1)) }

        fun process(input: Float): Float {
            var sum = 0f
            for (i in combs.indices) sum += combs[i].process(input)
            for (i in allpasses.indices) sum = allpasses[i].process(sum)
            return sum
        }

        fun clear() {
            for (comb in combs) comb.clear()
            for (allpass in allpasses) allpass.clear()
        }
    }

    private class Comb(size: Int) {
        private val buffer = FloatArray(size)
        private var position = 0
        private var filtered = 0f

        fun process(input: Float): Float {
            val output = buffer[position]
            filtered = output * (1f - DAMP) + filtered * DAMP
            // a decaying tail ends up in denormal floats, which are many times slower on ARM; flush them to zero
            if (filtered < DENORMAL && filtered > -DENORMAL) filtered = 0f
            buffer[position] = input + filtered * FEEDBACK
            if (++position == buffer.size) position = 0
            return output
        }

        fun clear() {
            buffer.fill(0f)
            filtered = 0f
            position = 0
        }
    }

    private class AllPass(size: Int) {
        private val buffer = FloatArray(size)
        private var position = 0

        fun process(input: Float): Float {
            val buffered = buffer[position]
            val stored = input + buffered * ALLPASS_FEEDBACK
            buffer[position] = if (stored < DENORMAL && stored > -DENORMAL) 0f else stored
            if (++position == buffer.size) position = 0
            return buffered - input
        }

        fun clear() {
            buffer.fill(0f)
            position = 0
        }
    }

    companion object {
        private val COMB_DELAYS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val ALLPASS_DELAYS = intArrayOf(556, 441, 341, 225)
        private const val STEREO_SPREAD = 23
        private const val FEEDBACK = 0.86f
        private const val DAMP = 0.25f
        private const val ALLPASS_FEEDBACK = 0.5f
        private const val INPUT_GAIN = 0.015f
        private const val DENORMAL = 1e-20f

        // dry/wet balance at amount 1, tuned so the tail is clearly audible but the song stays in front
        private const val DRY = 0.88f
        private const val WET = 3.5f
        private const val KNEE = 0.8f
        private const val CEILING = 0.98f

        // lo-fi: a warm low-pass at 4.2 kHz with a little make-up gain; 8D: one full turn around the head every 8 seconds
        private const val LOFI_CUTOFF = 4_200.0
        private const val LOFI_GAIN = 1.1f
        private const val PAN_SECONDS = 8.0
        private val SQRT2 = sqrt(2.0)

        /** Reverb on or off. Shared by every player, set from the sound style preference. */
        @Volatile
        var enabled = false

        /** How much reverb: 1 is the "Slowed + reverb" amount, smaller is a lighter room. */
        @Volatile
        var amount = 1f

        /** Warm low-pass for the lo-fi style. */
        @Volatile
        var lofi = false

        /** Circling pan for the 8D style (stereo only). */
        @Volatile
        var spatial = false

        /** Applies every colour setting of a sound style at once. */
        fun apply(vibe: SoundVibe) {
            amount = vibe.reverbAmount
            enabled = vibe.reverb
            lofi = vibe.lofi
            spatial = vibe.spatial
        }
    }
}
