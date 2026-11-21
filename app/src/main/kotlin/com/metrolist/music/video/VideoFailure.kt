/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Why a video did not play, how long to wait before trying again, and what the owner can read about it.
 *
 * Before this file every failure was the same failure: one retry, then "no video for the rest of the session" and, after
 * three songs, "no video until the app restarts". A dropped network, a slow phone and a real bug were treated alike, which
 * is why video mode "sometimes works". Now:
 * - [VideoFailures.classify] names the reason (pure, unit tested);
 * - [VideoFailureKind] says whether it is a property of the VIDEO (no clip, not playable), of the NETWORK (try again soon,
 *   never punish) or of the PATH (a real failure: one fresh try, then a time-limited cool-down);
 * - [VideoBackoff] gives the waits (jittered, growing, capped), all based on the clock, nothing lasts until a restart;
 * - [VideoLog] writes every state change under ONE Timber tag, `VideoMode`, without URLs or tokens;
 * - [VideoDiagnostics] keeps the last status per song for the line in the media-info dialog.
 */

package com.metrolist.music.video

import androidx.media3.common.PlaybackException
import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.music.utils.InnerTubeXPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** The family a failure belongs to; it decides what happens next. */
enum class VideoFailureClass {
    /** A property of this video: nothing to play, nothing to retry now. Not a failure of the app. */
    VIDEO,

    /** The network: wait and try again, never punish the song or the session. */
    NETWORK,

    /** The path is broken (a refusal, a protocol error, a decoder): one fresh try, then a cool-down. */
    PATH,
}

/** Short, loggable reasons. [key] is the log word and the key of the status text. */
enum class VideoFailureKind(
    val key: String,
    val family: VideoFailureClass,
) {
    OFFLINE("offline", VideoFailureClass.NETWORK),
    TIMEOUT("timeout", VideoFailureClass.NETWORK),
    STALL("stall", VideoFailureClass.NETWORK),
    NO_FORMAT("no_format", VideoFailureClass.VIDEO),
    UNPLAYABLE("unplayable", VideoFailureClass.VIDEO),
    AGE_GEO("age_geo", VideoFailureClass.VIDEO),
    UNSUPPORTED_CODEC("codec", VideoFailureClass.VIDEO),
    BOT_CHECK("bot_check", VideoFailureClass.PATH),
    FORBIDDEN("forbidden", VideoFailureClass.PATH),
    ATTESTATION("attestation", VideoFailureClass.PATH),
    SABR_PROTOCOL("sabr", VideoFailureClass.PATH),
    DECODER("decoder", VideoFailureClass.PATH),
    UNKNOWN("unknown", VideoFailureClass.PATH),
    ;

    /** Wait and try again soon, with growing waits, without punishing anything. */
    val transient: Boolean get() = family == VideoFailureClass.NETWORK

    /** Counts toward the cool-down of the whole video mode (only real failures of the path do). */
    val counts: Boolean get() = family == VideoFailureClass.PATH
}

