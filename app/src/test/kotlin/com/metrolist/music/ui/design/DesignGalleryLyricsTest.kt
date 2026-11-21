/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.WordTimestamp
import com.metrolist.music.ui.component.IntervalIndicator
import com.metrolist.music.ui.component.LyricDots
import com.metrolist.music.ui.component.LyricsLine
import com.metrolist.music.ui.component.LyricsMotion
import com.metrolist.music.ui.component.LyricsMotionEnv
import com.metrolist.music.ui.component.lyricsEdgeFade
import com.metrolist.music.ui.screens.settings.LyricsPosition
import com.metrolist.music.ui.theme.MetrolistTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * The lyrics view rendered to PNG without a device: the real [LyricsLine], [LyricDots], [IntervalIndicator] and the edge
 * fade, laid out like the experimental view lays them out (the active row a third from the top, the others stacked
 * around it). Output: build/design-gallery/lyrics, one PNG per scene.
 *
 * The pictures cannot show: blur (Robolectric draws in software and cannot draw a RenderEffect, so the blur is judged
 * by reading the code), the glide between states, touch, the frame rate.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w1240dp-h880dp-hdpi")
class DesignGalleryLyricsTest {
    @get:Rule
    val rule = createComposeRule()

    private fun save(name: String): android.graphics.Bitmap {
        rule.mainClock.advanceTimeBy(2000) // the staggered entrance of untimed lines waits up to 560 ms and then takes 450 ms
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/design-gallery/lyrics").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bmp.width > 100 && bmp.height > 100)
        return bmp
    }

