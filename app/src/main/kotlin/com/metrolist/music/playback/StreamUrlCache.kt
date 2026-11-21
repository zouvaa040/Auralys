/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.core.net.toUri
import androidx.media3.datasource.DataSpec

internal data class CachedStreamUrl(
    val url: String,
    val requestHeaders: Map<String, String>,
    val clientName: String,
    val requireBoundedRange: Boolean = false,
    val rangeChunkSizeBytes: Long = 0L,
    val useRangeChunks: Boolean = false,
)

/**
 * The DataSpec with the stream's URL and headers. A stream is NOT cut to one chunk here any more (the player took the end
 * of that chunk for the end of the song when it fell between two elements); it is marked with [StreamChunking] and
 * [RangeChunkDataSource], under the caches, fetches it range by range as one continuous stream (see [chunkBytesOrNull]).
 */
internal fun DataSpec.withResolvedStream(stream: CachedStreamUrl): DataSpec {
    val resolved =
        withUri(stream.url.toUri())
            .withRequestHeaders(httpRequestHeaders + stream.requestHeaders)
    val chunk = stream.chunkBytesOrNull() ?: return resolved
    return resolved.buildUpon().setCustomData(StreamChunking(chunk)).build()
}

/**
 * The range size to fetch [CachedStreamUrl] with, or null when it is read in one open request.
 *
 * Every googlevideo stream is read in ranges, not only the clients that require it: googlevideo serves a request without
 * an end (no Range header, or `bytes=N-`) at about twice the playback speed. Measured 2026-11-12 with the live harness on
 * 30 songs: 256 KiB in 6.7-8.2 s unbounded (255-313 kbit/s) against a bounded 1 MiB range in 0.1-0.6 s (10-65 Mbit/s) on
 * the same URL. With only 2x headroom a weak or busy connection (a game running) empties the buffer: the spinner and the
 * "macet-macet" of the reports.
 */
internal fun CachedStreamUrl.chunkBytesOrNull(): Long? {
    if ((requireBoundedRange || useRangeChunks) && rangeChunkSizeBytes > 0L) return rangeChunkSizeBytes
    if (!isGoogleVideoUrl(url)) return null
    return rangeChunkSizeBytes.takeIf { it > 0L } ?: DEFAULT_RANGE_CHUNK_BYTES
}

internal const val DEFAULT_RANGE_CHUNK_BYTES = 1_048_576L

internal fun isGoogleVideoUrl(url: String): Boolean {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) && (host == "googlevideo.com" || host.endsWith(".googlevideo.com"))
}

internal class StreamUrlCache(
    private val maxEntries: Int = 500,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(
        val stream: CachedStreamUrl,
        val expiresAtMillis: Long,
    )

    private val entries =
        object : LinkedHashMap<String, Entry>(0, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
                size > maxEntries
        }
    private val generations = HashMap<String, Long>()

    init {
        require(maxEntries > 0) { "maxEntries must be greater than zero" }
    }

    operator fun get(mediaId: String): CachedStreamUrl? =
        synchronized(entries) {
            val entry = entries[mediaId] ?: return@synchronized null
            if (entry.expiresAtMillis <= currentTimeMillis()) {
                entries.remove(mediaId)
                advanceGeneration(mediaId)
                null
            } else {
                entry.stream
            }
        }

    fun clientName(mediaId: String): String? =
        synchronized(entries) { entries[mediaId]?.stream?.clientName }

    fun generation(mediaId: String): Long =
        synchronized(entries) { generations[mediaId] ?: 0L }

    fun put(
        mediaId: String,
        url: String,
        requestHeaders: Map<String, String>,
        clientName: String,
        expiresInSeconds: Int,
        requireBoundedRange: Boolean = false,
        rangeChunkSizeBytes: Long = 0L,
        useRangeChunks: Boolean = false,
        expectedGeneration: Long = generation(mediaId),
    ): Boolean {
        val now = currentTimeMillis()
        val ttlMillis = expiresInSeconds.coerceAtLeast(0).toLong() * 1_000L
        val expiresAtMillis =
            runCatching { Math.addExact(now, ttlMillis) }
                .getOrDefault(Long.MAX_VALUE)

        synchronized(entries) {
            if ((generations[mediaId] ?: 0L) != expectedGeneration) return false
            entries[mediaId] =
                Entry(
                    stream =
                        CachedStreamUrl(
                            url = url,
                            requestHeaders = requestHeaders.toMap(),
                            clientName = clientName,
                            requireBoundedRange = requireBoundedRange,
                            rangeChunkSizeBytes = rangeChunkSizeBytes,
                            useRangeChunks = useRangeChunks,
                        ),
                    expiresAtMillis = expiresAtMillis,
                )
            return true
        }
    }

    fun invalidate(mediaId: String) {
        synchronized(entries) {
            entries.remove(mediaId)
            advanceGeneration(mediaId)
        }
    }

    private fun advanceGeneration(mediaId: String) {
        generations[mediaId] = (generations[mediaId] ?: 0L) + 1L
    }
}
