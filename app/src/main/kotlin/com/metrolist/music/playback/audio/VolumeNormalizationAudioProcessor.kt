package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * "Normalisasi volume" in the audio chain: the per-track gain from [LoudnessGain] (set with [setTargetGain]) followed by the
 * look-ahead limiter of [LoudnessEngine], so a boost can never clip.
 *
 * It is the LAST processor of the chain on purpose: the equaliser and the reverb have finished shaping the signal, and the
 * limiter must see what really goes to the speaker.
 *
 * Always in the path and always 5 ms late (the look-ahead): when [enabled] is false the samples come out unchanged (bit for
 * bit for 16-bit audio), just delayed. A constant delay means switching on or off, or a change of track, never moves the
 * audio in time and never clicks. Output size equals input size on every call, so the output buffer is reused.
 *
 * No allocation per buffer: the float scratch is made when the format is configured.
 */
@UnstableApi
class VolumeNormalizationAudioProcessor : BaseAudioProcessor() {
    private var encoding = C.ENCODING_INVALID
    private var bytesPerSample = 0
    private var channelCount = 0
    private var engine: LoudnessEngine? = null
    private var scratch = FloatArray(0)
    private var chunkFrames = 0
    private var framesSinceFlush = 0L

    @Volatile
    var enabled = false
        set(value) {
            if (field != value) {
                field = value
                Timber.tag(TAG).d("Normalization processor enabled: $value")
            }
        }

    @Volatile
    private var targetGainMb = 0

    @Volatile
    private var targetLinear = 1f

    fun setTargetGain(gainMb: Int) {
        if (targetGainMb != gainMb) {
            targetLinear = LoudnessGain.linearGainFromMb(gainMb)
            targetGainMb = gainMb
            Timber.tag(TAG).d("Target gain set to $gainMb mB (Linear multiplier: $targetLinear)")
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        encoding = inputAudioFormat.encoding
        bytesPerSample =
            when (encoding) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
                else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }
        channelCount = inputAudioFormat.channelCount
        if (channelCount < 1 || inputAudioFormat.sampleRate < 1) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        val newEngine = LoudnessEngine(inputAudioFormat.sampleRate, channelCount)
        engine = newEngine
        chunkFrames = max(CHUNK_FRAMES, newEngine.lookaheadFrames)
        scratch = FloatArray(chunkFrames * channelCount)

        Timber.tag(TAG).d(
            "Configured: sampleRate=${inputAudioFormat.sampleRate}, channels=$channelCount, encoding=$encoding, " +
                "lookahead=${newEngine.lookaheadFrames} frames",
        )
        return inputAudioFormat
    }

    override fun onFlush() {
        engine?.reset()
        framesSinceFlush = 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val engine = engine ?: return
        val frameBytes = bytesPerSample * channelCount
        val frames = inputBuffer.remaining() / frameBytes
        if (frames == 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val on = enabled
        val target = if (on) targetLinear else 1f
        val output = replaceOutputBuffer(frames * frameBytes)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)

        var left = frames
        while (left > 0) {
            val n = min(left, chunkFrames)
            readFrames(inputBuffer, n * channelCount)
            engine.process(scratch, scratch, n, target, on)
            writeFrames(output, n * channelCount)
            left -= n
        }
        framesSinceFlush += frames
        inputBuffer.position(inputBuffer.limit()) // a trailing partial frame would be noise; whole frames only
        output.flip()
    }

    override fun onQueueEndOfStream() {
        val engine = engine ?: return
        if (framesSinceFlush == 0L) return // nothing went in, so nothing is waiting in the delay line
        val frames = engine.lookaheadFrames
        val on = enabled
        val output = replaceOutputBuffer(frames * bytesPerSample * channelCount)
        output.order(ByteOrder.LITTLE_ENDIAN)
        engine.drain(scratch, if (on) targetLinear else 1f, on)
        writeFrames(output, frames * channelCount)
        output.flip()
        framesSinceFlush = 0L
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        bytesPerSample = 0
        channelCount = 0
        engine = null
        scratch = FloatArray(0)
        chunkFrames = 0
        framesSinceFlush = 0L
    }

    private fun readFrames(
        input: ByteBuffer,
        samples: Int,
    ) {
        val out = scratch
        when (encoding) {
            C.ENCODING_PCM_16BIT ->
                for (i in 0 until samples) out[i] = input.getShort() * INV_16
            C.ENCODING_PCM_24BIT ->
                for (i in 0 until samples) {
                    val b0 = input.get().toInt() and 0xFF
                    val b1 = input.get().toInt() and 0xFF
                    val b2 = input.get().toInt()
                    out[i] = ((b2 shl 16) or (b1 shl 8) or b0) * INV_24
                }
            C.ENCODING_PCM_32BIT ->
                for (i in 0 until samples) out[i] = input.getInt() * INV_32
            else ->
                for (i in 0 until samples) out[i] = input.getFloat()
        }
    }

    private fun writeFrames(
        output: ByteBuffer,
        samples: Int,
    ) {
        val src = scratch
        when (encoding) {
            C.ENCODING_PCM_16BIT ->
                for (i in 0 until samples) {
                    val v = src[i] * 32768f
                    output.putShort(
                        when {
                            v >= 32767f -> 32767
                            v <= -32768f -> -32768
                            else -> Math.round(v)
                        }.toShort(),
                    )
                }
            C.ENCODING_PCM_24BIT ->
                for (i in 0 until samples) {
                    val v = src[i] * 8388608f
                    val s =
                        when {
                            v >= 8388607f -> 8388607
                            v <= -8388608f -> -8388608
                            else -> Math.round(v)
                        }
                    output.put((s and 0xFF).toByte())
                    output.put(((s shr 8) and 0xFF).toByte())
                    output.put(((s shr 16) and 0xFF).toByte())
                }
            C.ENCODING_PCM_32BIT ->
                for (i in 0 until samples) {
                    val v = src[i].toDouble() * 2147483648.0
                    output.putInt(
                        when {
                            v >= 2147483647.0 -> Int.MAX_VALUE
                            v <= -2147483648.0 -> Int.MIN_VALUE
                            else -> Math.round(v).toInt()
                        },
                    )
                }
            else ->
                for (i in 0 until samples) output.putFloat(src[i])
        }
    }

    private companion object {
        const val TAG = "VolumeNormalizationProcessor"
        const val CHUNK_FRAMES = 1024
        const val INV_16 = 1f / 32768f
        const val INV_24 = 1f / 8388608f
        const val INV_32 = 1f / 2147483648f
    }
}
