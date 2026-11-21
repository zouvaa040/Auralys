/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The second extraction of video mode: asks the library for the SABR video of a song (the audio extraction is not
 * touched), checks that the phone can decode the format the library picked, and keeps the answer for a while.
 * Answers: [VideoStreamResult.Ready] (play it), [VideoStreamResult.NoVideo] (this song has no usable video, not an
 * error), [VideoStreamResult.Failed] (something went wrong, worth one retry).
 */

package com.metrolist.music.video

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.music.utils.InnerTubeXPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.Collections

/** What the library returned for one video, reduced to what the player needs. */
class VideoPlaybackSource(
    val bootstrap: SabrBootstrap,
    val mimeType: String?,
    val codecs: String?,
    val width: Int?,
    val height: Int?,
    val bitrate: Int?,
    val durationMs: Long,
    val contentLengthBytes: Long?,
    val expiresInSeconds: Int,
    /** Which profile of the library answered (for the log and the media-info line), null when not known. */
    val profileId: String? = null,
)

/** The one thing that needs the network and the library. Tests replace it. */
fun interface VideoExtractor {
    /** Fails with [InnerTubeXPlayer.NoSabrVideoException] when the library has no SABR video. */
    suspend fun extract(
        videoId: String,
        maxHeight: Int,
    ): Result<VideoPlaybackSource>
}

/**
 * The extraction through the app's extractor (the same bundle that resolves the audio), in two bounded stages:
 *
 * 1. the TOKENLESS profile alone (`VISIONOS_SABR`, no PO token, no WebView): the one that is proven to give the video;
 *    at most [STAGE1_MS];
 * 2. only if that failed (and the phone is not simply offline): the library's own order WITHOUT that profile (it may use
 *    the PO-token WebView, which is slow and can fail); at most [STAGE2_MS].
 *
 * Every stage that ends is logged under the `VideoMode` tag with the reason (the clients' own answers, no URLs). The
 * failure that comes back is the most informative one of the two, so the controller can tell a bot check from a timeout.
 */
object InnerTubeVideoExtractor : VideoExtractor {
    const val TOKENLESS_PROFILE = "VISIONOS_SABR"
    const val STAGE1_MS = 10_000L
    const val STAGE2_MS = 18_000L

    override suspend fun extract(
        videoId: String,
        maxHeight: Int,
    ): Result<VideoPlaybackSource> {
        // the sound goes first: a running audio extraction is never overtaken or held up by this one (REVIEW-2026-11-07 F13)
        VideoPriority.awaitIdle(InnerTubeXPlayer::audioExtractionRunning)
        return VideoStages
            .run(
                first = { InnerTubeXPlayer.playerResponseForVideo(videoId, maxHeight, clientOverrideId = TOKENLESS_PROFILE) },
                second = { InnerTubeXPlayer.playerResponseForVideo(videoId, maxHeight, excludedClients = setOf(TOKENLESS_PROFILE)) },
                firstLimitMs = STAGE1_MS,
                secondLimitMs = STAGE2_MS,
                onStage = { name, result, ms -> VideoLog.i(logLine(name, videoId, maxHeight, result, ms)) },
            ).map(::toSource)
    }

    private fun toSource(it: InnerTubeXPlayer.VideoPlaybackData) =
        VideoPlaybackSource(
            bootstrap = it.bootstrap,
            mimeType = it.mimeType,
            codecs = it.codecs,
            width = it.width,
            height = it.height,
            bitrate = it.bitrate,
            durationMs = it.durationMs,
            contentLengthBytes = it.contentLengthBytes,
            expiresInSeconds = it.expiresInSeconds,
            profileId = it.profileId,
        )

    private fun logLine(
        stage: String,
        videoId: String,
        maxHeight: Int,
        result: Result<InnerTubeXPlayer.VideoPlaybackData>,
        ms: Long,
    ): String {
        val data = result.getOrNull()
        return if (data != null) {
            "extract $stage ok video=$videoId cap=$maxHeight ms=$ms client=${data.clientName} profile=${data.profileId} ${data.width}x${data.height} ${data.mimeType}"
        } else {
            val error = result.exceptionOrNull()
            "extract $stage failed video=$videoId cap=$maxHeight ms=$ms kind=${VideoFailures.classify(error).key} ${VideoFailures.describe(error)}"
        }
    }
}

/** A video that can be played. */
class VideoStreamInfo(
    val videoId: String,
    val bootstrap: SabrBootstrap,
    val format: VideoFormatInfo,
    val durationMs: Long,
    val contentLengthBytes: Long?,
    val maxHeight: Int,
    val expiresAtMs: Long,
    val profileId: String? = null,
)

sealed interface VideoStreamResult {
    class Ready(
        val info: VideoStreamInfo,
    ) : VideoStreamResult

