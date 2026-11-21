/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsUtils
import com.metrolist.music.lyrics.WordTimestamp
import com.metrolist.music.ui.screens.settings.LyricsPosition
import com.metrolist.music.ui.theme.MetrolistTheme
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs

/**
 * The lyrics list frame by frame (Robolectric, paused clock, 60 fps): a song plays through line-synced, fast,
 * word-synced and 300-line lyrics, and every frame records the lyric position, how many lines recomposed, how often the
 * list measured and placed, how many scroll animations started, and where every visible row is.
 *
 * The new view ([LyricsListView], fed by the player every frame) must: move the position on every frame, never back;
 * recompose no line between line changes; never stop a row in the middle of a glide; never jump a row.
 * With LYRICS_SMOOTH_COMPARE=1 the 4.3.4 list ([OldLyricsListHarness], fed like 4.3.4 by the player screen's 100 ms
 * progress value) runs the same frames, for the before/after table in build/lyrics-smoothness/metrics.txt.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w411dp-h860dp-xhdpi")
class LyricsSmoothnessTest {
    @get:Rule
    val rule = createComposeRule()

    @OptIn(InternalComposeTracingApi::class)
    private class LineCounter : CompositionTracer {
        var lines = 0

        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            if (info.startsWith("com.metrolist.music.ui.component.LyricsLine ")) lines++
        }

        override fun traceEventEnd() = Unit

        override fun isTraceInProgress(): Boolean = true
    }

    private val counter = LineCounter()

    @OptIn(InternalComposeTracingApi::class)
    @Before
    fun traceOn() = Composer.setTracer(counter)

    @OptIn(InternalComposeTracingApi::class)
    @After
    fun traceOff() = Composer.setTracer(null)

    /** A player like ExoPlayer: the position in 10 ms steps with a few ms of jitter. */
    private class FakePlayer(var startPos: Long) {
        var startAt = 0L
        var jumps = 0

        fun position(now: Long): Long {
            val exact = startPos + (now - startAt)
            return (exact / 10L) * 10L - ((now / 10L) * 2654435761L % 5L).let { if (it < 0) -it else it }
        }
    }

    class Fixture(val name: String, val lines: List<LyricsEntry>, val merged: List<LyricsListItem>, val text: String)

    data class Metrics(
        val name: String,
        val frames: Int,
        val positionStillFrames: Int,
        val positionWorstStepMs: Double,
        val positionBackwards: Int,
        val lineChanges: Int,
        val lineCompositions: Int,
        val lineCompositionsQuiet: Int,
        val maxLineCompositionsInAFrame: Int,
        val measures: Int,
        val placementsPerFrame: Double,
        val rowsPlacedPerFrame: Double,
        val animationsStarted: Int,
        val maxRowStepPx: Float,
        val stopAndGo: Int,
    ) {
        fun row(): String =
            (
                "%-28s frames=%4d posStill=%4d posWorstStep=%6.1fms posBack=%2d lineChanges=%3d lineComps=%5d (quiet %4d, max/frame %3d) " +
                    "measures=%4d placements/frame=%6.1f rowsPlaced/frame=%6.1f animsStarted=%5d maxRowStep=%6.1fpx stopAndGo=%3d"
                ).format(
                        name, frames, positionStillFrames, positionWorstStepMs, positionBackwards, lineChanges, lineCompositions,
                        lineCompositionsQuiet, maxLineCompositionsInAFrame, measures, placementsPerFrame, rowsPlacedPerFrame,
                        animationsStarted, maxRowStepPx, stopAndGo,
                    )
    }

    private val style =
        LyricsListStyle(
            textPosition = LyricsPosition.LEFT,
            textSizeSp = LyricsMotion.TEXT_SP,
            lineSpacing = LyricsMotion.LINE_HEIGHT_RATIO,
            respectAgentPositioning = true,
            romanizeAsMain = false,
            enabledLanguages = emptyList(),
            romanizeLyrics = false,
            motion = LyricsMotionEnv.NoBlur,
            ink = Color(0xFFF2F2F2),
        )

    private val phrases =
        listOf(
            "Angin malam membawa cerita",
            "Di bawah lampu kota yang redup kita berjalan pelan menembus hujan yang turun",
            "Oh",
            "Hati yang lelah mencari rumah",
            "Semua yang pernah kita janjikan",
            "Tak ada yang tahu ke mana arah kita pergi setelah lagu ini selesai diputar",
            "Pelan pelan",
            "Kau dan aku",
        )

    private fun lrc(ms: Long) = "[%02d:%02d.%02d]".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10)

    /** The list the view model makes (LyricsViewModel.updateMergedList, gap dots on). */
    private fun merge(lines: List<LyricsEntry>): List<LyricsListItem> {
        val result = mutableListOf<LyricsListItem>()
        lines.forEachIndexed { i, entry ->
            if (entry.text.isNotBlank()) result.add(LyricsListItem.Line(i, entry))
            if (i < lines.size - 1) {
                val nextStart = lines[i + 1].time
                val currentEnd =
                    if (!entry.words.isNullOrEmpty()) {
                        (entry.words.last().endTime * 1000).toLong()
                    } else if (entry.text.isBlank()) {
                        entry.time
                    } else {
                        null
                    }
                if (currentEnd != null && currentEnd < nextStart && nextStart - currentEnd > 4000L) {
                    result.add(LyricsListItem.Indicator(i, nextStart - currentEnd, currentEnd, nextStart, lines[i + 1].agent))
                }
            }
        }
        return result
    }

    private fun fixture(name: String, count: Int, every: (Int) -> Long, words: Boolean = false): Fixture {
        var t = 6000L
        val body =
            (0 until count).map { i ->
                val text = phrases[i % phrases.size] + if (i >= phrases.size) " ${i / phrases.size}" else ""
                val start = t
                t += every(i)
                if (words) {
                    val parts = text.split(" ")
                    val span = (every(i) - 300L).coerceAtLeast(300L)
                    val ws =
                        parts.mapIndexed { k, w ->
                            val a = start + span * k / parts.size
                            val b = start + span * (k + 1) / parts.size
                            WordTimestamp(w, a / 1000.0, b / 1000.0, k < parts.size - 1)
                        }
                    LyricsEntry(start, text, words = ws)
                } else {
                    LyricsEntry(start, text)
                }
            }
        val lines = listOf(LyricsEntry.HEAD_LYRICS_ENTRY) + body
        val text = body.joinToString("\n") { lrc(it.time) + it.text }
        return Fixture(name, lines, merge(lines), text)
    }

    private val normal get() = fixture("line-synced", 40, { i -> if (i == 9) 9000L else 2200L + (i % 4) * 350L })
    private val rapid get() = fixture("fast lines (550 ms)", 60, { 550L })
    private val wordSynced get() = fixture("word-synced", 30, { i -> 2400L + (i % 3) * 300L }, words = true)
    private val large get() = fixture("300 lines", 300, { 1800L })

    private fun clockNow(): Long = rule.mainClock.currentTime

    private fun run(old: Boolean, fx: Fixture, startMs: Long, frames: Int): Metrics {
        val player = FakePlayer(startMs)
        val clock = LyricsClock()
        val follow = LyricsFollow()
        val rows = LyricsRowsState().also { it.probeY = FloatArray(fx.merged.size) { Float.NaN } }
        val probe = OldProbe()
        rule.mainClock.autoAdvance = false
        player.startAt = clockNow()
        val source =
            object : LyricsPlayheadSource {
                override fun positionMs(): Long = player.position(clockNow())

                override fun isPlaying(): Boolean = true

                override fun jumps(): Int = player.jumps
            }
        rule.setContent {
            MetrolistTheme(darkTheme = true) {
                Surface {
                    Box(Modifier.width(411.dp).height(700.dp)) {
                        if (old) {
                            // what 4.3.4 handed to the lyrics: the player screen's progress value, refreshed every 100 ms
                            val progress = remember { mutableLongStateOf(player.position(clockNow())) }
                            LaunchedEffect(Unit) {
                                while (true) {
                                    delay(100)
                                    progress.longValue = player.position(clockNow())
                                }
                            }
                            OldLyricsListHarness(
                                lines = fx.lines, mergedLyricsList = fx.merged, isSynced = true,
                                playerPosition = { player.position(clockNow()) }, playerIsPlaying = { true }, wallClockMs = { clockNow() },
                                sliderPositionProvider = { progress.longValue }, style = style, probe = probe,
                            )
                        } else {
                            LyricsListView(
                                lines = fx.lines, mergedLyricsList = fx.merged, lyricsText = fx.text, playhead = source,
                                sliderPositionProvider = { null }, lyricsOffset = 0, style = style, providerName = null,
                                onChipClick = {}, canSeek = true, songDurationMs = { 900_000L }, onSeek = {}, showTiming = false,
                                onTimingTap = { _, _ -> }, onShare = {}, follow = follow, rowsState = rows, clock = clock,
                            )
                        }
                    }
                }
            }
        }
        // settle: first layout, the first glide and the fade-in
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()

        val heightPx = 700f * 2f
        val n = fx.merged.size
        fun position(): Long = if (old) probe.position else clock.positionMs.toLong()
        fun rowYs(): FloatArray = if (old) FloatArray(n) { probe.rowY[it] ?: Float.NaN } else rows.probeY!!.copyOf()
        fun activeKey(p: Long) = LyricsUtils.findActiveLineIndices(fx.lines, p).toString() + LyricsUtils.findActiveLineIndices(fx.lines, p + 250).toString()

        var lastPos = position()
        var lastYs = rowYs()
        var lastDeltas = FloatArray(n)
        val stoppedFor = IntArray(n)
        val wasMoving = BooleanArray(n)
        var lastKey = activeKey(lastPos)
        var quietCountdown = 0
        var stillFrames = 0
        var worstStep = 0.0
        var backwards = 0
        var lineChanges = 0
        var comps = 0
        var compsQuiet = 0
        var maxComps = 0
        var maxRowStep = 0f
        var stopAndGo = 0
        val measures0 = if (old) probe.rowMeasures else rows.measures
        val placements0 = if (old) probe.rowPlacements else rows.placements
        val anims0 = if (old) probe.animationTargets else follow.starts
        var rowsPlaced = 0L

        repeat(frames) {
            val c0 = counter.lines
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            val dc = counter.lines - c0
            val p = position()
            val step = p - lastPos
            if (step == 0L) stillFrames++
            if (step < 0) backwards++
            worstStep = maxOf(worstStep, abs(step - 1000.0 / 60.0))
            val key = activeKey(p)
            if (key != lastKey) {
                lineChanges++
                quietCountdown = 4 // the frame of the change and the next few (4.3.4 updated its anchor a frame later)
            }
            comps += dc
            maxComps = maxOf(maxComps, dc)
            if (quietCountdown == 0) compsQuiet += dc else quietCountdown--
            lastKey = key
            lastPos = p
            if (!old) rowsPlaced += rows.placedRows
            val ys = rowYs()
            for (i in 0 until n) {
                val a = lastYs[i]
                val b = ys[i]
                if (a.isNaN() || b.isNaN()) continue
                val visible = (a in -200f..heightPx) && (b in -200f..heightPx)
                val d = abs(b - a)
                if (visible) maxRowStep = maxOf(maxRowStep, d)
                // a row that moved, stood still for 1-15 frames and moves again: the old tweens' delay after a new line
                if (d > 1f) {
                    if (wasMoving[i] && stoppedFor[i] in 1..15 && visible) stopAndGo++
                    wasMoving[i] = true
                    stoppedFor[i] = 0
                } else if (d < 0.1f) {
                    if (wasMoving[i]) stoppedFor[i]++
                    if (stoppedFor[i] > 15) wasMoving[i] = false
                }
                lastDeltas[i] = d
            }
            lastYs = ys
        }
        val measures = (if (old) probe.rowMeasures else rows.measures) - measures0
        val placements = (if (old) probe.rowPlacements else rows.placements) - placements0
        val anims = (if (old) probe.animationTargets else follow.starts) - anims0
        return Metrics(
            name = (if (old) "4.3.4 " else "4.3.5 ") + fx.name,
            frames = frames,
            positionStillFrames = stillFrames,
            positionWorstStepMs = worstStep + 1000.0 / 60.0,
            positionBackwards = backwards,
            lineChanges = lineChanges,
            lineCompositions = comps,
            lineCompositionsQuiet = compsQuiet,
            maxLineCompositionsInAFrame = maxComps,
            measures = measures,
            placementsPerFrame = placements.toDouble() / frames,
            rowsPlacedPerFrame = if (old) placements.toDouble() / frames else rowsPlaced.toDouble() / frames,
            animationsStarted = anims,
            maxRowStepPx = maxRowStep,
            stopAndGo = stopAndGo,
        ).also { m ->
            val out = File("build/lyrics-smoothness").apply { mkdirs() }
            File(out, "metrics.txt").appendText(m.row() + "\n")
            println(m.row())
        }
    }

    private fun compare() = assumeTrue("set LYRICS_SMOOTH_COMPARE=1 to measure 4.3.4", System.getenv("LYRICS_SMOOTH_COMPARE") == "1")

    private fun assertSmooth(m: Metrics) {
        assertEquals("${m.name}: frames where the lyric position did not move", 0, m.positionStillFrames)
        assertEquals("${m.name}: the position went back", 0, m.positionBackwards)
        assertTrue("${m.name}: a frame stepped ${m.positionWorstStepMs} ms", m.positionWorstStepMs < 25.0)
        assertEquals("${m.name}: lines recomposed between line changes", 0, m.lineCompositionsQuiet)
        assertEquals("${m.name}: rows stopped in the middle of a glide", 0, m.stopAndGo)
        assertTrue("${m.name}: a row jumped ${m.maxRowStepPx} px in one frame", m.maxRowStepPx < 60f)
        assertTrue("${m.name}: line changes seen", m.lineChanges > 0)
    }

    @Test
    fun `new - line-synced lyrics glide and the sweep moves every frame`() {
        val m = run(old = false, normal, startMs = 9000L, frames = 1200)
        assertSmooth(m)
        // one glide per line change, not one animation per row
        assertTrue(m.animationsStarted <= m.lineChanges + 2)
    }

    @Test
    fun `new - fast lines never stop the rows`() {
        val m = run(old = false, rapid, startMs = 7000L, frames = 720)
        assertSmooth(m)
    }

    @Test
    fun `new - word-synced lyrics`() {
        val m = run(old = false, wordSynced, startMs = 9000L, frames = 900)
        assertSmooth(m)
    }

    @Test
    fun `new - 300 lines recompose only the rows that change`() {
        val m = run(old = false, large, startMs = 120_000L, frames = 600)
        assertSmooth(m)
        // about ten rows change their look at a line change, not the 300 of the list
        assertTrue("${m.maxLineCompositionsInAFrame} lines recomposed in one frame", m.maxLineCompositionsInAFrame <= 16)
        assertTrue("rows placed per frame ${m.rowsPlacedPerFrame}", m.rowsPlacedPerFrame < 40.0)
    }

    @Test
    fun `old - line-synced`() {
        compare()
        run(old = true, normal, startMs = 9000L, frames = 1200)
    }

    @Test
    fun `old - fast lines`() {
        compare()
        run(old = true, rapid, startMs = 7000L, frames = 720)
    }

    @Test
    fun `old - word-synced`() {
        compare()
        run(old = true, wordSynced, startMs = 9000L, frames = 900)
    }

    @Test
    fun `old - 300 lines`() {
        compare()
        run(old = true, large, startMs = 120_000L, frames = 600)
    }

    @Test
    fun `new - a new lyrics offset moves the view back to the line sung (timing bar)`() {
        val fx = normal
        val player = FakePlayer(40_000L)
        val clock = LyricsClock()
        val rows = LyricsRowsState().also { it.probeY = FloatArray(fx.merged.size) { Float.NaN } }
        val offset = mutableIntStateOf(0)
        val follow = LyricsFollow()
        rule.mainClock.autoAdvance = false
        player.startAt = clockNow()
        val source =
            object : LyricsPlayheadSource {
                override fun positionMs(): Long = player.position(clockNow())

                override fun isPlaying(): Boolean = true
            }
        rule.setContent {
            MetrolistTheme(darkTheme = true) {
                Surface {
                    Box(Modifier.width(411.dp).height(700.dp)) {
                        LyricsListView(
                            lines = fx.lines, mergedLyricsList = fx.merged, lyricsText = fx.text, playhead = source,
                            sliderPositionProvider = { null }, lyricsOffset = offset.intValue, style = style, providerName = null,
                            onChipClick = {}, canSeek = true, songDurationMs = { 900_000L }, onSeek = {}, showTiming = true,
                            onTimingTap = { _, _ -> }, onShare = {}, follow = follow, rowsState = rows, clock = clock,
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        // "8 s earlier": the line sung is now one far above
        offset.intValue = -8000
        var frames = 0
        while (frames < 30 || (follow.moving && frames < 240)) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            frames++
        }
        val effective = clock.positionMs.toLong() + offset.intValue
        val sung = LyricsUtils.findActiveLineIndices(fx.lines, effective + 250).filter { !fx.lines[it].isBackground }.maxOrNull()!!
        val row = listIndexOfLine(fx.merged, sung)
        val anchorY = 700f * 2f * LyricsMotion.ANCHOR_RATIO
        assertEquals("the sung line (row $row) sits at the anchor", anchorY, rows.probeY!![row], 3f)
    }

    @Test
    fun `paused, a player with events is not read until something changes`() {
        var reads = 0
        val events = mutableIntStateOf(0)
        val withEvents =
            object : LyricsPlayheadSource {
                override fun positionMs(): Long {
                    reads++
                    return 5000L
                }

                override fun isPlaying(): Boolean = false

                override fun changeToken(): Any = events.intValue

                override fun hasEvents(): Boolean = true
            }
        var looks = 0
        val noEvents =
            object : LyricsPlayheadSource {
                override fun positionMs(): Long {
                    looks++
                    return 5000L
                }

                override fun isPlaying(): Boolean = false
            }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LaunchedEffect(Unit) { runLyricsPlayhead(withEvents, LyricsClock(), { null }) { _, _ -> } }
            LaunchedEffect(Unit) { runLyricsPlayhead(noEvents, LyricsClock(), { null }) { _, _ -> } }
        }
        rule.mainClock.advanceTimeBy(5000)
        rule.waitForIdle()
        assertEquals("reads while paused with nothing changing", 1, reads)
        assertTrue("a source without events looks about 4 times a second, not every frame: $looks", looks in 10..25)
        // a seek while paused: the player says so and the lyrics follow at once
        events.intValue++
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(2, reads)
    }

    @Test
    fun `the rows never take an offset that is not a number`() {
        val follow = LyricsFollow()
        follow.shift(Float.NaN, 0) // ignored
        val rows = LyricsRowsState()
        rule.setContent {
            Box(Modifier.width(300.dp).height(400.dp)) {
                LyricsRowsLayout(
                    keys = longArrayOf(1L, 2L, 3L), anchorIndex = { 1 }, noGapBefore = { false }, follow = follow,
                    manualOffset = { Float.NaN }, glide = { true }, state = rows,
                    chip = {}, rows = { repeat(3) { Box(Modifier.width(300.dp).height(40.dp)) } },
                )
            }
        }
        rule.waitForIdle()
        assertEquals(3, rows.placedRows)
        assertTrue(!follow.moving)
    }
}