object VideoFailures {
    /** Walks the cause chain of [error] and names the first concrete reason it recognises. */
    fun classify(error: Throwable?): VideoFailureKind {
        if (error == null) return VideoFailureKind.UNKNOWN
        val seen = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            val kind = classifyOne(current)
            // a vague wrapper (UNKNOWN) gives way to a concrete reason further down the chain
            if (kind != null && kind != VideoFailureKind.UNKNOWN) return kind
            current = current.cause
        }
        // an ExoPlayer error with no recognisable cause: the stream itself failed
        return if (error is PlaybackException) VideoFailureKind.SABR_PROTOCOL else VideoFailureKind.UNKNOWN
    }

    /**
     * One short line about [error] for the log: its type and, for a failed extraction, what each client answered
     * ("VISIONOS:playability:LOGIN_REQUIRED"). No messages that could hold an address, no tokens.
     */
    fun describe(error: Throwable?): String {
        if (error == null) return "-"
        val parts = ArrayList<String>()
        parts += error.javaClass.simpleName
        val resolve = generateSequence(error) { it.cause }.take(6).filterIsInstance<StreamResolveException>().firstOrNull()
        if (resolve != null) {
            parts += "reason=${resolve.reason}"
            val attempts = resolve.diagnostics?.attempts.orEmpty().filterNot { it.outcome.startsWith("selection:") }
            if (attempts.isNotEmpty()) parts += attempts.take(8).joinToString(prefix = "[", postfix = "]", separator = ",") { "${it.clientName}:${it.outcome}" }
        }
        generateSequence(error) { it.cause }.take(6).filterIsInstance<SabrProtocolException>().firstOrNull()?.let {
            parts += "sabr=${it.kind} http=${it.httpStatusCode ?: "-"}"
        }
        return parts.joinToString(" ")
    }

    private fun classifyOne(e: Throwable): VideoFailureKind? =
        when (e) {
            is InnerTubeXPlayer.NoSabrVideoException -> VideoFailureKind.NO_FORMAT
            is VideoExtractTimeoutException -> VideoFailureKind.TIMEOUT
            is StreamResolveException -> fromResolve(e)
            is SabrProtocolException -> fromSabr(e)
            is InnerTubeHttpException -> fromHttp(e.status.value)
            is PlaybackException -> fromPlayback(e)
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> VideoFailureKind.OFFLINE
            is SocketTimeoutException -> VideoFailureKind.TIMEOUT
            is SocketException -> VideoFailureKind.OFFLINE
            is kotlinx.coroutines.TimeoutCancellationException -> VideoFailureKind.TIMEOUT
            is IOException -> byName(e) ?: byMessage(e.message)
            else -> byName(e)
        }

    private fun byName(e: Throwable): VideoFailureKind? {
        val name = e.javaClass.simpleName
        return when {
            name.contains("Timeout", ignoreCase = true) -> VideoFailureKind.TIMEOUT
            name.contains("UnknownHost", ignoreCase = true) -> VideoFailureKind.OFFLINE
            else -> null
        }
    }

    private fun byMessage(message: String?): VideoFailureKind? {
        val m = message?.lowercase() ?: return null
        return when {
            "unable to resolve host" in m || "network is unreachable" in m || "no address associated" in m -> VideoFailureKind.OFFLINE
            "timed out" in m || "timeout" in m -> VideoFailureKind.TIMEOUT
            else -> null
        }
    }

    private fun fromResolve(e: StreamResolveException): VideoFailureKind {
        // the attempts say what each client answered: the worst clue in them is the reason
        val outcomes = e.diagnostics?.attempts?.map { it.outcome.lowercase() }.orEmpty().filterNot { it.startsWith("selection:") }
        fun any(vararg words: String) = outcomes.any { o -> words.any { it in o } }
        return when {
            e.reason == StreamResolveException.Reason.AGE_RESTRICTED || any("age_check", "age_verification", "content_check") -> VideoFailureKind.AGE_GEO
            any("login_required", "po_token_unavailable", "sign in") -> VideoFailureKind.BOT_CHECK
            e.reason == StreamResolveException.Reason.NETWORK ->
                classify(e.cause).takeIf { it != VideoFailureKind.UNKNOWN } ?: if (any("timeout")) VideoFailureKind.TIMEOUT else VideoFailureKind.UNKNOWN
            any("timeout") -> VideoFailureKind.TIMEOUT
            e.reason == StreamResolveException.Reason.UNAVAILABLE ||
                e.reason == StreamResolveException.Reason.EXPLICIT_UNSUPPORTED ||
                e.reason == StreamResolveException.Reason.NO_MUSIC_VIDEO ||
                any("unplayable", "video_unavailable") -> VideoFailureKind.UNPLAYABLE
            e.reason == StreamResolveException.Reason.NO_PLAYABLE_STREAM -> VideoFailureKind.NO_FORMAT
            else -> VideoFailureKind.UNKNOWN
        }
    }

    private fun fromHttp(status: Int): VideoFailureKind =
        when {
            status == 403 -> VideoFailureKind.FORBIDDEN
            status == 404 || status == 410 -> VideoFailureKind.UNPLAYABLE
            status == 408 || status == 429 || status >= 500 -> VideoFailureKind.TIMEOUT
            else -> VideoFailureKind.UNKNOWN
        }

    private fun fromSabr(e: SabrProtocolException): VideoFailureKind =
        when {
            e.kind == SabrFailureKind.ATTESTATION_REQUIRED -> VideoFailureKind.ATTESTATION
            e.httpStatusCode == 403 -> VideoFailureKind.FORBIDDEN
            e.httpStatusCode == 429 || (e.httpStatusCode ?: 0) >= 500 -> VideoFailureKind.TIMEOUT
            else -> VideoFailureKind.SABR_PROTOCOL
        }

    private fun fromPlayback(e: PlaybackException): VideoFailureKind =
        when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> VideoFailureKind.OFFLINE
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> VideoFailureKind.TIMEOUT
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            -> VideoFailureKind.DECODER
            // the data source wraps the SABR failure in an IOException: the cause chain has the concrete reason
            else -> VideoFailureKind.UNKNOWN
        }
}

