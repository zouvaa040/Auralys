/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.metrolist.music.ui.theme.HorizonColors
import com.metrolist.music.ui.theme.HorizonDark
import com.metrolist.music.ui.theme.HorizonLight
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The volume control on the menu sheet (the sheet is the surface-1 token: white by day, #15171C by night, #0B0C0F in
 * "Hitam murni"): WCAG contrast of what carries the meaning, and the floor of the decorative unfilled track.
 */
class VolumeControlContrastTest {
    private fun channel(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun ratio(fg: Color, bg: Color): Double {
        val a = luminance(fg.compositeOver(bg))
        val b = luminance(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private val palettes: List<Pair<String, HorizonColors>> =
        listOf("day" to HorizonLight, "night" to HorizonDark, "pure black" to HorizonDark.pureBlack())

    /** The sheet colour: surface-1, except pure black where the page is black and the sheet the old page colour (already in surface1). */
    private fun sheet(p: HorizonColors) = p.surface1

    @Test
    fun `the thumb and the filled part are ink on the sheet at 3 to 1 or better`() {
        for ((name, p) in palettes) {
            assertTrue("$name thumb", ratio(p.ink, sheet(p)) >= 3.0)
        }
    }

    @Test
    fun `the two speaker icons read at 4 5 to 1 or better`() {
        for ((name, p) in palettes) {
            val icon = p.ink.copy(alpha = VolumeControlDefaults.IconAlpha)
            assertTrue("$name icon ${ratio(icon, sheet(p))}", ratio(icon, sheet(p)) >= 4.5)
        }
    }

    @Test
    fun `the percent label is page colour on ink`() {
        for ((name, p) in palettes) {
            assertTrue("$name label ${ratio(p.bg, p.ink)}", ratio(p.bg, p.ink) >= 4.5)
        }
    }

    @Test
    fun `the unfilled track keeps a visible floor and the filled part stands out from it`() {
        for ((name, p) in palettes) {
            val rest = p.ink.copy(alpha = VolumeControlDefaults.RestAlpha)
            val vsSheet = ratio(rest, sheet(p))
            assertTrue("$name unfilled track against the sheet: $vsSheet", vsSheet >= 1.7)
            // the filled part against the unfilled one: the edge of the value
            val restOnSheet = rest.compositeOver(sheet(p))
            assertTrue("$name filled against unfilled: ${ratio(p.ink, restOnSheet)}", ratio(p.ink, restOnSheet) >= 3.0)
        }
    }
}
