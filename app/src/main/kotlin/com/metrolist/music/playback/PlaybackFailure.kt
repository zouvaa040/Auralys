/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.metrolist.innertubex.extraction.StreamResolveException
import java.io.FileNotFoundException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** What went wrong with a song, as far as the recovery is concerned. */
internal enum class PlaybackFailureKind {
    /** No connection at all (DNS, refused connection, no route): wait for the network, never burn the queue. */
    NETWORK_DOWN,

    /** The connection is there but too slow: the stream or its address did not arrive in time. */
    NETWORK_SLOW,

    /** The stream address was refused (403/410) or the stream was cut: a fresh address from another client. */
    STREAM_REJECTED,

    /** Cached bytes do not fit the stream (416, a file the cache lost, a container that does not parse). */
    CACHE_MISMATCH,

    /** YouTube says the song cannot be played (removed, private, region, age): retrying the same song is pointless. */
    SONG_UNAVAILABLE,

    /** The phone's decoder failed on this stream. */
    DECODER,

    /** The phone's audio output (AudioTrack) failed: not the song's fault. */
    AUDIO_OUTPUT,

    /** A file on the phone is gone or unreadable. */
    LOCAL_FILE,

    /** Anything else. */
    UNKNOWN,
}

/**
 * Thrown by the stream resolver (inside ExoPlayer's loader) when it cannot give a stream. It is deliberately NOT an
 * IOException: the loader then reports it at once instead of retrying the same failing resolution three times, and the
 * service's own recovery decides. [kind] survives every wrapper ExoPlayer puts around it (see [PlaybackFailures.classify]).
 */
internal class StreamResolveFailure(
    val kind: PlaybackFailureKind,
    message: String,
    cause: Throwable? = null,
    /** Round 4.3.5: a plain message for the user (the player's error view shows it instead of the raw cause texts). */
    val userText: String? = null,
) : Exception(message, cause)

internal object PlaybackFailures {
    /**
     * The kind of [error], from the WHOLE cause chain. ExoPlayer wraps a resolver failure in an UnexpectedLoaderException
     * with the generic code IO_UNSPECIFIED, so looking at the top error code alone (what the service did before round
     * 4.3.4) took "no internet" for a broken stream client and skipped the song.
     */
    fun classify(
        error: Throwable,
        isLocalSong: Boolean = false,
    ): PlaybackFailureKind {
        val chain = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(12).toList()

        chain.firstNotNullOfOrNull { it as? StreamResolveFailure }?.let { return it.kind }

        if (isLocalSong) {
            val code = (error as? PlaybackException)?.errorCode
            return if (code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED || code == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED) {
                PlaybackFailureKind.AUDIO_OUTPUT
            } else {
                PlaybackFailureKind.LOCAL_FILE
            }
        }

        val codes = chain.mapNotNull { (it as? PlaybackException)?.errorCode }
        if (codes.any { it == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED || it == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED }) {
            return PlaybackFailureKind.AUDIO_OUTPUT
        }
        if (codes.any { it == PlaybackException.ERROR_CODE_DECODING_FAILED || it == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED }) {
            return PlaybackFailureKind.DECODER
        }

        chain.firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }?.let { http ->
            return when (http.responseCode) {
                403, 410, 401, 404 -> PlaybackFailureKind.STREAM_REJECTED
                416 -> PlaybackFailureKind.CACHE_MISMATCH
                in 500..599 -> PlaybackFailureKind.NETWORK_SLOW
                else -> PlaybackFailureKind.STREAM_REJECTED
            }
        }

        chain.firstNotNullOfOrNull { it as? StreamResolveException }?.let { resolve ->
            return when (resolve.reason) {
                StreamResolveException.Reason.NETWORK -> networkKind(chain) ?: PlaybackFailureKind.NETWORK_SLOW
                StreamResolveException.Reason.UNAVAILABLE,
                StreamResolveException.Reason.AGE_RESTRICTED,
                StreamResolveException.Reason.EXPLICIT_UNSUPPORTED,
                StreamResolveException.Reason.NO_MUSIC_VIDEO,
                -> PlaybackFailureKind.SONG_UNAVAILABLE
                // a bot check or a client that gave no usable format: another client or a later try can still work
                StreamResolveException.Reason.NO_PLAYABLE_STREAM,
                StreamResolveException.Reason.UNKNOWN,
                -> PlaybackFailureKind.STREAM_REJECTED
            }
        }

        networkKind(chain)?.let { return it }

        if (chain.any { it is RangeChunkDataSource.RangeEndedEarlyException }) return PlaybackFailureKind.STREAM_REJECTED
        if (chain.any { it is FileNotFoundException } || codes.contains(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)) {
            return PlaybackFailureKind.CACHE_MISMATCH
        }
        if (codes.any {
                it == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                    it == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    it == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                    it == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
            }
        ) {
            return PlaybackFailureKind.CACHE_MISMATCH
        }
        if (codes.any {
                it == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            }
        ) {
            return PlaybackFailureKind.NETWORK_DOWN
        }
        if (codes.any {
                it == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            }
        ) {
            return PlaybackFailureKind.NETWORK_SLOW
        }
        if (codes.any {
                it == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    it == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
                    it == PlaybackException.ERROR_CODE_REMOTE_ERROR ||
                    it == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            }
        ) {
            return PlaybackFailureKind.STREAM_REJECTED
        }
        return PlaybackFailureKind.UNKNOWN
    }

    /**
     * True when the bytes did not parse as the song's container (any ERROR_CODE_PARSING_*, e.g. "Skipping atom with length
     * > 2147483647"): the cached or downloaded copy is broken (round 4.3.5), the network copy may still be fine.
     */
    fun isParseError(error: Throwable): Boolean {
        val chain = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(12).toList()
        if (chain.any { it is androidx.media3.common.ParserException }) return true
        return chain.any {
            val code = (it as? PlaybackException)?.errorCode ?: return@any false
            code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
        }
    }

    /** The HTTP status anywhere in the chain, or null. */
    fun httpStatus(error: Throwable): Int? =
        generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
            .take(12)
            .firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }
            ?.responseCode

    private fun networkKind(chain: List<Throwable>): PlaybackFailureKind? {
        if (chain.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException }) {
            return PlaybackFailureKind.NETWORK_DOWN
        }
        if (chain.any { it is SocketTimeoutException || it is InterruptedIOException }) {
            return PlaybackFailureKind.NETWORK_SLOW
        }
        if (chain.any { it is SSLException || it is SocketException }) return PlaybackFailureKind.NETWORK_SLOW
        if (chain.any { it is kotlinx.coroutines.TimeoutCancellationException }) return PlaybackFailureKind.NETWORK_SLOW
        return null
    }
}