/** Thrown when the extraction of a video takes longer than its budget; the reason is [VideoFailureKind.TIMEOUT]. */
class VideoExtractTimeoutException(
    val stage: String,
    val limitMs: Long,
) : java.io.IOException("video extraction ($stage) took longer than $limitMs ms")

/** Thrown by the host when the search for the other recording could not be answered (slow or no network): try again later. */
class CounterpartLookupFailed(
    val kind: VideoFailureKind,
) : RuntimeException("counterpart lookup failed: ${kind.key}")

/** The waits. Pure functions of the attempt number and a random number in [0, 1), so tests can pin them. */
object VideoBackoff {
    /** Waits after the 1st, 2nd ... failure of a NETWORK kind. */
    val RETRY_STEPS_MS = longArrayOf(1_000L, 3_000L, 8_000L, 20_000L, 45_000L, 90_000L)

    /** After this many network failures in a row the song is left alone for a while. */
    const val MAX_NETWORK_ATTEMPTS = 6

    /** The first cool-down; it doubles with every strike. */
    const val COOLDOWN_BASE_MS = 60_000L
    const val COOLDOWN_MAX_MS = 600_000L

    /** A song that failed for real is left alone this long (doubling per strike, capped). */
    const val SONG_COOLDOWN_BASE_MS = 120_000L

    /** A song without a usable video is not asked again for this long. */
    const val NO_VIDEO_TTL_MS = 10 * 60 * 1000L

    /** The wait before attempt number [failures] + 1: the step for that failure, 80 to 120 percent of it. */
    fun retryDelayMs(
        failures: Int,
        random: Double,
    ): Long {
        val step = RETRY_STEPS_MS[(failures - 1).coerceIn(0, RETRY_STEPS_MS.size - 1)]
        return (step * (0.8 + 0.4 * random.coerceIn(0.0, 1.0))).toLong()
    }

    /** How long video mode rests after strike number [strikes] (1 = 60 to 90 s, 2 = 120 to 180 s ... never more than 10 min). */
    fun cooldownMs(
        strikes: Int,
        random: Double,
    ): Long = scaled(COOLDOWN_BASE_MS, strikes, random, COOLDOWN_MAX_MS)

    /** How long one song rests after strike number [strikes] for that song. */
    fun songCooldownMs(
        strikes: Int,
        random: Double,
    ): Long = scaled(SONG_COOLDOWN_BASE_MS, strikes, random, COOLDOWN_MAX_MS)

    private fun scaled(
        base: Long,
        strikes: Int,
        random: Double,
        max: Long,
    ): Long {
        val doublings = (strikes - 1).coerceIn(0, 10)
        val raw = base shl doublings
        return (raw * (1.0 + 0.5 * random.coerceIn(0.0, 1.0))).toLong().coerceAtMost(max)
    }
}