    class NoVideo(
        val reason: NoVideoReason,
    ) : VideoStreamResult

    class Failed(
        val error: Throwable?,
    ) : VideoStreamResult
}

/** Does the phone have a decoder for a MIME type at a size? */
fun interface DecoderSupport {
    fun supports(
        mime: String,
        width: Int,
        height: Int,
    ): Boolean

    /**
     * By what kind of decoder. A plain yes / no answer is taken as "hardware" (nothing is known about the CPU); the real
     * [DeviceDecoderSupport] tells hardware from software.
     */
    fun kind(
        mime: String,
        width: Int,
        height: Int,
    ): DecoderKind = if (supports(mime, width, height)) DecoderKind.HARDWARE else DecoderKind.NONE
}

/** The decoders of this phone, asked through MediaCodecList (answers are cached). */
class DeviceDecoderSupport : DecoderSupport {
    private val cache = Collections.synchronizedMap(HashMap<String, DecoderKind>())

    override fun supports(
        mime: String,
        width: Int,
        height: Int,
    ): Boolean = kind(mime, width, height) != DecoderKind.NONE

    override fun kind(
        mime: String,
        width: Int,
        height: Int,
    ): DecoderKind = cache.getOrPut("$mime/$width/$height") { query(mime, width, height) }

    private fun query(
        mime: String,
        width: Int,
        height: Int,
    ): DecoderKind =
        runCatching {
            val capable =
                MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    .codecInfos
                    .filter { info ->
                        !info.isEncoder &&
                            info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                            runCatching {
                                val video = info.getCapabilitiesForType(mime).videoCapabilities
                                video == null || width <= 0 || height <= 0 || video.isSizeSupported(width, height)
                            }.getOrDefault(false)
                    }.map { info ->
                        DecoderCandidate(info.name, if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else null)
                    }
            DecoderChoice.kindOf(capable)
        }.getOrDefault(DecoderKind.NONE)
}

