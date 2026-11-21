package com.metrolist.music.video

import androidx.media3.common.PlaybackException
import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.innertubex.extraction.StreamAttemptDiagnostic
import com.metrolist.innertubex.extraction.StreamDiagnostics
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.music.utils.InnerTubeXPlayer
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** The reasons a video does not play are told apart, the waits are bounded, and the two-stage extraction picks the right failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VideoFailureTest {
    private fun resolve(
        reason: StreamResolveException.Reason,
        vararg outcomes: String,
        cause: Throwable? = null,
    ) = StreamResolveException(
        reason = reason,
        message = "x",
        cause = cause,
        diagnostics = StreamDiagnostics(outcomes.map { StreamAttemptDiagnostic("VISIONOS", "VISIONOS_SABR__nopo", "ua", it) }, usedAuthenticatedWatchPage = false),
    )

    private fun kind(e: Throwable?) = VideoFailures.classify(e)

    // ------------------------------------------------------------------------------------------------ the network

    @Test
    fun `no network is OFFLINE, a slow one is TIMEOUT, both are transient and never counted`() {
        assertEquals(VideoFailureKind.OFFLINE, kind(UnknownHostException("rr1.googlevideo.com")))
        assertEquals(VideoFailureKind.OFFLINE, kind(ConnectException("failed to connect")))
        assertEquals(VideoFailureKind.TIMEOUT, kind(SocketTimeoutException("timeout")))
        assertEquals(VideoFailureKind.TIMEOUT, kind(VideoExtractTimeoutException("tokenless", 10_000)))
        assertEquals(VideoFailureKind.OFFLINE, kind(IOException("Unable to resolve host \"www.youtube.com\": No address associated with hostname")))
        assertEquals(VideoFailureKind.TIMEOUT, kind(IOException("Read timed out")))
        for (k in listOf(VideoFailureKind.OFFLINE, VideoFailureKind.TIMEOUT, VideoFailureKind.STALL)) {
            assertTrue("$k transient", k.transient)
            assertFalse("$k counts", k.counts)
        }
    }

    @Test
    fun `a network failure inside the library's wrapper is still the network`() {
        val wrapped = resolve(StreamResolveException.Reason.NETWORK, "request:UnknownHostException", cause = UnknownHostException("youtube.com"))
        assertEquals(VideoFailureKind.OFFLINE, kind(wrapped))
        val slow = resolve(StreamResolveException.Reason.NETWORK, "request:PlayerRequestTimeoutException", cause = IllegalStateException("odd"))
        assertEquals(VideoFailureKind.TIMEOUT, kind(slow))
        // a wrapper with an unrecognisable cause and nothing else to go on is UNKNOWN, not "offline"
        assertEquals(VideoFailureKind.UNKNOWN, kind(resolve(StreamResolveException.Reason.NETWORK, cause = IllegalStateException("odd"))))
    }

    // ------------------------------------------------------------------------------------------------ the library's answers

    @Test
    fun `a login required answer is the bot check and counts as a real failure`() {
        assertEquals(VideoFailureKind.BOT_CHECK, kind(resolve(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "playability:LOGIN_REQUIRED")))
        assertEquals(VideoFailureKind.BOT_CHECK, kind(resolve(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "playability:LOGIN_REQUIRED", "po_token_unavailable")))
        assertTrue(VideoFailureKind.BOT_CHECK.counts)
        assertFalse(VideoFailureKind.BOT_CHECK.transient)
    }

    @Test
    fun `a video that cannot be played is a property of the video, not of the app`() {
        assertEquals(VideoFailureKind.UNPLAYABLE, kind(resolve(StreamResolveException.Reason.UNAVAILABLE, "playability:UNPLAYABLE")))
        assertEquals(VideoFailureKind.UNPLAYABLE, kind(resolve(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "playability:UNPLAYABLE")))
        assertEquals(VideoFailureKind.AGE_GEO, kind(resolve(StreamResolveException.Reason.AGE_RESTRICTED, "playability:AGE_CHECK_REQUIRED")))
        assertEquals(VideoFailureKind.NO_FORMAT, kind(resolve(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "playable_response")))
        assertEquals(VideoFailureKind.NO_FORMAT, kind(InnerTubeXPlayer.NoSabrVideoException("no SABR video")))
        for (k in listOf(VideoFailureKind.UNPLAYABLE, VideoFailureKind.AGE_GEO, VideoFailureKind.NO_FORMAT, VideoFailureKind.UNSUPPORTED_CODEC)) {
            assertEquals(VideoFailureClass.VIDEO, k.family)
            assertFalse("$k counts", k.counts)
            assertFalse("$k transient", k.transient)
        }
    }

    @Test
    fun `the rejected clients of the selection are not read as answers`() {
        // "selection:login required" only says the client was never asked (it needs a login the app does not have)
        val e = resolve(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "selection:login required", "selection:GVS PO-token provider unavailable", "playable_response")
        assertEquals(VideoFailureKind.NO_FORMAT, kind(e))
        assertFalse(VideoFailures.describe(e).contains("selection:"))
    }

    @Test
    fun `http answers of the library`() {
        assertEquals(VideoFailureKind.FORBIDDEN, kind(InnerTubeHttpException("player", HttpStatusCode.Forbidden)))
        assertEquals(VideoFailureKind.TIMEOUT, kind(InnerTubeHttpException("player", HttpStatusCode.TooManyRequests)))
        assertEquals(VideoFailureKind.TIMEOUT, kind(InnerTubeHttpException("player", HttpStatusCode.BadGateway)))
        assertEquals(VideoFailureKind.UNPLAYABLE, kind(InnerTubeHttpException("player", HttpStatusCode.NotFound)))
    }

    // ------------------------------------------------------------------------------------------------ the stream

    @Test
    fun `the SABR stream says what is wrong`() {
        assertEquals(VideoFailureKind.ATTESTATION, kind(SabrProtocolException("pending", SabrFailureKind.ATTESTATION_REQUIRED)))
        assertEquals(VideoFailureKind.FORBIDDEN, kind(SabrProtocolException("http", httpStatusCode = 403)))
        assertEquals(VideoFailureKind.TIMEOUT, kind(SabrProtocolException("http", httpStatusCode = 503)))
        assertEquals(VideoFailureKind.TIMEOUT, kind(SabrProtocolException("http", httpStatusCode = 429)))
        assertEquals(VideoFailureKind.SABR_PROTOCOL, kind(SabrProtocolException("duplicate segment")))
        assertEquals(VideoFailureKind.SABR_PROTOCOL, kind(SabrProtocolException("reload", SabrFailureKind.RELOAD_PLAYER)))
    }

    @Test
    fun `an ExoPlayer error is read through its causes`() {
        val sabr = SabrProtocolException("http", httpStatusCode = 403)
        val dataSource = IOException("SABR stream failed: http", sabr)
        val exo = PlaybackException("io", dataSource, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals(VideoFailureKind.FORBIDDEN, kind(exo))
        // a vague ExoPlayer error with no cause: the stream failed
        assertEquals(VideoFailureKind.SABR_PROTOCOL, kind(PlaybackException("io", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
        assertEquals(VideoFailureKind.OFFLINE, kind(PlaybackException("io", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
        assertEquals(VideoFailureKind.TIMEOUT, kind(PlaybackException("io", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)))
        assertEquals(VideoFailureKind.DECODER, kind(PlaybackException("d", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)))
        assertEquals(VideoFailureKind.DECODER, kind(PlaybackException("d", null, PlaybackException.ERROR_CODE_DECODING_FAILED)))
        assertEquals(VideoFailureKind.DECODER, kind(PlaybackException("d", null, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES)))
        // a network error below the data source
        val net = PlaybackException("io", IOException("read failed", UnknownHostException("x")), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals(VideoFailureKind.OFFLINE, kind(net))
    }

    @Test
    fun `anything else is UNKNOWN and counts as a real failure`() {
        assertEquals(VideoFailureKind.UNKNOWN, kind(null))
        assertEquals(VideoFailureKind.UNKNOWN, kind(RuntimeException("boom")))
        assertTrue(VideoFailureKind.UNKNOWN.counts)
    }

    @Test
    fun `a cause chain that loops does not hang`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertEquals(VideoFailureKind.UNKNOWN, kind(a))
    }

    @Test
    fun `the log line holds the type and the clients' answers and no address`() {
        val e = resolve(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "playability:LOGIN_REQUIRED", "request:PlayerRequestTimeoutException")
        val line = VideoFailures.describe(e)
        assertTrue(line, line.contains("StreamResolveException"))
        assertTrue(line, line.contains("VISIONOS:playability:LOGIN_REQUIRED"))
        assertFalse(line, line.contains("http"))
        assertEquals("-", VideoFailures.describe(null))
    }

    // ------------------------------------------------------------------------------------------------ the waits

    @Test
    fun `retry waits grow 1 3 8 20 45 90 seconds and are jittered by 20 percent`() {
        val expected = listOf(1_000L, 3_000L, 8_000L, 20_000L, 45_000L, 90_000L)
        for ((i, step) in expected.withIndex()) {
            val low = VideoBackoff.retryDelayMs(i + 1, 0.0)
            val high = VideoBackoff.retryDelayMs(i + 1, 0.999)
            assertEquals(step * 8 / 10, low)
            assertTrue("$step: $high", high in (step * 119 / 100)..(step * 12 / 10))
        }
        // beyond the table the last step stays
        assertEquals(VideoBackoff.retryDelayMs(6, 0.5), VideoBackoff.retryDelayMs(50, 0.5))
        assertEquals(VideoBackoff.retryDelayMs(1, 0.5), VideoBackoff.retryDelayMs(0, 0.5))
    }

    @Test
    fun `the rest of the whole mode is 60 to 90 s, then doubles, and never passes 10 minutes`() {
        assertEquals(60_000L, VideoBackoff.cooldownMs(1, 0.0))
        assertTrue(VideoBackoff.cooldownMs(1, 0.9999) in 89_000L..90_000L)
        assertEquals(120_000L, VideoBackoff.cooldownMs(2, 0.0))
        assertEquals(240_000L, VideoBackoff.cooldownMs(3, 0.0))
        assertEquals(480_000L, VideoBackoff.cooldownMs(4, 0.0))
        assertEquals(600_000L, VideoBackoff.cooldownMs(5, 0.0))
        assertEquals(600_000L, VideoBackoff.cooldownMs(30, 0.9999))
        // random numbers outside [0, 1) are tolerated
        assertEquals(60_000L, VideoBackoff.cooldownMs(1, -3.0))
        assertEquals(90_000L, VideoBackoff.cooldownMs(1, 9.0))
    }

    @Test
    fun `the rest of one song starts at 2 minutes`() {
        assertEquals(120_000L, VideoBackoff.songCooldownMs(1, 0.0))
        assertEquals(240_000L, VideoBackoff.songCooldownMs(2, 0.0))
        assertEquals(600_000L, VideoBackoff.songCooldownMs(9, 0.5))
    }

    // ------------------------------------------------------------------------------------------------ the two stages

    private fun ok(v: String) = Result.success(v)

    private fun fail(e: Throwable): Result<String> = Result.failure(e)

    @Test
    fun `stage one's answer is final when it works, the second is never asked`() =
        runBlocking {
            var asked = 0
            val r = VideoStages.run(first = { ok("tokenless") }, second = { asked++; ok("fallback") }, firstLimitMs = 500, secondLimitMs = 500)
            assertEquals("tokenless", r.getOrNull())
            assertEquals(0, asked)
        }

    @Test
    fun `when stage one fails the fallback is asked and its answer wins`() =
        runBlocking {
            val stages = ArrayList<String>()
            val r =
                VideoStages.run(
                    first = { fail(resolve(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "playability:UNPLAYABLE")) },
                    second = { ok("fallback") },
                    firstLimitMs = 500,
                    secondLimitMs = 500,
                    onStage = { name, _, _ -> stages += name },
                )
            assertEquals("fallback", r.getOrNull())
            assertEquals(listOf("stage1", "stage2"), stages)
        }

    @Test
    fun `offline ends the chain at once, there is no point in a second stage`() =
        runBlocking {
            var asked = 0
            val r = VideoStages.run(first = { fail(UnknownHostException("x")) }, second = { asked++; ok("fallback") }, firstLimitMs = 500, secondLimitMs = 500)
            assertEquals(VideoFailureKind.OFFLINE, kind(r.exceptionOrNull()))
            assertEquals(0, asked)
        }

    @Test
    fun `a stage that takes too long is cut off with a TIMEOUT`() =
        runBlocking {
            val r = VideoStages.run(first = { delay(5_000); ok("late") }, second = { delay(5_000); ok("late") }, firstLimitMs = 40, secondLimitMs = 40)
            assertEquals(VideoFailureKind.TIMEOUT, kind(r.exceptionOrNull()))
        }

    @Test
    fun `a concrete failure beats a timeout and the later one wins between equals`() =
        runBlocking {
            // stage 1 meets the bot check, stage 2 times out: the bot check is what the owner needs to read
            val botCheck = resolve(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "playability:LOGIN_REQUIRED")
            val r1 = VideoStages.run(first = { fail(botCheck) }, second = { delay(5_000); ok("late") }, firstLimitMs = 500, secondLimitMs = 40)
            assertEquals(VideoFailureKind.BOT_CHECK, kind(r1.exceptionOrNull()))
            // stage 1 times out, stage 2 meets the bot check: still the bot check
            val r2 = VideoStages.run(first = { delay(5_000); ok("late") }, second = { fail(botCheck) }, firstLimitMs = 40, secondLimitMs = 500)
            assertEquals(VideoFailureKind.BOT_CHECK, kind(r2.exceptionOrNull()))
            // two concrete reasons: the later stage's answer
            val forbidden = SabrProtocolException("http", httpStatusCode = 403)
            val r3 = VideoStages.run(first = { fail(botCheck) }, second = { fail(forbidden) }, firstLimitMs = 500, secondLimitMs = 500)
            assertEquals(VideoFailureKind.FORBIDDEN, kind(r3.exceptionOrNull()))
        }
}
