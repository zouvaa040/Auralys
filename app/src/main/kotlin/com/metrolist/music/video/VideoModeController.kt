/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The brain of the video mode (UI side, no edit in MusicService). It owns the ONE extra player that shows the picture and
 * decides when it exists, when it plays and where it is; the audio player is only read, never touched.
 *
 * Rules (the design of VIDEO-MODE-FEASIBILITY.md section 7 and BITCHORD-VIDEO-FLOW.md section 7):
 * - The second player exists only while: video is wanted for the song AND the player sheet is open AND the app is on
 *   screen AND nothing is casting. It is paused at once when it stops being needed and let go 3 s later; it is let go at
 *   once when the user picks Audio, when the song changes and when the controller is disposed.
 * - Songs that offer video: music videos / uploads and songs opened from the search Video chip (their own video), and
 *   audio recordings that have a music video of about the same length (that video plays silently over the same audio, the
 *   playing item is never swapped; see VideoEligibility.counterpartOffsetMs). The pill is there as soon as the sheet is
 *   open: the Video half shows a spinner while the video is searched for, and the pill goes when there is none. Never
 *   device songs, episodes, Listen Together guests or while casting.
 * - The picture follows the audio (VideoSyncLogic). It plays only while the audio plays and waits while the audio buffers.
 * - Failures are told apart (VideoFailures.classify): a NETWORK problem (offline, timeout, a picture that cannot get going)
 *   is retried by itself with growing, jittered waits (1, 3, 8, 20, 45, 90 s) and never punishes anything; a real failure of
 *   the path is tried once more with a fresh extraction (and without the start hint), then the song falls back to audio
 *   with a short message and RESTS for 2 to 10 minutes; after three such songs in a row the whole mode rests for 60 s to
 *   10 min (VideoSession). Nothing lasts until the app restarts.
 * - The stream of a known video is prepared ahead (prefetch): as soon as the song has been playing for a moment and video
 *   is wanted (Video mode, or the sheet is open), so the tap on Video starts at once. The search for the other recording
 *   of a catalogue song still waits for the sheet.
 *
 * Everything the controller needs from Android goes through [VideoModeHost] and [VideoPlayerFactory], so it is tested
 * on the JVM with fakes. All calls come from the main thread.
 */

package com.metrolist.music.video

import android.view.TextureView
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The audio player as the controller sees it. */
data class AudioSnapshot(
    val mediaId: String?,
    val positionMs: Long,
    val playing: Boolean,
    val buffering: Boolean,
    val speed: Float,
)

/** What the controller needs from the app. */
interface VideoModeHost {
    /** The audio player's state, or null while there is no player. */
    fun audio(): AudioSnapshot?

    fun isCasting(): Boolean

    /** The sharpest picture allowed right now (360 or 720). */
    fun maxHeight(): Int

    fun toast(kind: VideoToast)

    /** The user tapped the pill: store the mode. */
    fun persistMode(mode: VideoMode)

    /**
     * The music video of the other recording of [song] (the app search, one request, answers kept for the session), or
     * null when there is none. May take a while; never skipped because of the network type.
     */
    suspend fun counterpartVideo(song: MediaMetadata): CounterpartVideo?

    /** The answer for [song] when it is already known without a search (also "none"); null = not known. Free. */
    fun knownCounterpart(song: MediaMetadata): KnownCounterpart? = null

    /** May the stream be prepared before it is needed? False on a metered network with the data saver on. */
    fun prefetchAllowed(): Boolean = true
}

/**
 * The music video found for an audio recording; [durationSec] is its length (null = unknown). [official] = YouTube's own
 * link (not a title guess); [offsetMs] = the measured start of the video in relation to the audio, null = not known.
 */
class CounterpartVideo(
    val videoId: String,
    val durationSec: Int?,
    val offsetMs: Long? = null,
    val official: Boolean = false,
)

/** A counterpart search answered earlier: [video] is null when there is none. */
class KnownCounterpart(
    val video: CounterpartVideo?,
)

enum class VideoToast { FAILED, UNAVAILABLE }

/** The second player, reduced to what the controller uses. */
interface VideoPlayerHandle {
    val positionMs: Long

    /** 0 while unknown. */
    val durationMs: Long
    val isReady: Boolean
    val isBuffering: Boolean
    var playWhenReady: Boolean
    var speed: Float

    fun seekTo(positionMs: Long)

    fun setSurface(view: TextureView?)