class VideoStreamRepository(
    private val extractor: VideoExtractor,
    private val decoders: DecoderSupport,
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val maxEntries: Int = 16,
) {
    private data class Key(
        val videoId: String,
        val maxHeight: Int,
    )

    private class Entry(
        val result: VideoStreamResult,
        val expiresAtMs: Long,
    )

    private val cache: MutableMap<Key, Entry> =
        Collections.synchronizedMap(
            object : LinkedHashMap<Key, Entry>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?) = size > maxEntries
            },
        )
    private val inFlight = HashMap<Key, Deferred<VideoStreamResult>>()

    /** What is known without asking the network; null when nothing (valid) is stored. */
    fun peek(
        videoId: String,
        maxHeight: Int,
    ): VideoStreamResult? {
        val key = Key(videoId, maxHeight)
        val entry = cache[key] ?: return null
        if (now() >= entry.expiresAtMs) {
            cache.remove(key)
            return null
        }
        return entry.result
    }

    /** Forget the answers for [videoId] (a stream that failed has to be asked for again). */
    fun invalidate(videoId: String) {
        synchronized(cache) { cache.keys.removeAll { it.videoId == videoId } }
    }

    /**
     * The answer for [videoId]. Waiting callers can leave (cancel) without stopping the extraction, which other
     * callers may be waiting for; its answer lands in the cache. Failures are never cached.
     */
    suspend fun get(
        videoId: String,
        maxHeight: Int,
        forceFresh: Boolean = false,
    ): VideoStreamResult {
        val key = Key(videoId, maxHeight)
        if (forceFresh) invalidate(videoId) else peek(videoId, maxHeight)?.let { return it }
        val job: Deferred<VideoStreamResult> =
            synchronized(inFlight) {
                inFlight[key]?.let { return@synchronized it }
                val created = scope.async { resolve(key) }
                inFlight[key] = created
                created.invokeOnCompletion { synchronized(inFlight) { if (inFlight[key] === created) inFlight.remove(key) } }
                created
            }
        return job.await()
    }

    private suspend fun extractSafely(
        videoId: String,
        maxHeight: Int,
    ): Result<VideoPlaybackSource> =
        try {
            extractor.extract(videoId, maxHeight)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    private suspend fun resolve(key: Key): VideoStreamResult {
        val first = answerFor(key, key.maxHeight, extractSafely(key.videoId, key.maxHeight), secondTry = false)
        val answer: VideoStreamResult =
            first
                ?: run {
                    // Only a software decoder (the CPU) can play the picked format and the picture is sharp: ask once more
                    // for 360 p, which a CPU can keep up with (REVIEW-2026-11-07 F3). Still too sharp: no video.
                    val low = DecoderChoice.SOFTWARE_MAX_HEIGHT
                    answerFor(key, low, extractSafely(key.videoId, low), secondTry = true)
                        ?: VideoStreamResult.NoVideo(NoVideoReason.UNSUPPORTED_CODEC)
                }
        val ttl =
            when (answer) {
                is VideoStreamResult.Ready -> (answer.info.expiresAtMs - now()).coerceIn(0L, MAX_READY_TTL_MS)
                is VideoStreamResult.NoVideo -> NO_VIDEO_TTL_MS
                is VideoStreamResult.Failed -> 0L
            }
        if (ttl > 0L) cache[key] = Entry(answer, now() + ttl)
        return answer
    }

    /**
     * The answer for one extraction result, or null when the answer is "ask again at 360 p" (a software decoder only, a
     * picture sharper than [DecoderChoice.SOFTWARE_MAX_HEIGHT], and this was the first try with a taller cap).
     */
    private fun answerFor(
        key: Key,
        askedMaxHeight: Int,
        result: Result<VideoPlaybackSource>,
        secondTry: Boolean,
    ): VideoStreamResult? {
        val source = result.getOrNull()
        if (source != null) return classify(key, askedMaxHeight, source, secondTry)
        val error = result.exceptionOrNull()
        return when {
            error is InnerTubeXPlayer.NoSabrVideoException -> VideoStreamResult.NoVideo(NoVideoReason.NO_STREAM)
            error is StreamResolveException && error.reason in UNAVAILABLE_REASONS ->
                VideoStreamResult.NoVideo(NoVideoReason.UNAVAILABLE)
            else -> VideoStreamResult.Failed(error)
        }
    }

    private fun classify(
        key: Key,
        askedMaxHeight: Int,
        source: VideoPlaybackSource,
        secondTry: Boolean,
    ): VideoStreamResult? {
        val format =
            VideoFormats.parse(source.mimeType, source.codecs, source.width, source.height, source.bitrate)
                ?: return VideoStreamResult.NoVideo(NoVideoReason.UNKNOWN_FORMAT)
        when (decoders.kind(format.codec.decoderMime, format.width, format.height)) {
            DecoderKind.NONE -> return VideoStreamResult.NoVideo(NoVideoReason.UNSUPPORTED_CODEC)
            DecoderKind.SOFTWARE ->
                if (DecoderChoice.tooSharpForSoftware(format.height, askedMaxHeight)) {
                    // sharper than a CPU can play: another try at 360 p when that was not asked yet, else no video
                    return if (!secondTry && askedMaxHeight > DecoderChoice.SOFTWARE_MAX_HEIGHT) null else VideoStreamResult.NoVideo(NoVideoReason.UNSUPPORTED_CODEC)
                }
            DecoderKind.HARDWARE -> Unit
        }
        val ttlMs = (source.expiresInSeconds * 1000L - EXPIRY_MARGIN_MS).coerceAtLeast(MIN_READY_TTL_MS)
        return VideoStreamResult.Ready(
            VideoStreamInfo(
                videoId = key.videoId,
                bootstrap = source.bootstrap,
                format = format,
                durationMs = source.durationMs,
                contentLengthBytes = source.contentLengthBytes,
                maxHeight = askedMaxHeight,
                expiresAtMs = now() + ttlMs,
                profileId = source.profileId,
            ),
        )
    }

    companion object {
        private val UNAVAILABLE_REASONS =
            setOf(
                StreamResolveException.Reason.AGE_RESTRICTED,
                StreamResolveException.Reason.UNAVAILABLE,
                StreamResolveException.Reason.EXPLICIT_UNSUPPORTED,
                StreamResolveException.Reason.NO_MUSIC_VIDEO,
            )

        /** A song without video is not asked again for this long. */
        const val NO_VIDEO_TTL_MS = 10 * 60 * 1000L

        /** The stream address is used until this long before it expires, never for more than the maximum. */
        const val EXPIRY_MARGIN_MS = 10 * 60 * 1000L
        const val MIN_READY_TTL_MS = 60 * 1000L
        const val MAX_READY_TTL_MS = 2 * 60 * 60 * 1000L

        val shared: VideoStreamRepository by lazy { VideoStreamRepository(InnerTubeVideoExtractor, DeviceDecoderSupport()) }

        /** The sharpness cap for the next start: 360 p on a metered network, with the data saver and on a low-RAM phone. */
        fun maxHeightFor(
            context: Context,
            metered: Boolean,
            dataSaverOn: Boolean,
            dataSaverMobileOnly: Boolean,
        ): Int {
            val lowRam =
                (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.isLowRamDevice == true
            return VideoQuality.maxHeight(
                metered = metered,
                dataSaverActive = VideoQuality.dataSaverActive(dataSaverOn, dataSaverMobileOnly, metered),
                lowRam = lowRam,
            )
        }
    }
}
