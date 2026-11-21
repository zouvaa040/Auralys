/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The decisions of the video mode that need no player and no screen: which songs may show the pill, which mode a song
 * is in, the one-shot "open as video" request of the search Video chip, and the session kill switch. Pure Kotlin,
 * unit tested.
 */

package com.metrolist.music.video

import kotlin.math.abs

enum class VideoMode {
    AUDIO,
    VIDEO,
    ;

    companion object {
        /** The stored value of [com.metrolist.music.constants.VideoModeKey]; anything unknown is AUDIO. */
        fun fromStored(value: String?): VideoMode = if (value == VIDEO.name) VIDEO else AUDIO
    }
}

/** A song as the video mode needs to see it. */
data class VideoSong(
    val id: String,
    /** A music video or an upload (not the catalogue audio recording). */
    val isVideoSong: Boolean,
    val isEpisode: Boolean,
    /** A file on the phone. */
    val isLocal: Boolean,
    val durationSec: Int,
)

object VideoEligibility {
    /** A video of the other recording this close in length starts together with the audio (no offset). */
    const val COUNTERPART_MAX_DIFF_SEC = 15

    /**
     * A video that is LONGER than the audio by up to this much is taken to have an intro of that length: its picture then
     * runs [counterpartOffsetMs] ahead of the audio. (A music video often opens with a few seconds to half a minute of
     * scene before the song starts; the catalogue recording starts with the song.) This is an assumption, not a
     * measurement: BitChord aligns the two by their waveforms, which this app does not do.
     */
    const val COUNTERPART_MAX_INTRO_SEC = 45

    /** Everything about the situation that rules video out, whatever the song. */
    fun situationAllows(
        enabled: Boolean,
        killed: Boolean,
        listenTogetherGuest: Boolean,
        casting: Boolean,
    ): Boolean = enabled && !killed && !listenTogetherGuest && !casting

    /** Songs that never get video: files on the phone and episodes. */
    fun songAllows(song: VideoSong): Boolean = !song.isLocal && !song.isEpisode && song.id.isNotBlank()

    /** A song that is its own video: a music video or upload, or a song started from the search Video chip. */
    fun isOwnVideo(
        song: VideoSong,
        knownVideo: Boolean,
    ): Boolean = song.isVideoSong || knownVideo

    /**
     * Where the video of the other recording is, in relation to the audio of [songDurationSec]: 0 when the lengths are within
     * [COUNTERPART_MAX_DIFF_SEC], the length difference when the video is longer by more than that but at most
     * [COUNTERPART_MAX_INTRO_SEC] (the video position = the audio position + the offset), null when the lengths are too far
     * apart or not known (no video is offered then).
     */
    fun counterpartOffsetMs(
        songDurationSec: Int,
        counterpartDurationSec: Int?,
    ): Long? {
        if (songDurationSec <= 0 || counterpartDurationSec == null || counterpartDurationSec <= 0) return null
        val difference = counterpartDurationSec - songDurationSec
        return when {
            abs(difference) <= COUNTERPART_MAX_DIFF_SEC -> 0L
            difference > 0 && difference <= COUNTERPART_MAX_INTRO_SEC -> difference * 1000L
            else -> null
        }
    }
}

object VideoPlan {
    /** The second player is let go this long after it stopped being needed (it is paused at once). */
    const val RELEASE_DELAY_MS = 3_000L

    /** Which mode a song is in: what the user picked for this song, else a pending one-shot request, else the stored mode. */
    fun effectiveMode(
        songPick: VideoMode?,
        oneShotRequested: Boolean,
        stored: VideoMode,
    ): VideoMode = songPick ?: if (oneShotRequested) VideoMode.VIDEO else stored

    /** A second player exists only while video is wanted AND the player sheet is open AND the app is on screen. */
    fun needsPlayer(
        offered: Boolean,
        mode: VideoMode,
        expanded: Boolean,
        started: Boolean,
    ): Boolean = offered && mode == VideoMode.VIDEO && expanded && started
}

/**
 * Songs the user asked to see as video, and songs known to be videos. A request is one-shot and expires; the clock is
 * injected so tests can move it.
 */
class VideoModeMemory(
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = TTL_MS,
    private val maxEntries: Int = 64,
) {
    private val requests = LinkedHashMap<String, Long>()
    private val known = LinkedHashSet<String>()

    /** A result of the Video chip was tapped: that song opens as video (and is known to be a video). */
    @Synchronized
    fun requestPlayAsVideo(id: String) {
        if (id.isBlank()) return
        requests.remove(id)
        requests[id] = now()
        while (requests.size > maxEntries) requests.remove(requests.keys.first())
        markKnownVideo(id)
    }

    /** True once, and only while the request is fresh. */
    @Synchronized
    fun takeIfFresh(id: String): Boolean {
        val at = requests.remove(id) ?: return false
        return now() - at in 0..ttlMs
    }

    /** True while a fresh request exists; does not use it up. */
    @Synchronized
    fun peekFresh(id: String): Boolean {
        val at = requests[id] ?: return false
        return now() - at in 0..ttlMs
    }

    @Synchronized
    fun markKnownVideo(id: String) {
        if (id.isBlank()) return
        known.remove(id)
        known.add(id)
        while (known.size > maxEntries * 4) known.remove(known.first())
    }

    @Synchronized
    fun isKnownVideo(id: String): Boolean = id in known

    companion object {
        const val TTL_MS = 5 * 60 * 1000L
    }
}

