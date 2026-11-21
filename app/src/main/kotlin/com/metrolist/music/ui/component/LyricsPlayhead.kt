/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Where the song is, for the lyrics, on every frame. The player is read once per frame and the reading steers a clock
 * that runs on the frame time, so the sweep, the word fill and the line changes move on every frame, also when a reading
 * is late or arrives only twice a second (cast). Before (4.3.4) the view was handed the progress bar's position, which
 * the player screen refreshes every 100 ms, so the sung part of a line moved in 100 ms steps.
 */

package com.metrolist.music.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.playback.PlayerConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** What the lyrics need to know about playback. Everything is read on the main thread, once per frame at most. */
interface LyricsPlayheadSource {
    /** The player's position in ms (the song's own time, without the lyrics offset). */
    fun positionMs(): Long

    fun isPlaying(): Boolean

    fun speed(): Float = 1f

    /** A number that changes whenever the player jumps on purpose: a seek, another song. */
    fun jumps(): Int = 0

    /** A snapshot read that changes when anything above changes; a paused view waits on it instead of running every frame. */
    fun changeToken(): Any = Unit

    /** True when [changeToken] really changes on every change (a player listener): a paused view then waits with no timer at all. */
    fun hasEvents(): Boolean = false
}

/** A playhead read from plain functions (cast, tests). It has no events, so a paused view looks again every quarter second. */
internal class LambdaPlayheadSource(
    private val position: () -> Long,
    private val playing: () -> Boolean,
    private val rate: () -> Float = { 1f },
) : LyricsPlayheadSource {
    override fun positionMs(): Long = position()

    override fun isPlaying(): Boolean = playing()

    override fun speed(): Float = rate()
}

/**
 * The app's own player. The listener counts the jumps (seek, skip, a new song) and every change the lyrics wait for
 * while paused. The player object can be replaced by the service (crossfade), so it is always read through [player].
 */
internal class PlayerPlayheadSource(private val player: () -> Player?) : LyricsPlayheadSource, Player.Listener {
    private val changes = mutableIntStateOf(0)
    private var jumpCount = 0
    private var attached: Player? = null

    override fun positionMs(): Long = runCatching { player()?.currentPosition }.getOrNull() ?: 0L

    override fun isPlaying(): Boolean = runCatching { player()?.isPlaying }.getOrNull() == true

    override fun speed(): Float = runCatching { player()?.playbackParameters?.speed }.getOrNull() ?: 1f

    override fun jumps(): Int = jumpCount

    override fun changeToken(): Any = changes.intValue

    override fun hasEvents(): Boolean = true

    fun attach(to: Player?) {
        if (to === attached) return
        attached?.removeListener(this)
        attached = to
        to?.addListener(this)
        changes.intValue++
        jumpCount++
    }

    fun detach() = attach(null)

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        jumpCount++
        changes.intValue++
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        jumpCount++
        changes.intValue++
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        changes.intValue++
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        changes.intValue++
    }
}

/** The playhead of the app's player, following the player object the service hands out. */
@Composable
internal fun rememberPlayerPlayhead(playerConnection: PlayerConnection): PlayerPlayheadSource {
    val source = remember(playerConnection) { PlayerPlayheadSource { runCatching { playerConnection.player }.getOrNull() } }
    LaunchedEffect(source) {
        playerConnection.service.playerFlow.collect { source.attach(it ?: runCatching { playerConnection.player }.getOrNull()) }
    }
    DisposableEffect(source) { onDispose { source.detach() } }
    return source
}

/**
 * The lyric clock. Each frame it moves by the frame time times the playback speed, and the player's reading steers it:
 * behind the reading it runs up to [MAX_FAST] faster, ahead of it up to [MAX_SLOW] slower, never backwards. It jumps
 * only when the player says it jumped (a seek, another song), when the speed changes, or when it is [SNAP_MS] off (a
 * jump nobody announced). Paused, it stands where the player says. Plain arithmetic, tested frame by frame.
 * (BitChord's LyricClock steers the same way; it also learns the rate from sparse readings, which is not needed here:
 * the player is read every frame and tells its speed.)
 */
internal class LyricsClock {
    /** Where the lyrics are drawn, in song ms. */
    var positionMs: Double = 0.0
        private set

    /** True when the last step jumped: a seek or another song (announced or not). The view may then scroll backwards. */
    var jumped: Boolean = false
        private set

    private var lastNowMs = Double.NaN
    private var anchorAtMs = 0.0
    private var anchorMs = 0.0
    private var lastReading = Long.MIN_VALUE
    private var lastJumps = 0
    private var lastSpeed = 1.0

    /** Playback stands still (paused, loading, the slider held): the lyrics stand where the player says. */
    fun hold(readingMs: Long): Long {
        jumped = abs(readingMs - positionMs) > JUMP_MS
        positionMs = readingMs.toDouble()
        lastReading = readingMs
        lastNowMs = Double.NaN
        return readingMs
    }

