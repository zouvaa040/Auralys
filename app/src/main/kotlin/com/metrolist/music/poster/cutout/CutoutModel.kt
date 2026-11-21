/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster.cutout

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ====================================================================================================================
// The poster's cut-out network (MODNet, portrait matting) as a small program: the file assets/poster/modnet_q.bin is written by the
// converter in the poster tools (ONNX -> this format, weights compressed with per-channel palettes). Layout, little-endian:
//   "AMN1", numValues, inputValue, outputValue, numWeights, weights..., numOps, ops...
//   weight: kind u8 (0 f32, 1 f16, 2 palette), ndim u8, dims i32[ndim], then the data
//           palette: bits u8, LUT f16[dims0 * 2^bits] (one table per output channel), indices packed LSB first
//   op:     code u8 then its fields (see [ModelOp])

/** One weight tensor, still compressed; [decode] turns it into floats when the layer runs (and the floats are dropped after it). */
internal class ModelWeight(
    private val bytes: ByteArray,
    private val offset: Int,
    val kind: Int,
    val shape: IntArray,
    val bits: Int,
) {
    val size: Int = shape.fold(1) { a, b -> a * b }

    /** How many bytes of the file it takes. */
    val storedBytes: Int
        get() =
            when (kind) {
                KIND_F32 -> size * 4
                KIND_F16 -> size * 2
                else -> shape[0] * (1 shl bits) * 2 + (size.toLong() * bits + 7).div(8).toInt()
            }

    /** The values as floats, row-major in the stored shape. */
    fun decode(): FloatArray {
        val out = FloatArray(size)
        decodeInto(out)
        return out
    }

    fun decodeInto(out: FloatArray) {
        forEachValue { i, v -> out[i] = v }
    }

    /**
     * The values straight into the layout of the matrix kernel (see CutoutKernels.packWeights): four outputs at a time,
     * [(q * perOutput + k) * 4 + j] for output 4q + j. No plain copy is made on the way, which matters for the biggest layer (3 million weights).
     */
    fun decodeQuads(): FloatArray {
        val o = shape[0]
        val per = size / o
        val out = FloatArray(((o + 3) / 4) * per * 4)
        forEachValue { i, v ->
            val oc = i / per
            val k = i - oc * per
            out[((oc ushr 2) * per + k) * 4 + (oc and 3)] = v
        }
        return out
    }

    private inline fun forEachValue(put: (Int, Float) -> Unit) {
        val b = bytes
        when (kind) {
            KIND_F32 -> {
                var o = offset
                for (i in 0 until size) {
                    val bitsF = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 3].toInt() and 255) shl 24)
                    put(i, java.lang.Float.intBitsToFloat(bitsF))
                    o += 4
                }
            }
            KIND_F16 -> {
                var o = offset
                for (i in 0 until size) {
                    put(i, halfToFloat((b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)))
                    o += 2
                }
            }
            else -> {
                val oc = shape[0]
                val k = 1 shl bits
                val perOc = size / oc
                // the per-channel tables as floats
                val lut = FloatArray(oc * k)
                var o = offset
                for (i in lut.indices) {
                    lut[i] = halfToFloat((b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8))
                    o += 2
                }
                val mask = k - 1
                var bitPos = 0L
                val base = o
                var i = 0
                for (c in 0 until oc) {
                    val t = c * k
                    for (j in 0 until perOc) {
                        val byteIdx = base + (bitPos ushr 3).toInt()
                        val shift = (bitPos and 7L).toInt()
                        // at most 8 bits wide: two bytes always hold one index
                        val lo = b[byteIdx].toInt() and 255
                        val hi = if (shift + bits > 8) (b[byteIdx + 1].toInt() and 255) else 0
                        val idx = ((lo or (hi shl 8)) ushr shift) and mask
                        put(i++, lut[t + idx])
                        bitPos += bits
                    }
                }
            }
        }
    }

    companion object {
        const val KIND_F32 = 0
        const val KIND_F16 = 1
        const val KIND_PALETTE = 2

        /** IEEE 754 half (as the low 16 bits of [h]) to float, subnormals included. */
        fun halfToFloat(h: Int): Float {
            val sign = (h ushr 15) and 1
            val exp = (h ushr 10) and 0x1F
            val mant = h and 0x3FF
            if (exp == 0) {
                // zero and the subnormals: mant x 2^-24, exact in a float
                val v = mant * 5.9604645E-8f
                return if (sign == 1) -v else v
            }
            val bits =
                if (exp == 0x1F) {
                    (sign shl 31) or (0xFF shl 23) or (mant shl 13)
                } else {
                    (sign shl 31) or ((exp - 15 + 127) shl 23) or (mant shl 13)
                }
            return java.lang.Float.intBitsToFloat(bits)
        }
    }
}

