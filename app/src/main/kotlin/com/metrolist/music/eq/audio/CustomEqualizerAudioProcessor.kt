package com.metrolist.music.eq.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.metrolist.music.eq.data.ParametricEQ
import com.metrolist.music.eq.data.ParametricEQBand
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow

/**
 * Applies AutoEQ parametric profiles with biquad filters.
 */
@UnstableApi
class CustomEqualizerAudioProcessor : BaseAudioProcessor() {
    private var sampleRate = 0
    private var channelCount = 0
    @Volatile private var equalizerEnabled = false
    @Volatile private var filters: List<BiquadFilter> = emptyList()

    /** The preamp the profile asks for; the audio thread glides to it over one buffer (a jump would click). */
    @Volatile private var preampGain = 1.0

    /** The preamp the last buffer ended on. */
    @Volatile private var appliedPreamp = 1.0
    private var pendingProfile: ParametricEQ? = null

    /** New gains for the bands that are already running (a slider is being dragged); the audio thread takes them. */
    private val pendingGains = AtomicReference<DoubleArray?>(null)

    @Synchronized
    fun applyProfile(parametricEQ: ParametricEQ) {
        if (sampleRate == 0) {
            Timber.tag(TAG)
                .d("Audio processor not configured yet. Storing profile as pending with ${parametricEQ.bands.size} bands")
            pendingProfile = parametricEQ
            return
        }

        val target = 10.0.pow(parametricEQ.preamp / 20.0)
        val usable = usableBands(parametricEQ.bands)
        val running = filters
        if (equalizerEnabled && running.isNotEmpty() && running.size == usable.size && running.indices.all { running[it].sameShape(usable[it]) }) {
            // The same bands with other gains: only the numbers change. The filters keep their memory (a reset would click at
            // every step of a slider) and the audio thread swaps the coefficients between two buffers.
            pendingGains.set(DoubleArray(usable.size) { usable[it].gain })
            preampGain = target
            return
        }

        pendingGains.set(null)
        preampGain = target
        appliedPreamp = target
        createFilters(parametricEQ.bands)
        equalizerEnabled = true
        filters.forEach { it.reset() }

        Timber.tag(TAG)
            .d("Applied EQ profile with ${filters.size} bands and ${parametricEQ.preamp} dB preamp")
    }

    @Synchronized
    fun disable() {
        equalizerEnabled = false
        filters = emptyList()
        preampGain = 1.0
        appliedPreamp = 1.0
        pendingGains.set(null)
        pendingProfile = null
        Timber.tag(TAG).d("Equalizer disabled")
    }

    fun isEnabled(): Boolean = equalizerEnabled

    /** The bands that can run: a band with no width (q <= 0) or a broken number makes the filter unstable, it is left out. */
    private fun usableBands(bands: List<ParametricEQBand>): List<ParametricEQBand> =
        bands.filter { it.enabled && it.frequency > 0.0 && it.frequency < sampleRate / 2.0 && it.q > 0.0 && it.gain.isFinite() }

    private fun createFilters(bands: List<ParametricEQBand>) {
        filters =
            usableBands(bands)
                .map { band ->
                    BiquadFilter(
                        sampleRate = sampleRate,
                        frequency = band.frequency,
                        gain = band.gain,
                        q = band.q,
                        filterType = band.filterType,
                    )
                }

        Timber.tag(TAG)
            .d("Created ${filters.size} biquad filters from ${bands.size} bands (PK/LSC/HSC)")
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount > 2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        Timber.tag(TAG).d(
            "Configured: sampleRate=$sampleRate, channels=$channelCount, encoding=${inputAudioFormat.encoding}",
        )

        pendingProfile?.let { profile ->
            preampGain = 10.0.pow(profile.preamp / 20.0)
            appliedPreamp = preampGain
            createFilters(profile.bands)
            equalizerEnabled = true
            pendingProfile = null
            Timber.tag(TAG)
                .d("Applied pending profile with ${filters.size} bands and ${profile.preamp} dB preamp")
        }

        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        // gains that moved since the last buffer: set them now, on this thread, so no sample is filtered half old, half new
        pendingGains.getAndSet(null)?.let { gains ->
            val running = filters
            if (gains.size == running.size) for (i in running.indices) running[i].setGain(gains[i])
        }

        val output = replaceOutputBuffer(inputBuffer.remaining())
        if (!equalizerEnabled || filters.isEmpty()) {
            output.put(inputBuffer)
        } else {
            processAudioBuffer(inputBuffer, output)
        }
        output.flip()
    }

    private fun processAudioBuffer(input: ByteBuffer, output: ByteBuffer) {
        // one array read per buffer, plain index loops: nothing may allocate per sample on the audio thread (a Pair per band
        // per frame used to, and its garbage made the sound stutter on slow phones)
        val bank = filters.toTypedArray()
        val frames = input.remaining() / 2 / channelCount
        val goal = preampGain
        val step = if (frames > 0) (goal - appliedPreamp) / frames else 0.0
        var preamp = appliedPreamp
        repeat(frames) {
            preamp += step
            when (channelCount) {
                1 -> {
                    val dry = input.getShort().toDouble() / 32768.0
                    var processed = dry
                    for (i in bank.indices) processed = bank[i].processSample(processed)
                    if (!processed.isFinite()) {
                        // an unstable band (an odd profile) turns every sample into NaN, which reached the speaker as
                        // silence (and with "skip silence" on, raced through the queue): start the bands again, pass the sound
                        for (i in bank.indices) bank[i].reset()
                        processed = dry
                    }
                    processed *= preamp
                    output.putShort(
                        (processed * 32768.0)
                            .coerceIn(-32768.0, 32767.0)
                            .toInt()
                            .toShort(),
                    )
                }

                2 -> {
                    val dryLeft = input.getShort().toDouble() / 32768.0
                    val dryRight = input.getShort().toDouble() / 32768.0
                    var left = dryLeft
                    var right = dryRight
                    for (i in bank.indices) {
                        left = bank[i].processSample(left)
                        right = bank[i].processRight(right)
                    }
                    if (!left.isFinite() || !right.isFinite()) {
                        for (i in bank.indices) bank[i].reset()
                        left = dryLeft
                        right = dryRight
                    }
                    output.putShort(
                        (left * preamp * 32768.0)
                            .coerceIn(-32768.0, 32767.0)
                            .toInt()
                            .toShort(),
                    )
                    output.putShort(
                        (right * preamp * 32768.0)
                            .coerceIn(-32768.0, 32767.0)
                            .toInt()
                            .toShort(),
                    )
                }
            }
        }
        appliedPreamp = goal
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        filters.forEach { it.reset() }
    }

    override fun onReset() {
        sampleRate = 0
        channelCount = 0
        filters.forEach { it.reset() }
    }

    private companion object {
        const val TAG = "CustomEqualizerAudioProcessor"
    }
}