    /** Advances to the frame at [nowMs] (frame time, ms) and returns where the lyrics are. */
    fun frame(nowMs: Double, readingMs: Long, speed: Float, jumps: Int): Long {
        val rate = if (speed.isFinite() && speed > 0f) speed.toDouble() else 1.0
        val announced = jumps != lastJumps
        lastJumps = jumps
        if (lastNowMs.isNaN() || announced || rate != lastSpeed) {
            jumped = announced || abs(readingMs - positionMs) > JUMP_MS
            lastSpeed = rate
            lastNowMs = nowMs
            anchorAtMs = nowMs
            anchorMs = readingMs.toDouble()
            lastReading = readingMs
            positionMs = readingMs.toDouble()
            return readingMs
        }
        val dt = (nowMs - lastNowMs).coerceAtLeast(0.0)
        lastNowMs = nowMs
        if (readingMs != lastReading) {
            lastReading = readingMs
            anchorAtMs = nowMs
            anchorMs = readingMs.toDouble()
        }
        // a reading that stopped moving while "playing" (a cast device buffering) is carried forward [MAX_AHEAD_MS] at most
        val sinceReading = nowMs - anchorAtMs
        val stalled = sinceReading > MAX_AHEAD_MS
        val target = anchorMs + minOf(sinceReading, MAX_AHEAD_MS) * rate
        val step = dt * rate
        val error = target - (positionMs + step)
        // forwards a far reading is jumped to; backwards only beyond what the clock may have run ahead, so the end of a
        // stall is caught up by running slower, never by going back
        val far = error >= SNAP_MS || error <= -(MAX_AHEAD_MS + SNAP_MS)
        jumped = !stalled && far
        positionMs =
            when {
                // stalled: walk up to where the song was last seen and stand there (no run ahead)
                stalled -> maxOf(positionMs, minOf(positionMs + step, target))
                far -> target
                else -> positionMs + step + (error * dt / (dt + CORRECTION_MS)).coerceIn(-MAX_SLOW * step, MAX_FAST * step)
            }
        return positionMs.toLong()
    }

    companion object {
        /** The gap to the reading closes with this time constant (a few ms of the player's own jitter never shows). */
        const val CORRECTION_MS = 300.0

        /** At most this much faster than the song while catching up, and this much slower while ahead (never stopped). */
        const val MAX_FAST = 0.25
        const val MAX_SLOW = 0.5

        /** A reading this far ahead is another place in the song: the clock goes there at once (backwards: see [frame]). */
        const val SNAP_MS = 500.0

        /** A snap or a paused reading that moves more than this counts as a jump (a seek) for the view. */
        const val JUMP_MS = 50.0

        /** A reading is carried forward this long at most; the cast position comes every 500 ms. */
        const val MAX_AHEAD_MS = 800.0
    }
}

/** A paused view without player events (cast) looks again after this long. */
internal const val PAUSED_LOOK_MS = 250L

/**
 * Runs the clock while the composition lives: every frame while playing or while the slider is held, and while paused
 * only when the player says something changed. [onPosition] gets the song position (ms) and whether the user holds the
 * slider; it runs inside the frame, so what it writes is drawn in the same frame.
 */
internal suspend fun runLyricsPlayhead(
    source: LyricsPlayheadSource,
    clock: LyricsClock,
    sliderPosition: () -> Long?,
    onPosition: (positionMs: Long, seeking: Boolean) -> Unit,
) {
    while (currentCoroutineContext().isActive) {
        when {
            sliderPosition() != null ->
                withFrameNanos {
                    val held = sliderPosition()
                    if (held != null) onPosition(clock.hold(held), true)
                }
            !source.isPlaying() -> {
                onPosition(clock.hold(source.positionMs()), false)
                val seen = source.changeToken()
                if (source.hasEvents()) {
                    // the player says when anything changes: nothing runs while paused, also in the background
                    snapshotFlow { source.changeToken() != seen || sliderPosition() != null }.first { it }
                } else {
                    // no events (cast): look again a quarter second later, and only while frames are drawn
                    withFrameNanos { }
                    withTimeoutOrNull(PAUSED_LOOK_MS) {
                        snapshotFlow { source.changeToken() != seen || sliderPosition() != null }.first { it }
                    }
                }
            }
            else ->
                withFrameNanos { frameNanos ->
                    onPosition(clock.frame(frameNanos / 1_000_000.0, source.positionMs(), source.speed(), source.jumps()), false)
                }
        }
    }
}

/**
 * [com.metrolist.music.lyrics.LyricsUtils.findActiveLineIndices] for one list, asked every frame: its answer only changes
 * where a line starts or ends, so it is worked out again only when the position crosses one of those times (before,
 * the view rebuilt the set of active lines 30 times a second). Gives exactly the same answer at every position.
 */
internal class ActiveLinesCache(lines: List<LyricsEntry>, private val find: (Long) -> Set<Int>) {
    private val breaks: LongArray
    private var from = Long.MAX_VALUE
    private var until = Long.MIN_VALUE
    private var cached: Set<Int> = emptySet()

    init {
        val all = ArrayList<Long>(lines.size * 2)
        for ((i, line) in lines.withIndex()) {
            all += line.time
            val end =
                if (!line.words.isNullOrEmpty()) {
                    (line.words.last().endTime * 1000).toLong()
                } else if (i + 1 < lines.size) {
                    lines[i + 1].time
                } else {
                    Long.MAX_VALUE
                }
            if (end != Long.MAX_VALUE) all += end + 1
        }
        breaks = all.distinct().sorted().toLongArray()
    }

    fun at(positionMs: Long): Set<Int> {
        if (positionMs >= from && positionMs < until) return cached
        cached = find(positionMs)
        // the first break after the position
        var lo = 0
        var hi = breaks.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (breaks[mid] <= positionMs) lo = mid + 1 else hi = mid
        }
        from = if (lo == 0) Long.MIN_VALUE else breaks[lo - 1]
        until = if (lo == breaks.size) Long.MAX_VALUE else breaks[lo]
        return cached
    }
}