    private fun show(dark: Boolean, content: @Composable () -> Unit) {
        rule.setContent {
            MetrolistTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        rule.waitForIdle()
    }

    // ---------------------------------------------------------------- the stage

    /** One row of the stage: a lyric line or the dots of an instrumental gap (filled to [gap]). */
    private class SRow(val entry: LyricsEntry? = null, val gap: Float = 0f)

    /** Where the active row ended up, in px inside the stage, so the pictures can be measured. */
    private class Bounds {
        var top = 0
        var height = 0
        var width = 0
    }

    private fun line(time: Long, text: String) = LyricsEntry(time, text)

    /** A line with a timing for every word: [perWordMs] each. */
    private fun timed(time: Long, text: String, perWordMs: Long = 420L): LyricsEntry {
        val parts = text.split(" ")
        return LyricsEntry(
            time,
            text,
            words = parts.mapIndexed { i, w ->
                WordTimestamp(w, (time + i * perWordMs) / 1000.0, (time + (i + 1) * perWordMs) / 1000.0, i < parts.lastIndex)
            },
        )
    }

    /** The position that puts the sweep of the active line at [fraction] (lines without words: the Windows rule). */
    private fun positionAt(entry: LyricsEntry, nextTime: Long?, fraction: Float): Long {
        val words = entry.words
        return if (words != null) {
            val start = (words.first().startTime * 1000).toLong()
            val end = (words.last().endTime * 1000).toLong()
            start + ((end - start) * fraction).toLong()
        } else {
            entry.time + ((LyricsMotion.lineSweepEndMs(entry.time, nextTime, entry.text.length) - entry.time) * fraction).toLong()
        }
    }

    @Composable
    private fun Stage(
        rows: List<SRow>,
        active: Int,
        fraction: Float,
        position: LyricsPosition = LyricsPosition.LEFT,
        ink: Color = MaterialTheme.colorScheme.onSurface,
        modifier: Modifier = Modifier,
        synced: Boolean = true,
        relaxed: Boolean = false,
        bounds: Bounds? = null,
        steps: List<Int> = emptyList(),
    ) {
        val activeEntry = rows[active].entry
        val pos = if (activeEntry != null) positionAt(activeEntry, rows.getOrNull(active + 1)?.entry?.time, fraction) else 0L
        val horizontal = LyricsMotion.horizontalFor(null, true, position)
        val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { LyricsMotion.ROW_GAP_DP.dp.roundToPx() }
        Layout(
            modifier = modifier.lyricsEdgeFade().clipToBounds(),
            content = {
                rows.forEachIndexed { i, row ->
                    val entry = row.entry
                    if (entry != null) {
                        LyricsLine(
                            index = i, item = entry, nextTimeMs = rows.getOrNull(i + 1)?.entry?.time, isSynced = synced,
                            isActiveLine = i == active && synced, bgVisible = true, isSelected = false, isSelectionModeActive = false,
                            currentPositionProvider = { pos }, lyricsOffset = 0L,
                            distance = abs(i - active), onScreen = true, edgeStep = { steps.getOrElse(i) { 0 } }, motion = LyricsMotionEnv.Still,
                            lyricsTextSize = LyricsMotion.TEXT_SP, lyricsLineSpacing = LyricsMotion.LINE_HEIGHT_RATIO,
                            expressiveAccent = ink, lyricsTextPosition = position, respectAgentPositioning = true,
                            isAutoScrollEnabled = !relaxed, romanizeAsMain = false, enabledLanguages = emptyList(), romanizeLyrics = false,
                            onSizeChanged = {}, onClick = {}, onLongClick = {},
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                            LyricDots(breathing = false, progress = { row.gap }, ink = ink, horizontal = horizontal)
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val loose = Constraints(minWidth = constraints.maxWidth, maxWidth = constraints.maxWidth, minHeight = 0, maxHeight = Constraints.Infinity)
            val placeables = measurables.map { it.measure(loose) }
            val anchor = LyricsMotion.anchorY(constraints.maxHeight.toFloat()).toInt()
            val tops = IntArray(placeables.size)
            tops[active] = anchor
            for (i in active - 1 downTo 0) tops[i] = tops[i + 1] - placeables[i].height - gapPx
            for (i in active + 1 until placeables.size) tops[i] = tops[i - 1] + placeables[i - 1].height + gapPx
            bounds?.let { it.top = tops[active]; it.height = placeables[active].height; it.width = constraints.maxWidth }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { i, p -> p.place(0, tops[i]) }
            }
        }
    }

    /** Three phones side by side, each [Stage]d the same way. */
    @Composable
    private fun Phones(content: @Composable (Int) -> Unit) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (i in 0 until 3) {
                Box(Modifier.width(396.dp).height(860.dp).padding(top = 8.dp)) { content(i) }
            }
        }
    }

    private val windowsRows = listOf(
        SRow(line(0, "Lampu kota menyala pelan")),
        SRow(line(4000, "Kita berjalan tanpa arah")),
        SRow(gap = 0f),
        SRow(line(12000, "Angin malam membawa cerita")),
        SRow(line(16000, "Tentang hari yang hampir selesai")),
        SRow(line(20000, "Dan lagu ini tetap berputar")),
        SRow(line(24000, "Di kepala sampai pagi")),
        SRow(gap = 0.4f),
        SRow(line(36000, "Pelan pelan semua terasa ringan")),
    )

    // ---------------------------------------------------------------- pictures

    private fun sweepScene(dark: Boolean, name: String) {
        show(dark) {
            Phones { i ->
                Stage(windowsRows, active = 3, fraction = listOf(0f, 0.5f, 1f)[i], modifier = Modifier.fillMaxSize())
            }
        }
        save(name)
    }

    /** The sweep read from the pixels: ink of the active line by column at 0, 50 and 100 percent. */
    @Test
    fun the_sweep_shows_in_the_pixels() {
        val bounds = List(3) { Bounds() }
        val shortRows = listOf(
            SRow(line(0, "Lampu kota menyala pelan")),
            SRow(line(4000, "Angin malam")),
            SRow(line(8000, "Tentang hari yang hampir selesai")),
        )
        show(false) {
            Phones { i ->
                Stage(shortRows, active = 1, fraction = listOf(0f, 0.5f, 1f)[i], bounds = bounds[i], modifier = Modifier.fillMaxSize())
            }
        }
        val bmp = save("lyrics-light-sweep-pixels")
        fun columns(i: Int): DoubleArray {
            val b = bounds[i]
            val phoneX = ((12 + i * (396 + 12)) * 1.5f).toInt()
            val top = b.top + (8 * 1.5f).toInt()
            return DoubleArray(b.width) { x ->
                var sum = 0.0
                for (y in top until top + b.height) {
                    val px = bmp.getPixel(phoneX + x, y)
                    sum += 1.0 - (0.299 * android.graphics.Color.red(px) + 0.587 * android.graphics.Color.green(px) + 0.114 * android.graphics.Color.blue(px)) / 255.0
                }
                sum / b.height
            }
        }
        val c0 = columns(0)
        val c50 = columns(1)
        val c100 = columns(2)
        val inked = c100.indices.filter { c100[it] > 0.06 }
        assertTrue("the line has ink", inked.size > 50)
        val from = inked.first()
        val to = inked.last()
        val quarter = (to - from) / 4
        fun mean(c: DoubleArray, a: Int, b: Int) = (a until b).map { c[it] }.average()
        val full = mean(c100, from, to)
        val faint = mean(c0, from, to)
        assertTrue("0 percent is the faint ink, about 40 percent of full: $faint / $full", faint / full in 0.25..0.60)
        val leftSung = mean(c50, from, from + quarter) / mean(c100, from, from + quarter)
        val rightUnsung = mean(c50, to - quarter, to) / mean(c100, to - quarter, to)
        assertTrue("at 50 percent the left of the line is sung: $leftSung", leftSung > 0.85)
        assertTrue("at 50 percent the right of the line is not sung: $rightUnsung", rightUnsung < 0.60)
    }

    @Test
    fun sweep_light() = sweepScene(dark = false, name = "lyrics-light-sweep-0-50-100")

    @Test
    fun sweep_dark() = sweepScene(dark = true, name = "lyrics-dark-sweep-0-50-100")

    @Test
    fun positions_light() {
        show(false) {
            Phones { i ->
                Stage(windowsRows, active = 3, fraction = 0.5f, position = listOf(LyricsPosition.LEFT, LyricsPosition.CENTER, LyricsPosition.RIGHT)[i], modifier = Modifier.fillMaxSize())
            }
        }
        save("lyrics-light-left-centre-right")
    }

    @Test
    fun special_lines_light() = specialLines(dark = false)

    @Test
    fun special_lines_dark() = specialLines(dark = true)

    private fun specialLines(dark: Boolean) {
        run {
            val long = timed(8000, "Aku masih ingat bagaimana senja itu pelan pelan memeluk kota kecil kita")
            val rows1 = listOf(
                SRow(line(0, "Kita berjalan tanpa arah")),
                SRow(line(4000, "Lampu kota menyala pelan")),
                SRow(long),
                SRow(line(16000, "Dan malam pun jatuh tanpa suara")),
                SRow(line(20000, "Tentang hari yang hampir selesai")),
                SRow(line(24000, "Pelan pelan semua terasa ringan")),
            )
            val rows2 = listOf(
                SRow(line(0, "Мы шли по ночному городу")),
                SRow(timed(4000, "كل شيء هادئ هذه الليلة", 500)),
                SRow(line(10000, "Огни города таяли вдали")),
                SRow(line(14000, "И песня продолжала звучать")),
                SRow(line(18000, "Привет, мир")),
                SRow(line(22000, "Тихо, тихо, до самого утра")),
            )
            val bg = LyricsEntry(12000, "(ooh, ooh)", words = listOf(WordTimestamp("(ooh,", 12.0, 12.6, true), WordTimestamp("ooh)", 12.6, 13.2, false)), isBackground = true)
            val rows3 = listOf(
                SRow(line(0, "Lampu kota menyala pelan")),
                SRow(line(4000, "Kita berjalan tanpa arah")),
                SRow(timed(8000, "Angin malam membawa cerita", 520)),
                SRow(bg),
                SRow(timed(12000, "Oh-oh-oh sampai pagi", 600)),
                SRow(line(16000, "Tentang hari yang hampir selesai")),
            )
            show(dark) {
                Phones { i ->
                    when (i) {
                        0 -> Stage(rows1, active = 2, fraction = 0.5f, modifier = Modifier.fillMaxSize())
                        1 -> Stage(rows2, active = 1, fraction = 0.5f, modifier = Modifier.fillMaxSize())
                        else -> Stage(rows3, active = 2, fraction = 0.7f, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            save(if (dark) "lyrics-dark-special-wrapped-rtl-bg" else "lyrics-light-special-wrapped-rtl-bg")
        }
    }

    @Test
    fun dots_light() = dots(dark = false)

    @Test
    fun dots_dark() = dots(dark = true)

    private fun dots(dark: Boolean) {
        run {
            show(dark) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("0%, 17%, 50%, 83%, 100% (left)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                        for (p in listOf(0f, 0.17f, 0.5f, 0.83f, 1f)) {
                            Box(Modifier.width(80.dp)) { LyricDots(false, { p }, MaterialTheme.colorScheme.onSurface, horizontal = 0f) }
                        }
                    }
                    Text("centre and right", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LyricDots(false, { 0.5f }, MaterialTheme.colorScheme.onSurface, Modifier.width(400.dp), horizontal = 0.5f)
                    LyricDots(false, { 0.5f }, MaterialTheme.colorScheme.onSurface, Modifier.width(400.dp), horizontal = 1f)
                    Text("the row of a gap as the view builds it (grown, 40% through)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.width(400.dp).background(MaterialTheme.colorScheme.surfaceContainer)) {
                        IntervalIndicator(0L, 1000L, { 400L }, visible = true, color = MaterialTheme.colorScheme.onSurface, horizontal = 0f, animate = false)
                    }
                }
            }
            save(if (dark) "lyrics-dark-dots" else "lyrics-light-dots")
        }
    }

    @Test
    fun over_a_cover_colour_dark() {
        show(true) {
            Box(
                Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A5568), Color(0xFF1B2A35), Color(0xFF0B1319)))),
            ) {
                Phones { i ->
                    Stage(windowsRows, active = 3, fraction = listOf(0.1f, 0.5f, 0.9f)[i], ink = Color(0xFFF2F4F7), modifier = Modifier.fillMaxSize())
                }
            }
        }
        save("lyrics-dark-over-cover-colour")
    }