/** ONE Timber tag for the whole video mode: filter logcat on `VideoMode`. No URLs, no tokens, no signatures. */
object VideoLog {
    const val TAG = "VideoMode"

    fun i(message: String) {
        Timber.tag(TAG).i(message)
    }

    fun w(message: String) {
        Timber.tag(TAG).w(message)
    }

    fun d(message: String) {
        Timber.tag(TAG).d(message)
    }
}

/** What the media-info dialog says about the video of a song. */
sealed interface VideoStatus {
    /** Nothing was asked yet. */
    data object Unknown : VideoStatus

    /** The other recording is being searched for or the stream is being prepared. */
    data object Checking : VideoStatus

    /** A video was found and can be played. */
    data object Available : VideoStatus

    /** The search ended without a clip that matches this song. */
    data object NoMatch : VideoStatus

    /** The last try failed for [kind]; [retrying] = the app tries again by itself. */
    data class Failed(
        val kind: VideoFailureKind,
        val retrying: Boolean,
    ) : VideoStatus
}

/** The last status per song, newest last. Written by the controller, read by the dialog. */
object VideoDiagnostics {
    private const val MAX = 64
    private val map = LinkedHashMap<String, VideoStatus>()
    private val _version = MutableStateFlow(0L)

    /** Changes with every write: the dialog reads [statusFor] again when it changes. */
    val version: StateFlow<Long> = _version.asStateFlow()

    @Synchronized
    fun set(
        songId: String,
        status: VideoStatus,
    ) {
        if (songId.isBlank()) return
        if (map[songId] == status) return
        map.remove(songId)
        map[songId] = status
        while (map.size > MAX) map.remove(map.keys.first())
        _version.value = _version.value + 1
    }

    @Synchronized
    fun statusFor(songId: String): VideoStatus = map[songId] ?: VideoStatus.Unknown

    @Synchronized
    fun clear() {
        map.clear()
        _version.value = _version.value + 1
    }
}

/**
 * Two bounded stages for one extraction: [run] asks the first (the proven, tokenless one) with its own time limit and, only
 * if that failed and the phone is not simply offline, the second (the library's order, which may use the slow PO-token
 * WebView) with its own limit. The failure that comes back is the most informative of the two (a timeout says nothing about
 * the video; a concrete answer of a client does). [onStage] is told about every stage that ended, for the log.
 */
object VideoStages {
    suspend fun <T> run(
        first: suspend () -> Result<T>,
        second: suspend () -> Result<T>,
        firstLimitMs: Long,
        secondLimitMs: Long,
        onStage: (name: String, result: Result<T>, ms: Long) -> Unit = { _, _, _ -> },
        clockNs: () -> Long = System::nanoTime,
    ): Result<T> {
        val t0 = clockNs()
        val one = bounded("tokenless", firstLimitMs, first)
        onStage("stage1", one, (clockNs() - t0) / 1_000_000L)
        if (one.isSuccess) return one
        if (VideoFailures.classify(one.exceptionOrNull()) == VideoFailureKind.OFFLINE) return one
        val t1 = clockNs()
        val two = bounded("fallback", secondLimitMs, second)
        onStage("stage2", two, (clockNs() - t1) / 1_000_000L)
        if (two.isSuccess) return two
        return if (rank(two) >= rank(one)) two else one
    }

    private suspend fun <T> bounded(
        name: String,
        limitMs: Long,
        block: suspend () -> Result<T>,
    ): Result<T> = kotlinx.coroutines.withTimeoutOrNull(limitMs) { block() } ?: Result.failure(VideoExtractTimeoutException(name, limitMs))

    private fun rank(r: Result<*>): Int =
        when (VideoFailures.classify(r.exceptionOrNull())) {
            VideoFailureKind.TIMEOUT, VideoFailureKind.UNKNOWN -> 0
            VideoFailureKind.NO_FORMAT -> 1
            else -> 2
        }
}
