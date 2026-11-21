/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import java.io.IOException

/**
 * One cached song = the bytes of ONE file (round 4.3.5, report "About You": a download that would not play with
 * "Skipping atom with length > 2147483647 (unsupported)").
 *
 * The player cache and the download cache keep a song under its video id only. YouTube serves several files for one
 * song: other itags (opus 251 on Wi-Fi, AAC 139/140 on a metered network), and inside one itag the DRC variant and
 * re-encoded versions (another `lmt`). Bytes of two of them under the same key make a file whose header describes one
 * layout and whose later bytes come from another: the MP4/WebM parser then reads box sizes out of audio data.
 *
 * [signature] names the file a stream address points to (itag, `lmt`, `xtags`, all from the address itself), it is
 * stored next to the cached bytes ([pin]) and compared before new bytes are added: another file means the old bytes go.
 */
@UnstableApi
internal object CacheFormatPin {
    /** Content-metadata name of the signature of the file whose bytes are cached under a key. */
    const val METADATA_KEY = "auralys_file"

    /** Prefix of the download-layer key of a read that must not use the download cache (see [downloadLayerKeys]). */
    const val NOT_FROM_DOWNLOAD = "nodl:"

    /** Put as custom data on a DataSpec that may read a COMPLETE download (see [downloadLayerKeys]). */
    object FromDownload

    /** The file behind a stream address: itag + `lmt` (file version) + `xtags` (DRC, dubbed track). No regex. */
    fun signature(
        itag: Int,
        url: String,
    ): String {
        val query = url.substringAfter('?', "")
        var lmt = ""
        var xtags = ""
        var urlItag: Int? = null
        for (part in query.split('&')) {
            when {
                part.startsWith("lmt=") -> lmt = part.substring(4)
                part.startsWith("xtags=") -> xtags = part.substring(6)
                part.startsWith("itag=") -> urlItag = part.substring(5).toIntOrNull()
            }
        }
        return "${urlItag ?: itag}|$lmt|$xtags"
    }

    /** The itag part of a [signature]. */
    fun itagOf(signature: String): Int? = signature.substringBefore('|').toIntOrNull()

    /**
     * True when the bytes cached under a key must be dropped before bytes of [newSignature] are added: they exist and
     * belong to another file. Bytes without a stored signature (cached before 4.3.5) are judged by the itag the database
     * knows for the song ([legacyItag]); unknown stays (nothing better is known, and the parse-error repair catches it).
     */
    fun mustDrop(
        hasBytes: Boolean,
        pinned: String?,
        newSignature: String,
        legacyItag: Int?,
    ): Boolean {
        if (!hasBytes) return false
        if (pinned != null) return pinned != newSignature
        return legacyItag != null && legacyItag != itagOf(newSignature)
    }

    fun pinned(
        cache: Cache,
        key: String,
    ): String? = runCatching { cache.getContentMetadata(key).get(METADATA_KEY, null as String?) }.getOrNull()

    fun hasBytes(
        cache: Cache,
        key: String,
    ): Boolean = runCatching { cache.getCachedSpans(key).isNotEmpty() }.getOrDefault(false)

    fun pin(
        cache: Cache,
        key: String,
        signature: String,
    ) {
        runCatching { cache.applyContentMetadataMutations(key, ContentMetadataMutations().set(METADATA_KEY, signature)) }
    }

    /** Removes every byte of [key] and its stored length, and pins [signature] for the bytes that come next. */
    fun dropAndPin(
        cache: Cache,
        key: String,
        signature: String,
    ) {
        runCatching { cache.removeResource(key) }
        runCatching {
            cache.applyContentMetadataMutations(
                key,
                ContentMetadataMutations().remove(ContentMetadata.KEY_CONTENT_LENGTH).set(METADATA_KEY, signature),
            )
        }
    }

    /**
     * A download of [key] goes on with the file [signature]: when bytes of another file (or of an unknown one, saved before
     * 4.3.5) are already saved, they are removed and [DownloadFileChangedException] is thrown, so the DownloadManager's
     * retry starts again at byte 0 (it then finds nothing saved and the new file is pinned). A single file never restarts.
     */
    fun keepOneFile(
        cache: Cache,
        key: String,
        signature: String,
        onRestart: (String?) -> Unit = {},
    ) {
        val pinned = pinned(cache, key)
        if (hasBytes(cache, key) && pinned != signature) {
            onRestart(pinned)
            dropAndPin(cache, key, signature)
            throw DownloadFileChangedException(key)
        }
        if (pinned != signature) pin(cache, key, signature)
    }

    /** The length the cache stored for [key], or null. */
    fun storedLength(
        cache: Cache,
        key: String,
    ): Long? = runCatching { ContentMetadata.getContentLength(cache.getContentMetadata(key)) }.getOrNull()?.takeIf { it > 0L }

    /** True when the download cache holds the WHOLE file of [key]. */
    fun isComplete(
        cache: Cache,
        key: String,
    ): Boolean {
        val length = storedLength(cache, key) ?: return false
        return runCatching { cache.isCached(key, 0L, length) }.getOrDefault(false)
    }

    /**
     * Key of the read-only download layer of the player. Only a read the resolver marked [FromDownload] (a complete download)
     * uses the downloaded bytes; every other read gets a key under which nothing is stored, so a partial download (still
     * running, failed, or of another file than the stream being played) can never be mixed into the played stream.
     */
    val downloadLayerKeys =
        CacheKeyFactory { spec: DataSpec ->
            val key = spec.key ?: spec.uri.toString()
            if (spec.customData === FromDownload) key else NOT_FROM_DOWNLOAD + key
        }

    /** Key of the player-cache layer under the download layer: the video id again. */
    val playerLayerKeys =
        CacheKeyFactory { spec: DataSpec -> (spec.key ?: spec.uri.toString()).removePrefix(NOT_FROM_DOWNLOAD) }

    /** The cached bytes of a download belong to another file than the address it resumes with: it starts again at 0. */
    class DownloadFileChangedException(
        key: String,
    ) : IOException("download of $key: the stream is another file than the bytes already saved, starting again")
}
