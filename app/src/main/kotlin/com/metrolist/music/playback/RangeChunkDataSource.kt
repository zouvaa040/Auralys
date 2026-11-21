/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Put on a [DataSpec] (as its custom data) by the stream resolver when the stream URL only answers bounded byte ranges
 * (the IOS, ANDROID_VR and TVHTML5_SIMPLY clients): the bytes are then fetched [chunkBytes] at a time.
 */
internal data class StreamChunking(
    val chunkBytes: Long,
)

/**
 * Reads a stream that only answers bounded byte ranges as ONE continuous stream.
 *
 * Before round 4.3.4 the resolver cut the DataSpec itself to one chunk. The player then saw the stream end after that chunk
 * and only went on because the extractor happened to be in the middle of an element (EOFException, the loader re-opens at
 * the same position). When a chunk ended exactly between two elements the extractor saw a clean end of input and the song
 * ended there, minutes early ("lagu belum selesai sudah di-skip"). Here every chunk boundary is invisible to the reader:
 * the next range is requested inside [read], and the end of input is reported only at the real end of the stream.
 *
 * A DataSpec without [StreamChunking] goes through untouched.
 */
@UnstableApi
internal class RangeChunkDataSource(
    private val upstream: DataSource,
) : DataSource {
    class Factory(
        private val upstream: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = RangeChunkDataSource(upstream.createDataSource())
    }

    /** The server closed a range before its last byte while more of the stream was promised: the loader retries. */
    class RangeEndedEarlyException(
        position: Long,
    ) : IOException("range ended early at byte $position")

    /** How many ranges were requested since this source was created (for tests and the live harness). */
    var requestCount = 0
        private set

    private var spec: DataSpec? = null
    private var chunkBytes = 0L
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var pieceLeft = 0L
    private var pieceOpen = false
    private var passThrough = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val chunking = dataSpec.customData as? StreamChunking
        if (chunking == null || chunking.chunkBytes <= 0L) {
            passThrough = true
            return upstream.open(dataSpec)
        }
        passThrough = false
        spec = dataSpec
        chunkBytes = chunking.chunkBytes
        position = dataSpec.position
        remaining = dataSpec.length
        val asked = pieceSize()
        val first = openPiece()
        val total = totalLength(upstream.responseHeaders)
        val available =
            when {
                total != null -> (total - dataSpec.position).coerceAtLeast(0L)
                // a first range shorter than asked is the whole rest of the stream
                first != C.LENGTH_UNSET.toLong() && first < asked -> first
                else -> C.LENGTH_UNSET.toLong()
            }
        if (available != C.LENGTH_UNSET.toLong()) {
            remaining = if (remaining == C.LENGTH_UNSET.toLong()) available else minOf(remaining, available)
        }
        return remaining
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (passThrough) return upstream.read(buffer, offset, length)
        while (true) {
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            if (!pieceOpen) {
                val next =
                    try {
                        openPiece()
                    } catch (e: HttpDataSource.InvalidResponseCodeException) {
                        // asked past the end: the stream is over
                        if (e.responseCode == 416 && remaining == C.LENGTH_UNSET.toLong()) return C.RESULT_END_OF_INPUT
                        throw e
                    }
                if (next == 0L) return endOrFail()
            }
            val want = if (pieceLeft > 0L) minOf(length.toLong(), pieceLeft).toInt() else length
            val read = upstream.read(buffer, offset, want)
            if (read == C.RESULT_END_OF_INPUT) {
                val shortPiece = pieceLeft > 0L
                closePiece()
                if (shortPiece) return endOrFail()
                continue
            }
            position += read
            if (pieceLeft > 0L) pieceLeft -= read
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
            if (pieceLeft == 0L) closePiece()
            return read
        }
    }

    override fun getUri(): Uri? = upstream.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        try {
            // also after an open that threw: the DataSource contract wants close() then too
            upstream.close()
        } finally {
            pieceOpen = false
            passThrough = false
            spec = null
            pieceLeft = 0L
            remaining = C.LENGTH_UNSET.toLong()
        }
    }

    /** A range came back shorter than asked: that is the end when the length is unknown, an error when more was promised. */
    private fun endOrFail(): Int {
        if (remaining == C.LENGTH_UNSET.toLong()) {
            remaining = 0L
            return C.RESULT_END_OF_INPUT
        }
        throw RangeEndedEarlyException(position)
    }

    private fun pieceSize(): Long = if (remaining == C.LENGTH_UNSET.toLong()) chunkBytes else minOf(chunkBytes, remaining)

    private fun openPiece(): Long {
        val wanted = pieceSize()
        val piece =
            requireNotNull(spec)
                .buildUpon()
                .setPosition(position)
                .setLength(wanted)
                .setCustomData(null)
                .build()
        requestCount++
        val length = upstream.open(piece)
        pieceOpen = true
        checkPiece(piece, upstream.responseHeaders)
        pieceLeft = if (length == C.LENGTH_UNSET.toLong()) wanted else length
        return pieceLeft
    }

    private fun closePiece() {
        if (pieceOpen) {
            pieceOpen = false
            upstream.close()
        }
        pieceLeft = 0L
    }

    /** A range answer that starts at another byte than asked: its bytes would land at the wrong place of the file. */
    class RangeMismatchException(
        asked: Long,
        served: Long,
    ) : IOException("asked for byte $asked, the server sent from byte $served")

    internal companion object {
        /**
         * Round 4.3.5 (downloads that would not play): only media bytes may reach the caches. An answer that is a web page,
         * JSON or text (a captive portal, an error page with status 200) or a range that starts elsewhere than asked (a
         * server that took the range from the address instead of the header) is refused here instead of being saved as part
         * of the song.
         */
        fun checkPiece(
            piece: DataSpec,
            headers: Map<String, List<String>>,
        ) {
            header(headers, "Content-Type")?.let { type ->
                if (!isMediaContentType(type)) throw HttpDataSource.InvalidContentTypeException(type, piece)
            }
            val start = header(headers, "Content-Range")?.let(::rangeStart)
            if (start != null && start != piece.position) throw RangeMismatchException(piece.position, start)
        }

        /** False for pages and documents (text, HTML, JSON, XML); anything else may be media. */
        fun isMediaContentType(type: String): Boolean {
            val t = type.trim().lowercase()
            return !(t.startsWith("text/") || "html" in t || "json" in t || "xml" in t)
        }

        /** The first byte of a `Content-Range: bytes a-b/size` header, or null. */
        fun rangeStart(value: String): Long? {
            val v = value.trim()
            if (!v.startsWith("bytes", ignoreCase = true)) return null
            return v.substring(5).trim().substringBefore('-', "").trim().toLongOrNull()
        }

        private fun header(
            headers: Map<String, List<String>>,
            name: String,
        ): String? =
            headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.firstOrNull()

        /** The size after the slash of a `Content-Range: bytes a-b/size` header, or null when absent or `*`. */
        fun totalLength(headers: Map<String, List<String>>): Long? =
            headers.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value
                ?.firstOrNull()
                ?.substringAfterLast('/', "")
                ?.trim()
                ?.toLongOrNull()
    }
}