/** The process-wide memory. The search screen calls [requestPlayAsVideo]; the player reads it. */
object GlobalVideoModeMemory {
    val shared = VideoModeMemory()

    fun requestPlayAsVideo(id: String) = shared.requestPlayAsVideo(id)
}

/**
 * What went wrong in this run of the app, with memory that FORGETS (everything is a time-limited rest, nothing lasts until
 * the app restarts):
 * - a song with no usable video ([markNoVideo]) is left alone for [VideoBackoff.NO_VIDEO_TTL_MS];
 * - a song whose video failed for real ([markSongFailed]) rests 2 min, then 4, 8, 10 (per strike, jittered);
 * - after [maxConsecutiveFailures] REAL failures in a row (songs in a row; network problems and "no video" never count)
 *   the whole video mode rests ([killed] = true) for 60 to 90 s, then 2 to 3 min ... never more than 10 min;
 * - a first frame ([recordSuccess]) ends the run of failures and resets the length of the next rest.
 */
class VideoSession(
    private val maxConsecutiveFailures: Int = 3,
    private val maxNoVideoEntries: Int = 200,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> Double = Math::random,
) {
    private val restUntil = LinkedHashMap<String, Long>()
    private val songStrikes = HashMap<String, Int>()

    @Volatile
    var consecutiveFailures: Int = 0
        private set

    /** How many times the whole mode rested since the last first frame (sets the length of the next rest). */
    @Volatile
    var strikes: Int = 0
        private set

    @Volatile
    private var coolUntilMs: Long = 0L

    /** The whole video mode is resting now (it comes back by itself). */
    val killed: Boolean
        get() = clock() < coolUntilMs

    /** Milliseconds until the rest of the whole mode ends; 0 when it is not resting. */
    fun cooldownRemainingMs(): Long = (coolUntilMs - clock()).coerceAtLeast(0L)

    @Synchronized
    fun markNoVideo(
        id: String,
        ttlMs: Long = VideoBackoff.NO_VIDEO_TTL_MS,
    ) {
        restUntil.remove(id)
        restUntil[id] = clock() + ttlMs
        while (restUntil.size > maxNoVideoEntries) restUntil.remove(restUntil.keys.first())
    }

    /** A video failed for real for this song: the song rests for a growing, jittered time. Returns the time in ms. */
    @Synchronized
    fun markSongFailed(id: String): Long {
        val strike = (songStrikes[id] ?: 0) + 1
        songStrikes.remove(id)
        songStrikes[id] = strike
        while (songStrikes.size > maxNoVideoEntries) songStrikes.remove(songStrikes.keys.first())
        val ms = VideoBackoff.songCooldownMs(strike, random())
        markNoVideo(id, ms)
        return ms
    }

    @Synchronized
    fun isNoVideo(id: String): Boolean {
        val until = restUntil[id] ?: return false
        if (clock() < until) return true
        restUntil.remove(id)
        return false
    }

    /** Milliseconds until [id] is tried again; 0 when it is not resting. */
    @Synchronized
    fun songRestRemainingMs(id: String): Long = ((restUntil[id] ?: 0L) - clock()).coerceAtLeast(0L)

    /**
     * A start failed for real (after its fresh try). Returns true when this one made the whole mode rest. Network
     * problems are not reported here.
     */
    @Synchronized
    fun recordFailure(): Boolean {
        if (killed) return false
        if (coolUntilMs != 0L) {
            // the last rest is over: a new run of failures starts from zero
            coolUntilMs = 0L
            consecutiveFailures = 0
        }
        consecutiveFailures++
        if (consecutiveFailures >= maxConsecutiveFailures) {
            strikes++
            coolUntilMs = clock() + VideoBackoff.cooldownMs(strikes, random())
            return true
        }
        return false
    }

    /** A first frame was drawn: the run of failures is over and the next rest starts short again. */
    @Synchronized
    fun recordSuccess(id: String? = null) {
        consecutiveFailures = 0
        strikes = 0
        if (id != null) songStrikes.remove(id)
    }
}

object GlobalVideoSession {
    val shared = VideoSession()
}

/**
 * Video work never takes a turn before the sound: a video extraction waits (a few seconds at most) while an audio
 * extraction is running, so the song that is loading is never held up by a picture (REVIEW-2026-11-07 F13).
 */
object VideoPriority {
    const val POLL_MS = 150L
    const val MAX_WAIT_MS = 6_000L

    /** Returns when [busy] is false, or after [maxWaitMs]; never longer. */
    suspend fun awaitIdle(
        busy: () -> Boolean,
        pollMs: Long = POLL_MS,
        maxWaitMs: Long = MAX_WAIT_MS,
    ) {
        var waited = 0L
        while (waited < maxWaitMs && busy()) {
            kotlinx.coroutines.delay(pollMs)
            waited += pollMs
        }
    }
}
