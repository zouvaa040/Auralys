package com.metrolist.music.utils

import android.content.Context
import android.net.ConnectivityManager
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.Thumbnail
import com.metrolist.innertube.models.Thumbnails
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality as InnerTubeXAudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.potoken.PoTokenGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import kotlin.time.Clock

/** The sole stream extraction entry point for the Android app. */
object InnerTubeXPlayer {
    private const val TAG = "InnerTubeXPlayer"
    private const val STREAM_CLIENT_FAILURE_TTL_MS = 5 * 60 * 1000L
    private const val DEFAULT_STREAM_TTL_SECONDS = 5 * 60

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var currentBundle: ExtractionBundle? = null

    private val bundleMutex = Mutex()
    private val streamClientFailures = java.util.concurrent.ConcurrentHashMap<String, FailedStreamClients>()

    @Synchronized
    fun initialize(context: Context) {
        if (applicationContext == null) applicationContext = context.applicationContext
    }

    suspend fun prewarm() = bundle().extractor.prewarm()

    /** How many audio extractions are running now; the video mode waits for 0 before it starts an extraction of its own. */
    private val audioExtractions = java.util.concurrent.atomic.AtomicInteger(0)

    fun audioExtractionRunning(): Boolean = audioExtractions.get() > 0

    /**
     * The audio extraction. Behaviour is exactly that of [extractForPlayback] (the former body of this function); the only
     * addition is the counter around it (REVIEW-2026-11-07 F13).
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        contentHints: ContentHints = ContentHints(),
        allowBoundedRange: Boolean = true,
        /** Title, artists and length of the song, for the last-resort alternative (see [AlternativeMatch]); null = none. */
        song: AlternativeMatch.Song? = null,
    ): Result<PlaybackData> {
        audioExtractions.incrementAndGet()
        try {
            return extractForPlayback(videoId, playlistId, audioQuality, connectivityManager, contentHints, allowBoundedRange, song)
        } finally {
            audioExtractions.decrementAndGet()
        }
    }

    private suspend fun extractForPlayback(
        videoId: String,
        playlistId: String?,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        contentHints: ContentHints,
        allowBoundedRange: Boolean,
        song: AlternativeMatch.Song?,
    ): Result<PlaybackData> {
        val hints =
            contentHints.copy(
                // Round 4.3.4, videos C/D (Snooze, Dracula, The Less I Know The Better, Loser: all EXPLICIT, stuck at
                // 0:00): with isExplicit=true the library skips the tokenless direct clients and goes straight to
                // WEB_REMIX + PO token + cipher, 18-28 s per song on the emulator (a WebView and a JS solver that
                // can also hang on a phone). The direct clients play these songs (emulator: first audio 2-5 s), so an
                // explicit song tries them first; when they give nothing the library still falls through to the
                // full path (watch config, cipher, signed-in retry) by itself. See explicitFirstPass.
                isExplicit = explicitFirstPass(contentHints.isExplicit),
                // with isExplicit=null the library would add a WEB_KIDS attempt after everything failed, a step the
                // explicit path never took (an explicit song is not kids content): kept off (review 2026-11-12, 22)
                isKidsContent = if (contentHints.isExplicit == true) contentHints.isKidsContent ?: false else contentHints.isKidsContent,
                isUploaded =
                    contentHints.isUploaded == true ||
                        playlistId == "MLPT" ||
                        playlistId?.contains("MLPT") == true,
            ).withStreamCapabilities(
                allowHls = false,
                allowSabr = false,
                allowBoundedRange = allowBoundedRange,
            )
        val excludedClients = failedStreamClients(videoId)
        val quality = audioQuality.toInnerTubeX(connectivityManager)
        var timedOut = false
        val failure: Throwable =
            try {
                // bounded (round 4.3.5): live, with the direct client refused the library's token + cipher path ran 58-114 s
                // on the JVM (no WebView token, no QuickJS = a phone where both fail) before giving up, longer than the
                // player's whole extraction budget; the rescue below then never ran
                val data =
                    withTimeoutOrNull(FIRST_PASS_TIMEOUT_MS) { extractOnce(videoId, hints, excludedClients, quality) }
                        ?: run {
                            timedOut = true
                            throw StreamResolveException(
                                reason = StreamResolveException.Reason.NO_PLAYABLE_STREAM,
                                message = "No stream within ${FIRST_PASS_TIMEOUT_MS / 1000} s",
                            )
                        }
                return Result.success(data)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error
            } catch (error: LinkageError) {
                // the JS runtime of the cipher path could not load on this phone (QuickJS native library): not the song
                error
            }
        if (hints.isUploaded != true && isRescuable(failure)) {
            rescueWithDirectClients(videoId, excludedClients, quality, allowBoundedRange)?.let { return Result.success(it) }
            if (song != null) playAlternative(song, quality, allowBoundedRange)?.let { return Result.success(it) }
        }
        // a pass that only ran out of time stays a slow connection for the service (retried, never "no audio")
        if (timedOut) return Result.failure(java.net.SocketTimeoutException("stream extraction took longer than ${FIRST_PASS_TIMEOUT_MS / 1000} s"))
        return Result.failure(finalFailure(contentHints.isExplicit, failure))
    }

    private suspend fun extractOnce(
        videoId: String,
        hints: ContentHints,
        excludedClients: Set<String>,
        quality: InnerTubeXAudioQuality,
    ): PlaybackData {
        val stream =
            bundle().extractor.extract(
                videoId = videoId,
                hints = hints,
                excludedClients = excludedClients,
                audioQuality = quality,
                clientPlaybackNonce = generateClientPlaybackNonce(),
            ) ?: throw StreamResolveException(
                reason = StreamResolveException.Reason.NO_PLAYABLE_STREAM,
                message = "InnerTubeX returned no playable stream",
            )
        check(stream.sabrBootstrap == null) { "SABR is not supported by this playback engine" }
        return stream.toPlaybackData()
    }

    /**
     * Round 4.3.5 (reports Anak Kampung "This track is unavailable", Brooklyn Baby / Toosie Slide / Not You Too "No playable
     * stream found"). Live probe 2026-11-13 from Indonesia: every one of these songs is served at once by the tokenless
     * VISIONOS client (direct m4a/opus addresses, no DRM, available in ID). The library's final verdict comes from OTHER
     * clients: TVHTML5 answers UNPLAYABLE "The page needs to be reloaded." for EVERY song (also the control song) and any
     * UNPLAYABLE answer turns the whole extraction into UNAVAILABLE = "This track is unavailable" (skipped at once); explicit
     * songs did not try the direct clients at all before 4.3.4 and ended in the token + cipher path, which fails on phones
     * whose WebView token or QuickJS runtime does not work ("No playable stream found", one TECNO phone, every explicit
     * song). So a failure that is not the network, an age gate or an upload is never final before the direct clients were
     * asked by name.
     */
    internal fun isRescuable(error: Throwable): Boolean =
        when (error) {
            is LinkageError -> true
            is StreamResolveException ->
                error.reason == StreamResolveException.Reason.UNAVAILABLE ||
                    error.reason == StreamResolveException.Reason.NO_PLAYABLE_STREAM ||
                    error.reason == StreamResolveException.Reason.EXPLICIT_UNSUPPORTED ||
                    error.reason == StreamResolveException.Reason.UNKNOWN
            else -> false
        }

    /** How long the library's automatic order may take before the direct rescue runs (the cipher path takes ~3-20 s). */
    private const val FIRST_PASS_TIMEOUT_MS = 25_000L

    /** The tokenless direct profiles, asked one by one by name (one player request each, about 0.1-0.3 s). */
    internal val RESCUE_PROFILES = listOf("VISIONOS_0_1", "VISIONOS")

    private suspend fun rescueWithDirectClients(
        videoId: String,
        excludedClients: Set<String>,
        quality: InnerTubeXAudioQuality,
        allowBoundedRange: Boolean,
    ): PlaybackData? {
        // VISIONOS 0.1 answers only with a visitor id; the app sets one at start, but it can be missing (first start offline)
        if (YouTube.visitorData.isNullOrBlank()) {
            runCatching { YouTube.visitorData().getOrNull()?.takeIf(String::isNotBlank)?.let { YouTube.visitorData = it } }
        }
        for (profile in RESCUE_PROFILES) {
            val hints =
                ContentHints(isExplicit = null, isUploaded = false, playbackClientOverrideId = profile)
                    .withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = allowBoundedRange)
            try {
                val data = extractOnce(videoId, hints, excludedClients, quality)
                Timber.tag(TAG).i("Stream of $videoId rescued by $profile after the automatic order failed")
                return data
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.tag(TAG).d("Rescue with $profile failed for $videoId: ${error.message}")
            } catch (error: LinkageError) {
                Timber.tag(TAG).d("Rescue with $profile failed for $videoId: ${error.javaClass.simpleName}")
            }
        }
        return null
    }

    /**
     * Last resort when no client gives this video at all: the same recording from an official upload (see [AlternativeMatch]
     * for the rules). The song keeps its own id, title and place in the queue; only the audio comes from the other upload,
     * and the service tells the user ([PlaybackData.alternativeVideoId]). Nothing is reported to YouTube's history for it.
     */
    private suspend fun playAlternative(
        song: AlternativeMatch.Song,
        quality: InnerTubeXAudioQuality,
        allowBoundedRange: Boolean,
    ): PlaybackData? {
        val query = (song.title + " " + song.artists.firstOrNull().orEmpty()).trim()
        val candidates = ArrayList<AlternativeMatch.Candidate>()
        for (filter in listOf(YouTube.SearchFilter.FILTER_SONG, YouTube.SearchFilter.FILTER_VIDEO)) {
            try {
                YouTube
                    .search(query, filter, recordSearchHistory = false)
                    .getOrNull()
                    ?.items
                    ?.filterIsInstance<SongItem>()
                    ?.filterNot { it.isEpisode }
                    ?.mapTo(candidates) {
                        AlternativeMatch.Candidate(
                            videoId = it.id,
                            title = it.title,
                            artists = it.artists.map { artist -> artist.name },
                            durationSeconds = it.duration,
                            musicVideoType =
                                it.musicVideoType
                                    ?: if (filter == YouTube.SearchFilter.FILTER_SONG) "MUSIC_VIDEO_TYPE_ATV" else null,
                        )
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.tag(TAG).d("Alternative search failed for ${song.videoId}: ${error.message}")
            }
        }
        val pick = AlternativeMatch.pick(song, candidates) ?: return null
        val hints =
            ContentHints(isExplicit = null, isUploaded = false)
                .withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = allowBoundedRange)
        val excluded = failedStreamClients(pick.videoId)
        val data =
            try {
                withTimeoutOrNull(FIRST_PASS_TIMEOUT_MS) { extractOnce(pick.videoId, hints, excluded, quality) }
                    ?: rescueWithDirectClients(pick.videoId, excluded, quality, allowBoundedRange)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                rescueWithDirectClients(pick.videoId, excluded, quality, allowBoundedRange)
            } catch (error: LinkageError) {
                rescueWithDirectClients(pick.videoId, excluded, quality, allowBoundedRange)
            } ?: return null
        Timber.tag(TAG).w("${song.videoId} has no stream of its own: playing ${pick.videoId} (${pick.title})")
        return data.asAlternativeOf(pick.videoId)
    }

    private fun PlaybackData.asAlternativeOf(alternativeId: String): PlaybackData =
        PlaybackData(
            audioConfig = audioConfig,
            // the song keeps its own title and cover, and the other upload is not reported as watched
            videoDetails = null,
            playbackTracking = null,
            format = format,
            streamUrl = streamUrl,
            streamExpiresInSeconds = streamExpiresInSeconds,
            streamClient = streamClient,
            streamHeaders = streamHeaders,
            requireBoundedRange = requireBoundedRange,
            rangeChunkSizeBytes = rangeChunkSizeBytes,
            useRangeChunks = useRangeChunks,
            alternativeVideoId = alternativeId,
        )

    /** The failure handed to the service: the explicit mapping of finding 22, a network cause unwrapped. */
    private fun finalFailure(
        isExplicit: Boolean?,
        failure: Throwable,
    ): Throwable {
        if (failure is LinkageError) {
            return StreamResolveException(
                reason = StreamResolveException.Reason.NO_PLAYABLE_STREAM,
                message = "No playable stream found for this track.",
                cause = failure,
            )
        }
        val raw = failure as? StreamResolveException ?: return failure
        val error = explicitFinalFailure(isExplicit, raw)
        val cause = error.cause
        return if (error.reason == StreamResolveException.Reason.NETWORK && cause != null) cause else error
    }

    /**
     * Video mode (additive, the audio call above is not touched): asks the same extractor for the SABR video of
     * [videoId], no taller than [maxVideoHeight]. Only a SABR result is usable (the library gives no direct video URL),
     * so a result without [com.metrolist.innertubex.sabr.SabrBootstrap] is a failure of kind [NoSabrVideoException].
     */
    @OptIn(com.metrolist.innertubex.sabr.ExperimentalSabrApi::class)
    suspend fun playerResponseForVideo(
        videoId: String,
        maxVideoHeight: Int,
        /** Ask ONE profile only (for example the tokenless `VISIONOS_SABR`); null = the library's own order. */
        clientOverrideId: String? = null,
        /** Profile ids / client names the library must not use (the ones a first stage already tried). */
        excludedClients: Set<String> = emptySet(),
    ): Result<VideoPlaybackData> =
        try {
            val hints = videoHints(maxVideoHeight, clientOverrideId)
            val stream =
                requireNotNull(
                    bundle().extractor.extract(
                        videoId = videoId,
                        hints = hints,
                        excludedClients = excludedClients,
                        audioQuality = InnerTubeXAudioQuality.AUTO,
                        clientPlaybackNonce = generateClientPlaybackNonce(),
                    ),
                ) { "InnerTubeX returned no stream" }
            val bootstrap = stream.sabrVideoBootstrap ?: throw NoSabrVideoException("no SABR video (client ${stream.clientName})")
            Result.success(
                VideoPlaybackData(
                    bootstrap = bootstrap,
                    mimeType = stream.videoMimeType ?: bootstrap.selectedVideoMimeType,
                    codecs = stream.videoCodecs,
                    width = stream.videoWidth ?: bootstrap.selectedVideoWidth,
                    height = stream.videoHeight ?: bootstrap.selectedVideoHeight,
                    bitrate = stream.videoBitrate ?: bootstrap.selectedVideoBitrate,
                    durationMs = bootstrap.durationMs,
                    contentLengthBytes = stream.videoContentLengthBytes ?: bootstrap.selectedVideoContentLengthBytes,
                    expiresInSeconds =
                        stream.expiresAt
                            ?.let { ((it.toEpochMilliseconds() - Clock.System.now().toEpochMilliseconds()) / 1000L).toInt() }
                            ?.coerceAtLeast(1)
                            ?: DEFAULT_STREAM_TTL_SECONDS,
                    clientName = stream.clientName,
                    profileId = stream.profileId,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }

    /** The hints of a video extraction, in one place so the live reliability test asks the library exactly what the app asks. */
    internal fun videoHints(
        maxVideoHeight: Int,
        clientOverrideId: String? = null,
    ): ContentHints =
        ContentHints(wantVideo = true, maxVideoHeight = maxVideoHeight, sabrFirst = true, playbackClientOverrideId = clientOverrideId)
            .withStreamCapabilities(allowHls = false, allowSabr = true, allowBoundedRange = true)

    class NoSabrVideoException(
        message: String,
    ) : IllegalStateException(message)

    class VideoPlaybackData(
        val bootstrap: com.metrolist.innertubex.sabr.SabrBootstrap,
        val mimeType: String?,
        val codecs: String?,
        val width: Int?,
        val height: Int?,
        val bitrate: Int?,
        val durationMs: Long,
        val contentLengthBytes: Long?,
        val expiresInSeconds: Int,
        val clientName: String,
        val profileId: String? = null,
    )

    /**
     * The explicit hint given to the extraction: unknown (null) instead of true, so the fast tokenless clients are tried
     * before the cipher path. Explicit is a label, not an age gate: the same video id plays on those clients. An age-gated
     * or private song still gets the full path, because the library falls through to it when the direct clients refuse.
     */
    internal fun explicitFirstPass(isExplicit: Boolean?): Boolean? = if (isExplicit == true) null else isExplicit

    /**
     * Review 2026-11-12, finding 22: with the explicit flag hidden in the first pass, a song that NO client can play ends
     * with NO_PLAYABLE_STREAM instead of EXPLICIT_UNSUPPORTED. The service treats the first as "another client may work"
     * (three fresh extractions, about a minute) and the second as "this song cannot be played" (skipped at once), which is
     * what 4.3.3 did for these songs. So for a song the database knows as explicit the final reason is mapped back.
     * Network, age-gate and unavailable reasons are kept: they carry their own handling.
     *
     * Two more questions of that review, answered here so the choice stays next to the code:
     *  - Signed-in users: the hidden flag also drops the library's cookie-first order for explicit songs. Kept that way on
     *    purpose. Cookie-first was not what made age-gated songs work: with isExplicit=null the library still ends with
     *    the signed-in watch-page retry when the anonymous clients refuse (innertubex InnerTubeExtractor, the
     *    `else if (innerTube.hasSapCookieAuth())` branch after the signed-out watch config), so an age-gated song still
     *    plays for a signed-in user, a few seconds later. Cookie-first would bring back the 18-28 s WEB_REMIX + PO token +
     *    cipher wait for EVERY explicit song of every signed-in user (videos C/D, "stuck at 0:00").
     *  - Client health: the extractor is built without a ClientHealthMonitor (the library default is
     *    ClientHealthMonitor.NONE), so a refusal in the first pass is not scored against a client for other songs; the
     *    app's own exclusions ([markStreamClientFailed]) are per song and expire after five minutes.
     */
    internal fun explicitFinalFailure(
        isExplicit: Boolean?,
        error: StreamResolveException,
    ): StreamResolveException {
        if (isExplicit != true) return error
        if (error.reason != StreamResolveException.Reason.NO_PLAYABLE_STREAM) return error
        return StreamResolveException(
            reason = StreamResolveException.Reason.EXPLICIT_UNSUPPORTED,
            message = error.message ?: "No playable stream found for this explicit track.",
            cause = error.cause,
            diagnostics = error.diagnostics,
        )
    }

    internal fun markStreamClientFailed(
        videoId: String,
        clientName: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        streamClientFailures.compute(videoId) { _, failures ->
            FailedStreamClients(failures?.clientNames.orEmpty() + clientName, nowMs)
        }
    }

    fun clearStreamClientFailures() {
        streamClientFailures.clear()
    }

    suspend fun refreshAfterStreamRejection(): Boolean = bundle().cipherService.refreshAfterStreamRejection()

    internal fun failedStreamClients(
        videoId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Set<String> {
        val failures = streamClientFailures[videoId] ?: return emptySet()
        if ((nowMs - failures.failedAtMs) !in 0 until STREAM_CLIENT_FAILURE_TTL_MS) {
            streamClientFailures.remove(videoId, failures)
            return emptySet()
        }
        return failures.clientNames
    }

    private suspend fun bundle(): ExtractionBundle {
        val transport = YouTube.extractionTransport()
        currentBundle?.takeIf { it.transportGeneration == transport.generation }?.let { return it }
        return bundleMutex.withLock {
            val latestTransport = YouTube.extractionTransport()
            currentBundle?.takeIf { it.transportGeneration == latestTransport.generation }?.let { return@withLock it }
            try {
                currentBundle?.cipherService?.dispose()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.log(
                    com.metrolist.innertubex.InnerTubeLogEvent(
                        level = InnerTubeLogLevel.WARN,
                        tag = TAG,
                        message = "old cipher disposal failed",
                        details = mapOf("exceptionType" to (error::class.simpleName ?: "unknown")),
                    ),
                )
            }

            val remoteStore = RemotePlayerConfigStore(latestTransport.httpClient, configRepository, logger)
            val cipherService = YouTubeCipherService(latestTransport.httpClient, remoteStore, logger)
            // Round 4.3.4 (emulator, 2026-11-12): without a persistent store every cipher solve ran the EJS solver on the
            // FULL player script ("EJS evaluate done preprocessed=false elapsed=18-22 s" for every explicit song, the
            // runtime is released after 20 s idle). The preprocessed player is kept on disk once per player version.
            applicationContext?.let { context ->
                val dir = java.io.File(context.filesDir, EjsPlayerDiskCache.DIR_NAME)
                cipherService.setPreprocessedPlayerCache(
                    read = { key -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { EjsPlayerDiskCache.read(dir, key) } },
                    write = { key, value -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { EjsPlayerDiskCache.write(dir, key, value) } },
                )
            }
            val extractor =
                InnerTubeExtractor(
                    configParser =
                        YtConfigParserImpl(
                            latestTransport.httpClient,
                            latestTransport.innerTube,
                            remoteStore,
                            logger,
                        ).withEmbeddedConfigFallback(),
                    cipherService = cipherService,
                    innerTube = latestTransport.innerTube,
                    tokenProvider = tokenProvider,
                    logger = logger,
                )
            ExtractionBundle(latestTransport.generation, cipherService, extractor).also { currentBundle = it }
        }
    }

    private val configRepository: PlayerConfigRepository by lazy {
        AndroidPlayerConfigRepository(requireNotNull(applicationContext) { "InnerTubeXPlayer is not initialized" })
    }

    private val poTokenGenerator: PoTokenGenerator by lazy {
        PoTokenGenerator(requireNotNull(applicationContext) { "InnerTubeXPlayer is not initialized" })
    }

    private val tokenProvider =
        object : TokenProvider {
            override val capabilities =
                TokenProviderCapabilities(
                    providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
                    usesWebView = true,
                )

            override suspend fun getPoToken(
                videoId: String,
                visitorData: String,
                cookie: String?,
            ): PoTokenResult? =
                poTokenGenerator.getWebClientPoToken(videoId, visitorData)?.let { token ->
                    PoTokenResult(
                        playerRequestToken = token.playerRequestPoToken,
                        streamingDataToken = token.streamingDataPoToken,
                        visitorData = visitorData,
                    )
                }

            override suspend fun close() {
                poTokenGenerator.close()
            }
        }

    private val logger =
        InnerTubeLogger { event ->
            val details = event.details.entries.joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
            val message = event.message + details.takeUnless { event.details.isEmpty() }.orEmpty()
            when (event.level) {
                InnerTubeLogLevel.DEBUG -> Timber.tag(event.tag).d(message)
                InnerTubeLogLevel.INFO -> Timber.tag(event.tag).i(message)
                InnerTubeLogLevel.WARN -> Timber.tag(event.tag).w(message)
                InnerTubeLogLevel.ERROR -> Timber.tag(event.tag).e(message)
            }
        }

    class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        val streamClient: String,
        val streamHeaders: Map<String, String>,
        val requireBoundedRange: Boolean,
        val rangeChunkSizeBytes: Long,
        val useRangeChunks: Boolean,
        /** Round 4.3.5: the audio comes from this other upload of the same song (no stream of its own); null = its own. */
        val alternativeVideoId: String? = null,
    )

    private data class ExtractionBundle(
        val transportGeneration: Long,
        val cipherService: YouTubeCipherService,
        val extractor: InnerTubeExtractor,
    )

    private data class FailedStreamClients(
        val clientNames: Set<String>,
        val failedAtMs: Long,
    )

    private class AndroidPlayerConfigRepository(context: Context) : PlayerConfigRepository {
        private val preferences = context.getSharedPreferences("innertubex_player_config", Context.MODE_PRIVATE)

        override val enabled: Boolean = true
        override val sourceUrl: String = PLAYER_CONFIG_URL
        override val defaultSourceUrl: String = PLAYER_CONFIG_URL
        override var cachedJson: String
            get() = preferences.getString("json", "").orEmpty()
            set(value) = preferences.edit().putString("json", value).apply()
        override var cachedAtMs: Long
            get() = preferences.getLong("cached_at_ms", 0L)
            set(value) = preferences.edit().putLong("cached_at_ms", value).apply()
        override var cachedSourceUrl: String
            get() = preferences.getString("source_url", "").orEmpty()
            set(value) = preferences.edit().putString("source_url", value).apply()
        override var cachedEtag: String
            get() = preferences.getString("etag", "").orEmpty()
            set(value) = preferences.edit().putString("etag", value).apply()

        private companion object {
            const val PLAYER_CONFIG_URL =
                "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
        }
    }

    internal fun YtConfigParser.withEmbeddedConfigFallback(): YtConfigParser =
        object : YtConfigParser by this {
            override suspend fun fetchConfig(
                videoId: String,
                useLoginCookies: Boolean,
            ) =
                try {
                    this@withEmbeddedConfigFallback.fetchConfig(videoId, useLoginCookies)
                } catch (_: IllegalStateException) {
                    this@withEmbeddedConfigFallback.fetchEmbeddedConfig(videoId, useLoginCookies = false)
                }
        }

    private fun AudioQuality.toInnerTubeX(connectivityManager: ConnectivityManager): InnerTubeXAudioQuality =
        when (this) {
            AudioQuality.HIGH -> InnerTubeXAudioQuality.HIGH
            AudioQuality.LOW -> InnerTubeXAudioQuality.LOW
            AudioQuality.AUTO ->
                if (connectivityManager.isActiveNetworkMetered) {
                    InnerTubeXAudioQuality.LOW
                } else {
                    InnerTubeXAudioQuality.AUTO
                }
        }

    private fun ExtractedStream.toPlaybackData(): PlaybackData {
        val metadata = mediaMetadata
        val tracking = playbackTracking
        val fullMimeType =
            if (codecs.isNullOrBlank()) {
                mimeType.orEmpty()
            } else {
                "${mimeType.orEmpty()}; codecs=\"$codecs\""
            }
        return PlaybackData(
            audioConfig =
                if (loudnessDb != null || perceptualLoudnessDb != null) {
                    PlayerResponse.PlayerConfig.AudioConfig(loudnessDb, perceptualLoudnessDb)
                } else {
                    null
                },
            videoDetails =
                metadata?.let {
                    PlayerResponse.VideoDetails(
                        videoId = videoId,
                        title = it.title,
                        author = it.author,
                        channelId = it.channelId.orEmpty(),
                        lengthSeconds = it.durationSeconds?.toString().orEmpty(),
                        musicVideoType = it.musicVideoType,
                        viewCount = it.viewCount,
                        thumbnail =
                            Thumbnails(
                                it.thumbnails.map { thumbnail ->
                                    Thumbnail(thumbnail.url, thumbnail.width, thumbnail.height)
                                },
                            ),
                    )
                },
            playbackTracking =
                tracking?.let {
                    PlayerResponse.PlaybackTracking(
                        videostatsPlaybackUrl =
                            it.playbackUrl?.let(PlayerResponse.PlaybackTracking::VideostatsPlaybackUrl),
                        videostatsWatchtimeUrl =
                            it.watchtimeUrl?.let(PlayerResponse.PlaybackTracking::VideostatsWatchtimeUrl),
                        atrUrl = null,
                    )
                },
            format =
                PlayerResponse.StreamingData.Format(
                    itag = itag,
                    url = audioUrl,
                    mimeType = fullMimeType,
                    bitrate = bitrate ?: 0,
                    width = null,
                    height = null,
                    contentLength = contentLengthBytes,
                    quality = "",
                    fps = null,
                    qualityLabel = null,
                    averageBitrate = bitrate,
                    audioQuality = null,
                    approxDurationMs = metadata?.durationSeconds?.times(1000)?.toString(),
                    audioSampleRate = sampleRate,
                    audioChannels = null,
                    loudnessDb = loudnessDb,
                    lastModified = null,
                    signatureCipher = null,
                    cipher = null,
                    audioTrack = null,
                ),
            streamUrl = audioUrl,
            streamExpiresInSeconds =
                expiresAt
                    ?.let { ((it.toEpochMilliseconds() - Clock.System.now().toEpochMilliseconds()) / 1000L).toInt() }
                    ?.coerceAtLeast(1)
                    ?: DEFAULT_STREAM_TTL_SECONDS,
            streamClient = clientName,
            streamHeaders = headers,
            requireBoundedRange = this.requireBoundedRange,
            rangeChunkSizeBytes = this.rangeChunkSizeBytes,
            useRangeChunks = this.useRangeChunks,
        )
    }
}
