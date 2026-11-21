/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrolist.music.R
import com.metrolist.music.ui.theme.MetrolistTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The play disc on a home tile (REVIEW-2026-11-07 F11): 48 dp, and a long press that starts on it opens the tile's menu
 * instead of being swallowed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w411dp-h860dp-xhdpi")
class OnCoverButtonTest {
    @get:Rule
    val rule = createComposeRule()

    private var clicks = 0
    private var longClicks = 0

    private fun show(withLongClick: Boolean) {
        rule.setContent {
            MetrolistTheme(darkTheme = true) {
                OnCoverButton(
                    icon = R.drawable.play,
                    contentDescription = "Putar",
                    onClick = { clicks++ },
                    onLongClick = if (withLongClick) ({ longClicks++ }) else null,
                )
            }
        }
    }

    @Test
    fun `a long press on the disc runs the long press action and not the click`() {
        show(withLongClick = true)
        rule.onNodeWithContentDescription("Putar").performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals(1, longClicks)
        assertEquals(0, clicks)
    }

    @Test
    fun `a tap on the disc plays`() {
        show(withLongClick = true)
        rule.onNodeWithContentDescription("Putar").performClick()
        rule.waitForIdle()
        assertEquals(1, clicks)
        assertEquals(0, longClicks)
    }

    @Test
    fun `without a long press action the disc is a plain button`() {
        show(withLongClick = false)
        rule.onNodeWithContentDescription("Putar").performClick()
        rule.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    fun `the disc is at least 48 dp`() {
        show(withLongClick = true)
        rule.onNodeWithContentDescription("Putar").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }
}
