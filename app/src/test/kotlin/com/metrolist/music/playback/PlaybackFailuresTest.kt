/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.upstream.Loader
import com.metrolist.innertubex.extraction.StreamResolveException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The kind of a playback error from its WHOLE cause chain. ExoPlayer hands a resolver failure to the service as
 * ExoPlaybackException(IO_UNSPECIFIED) <- UnexpectedLoaderException <- the resolver's exception; the old code looked at the
 * top code only, so "no internet" during the extraction was taken for a broken client and the song was skipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaybackFailuresTest {
    /** What ExoPlayer delivers for an exception thrown inside the resolver. */
    private fun fromResolver(thrown: Throwable): PlaybackException =
        ExoPlaybackException.createForSource(Loader.UnexpectedLoaderException(thrown), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

    private fun http(code: Int): PlaybackException {
        val spec = DataSpec.Builder().setUri("https://rr1---sn.googlevideo.com/videoplayback").build()
        val cause = HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), spec, ByteArray(0))
        return ExoPlaybackException.createForSource(cause, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
    }

    @Test
    fun `the resolver's own kind survives every wrapper`() {
        for (kind in PlaybackFailureKind.entries) {
            assertEquals(kind, PlaybackFailures.classify(fromResolver(StreamResolveFailure(kind, "x"))))
        }
    }

    @Test
    fun `the old wrapping - no internet inside the extraction is a network error, not a broken client`() {
        val old = PlaybackException("Tidak ada koneksi jaringan", UnknownHostException("youtubei.googleapis.com"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertEquals(PlaybackFailureKind.NETWORK_DOWN, PlaybackFailures.classify(fromResolver(old)))
        val slow = PlaybackException("Waktu habis", SocketTimeoutException("read timed out"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        assertEquals(PlaybackFailureKind.NETWORK_SLOW, PlaybackFailures.classify(fromResolver(slow)))
    }

    @Test
    fun `http statuses of the stream`() {
        assertEquals(PlaybackFailureKind.STREAM_REJECTED, PlaybackFailures.classify(http(403)))
        assertEquals(PlaybackFailureKind.STREAM_REJECTED, PlaybackFailures.classify(http(410)))
        assertEquals(PlaybackFailureKind.CACHE_MISMATCH, PlaybackFailures.classify(http(416)))
        assertEquals(PlaybackFailureKind.NETWORK_SLOW, PlaybackFailures.classify(http(503)))
        assertEquals(403, PlaybackFailures.httpStatus(http(403)))
    }

    @Test
    fun `what the extraction library says`() {
        fun lib(
            reason: StreamResolveException.Reason,
            cause: Throwable? = null,
        ) = PlaybackFailures.classify(fromResolver(StreamResolveException(reason, "no stream", cause)))
        assertEquals(PlaybackFailureKind.SONG_UNAVAILABLE, lib(StreamResolveException.Reason.UNAVAILABLE))
        assertEquals(PlaybackFailureKind.SONG_UNAVAILABLE, lib(StreamResolveException.Reason.AGE_RESTRICTED))
        assertEquals(PlaybackFailureKind.STREAM_REJECTED, lib(StreamResolveException.Reason.NO_PLAYABLE_STREAM))
        assertEquals(PlaybackFailureKind.NETWORK_SLOW, lib(StreamResolveException.Reason.NETWORK, SocketTimeoutException()))
        assertEquals(PlaybackFailureKind.NETWORK_DOWN, lib(StreamResolveException.Reason.NETWORK, UnknownHostException()))
    }

    @Test
    fun `phone side errors`() {
        val track = PlaybackException("AudioTrack init failed", null, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED)
        assertEquals(PlaybackFailureKind.AUDIO_OUTPUT, PlaybackFailures.classify(track))
        val decoder = PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        assertEquals(PlaybackFailureKind.DECODER, PlaybackFailures.classify(decoder))
        val file = PlaybackException("gone", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        assertEquals(PlaybackFailureKind.LOCAL_FILE, PlaybackFailures.classify(file, isLocalSong = true))
        assertEquals(PlaybackFailureKind.CACHE_MISMATCH, PlaybackFailures.classify(file))
    }

    @Test
    fun `a refused address is not asked again by the loader, a dropped connection still is`() {
        val spec = DataSpec.Builder().setUri("https://rr1---sn.googlevideo.com/videoplayback").build()
        fun info(
            e: IOException,
            count: Int,
        ) = androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo(
            androidx.media3.exoplayer.source.LoadEventInfo(1L, spec, 0L),
            androidx.media3.exoplayer.source.MediaLoadData(androidx.media3.common.C.DATA_TYPE_MEDIA),
            e,
            count,
        )
        val policy = StreamLoadErrorPolicy()
        for (code in listOf(401, 403, 404, 410)) {
            val refused = HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), spec, ByteArray(0))
            assertEquals(androidx.media3.common.C.TIME_UNSET, policy.getRetryDelayMsFor(info(refused, 1)))
        }
        assertEquals(1_000L, policy.getRetryDelayMsFor(info(IOException("connection reset"), 2)))
        val busy = HttpDataSource.InvalidResponseCodeException(503, null, null, emptyMap(), spec, ByteArray(0))
        assertEquals(1_000L, policy.getRetryDelayMsFor(info(busy, 2)))
    }

    @Test
    fun `a range cut short by the server is a refused stream (fresh address), not the end of the song`() {
        val cut = ExoPlaybackException.createForSource(RangeChunkDataSource.RangeEndedEarlyException(900_000), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals(PlaybackFailureKind.STREAM_REJECTED, PlaybackFailures.classify(cut))
        val plainIo = ExoPlaybackException.createForSource(IOException("?"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals(PlaybackFailureKind.STREAM_REJECTED, PlaybackFailures.classify(plainIo))
    }
}