internal sealed class ModelOp {
    /** act: 0 none, 1 ReLU, 2 ReLU6, 3 sigmoid. */
    class Conv(val input: Int, val output: Int, val weight: Int, val bias: Int, val k: Int, val stride: Int, val pad: Int, val group: Int, val act: Int) : ModelOp()

    /** In place on [t]: channels below [half] get the folded batch norm (scale, shift), the rest an instance norm (gamma, beta), then ReLU. */
    class Ibn(val t: Int, val channels: Int, val half: Int, val scale: Int, val shift: Int, val gamma: Int, val beta: Int, val eps: Float) : ModelOp()

    /** In place on [t]: squeeze and excitation, t *= sigmoid(w2 . relu(w1 . mean(t))); w1 and w2 stored [out][in]. */
    class Se(val t: Int, val w1: Int, val w2: Int) : ModelOp()

    /** Bilinear, half-pixel centres (PyTorch's align_corners = false); the output side is floor(side * scale). */
    class Resize(val input: Int, val output: Int, val scaleH: Float, val scaleW: Float) : ModelOp()

    class Concat(val output: Int, val inputs: IntArray) : ModelOp()

    class Add(val a: Int, val b: Int, val output: Int) : ModelOp()
}

internal class CutoutModel(
    val numValues: Int,
    val inputValue: Int,
    val outputValue: Int,
    val weights: List<ModelWeight>,
    val ops: List<ModelOp>,
    /** Size of the file in bytes. */
    val fileBytes: Int,
) {
    companion object {
        private const val OP_CONV = 1
        private const val OP_IBN = 2
        private const val OP_SE = 3
        private const val OP_RESIZE = 4
        private const val OP_CONCAT = 5
        private const val OP_ADD = 6

        fun read(stream: InputStream): CutoutModel = parse(stream.readBytes())

        fun parse(bytes: ByteArray): CutoutModel {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            bb.get(magic)
            require(String(magic, Charsets.US_ASCII) == "AMN1") { "not a cut-out model" }
            val numValues = bb.int
            val input = bb.int
            val output = bb.int
            val nw = bb.int
            require(nw in 1..10_000)
            val weights = ArrayList<ModelWeight>(nw)
            repeat(nw) {
                val kind = bb.get().toInt() and 255
                val nd = bb.get().toInt() and 255
                require(nd in 1..6)
                val shape = IntArray(nd) { bb.int }
                var bits = 0
                if (kind == ModelWeight.KIND_PALETTE) {
                    bits = bb.get().toInt() and 255
                    require(bits in 1..8)
                }
                val w = ModelWeight(bytes, bb.position() + 0, kind, shape, bits)
                require(kind in 0..2)
                // the table of a palette sits before the indices: decode reads both from the same start
                val len = w.storedBytes
                require(bb.position() + len <= bytes.size) { "model file is cut short" }
                weights.add(w)
                bb.position(bb.position() + len)
            }
            val nops = bb.int
            require(nops in 1..10_000)
            val ops = ArrayList<ModelOp>(nops)
            repeat(nops) {
                when (bb.get().toInt() and 255) {
                    OP_CONV -> {
                        val a = IntArray(10) { bb.int }
                        require(a[4] == a[5]) { "only square kernels" }
                        ops.add(ModelOp.Conv(a[0], a[1], a[2], a[3], a[4], a[6], a[7], a[8], a[9]))
                    }
                    OP_IBN -> {
                        val a = IntArray(7) { bb.int }
                        ops.add(ModelOp.Ibn(a[0], a[1], a[2], a[3], a[4], a[5], a[6], bb.float))
                    }
                    OP_SE -> ops.add(ModelOp.Se(bb.int, bb.int, bb.int))
                    OP_RESIZE -> ops.add(ModelOp.Resize(bb.int, bb.int, bb.float, bb.float))
                    OP_CONCAT -> {
                        val out = bb.int
                        val n = bb.int
                        require(n in 1..64)
                        ops.add(ModelOp.Concat(out, IntArray(n) { bb.int }))
                    }
                    OP_ADD -> ops.add(ModelOp.Add(bb.int, bb.int, bb.int))
                    else -> throw IllegalArgumentException("unknown op")
                }
            }
            return CutoutModel(numValues, input, output, weights, ops, bytes.size)
        }
    }
}
