/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.WordTimestamp
import com.metrolist.music.ui.screens.settings.LyricsPosition
import com.metrolist.music.ui.theme.MetrolistTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** One lyric line through real touches and a real layout (Robolectric): what must keep working under the new look. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h860dp-xhdpi")
class LyricsLineTest {
    @get:Rule
    val rule = createComposeRule()

    private var clicks = 0
    private var longClicks = 0
    private val heights = mutableListOf<Int>()

    @Composable
    private fun Line(
        item: LyricsEntry,
        active: Boolean = false,
        distance: Int = 2,
        bgVisible: Boolean = true,
        motion: LyricsMotionEnv = LyricsMotionEnv.Still,
        position: () -> Long = { 0L },
        romanize: Boolean = false,
        selecting: Boolean = false,
        selected: Boolean = false,
        synced: Boolean = true,
    ) {
        MetrolistTheme(darkTheme = false) {
            Surface {
                Box(Modifier.width(380.dp)) {
                    LyricsLine(
                        index = 1, item = item, nextTimeMs = 9000L, isSynced = synced,
                        isActiveLine = active, bgVisible = bgVisible, isSelected = selected, isSelectionModeActive = selecting,
                        currentPositionProvider = position, lyricsOffset = 0L,
                        distance = distance, onScreen = true, edgeStep = { 0 }, motion = motion,
                        lyricsTextSize = LyricsMotion.TEXT_SP, lyricsLineSpacing = LyricsMotion.LINE_HEIGHT_RATIO,
                        expressiveAccent = Color(0xFF0E1116), lyricsTextPosition = LyricsPosition.LEFT, respectAgentPositioning = true,
                        isAutoScrollEnabled = true, romanizeAsMain = false, enabledLanguages = if (romanize) listOf("ja") else emptyList(), romanizeLyrics = romanize,
                        onSizeChanged = { heights += it }, onClick = { clicks++ }, onLongClick = { longClicks++ },
                        modifier = Modifier.testTag("line"),
                    )
                }
            }
        }
    }

    private val song = LyricsEntry(4000L, "Angin malam membawa cerita")

    @Test
    fun `a tap and a long press reach their handlers`() {
        rule.setContent { Line(song) }
        rule.onNodeWithTag("line").performClick()
        assertEquals(1, clicks)
        rule.onNodeWithTag("line").performTouchInput { longClick() }
        assertEquals(1, longClicks)
    }

    @Test
    fun `the line shows its text and its translation and romanisation`() {
        val item = LyricsEntry(4000L, "Angin malam membawa cerita")
        item.translatedTextFlow.value = "The night wind carries a story"
        item.romanizedTextFlow.value = "anjin yoru"
        rule.setContent { Line(item, romanize = true) }
        rule.onNodeWithText("Angin malam membawa cerita").assertExists()
        rule.onNodeWithText("The night wind carries a story").assertExists()
        rule.onNodeWithText("anjin yoru").assertExists()
    }

    @Test
    fun `a background line is there only while its singer is`() {
        val bg = LyricsEntry(4000L, "(ooh, ooh)", isBackground = true)
        rule.setContent { Line(bg, bgVisible = false) }
        rule.onNodeWithText("ooh, ooh").assertDoesNotExist()
    }

    @Test
    fun `a background line shows without its brackets`() {
        val bg = LyricsEntry(4000L, "(ooh, ooh)", isBackground = true)
        rule.setContent { Line(bg, bgVisible = true) }
        rule.onNodeWithText("ooh, ooh").assertExists()
    }

    @Test
    fun `the depth never changes the measured size of a line`() {
        val active = mutableStateOf(false)
        val distance = mutableStateOf(3)
        rule.setContent { Line(song, active = active.value, distance = distance.value, motion = LyricsMotionEnv.Full) }
        rule.waitForIdle()
        val before = heights.last()
        rule.runOnIdle { distance.value = 1 }
        rule.waitForIdle()
        rule.runOnIdle { active.value = true; distance.value = 0 }
        rule.waitForIdle()
        rule.runOnIdle { active.value = false; distance.value = 4 }
        rule.waitForIdle()
        assertTrue("sizes seen: $heights", heights.all { it == before })
    }

    @Test
    fun `a line that is not being sung never reads the playback position`() {
        var reads = 0
        rule.setContent { Line(song, active = false, position = { reads++; 5000L }) }
        rule.waitForIdle()
        rule.onNodeWithTag("line").captureToImage() // draws the line
        assertEquals("an inactive line must not follow the position", 0, reads)
    }

    @Test
    fun `the line being sung follows the position`() {
        var reads = 0
        rule.setContent { Line(song, active = true, position = { reads++; 5000L }) }
        rule.waitForIdle()
        val beforeDraw = reads
        rule.onNodeWithTag("line").captureToImage() // draws the line
        assertEquals("the position is not read while composing or laying out", 0, beforeDraw)
        assertTrue("the active line reads the position in its draw", reads > 0)
    }

    @Test
    fun `a line with word timing and a held word are drawn word by word without a crash`() {
        val held = LyricsEntry(
            4000L, "Oh-oh-oh sampai pagi",
            words = listOf(
                WordTimestamp("Oh-", 4.0, 4.5, false), WordTimestamp("oh-", 4.5, 5.0, false), WordTimestamp("oh", 5.0, 5.5, true),
                WordTimestamp("sampai", 5.5, 6.0, true), WordTimestamp("pagi", 6.0, 6.5, false),
            ),
        )
        rule.setContent { Line(held, active = true, position = { 5200L }) }
        rule.waitForIdle()
        // drawn on a canvas by the word machinery: it has a size, and it took a tap
        assertTrue("height ${heights.lastOrNull()}", (heights.lastOrNull() ?: 0) > 0)
        rule.onNodeWithTag("line").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `an Arabic line that is being sung is drawn word by word`() {
        val arabic = LyricsEntry(4000L, "كل شيء هادئ", words = listOf(WordTimestamp("كل", 4.0, 4.4, true), WordTimestamp("شيء", 4.4, 4.8, true), WordTimestamp("هادئ", 4.8, 5.2, false)))
        rule.setContent { Line(arabic, active = true, position = { 4500L }) }
        rule.waitForIdle()
        assertTrue("height ${heights.lastOrNull()}", (heights.lastOrNull() ?: 0) > 0)
    }

    @Test
    fun `the row of a gap folds away when it is not on and is 44 dp tall when it is`() {
        val on = mutableStateOf(false)
        rule.setContent {
            MetrolistTheme(darkTheme = false) {
                Surface {
                    Box(Modifier.width(380.dp)) {
                        IntervalIndicator(0L, 8000L, { 2000L }, visible = on.value, color = Color.Black, modifier = Modifier.testTag("gap"), animate = false)
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("gap").assertHeightIsEqualTo(0.dp)
        rule.runOnIdle { on.value = true }
        rule.waitForIdle()
        rule.onNodeWithTag("gap").assertHeightIsEqualTo(44.dp)
        rule.runOnIdle { on.value = false }
        rule.waitForIdle()
        rule.onNodeWithTag("gap").assertHeightIsEqualTo(0.dp)
    }
}