    fun release()
}

interface VideoPlayerEvents {
    fun onFirstFrame()

    fun onError(error: Throwable)

    fun onVideoAspect(aspect: Float)
}

fun interface VideoPlayerFactory {
    fun create(
        info: VideoStreamInfo,
        startMs: Long,
        useStartHint: Boolean,
        clock: VideoClock,
        events: VideoPlayerEvents,
    ): VideoPlayerHandle
}

data class VideoModeInputs(
    val song: MediaMetadata?,
    /** The experiment switch "Mode video". */
    val enabled: Boolean,
    /** The stored mode (what the user last chose with the pill). */
    val storedMode: VideoMode,
    /** The switch "Putar video otomatis dari hasil Video". */
    val autoFromSearch: Boolean,
    val listenTogetherGuest: Boolean,
    /** The app is on screen (lifecycle STARTED). */
    val started: Boolean,
)

data class VideoUiState(
    /** Show the Audio | Video pill. */
    val pillVisible: Boolean = false,
    val mode: VideoMode = VideoMode.AUDIO,
    /** The first video frame is on screen: draw the picture over the cover. */
    val showing: Boolean = false,
    /** Width / height of the picture. */
    val aspect: Float = 16f / 9f,
    /** The video is being searched for or prepared: the Video half of the pill shows a spinner. */
    val loading: Boolean = false,
)

