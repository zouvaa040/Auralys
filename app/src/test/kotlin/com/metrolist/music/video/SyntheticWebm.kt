package com.metrolist.music.video

import com.metrolist.innertubex.sabr.SabrChunk
import java.io.ByteArrayOutputStream

/**
 * A small but valid WebM written by hand, laid out like the file YouTube's SABR delivers: EBML header, Segment (size
 * unknown), Info, Tracks, Cues, then one Cluster per "segment". The first part (up to the first cluster) is the init
 * segment; every cluster is a media segment whose `startRange` is its byte offset in the file and whose cue says the
 * same. Frames are fake bytes (nothing decodes them), which is all MatroskaExtractor needs.
 */
class SyntheticWebm(
    val clusterCount: Int = 20,
    val clusterMs: Int = 4_000,
    val framesPerCluster: Int = 40,
    val frameBytes: Int = 50,
) {
    class Segment(
        val index: Int,
        val startRange: Long,
        val startMs: Long,
        val durationMs: Long,
        val bytes: ByteArray,
    ) {
        val endRange: Long get() = startRange + bytes.size
    }

    val durationMs: Long = clusterCount.toLong() * clusterMs
    val bytes: ByteArray
    val initEnd: Int
    val segments: List<Segment>

    init {
        val clusterBodies = (0 until clusterCount).map { buildCluster(it) }
        val head = buildHead(clusterBodies.map { it.size })
        val out = ByteArrayOutputStream()
        out.write(head)
        initEnd = head.size
        val segs = ArrayList<Segment>()
        clusterBodies.forEachIndexed { i, body ->
            segs += Segment(i, out.size().toLong(), i.toLong() * clusterMs, clusterMs.toLong(), body)
            out.write(body)
        }
        bytes = out.toByteArray()
        segments = segs
    }

    /** The chunks SABR would send for a stream started at [startMs]: the init chunk, then the segments from the one that contains it. */
    fun chunksFrom(startMs: Long): List<SabrChunk> {
        val list = ArrayList<SabrChunk>()
        list += SabrChunk(bytes.copyOfRange(0, initEnd), 0L, true, null, null, null)
        val first = segments.firstOrNull { startMs < it.startMs + it.durationMs } ?: return list
        for (s in segments.drop(first.index)) list += s.chunk()
        return list
    }

    fun Segment.chunk() = SabrChunk(this.bytes, this.startRange, false, this.index, this.startMs, this.durationMs)

    fun segmentChunk(index: Int): SabrChunk = segments[index].chunk()

    // ------------------------------------------------------------------------------------------------ the writer

    private fun buildHead(clusterSizes: List<Int>): ByteArray {
        val ebml =
            element(
                0x1A45DFA3,
                element(0x4286, uint(1, 1)),
                element(0x42F7, uint(1, 1)),
                element(0x42F2, uint(4, 1)),
                element(0x42F3, uint(8, 1)),
                element(0x4282, "webm".toByteArray()),
                element(0x4287, uint(4, 1)),
                element(0x4285, uint(2, 1)),
            )
        val info =
            element(
                0x1549A966,
                element(0x2AD7B1, uint(1_000_000, 4)), // timecode scale: 1 ms
                element(0x4489, float(durationMs.toFloat())),
            )
        val tracks =
            element(
                0x1654AE6B,
                element(
                    0xAE,
                    element(0xD7, uint(1, 1)),
                    element(0x73C5, uint(1, 4)),
                    element(0x83, uint(1, 1)),
                    element(0x86, "V_VP9".toByteArray()),
                    element(0xE0, element(0xB0, uint(640, 2)), element(0xBA, uint(360, 2))),
                ),
            )
        // The cue positions are relative to the start of the Segment's content and need the size of everything before
        // the clusters, which includes the Cues themselves: their size does not depend on the numbers (fixed width).
        val segmentHeaderSize = 4 + 8
        val cuesSize = cues(clusterSizes, 0, 0).size
        val beforeClusters = ebml.size + segmentHeaderSize + info.size + tracks.size + cuesSize
        val cues = cues(clusterSizes, beforeClusters - ebml.size - segmentHeaderSize, 0)
        val segmentHeader = idBytes(0x18538067) + byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1) // size unknown
        return ebml + segmentHeader + info + tracks + cues
    }

    /** Cues with fixed-width numbers. [contentStart] = where the first cluster starts, relative to the Segment content. */
    private fun cues(
        clusterSizes: List<Int>,
        contentStart: Int,
        @Suppress("UNUSED_PARAMETER") unused: Int,
    ): ByteArray {
        var position = contentStart
        val points = ArrayList<ByteArray>()
        clusterSizes.forEachIndexed { i, size ->
            points +=
                element(
                    0xBB,
                    element(0xB3, uint(i.toLong() * clusterMs, 4)),
                    element(0xB7, element(0xF7, uint(1, 1)), element(0xF1, uint(position.toLong(), 4))),
                )
            position += size
        }
        return element(0x1C53BB6B, *points.toTypedArray())
    }

    private fun buildCluster(index: Int): ByteArray {
        val frames = ArrayList<ByteArray>()
        val step = clusterMs / framesPerCluster
        for (f in 0 until framesPerCluster) {
            val rel = f * step
            val flags = if (f == 0) 0x80 else 0x00
            val header = byteArrayOf(0x81.toByte(), (rel shr 8).toByte(), rel.toByte(), flags.toByte())
            val payload = ByteArray(frameBytes) { (index + f + it).toByte() }
            frames += element(0xA3, header + payload)
        }
        return element(0x1F43B675, element(0xE7, uint(index.toLong() * clusterMs, 4)), *frames.toTypedArray())
    }

    private fun element(
        id: Long,
        vararg children: ByteArray,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        children.forEach { body.write(it) }
        return element(id, body.toByteArray())
    }

    private fun element(
        id: Long,
        body: ByteArray,
    ): ByteArray = idBytes(id) + sizeBytes(body.size.toLong()) + body

    private fun element(
        id: Int,
        vararg children: ByteArray,
    ): ByteArray = element(id.toLong() and 0xFFFFFFFFL, *children)

    private fun element(
        id: Int,
        body: ByteArray,
    ): ByteArray = element(id.toLong() and 0xFFFFFFFFL, body)

    private fun idBytes(id: Long): ByteArray {
        val n = if (id > 0xFFFFFF) 4 else if (id > 0xFFFF) 3 else if (id > 0xFF) 2 else 1
        return ByteArray(n) { (id shr (8 * (n - 1 - it))).toByte() }
    }

    private fun idBytes(id: Int): ByteArray = idBytes(id.toLong() and 0xFFFFFFFFL)

    /** An EBML size: always 4 bytes wide, so sizes do not change the layout when numbers change. */
    private fun sizeBytes(size: Long): ByteArray {
        require(size < (1L shl 28) - 1)
        return byteArrayOf((0x10 or ((size shr 24) and 0x0F).toInt()).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte())
    }

    private fun uint(
        value: Long,
        width: Int,
    ): ByteArray = ByteArray(width) { (value shr (8 * (width - 1 - it))).toByte() }

    private fun uint(
        value: Int,
        width: Int,
    ): ByteArray = uint(value.toLong(), width)

    private fun float(value: Float): ByteArray = uint(java.lang.Float.floatToIntBits(value).toLong() and 0xFFFFFFFFL, 4)
}