    @Test
    fun untimed_lyrics_step_towards_the_edges_and_manual_scroll() {
        val plainRows = listOf(
            SRow(line(0, "Lampu kota menyala pelan")),
            SRow(line(1, "Kita berjalan tanpa arah")),
            SRow(line(2, "Angin malam membawa cerita")),
            SRow(line(3, "Tentang hari yang hampir selesai")),
            SRow(line(4, "Dan lagu ini tetap berputar")),
            SRow(line(5, "Di kepala sampai pagi")),
            SRow(line(6, "Pelan pelan semua terasa ringan")),
            SRow(line(7, "Sampai kita lupa pulang")),
        )
        show(false) {
            Phones { i ->
                when (i) {
                    0 -> Stage(plainRows, active = 0, fraction = 0f, synced = false, steps = listOf(0, 0, 0, 1, 1, 2, 2, 2), modifier = Modifier.fillMaxSize())
                    // a user scrolling by hand: no distance dimming, the sung line stays full
                    1 -> Stage(windowsRows, active = 3, fraction = 0.5f, relaxed = true, modifier = Modifier.fillMaxSize())
                    else -> Stage(windowsRows, active = 0, fraction = 0.3f, modifier = Modifier.fillMaxSize())
                }
            }
        }
        save("lyrics-light-untimed-manual-start")
    }
}