class VideoModeController(
    private val host: VideoModeHost,
    private val scope: CoroutineScope,
    private val repository: VideoStreamRepository,
    private val playerFactory: VideoPlayerFactory,
    private val memory: VideoModeMemory = GlobalVideoModeMemory.shared,
    private val session: VideoSession = GlobalVideoSession.shared,
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val retryDelayMs: Long = 1_000L,
    private val stallLimitMs: Long = 20_000L,
    /** The song must have played this long before its stream is prepared ahead. */
    private val prefetchDelayMs: Long = 2_500L,
    /** For the jitter of the waits; tests pin it. */
    private val random: () -> Double = Math::random,
) {
    private val _state = MutableStateFlow(VideoUiState())
    val state: StateFlow<VideoUiState> = _state.asStateFlow()

    /** The video player's position for the SABR stream. */
    val clock = VideoClock()

    private var inputs =
        VideoModeInputs(null, enabled = false, storedMode = VideoMode.AUDIO, autoFromSearch = true, listenTogetherGuest = false, started = false)

    /** Is the player sheet open? Read on every look. */
    var expanded: () -> Boolean = { false }

    // per song
    private var songId: String? = null
    private val songPicks = LinkedHashMap<String, VideoMode>()
    private var videoId: String? = null

    /** Where the video runs in relation to the audio (0 for the own video of a song, see VideoEligibility.counterpartOffsetMs). */
    private var videoOffsetMs = 0L
    private var sourceNone = false
    private var lookup: Job? = null
    private var attempt = 0
    private var retryAt = 0L
    private var userAskedVideo = false

    // failures of this song: network problems and real ones are counted apart (they are treated apart)
    private var netFailures = 0
    private var pathFailures = 0
    private var shownSince = NONE

    // the search for the other recording failed (slow or no network): try again later, never decide "no video" from it
    private var lookupFailures = 0
    private var lookupRetryAt = 0L

    // the stream prepared ahead
    private var songStartedAt = 0L
    private var prefetchJob: Job? = null
    private var prefetchRetryAt = 0L

    // the second player
    private var player: VideoPlayerHandle? = null
    private var startJob: Job? = null
    private var startEpoch = 0
    private var firstFrame = false
    private var aspect = 16f / 9f
    private var surface: TextureView? = null
    private var releaseAt = NONE
    private var lastCorrectionAt = VideoSyncLogic.NO_CORRECTION
    private var forceSync = false
    private var wasReady = false
    private var notReadySince = NONE
    private var lastRate = 1f
    private var releaseTimer: Job? = null

    /** True while a timer or a player needs the 250 ms tick. */
    val busy: Boolean
        get() = player != null || startJob?.isActive == true || releaseAt != NONE || retryAt > now() || lookupRetryAt > now()

    // ---------------------------------------------------------------------------------------------- inputs and events

    fun update(newInputs: VideoModeInputs) {
        inputs = newInputs
        reconcile()
    }

    fun bindSurface(view: TextureView?) {
        surface = view
        player?.setSurface(view)
    }

    /**
     * A view is being let go. Only the view that is bound NOW is unbound: when the song changes the old carousel item can
     * release its view after the new item bound its own, and that late release must not take the new surface away
     * (REVIEW-2026-11-07 F1).
     */
    fun unbindSurface(view: TextureView) {
        if (surface !== view) return
        surface = null
        player?.setSurface(null)
    }

    /**
     * The app went to the background or came back (lifecycle STOP / START), straight from the lifecycle: the composition is
     * paused in the background, so these facts must not wait for a recomposition (REVIEW-2026-11-07 F2). Leaving pauses the
     * picture at once and lets the second player go after the usual 3 s; coming back starts it again when it is wanted.
     */
    fun onAppStarted(started: Boolean) {
        if (inputs.started == started) return
        inputs = inputs.copy(started = started)
        tick()
    }

    /** The song changed (from the player's own flow, not from a recomposition): follow it also in the background. */
    fun setSong(song: MediaMetadata?) {
        if (inputs.song == song) return
        inputs = inputs.copy(song = song)
        tick()
    }

    /** Follows the song of the player for as long as the caller collects; see [setSong]. */
    suspend fun followSongs(songs: kotlinx.coroutines.flow.Flow<MediaMetadata?>) {
        songs.collect { setSong(it) }
    }

    /** One look at the clocks; the UI calls it every 250 ms while [busy], and after every change that matters. */
    fun tick() {
        reconcile()
        releaseIfDue()
        syncStep()
    }

    /** The audio player seeked, changed song or state: the next look corrects the picture without the throttle. */
    fun onAudioEvent(discontinuity: Boolean) {
        if (discontinuity) forceSync = true
        tick()
    }

    fun onUserPick(mode: VideoMode) {
        val id = songId ?: return
        rememberPick(id, mode)
        host.persistMode(mode)
        if (mode == VideoMode.VIDEO) {
            userAskedVideo = true
            attempt = 0
            retryAt = 0L
        } else {
            releasePlayerNow()
        }
        reconcile()
    }

    fun dispose() {
        lookup?.cancel()
        prefetchJob?.cancel()
        startJob?.cancel()
        startEpoch++
        releasePlayerNow()
        publish()
    }

    // ---------------------------------------------------------------------------------------------- the plan

    private var reconciling = false
    private var again = false

    /** Never runs inside itself (a lookup or a start that finishes at once would otherwise act on stale facts). */
    private fun reconcile() {
        if (reconciling) {
            again = true
            return
        }
        reconciling = true
        try {
            do {
                again = false
                reconcileOnce()
            } while (again)
        } finally {
            reconciling = false
        }
    }

    private fun reconcileOnce() {
        val song = inputs.song
        if (song?.id != songId) onSongChanged(song)
        // before the facts below: an answer that is already known changes what the song offers
        maybeLookUpCounterpart(song)
        val offered = isEligible(song) && videoId != null
        val mode = if (song == null) VideoMode.AUDIO else effectiveMode(song.id)
        val wantsVideo = offered && mode == VideoMode.VIDEO
        val needsPlayer = VideoPlan.needsPlayer(offered, mode, expanded(), inputs.started)

        when {
            needsPlayer -> {
                releaseAt = NONE
                releaseTimer?.cancel()
                releaseTimer = null
                if (player == null) ensurePlayer()
            }
            !wantsVideo -> {
                // Audio picked, nothing offered any more, a failure: let go at once
                startJob?.cancel()
                startEpoch++
                releasePlayerNow()
            }
            else -> {
                // sheet closed or app in the background: pause now, let go in 3 s
                startJob?.cancel()
                startEpoch++
                player?.let {
                    it.playWhenReady = false
                    if (releaseAt == NONE) {
                        releaseAt = now() + VideoPlan.RELEASE_DELAY_MS
                        // no tick runs while the app is in the background: the timer looks once when the time is up
                        releaseTimer?.cancel()
                        releaseTimer =
                            scope.launch {
                                delay(VideoPlan.RELEASE_DELAY_MS + 50L)
                                tick()
                            }
                    }
                }
            }
        }
        maybePrefetch(song)
        publish()
    }

    /** The pill is there: the situation and the song allow video and no earlier try said "no video" for it. */
    private fun isEligible(song: MediaMetadata?): Boolean {
        if (song == null) return false
        val situation =
            VideoEligibility.situationAllows(
                enabled = inputs.enabled,
                killed = session.killed,
                listenTogetherGuest = inputs.listenTogetherGuest,
                casting = host.isCasting(),
            )
        return situation && song.toVideoSong().let(VideoEligibility::songAllows) && !sourceNone && !session.isNoVideo(song.id)
    }

    private fun effectiveMode(id: String): VideoMode = VideoPlan.effectiveMode(songPicks[id], oneShotRequested = false, stored = inputs.storedMode)

    private fun onSongChanged(song: MediaMetadata?) {
        lookup?.cancel()
        lookup = null
        prefetchJob?.cancel()
        prefetchJob = null
        startJob?.cancel()
        startEpoch++
        releasePlayerNow()
        songId = song?.id
        attempt = 0
        retryAt = 0L
        netFailures = 0
        pathFailures = 0
        shownSince = NONE
        lookupFailures = 0
        lookupRetryAt = 0L
        prefetchRetryAt = 0L
        songStartedAt = now()
        videoId = null
        videoOffsetMs = 0L
        sourceNone = false
        userAskedVideo = false
        if (song == null) return
        // a tap on a result of the search Video chip opens that song as video (once, for a few minutes)
        if (memory.takeIfFresh(song.id) && inputs.autoFromSearch) {
            rememberPick(song.id, VideoMode.VIDEO)
            userAskedVideo = true
        }
        val videoSong = song.toVideoSong()
        if (VideoEligibility.songAllows(videoSong) && VideoEligibility.isOwnVideo(videoSong, memory.isKnownVideo(song.id))) {
            videoId = song.id
            VideoDiagnostics.set(song.id, VideoStatus.Available)
        }
    }

    private fun rememberPick(
        id: String,
        mode: VideoMode,
    ) {
        songPicks.remove(id)
        songPicks[id] = mode
        while (songPicks.size > 64) songPicks.remove(songPicks.keys.first())
    }

    /**
     * Audio recordings: look for their music video. The search starts the moment the sheet is open and the app is on screen
     * (no waiting, and not held back by a metered network, the data saver or a low-RAM phone: it is one search request; only
     * the sharpness of the picture is capped there). An answer that is already known (also "none", kept by the resolver for
     * the session) is used at once, also while the sheet is closed. Never while "Mode video" is off (the situation check).
     */
    private fun maybeLookUpCounterpart(song: MediaMetadata?) {
        if (song == null || videoId != null || sourceNone) return
        if (!VideoEligibility.songAllows(song.toVideoSong())) return
        if (!VideoEligibility.situationAllows(inputs.enabled, session.killed, inputs.listenTogetherGuest, host.isCasting())) return
        if (session.isNoVideo(song.id)) return
        host.knownCounterpart(song)?.let { known ->
            useCounterpart(song, known.video)
            return
        }
        if (lookup?.isActive == true) return
        if (!inputs.started || !expanded()) return
        if (now() < lookupRetryAt) return
        val id = song.id
        VideoDiagnostics.set(id, VideoStatus.Checking)
        lookup =
            scope.launch {
                var failed: VideoFailureKind? = null
                val found =
                    try {
                        host.counterpartVideo(song)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: CounterpartLookupFailed) {
                        failed = e.kind
                        null
                    } catch (_: Throwable) {
                        null
                    }
                if (songId != id) return@launch
                if (failed != null) {
                    onLookupFailed(song, failed)
                } else {
                    lookupFailures = 0
                    useCounterpart(song, found)
                }
                reconcile()
            }
    }

    /** The search could not be answered: try again with growing waits, then leave the song alone for a while. */
    private fun onLookupFailed(
        song: MediaMetadata,
        kind: VideoFailureKind,
    ) {
        lookupFailures++
        VideoLog.w("lookup failed song=${song.id} kind=${kind.key} n=$lookupFailures")
        if (lookupFailures >= LOOKUP_ATTEMPTS) {
            lookupFailures = 0
            session.markNoVideo(song.id, VideoBackoff.SONG_COOLDOWN_BASE_MS)
            VideoDiagnostics.set(song.id, VideoStatus.Failed(kind, retrying = false))
            return
        }
        lookupRetryAt = now() + VideoBackoff.retryDelayMs(lookupFailures + 1, random())
        VideoDiagnostics.set(song.id, VideoStatus.Failed(kind, retrying = true))
    }

    /** Takes the answer of the search: a video of about the same length is offered, anything else means "no video". */
    private fun useCounterpart(
        song: MediaMetadata,
        found: CounterpartVideo?,
    ) {
        val offset = found?.let { counterpartOffset(song, it) }
        if (found == null || offset == null) {
            sourceNone = true
            VideoLog.i("no counterpart song=${song.id} found=${found?.videoId ?: "-"} songSec=${song.duration} videoSec=${found?.durationSec ?: "-"}")
            VideoDiagnostics.set(song.id, VideoStatus.NoMatch)
            if (userAskedVideo) host.toast(VideoToast.UNAVAILABLE)
        } else {
            videoId = found.videoId
            videoOffsetMs = offset
            VideoLog.i("counterpart song=${song.id} video=${found.videoId} official=${found.official} offsetMs=$offset")
            VideoDiagnostics.set(song.id, VideoStatus.Available)
        }
    }

    /**
     * Where the video runs in relation to the audio. YouTube's own link with a measured offset is trusted (and so is its
     * link without a length); everything else goes through the length rule of [VideoEligibility.counterpartOffsetMs].
     */
    private fun counterpartOffset(
        song: MediaMetadata,
        found: CounterpartVideo,
    ): Long? =
        when {
            found.official && found.offsetMs != null -> found.offsetMs
            found.official && found.durationSec == null -> 0L
            else -> VideoEligibility.counterpartOffsetMs(song.duration, found.durationSec)
        }

    // ---------------------------------------------------------------------------------------------- starting and stopping

    private fun ensurePlayer() {
        if (player != null || startJob?.isActive == true) return
        if (now() < retryAt) return
        val id = videoId ?: return
        // the sound goes first (REVIEW-2026-11-07 F13): no video extraction while the audio player is still loading a song,
        // it would compete for the network and for the PO-token WebView. The audio state events tick again at once.
        if (host.audio()?.buffering == true) return
        val cap = host.maxHeight()
        val epoch = ++startEpoch
        val retry = attempt > 0
        val forSong = songId
        startJob =
            scope.launch {
                val result = repository.get(id, cap, forceFresh = retry)
                if (epoch != startEpoch || forSong != songId) return@launch
                when (result) {
                    is VideoStreamResult.Ready -> startPlayer(result.info, useStartHint = !retry)
                    is VideoStreamResult.NoVideo -> onNoVideo(result.reason)
                    is VideoStreamResult.Failed -> onFailure(VideoFailures.classify(result.error))
                }
            }
    }

    private fun startPlayer(
        info: VideoStreamInfo,
        useStartHint: Boolean,
    ) {
        val song = inputs.song ?: return
        val stillWanted =
            isEligible(song) && videoId != null && VideoPlan.needsPlayer(true, effectiveMode(song.id), expanded(), inputs.started)
        if (!stillWanted || player != null) return
        val startMs = (host.audio()?.positionMs ?: 0L) + videoOffsetMs
        VideoLog.i("start player song=${song.id} video=$videoId ${info.format.codec} ${info.format.width}x${info.format.height} cap=${info.maxHeight} profile=${info.profileId} startMs=$startMs hint=$useStartHint")
        clock.positionMs = startMs
        firstFrame = false
        wasReady = false
        notReadySince = NONE
        lastCorrectionAt = VideoSyncLogic.NO_CORRECTION
        lastRate = 1f
        aspect = info.format.width.takeIf { it > 0 && info.format.height > 0 }?.let { it.toFloat() / info.format.height } ?: aspect
        val created =
            try {
                playerFactory.create(info, startMs, useStartHint, clock, events)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
        if (created == null) {
            onFailure(VideoFailureKind.DECODER)
            return
        }
        player = created
        created.setSurface(surface)
        created.playWhenReady = false
        publish()
    }

    private val events =
        object : VideoPlayerEvents {
            override fun onFirstFrame() {
                if (player == null) return
                firstFrame = true
                // the counters are cleared only after the picture held for a while (see syncStep): a flapping network that
                // gives one frame and dies again must keep growing its waits
                shownSince = now()
                session.recordSuccess(songId)
                songId?.let { VideoDiagnostics.set(it, VideoStatus.Available) }
                VideoLog.i("first frame song=$songId video=$videoId")
                publish()
            }

            override fun onError(error: Throwable) {
                if (player == null) return
                onFailure(VideoFailures.classify(error))
            }

            override fun onVideoAspect(aspect: Float) {
                if (aspect > 0f && abs(aspect - this@VideoModeController.aspect) > 0.001f) {
                    this@VideoModeController.aspect = aspect
                    publish()
                }
            }
        }

    private fun onNoVideo(reason: NoVideoReason) {
        val id = songId ?: return
        session.markNoVideo(id)
        val kind =
            when (reason) {
                NoVideoReason.UNAVAILABLE -> VideoFailureKind.UNPLAYABLE
                NoVideoReason.UNSUPPORTED_CODEC -> VideoFailureKind.UNSUPPORTED_CODEC
                else -> VideoFailureKind.NO_FORMAT
            }
        VideoLog.i("no video song=$id video=$videoId reason=${reason.name.lowercase()}")
        VideoDiagnostics.set(id, VideoStatus.Failed(kind, retrying = false))
        if (userAskedVideo) host.toast(VideoToast.UNAVAILABLE)
        reconcile()
    }

    /**
     * A start or the playing failed. The reason decides (see the header): a network problem waits and tries again by
     * itself and punishes nothing; a real failure gets one more try with a fresh extraction, then the song rests and the
     * failure counts toward the rest of the whole mode.
     */
    private fun onFailure(kind: VideoFailureKind = VideoFailureKind.UNKNOWN) {
        val id = songId ?: return
        releasePlayerNow()
        startJob = null
        videoId?.let(repository::invalidate)
        attempt++
        shownSince = NONE
        VideoLog.w("failure song=$id video=$videoId kind=${kind.key} family=${kind.family.name.lowercase()} attempt=$attempt")
        if (kind.transient) {
            netFailures++
            if (netFailures >= VideoBackoff.MAX_NETWORK_ATTEMPTS) {
                giveUp(id, kind, counted = false)
            } else {
                retryAt = now() + VideoBackoff.retryDelayMs(netFailures, random())
                VideoDiagnostics.set(id, VideoStatus.Failed(kind, retrying = true))
            }
        } else {
            pathFailures++
            if (pathFailures == 1) {
                retryAt = now() + retryDelayMs
                VideoDiagnostics.set(id, VideoStatus.Failed(kind, retrying = true))
            } else {
                giveUp(id, kind, counted = true)
            }
        }
        reconcile()
    }

    /** The song goes back to audio and rests for a while; [counted] = a real failure of the path (not the network). */
    private fun giveUp(
        id: String,
        kind: VideoFailureKind,
        counted: Boolean,
    ) {
        val restMs = session.markSongFailed(id)
        rememberPick(id, VideoMode.AUDIO)
        val switchedOff = counted && session.recordFailure()
        VideoLog.w("gave up song=$id kind=${kind.key} restS=${restMs / 1000} countedFailures=${session.consecutiveFailures} modeResting=$switchedOff")
        VideoDiagnostics.set(id, VideoStatus.Failed(kind, retrying = false))
        host.toast(VideoToast.FAILED)
    }

    private fun releaseIfDue() {
        val at = releaseAt
        if (at != NONE && now() >= at) releasePlayerNow()
    }

    private fun releasePlayerNow() {
        val p = player
        player = null
        releaseAt = NONE
        releaseTimer?.cancel()
        releaseTimer = null
        firstFrame = false
        wasReady = false
        notReadySince = NONE
        lastRate = 1f
        if (p != null) {
            runCatching { p.setSurface(null) }
            runCatching { p.release() }
        }
    }

    // ---------------------------------------------------------------------------------------------- following the audio

    private fun syncStep() {
        val p = player ?: return
        val song = inputs.song ?: return
        val a = host.audio() ?: return
        // right after a song change the audio player may already be on another song: wait for the inputs to catch up
        if (a.mediaId != null && a.mediaId != song.id) return
        clock.positionMs = p.positionMs
        val needs = releaseAt == NONE
        if (!needs) return

        val t = now()
        val ready = p.isReady
        if (ready && !wasReady) forceSync = true
        wasReady = ready

        if (!ready) {
            p.playWhenReady = a.playing && !a.buffering
            // a picture that cannot get going while the audio plays is a failure, not an eternal spinner
            if (a.playing && !a.buffering) {
                if (notReadySince == NONE) notReadySince = t
                if (t - notReadySince > stallLimitMs) {
                    notReadySince = NONE
                    onFailure(VideoFailureKind.STALL)
                }
            } else {
                notReadySince = NONE
            }
            return
        }
        notReadySince = NONE
        if (shownSince != NONE && t - shownSince >= STABLE_PICTURE_MS) {
            // the picture held: the earlier trouble of this song is over
            shownSince = NONE
            attempt = 0
            netFailures = 0
            pathFailures = 0
        }

        val decision =
            VideoSyncLogic.decide(
                VideoSyncLogic.Input(
                    // the audio position as the video knows it: a video with an intro runs ahead by the offset
                    audioPositionMs = a.positionMs + videoOffsetMs,
                    videoPositionMs = p.positionMs,
                    audioPlaying = a.playing,
                    audioBuffering = a.buffering,
                    videoReady = ready,
                    videoBuffering = p.isBuffering,
                    videoDurationMs = p.durationMs,
                    speed = a.speed,
                    nowMs = t,
                    lastCorrectionAtMs = lastCorrectionAt,
                    force = forceSync,
                ),
            )
        forceSync = false
        decision.seekToMs?.let {
            lastCorrectionAt = t
            clock.positionMs = it
            p.seekTo(it)
        }
        if (abs(decision.rate - lastRate) > 0.003f) {
            p.speed = decision.rate
            lastRate = decision.rate
        }
        p.playWhenReady = decision.play
    }

    private fun publish() {
        val song = inputs.song
        val eligible = isEligible(song)
        val offered = eligible && videoId != null
        val mode = if (eligible && song != null) effectiveMode(song.id) else VideoMode.AUDIO
        val showing = offered && mode == VideoMode.VIDEO && player != null && firstFrame
        // the spinner: the video is being searched for, or it is wanted and its first picture is not there yet
        val searching = eligible && videoId == null && lookup?.isActive == true
        val preparing = offered && mode == VideoMode.VIDEO && !showing
        val next =
            VideoUiState(
                pillVisible = eligible,
                mode = mode,
                showing = showing,
                aspect = aspect,
                loading = searching || preparing,
            )
        if (next != _state.value) _state.value = next
    }

    /**
     * Prepares the stream of a known video ahead: the song has been playing for a moment, video is wanted (Video mode, or the
     * sheet is open and the pill is there) and the answer is not stored yet. One extraction (it lands in the repository's
     * cache and is shared with a start that comes while it runs); a failure here is only logged, it never counts against the
     * song or the mode. The data saver on a metered network switches it off (host).
     */
    private fun maybePrefetch(song: MediaMetadata?) {
        val id = videoId ?: return
        // looked at afresh: a start that failed a moment ago in this very pass may have put the song to rest
        if (song == null || !inputs.started || !isEligible(song)) return
        if (player != null || startJob?.isActive == true || prefetchJob?.isActive == true) return
        if (now() < retryAt || now() < prefetchRetryAt) return
        if (effectiveMode(song.id) != VideoMode.VIDEO && !expanded()) return
        val a = host.audio() ?: return
        if (a.mediaId != song.id || !a.playing || a.buffering) return
        if (now() - songStartedAt < prefetchDelayMs) return
        if (!host.prefetchAllowed()) return
        val cap = host.maxHeight()
        if (repository.peek(id, cap) != null) return
        val forSong = song.id
        prefetchJob =
            scope.launch {
                val result = repository.get(id, cap)
                if (forSong != songId) return@launch
                when (result) {
                    is VideoStreamResult.Failed -> {
                        val kind = VideoFailures.classify(result.error)
                        prefetchRetryAt = now() + VideoBackoff.retryDelayMs(2, random())
                        VideoLog.w("prefetch failed song=$forSong kind=${kind.key}")
                    }
                    is VideoStreamResult.NoVideo -> {
                        VideoLog.d("prefetched song=$forSong video=$id no video")
                        onNoVideo(result.reason)
                    }
                    is VideoStreamResult.Ready -> VideoLog.d("prefetched song=$forSong video=$id ready")
                }
            }
    }

    private companion object {
        const val NONE = Long.MIN_VALUE

        /** A picture that held this long ends the trouble counters of its song. */
        const val STABLE_PICTURE_MS = 30_000L

        /** The search for the other recording is asked this many times before the song is left alone for a while. */
        const val LOOKUP_ATTEMPTS = 4
    }
}

internal fun MediaMetadata.toVideoSong(): VideoSong =
    VideoSong(
        id = id,
        isVideoSong = isVideoSong,
        isEpisode = isEpisode,
        isLocal = com.metrolist.music.local.LocalMusic.isLocalSongId(id),
        durationSec = duration,
    )
