/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * WCAG contrast of every text-on-surface and icon-on-surface pair of the Horizon palettes, computed from the real token
 * values (and from the Material slots they are mapped to), in light, dark and pure black. AA: 4.5:1 for text, 3:1 for
 * large text, icons and the shapes of controls.
 */
class HorizonContrastTest {
    private fun channel(v: Float): Double {
        val c = v.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    /** A translucent colour is first laid over its background, as the screen does. */
    private fun over(fg: Color, bg: Color): Color {
        val a = fg.alpha
        return Color(fg.red * a + bg.red * (1 - a), fg.green * a + bg.green * (1 - a), fg.blue * a + bg.blue * (1 - a), 1f)
    }

    private fun ratio(fg: Color, bg: Color): Double {
        val l1 = luminance(over(fg, bg))
        val l2 = luminance(bg)
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    private val palettes = listOf(
        "light" to HorizonLight,
        "dark" to HorizonDark,
        "pure black" to HorizonDark.pureBlack(),
    )

    private fun check(name: String, min: Double, fg: Color, bg: Color) {
        val r = ratio(fg, bg)
        assertTrue("$name: contrast ${"%.2f".format(r)} is below $min", r >= min)
    }

    @Test
    fun `ink and secondary text read on every surface`() {
        for ((label, p) in palettes) {
            for ((surfaceName, surface) in listOf("bg" to p.bg, "surface-1" to p.surface1, "surface-2" to p.surface2, "surface-3" to p.surface3)) {
                check("$label ink on $surfaceName", 4.5, p.ink, surface)
                check("$label text-2 on $surfaceName", 4.5, p.text2, surface)
            }
        }
    }

    @Test
    fun `hints read on the page, grouped rows and fields`() {
        // text-3 is for hints, timestamps and placeholders: bg, surface-1 and surface-2 only (not on a selected row)
        for ((label, p) in palettes) {
            for ((surfaceName, surface) in listOf("bg" to p.bg, "surface-1" to p.surface1, "surface-2" to p.surface2)) {
                check("$label text-3 on $surfaceName", 4.5, p.text3, surface)
            }
        }
    }

    @Test
    fun `danger reads as text and as a button fill`() {
        for ((label, p) in palettes) {
            for ((surfaceName, surface) in listOf("bg" to p.bg, "surface-1" to p.surface1, "surface-2" to p.surface2)) {
                check("$label danger on $surfaceName", 4.5, p.danger, surface)
            }
            check("$label label on a danger fill", 4.5, p.onDanger, p.danger)
        }
    }

    @Test
    fun `the ink fill and its label`() {
        for ((label, p) in palettes) {
            check("$label label on the ink fill", 4.5, p.onInk, p.ink)
            // the shape of an ink button or an ink toggle track against the page
            check("$label ink fill against the page", 3.0, p.ink, p.bg)
            check("$label ink fill against surface-1", 3.0, p.ink, p.surface1)
        }
    }

    @Test
    fun `icons and control outlines are visible`() {
        for ((label, p) in palettes) {
            for ((surfaceName, surface) in listOf("bg" to p.bg, "surface-1" to p.surface1, "surface-2" to p.surface2)) {
                check("$label icon (ink) on $surfaceName", 3.0, p.ink, surface)
                check("$label inactive icon (text-2) on $surfaceName", 3.0, p.text2, surface)
                check("$label outline (text-3) on $surfaceName", 3.0, p.text3, surface)
            }
        }
    }

    @Test
    fun `the switch thumb reads on its on-state track`() {
        for ((label, p) in palettes) {
            // on: page-coloured thumb on an ink track
            check("$label switch thumb on the ink track", 3.0, p.onInk, p.ink)
        }
    }

    @Test
    fun `material slots keep every pair readable`() {
        for ((label, p) in palettes) {
            val s = horizonColorScheme(p)
            val containers = listOf(s.surface, s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest)
            for (c in containers) {
                check("$label onSurface on a container", 4.5, s.onSurface, c)
                check("$label onSurfaceVariant on a container", 4.5, s.onSurfaceVariant, c)
            }
            check("$label onPrimary on primary", 4.5, s.onPrimary, s.primary)
            check("$label onPrimaryContainer on primaryContainer", 4.5, s.onPrimaryContainer, s.primaryContainer)
            check("$label onSecondary on secondary", 4.5, s.onSecondary, s.secondary)
            check("$label onSecondaryContainer on secondaryContainer", 4.5, s.onSecondaryContainer, s.secondaryContainer)
            check("$label onTertiaryContainer on tertiaryContainer", 4.5, s.onTertiaryContainer, s.tertiaryContainer)
            check("$label onError on error", 4.5, s.onError, s.error)
            check("$label onErrorContainer on errorContainer", 4.5, s.onErrorContainer, s.errorContainer)
            check("$label snackbar text", 4.5, s.inverseOnSurface, s.inverseSurface)
            check("$label snackbar action", 4.5, s.inversePrimary, s.inverseSurface)
        }
    }

    @Test
    fun `primary is ink and no slot carries a hue`() {
        for ((label, p) in palettes) {
            val s = horizonColorScheme(p)
            assertEquals("$label primary", p.ink, s.primary)
            assertEquals("$label onPrimary", p.bg, s.onPrimary)
            for ((slot, c) in listOf(
                "primary" to s.primary, "secondary" to s.secondary, "tertiary" to s.tertiary,
                "primaryContainer" to s.primaryContainer, "secondaryContainer" to s.secondaryContainer,
                "surface" to s.surface, "surfaceContainer" to s.surfaceContainer, "surfaceContainerHighest" to s.surfaceContainerHighest,
                "onSurface" to s.onSurface, "onSurfaceVariant" to s.onSurfaceVariant, "outline" to s.outline,
            )) {
                val spread = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)
                // neutrals have a faint blue cast (hue about 220): the channel spread stays small
                assertTrue("$label $slot is not neutral (spread $spread)", spread < 0.10f)
            }
        }
    }

    @Test
    fun `the palette holds the values of the design report`() {
        assertEquals(Color(0xFFF5F6F8), HorizonLight.bg)
        assertEquals(Color(0xFFFFFFFF), HorizonLight.surface1)
        assertEquals(Color(0xFFECEEF2), HorizonLight.surface2)
        assertEquals(Color(0xFFDFE2E8), HorizonLight.surface3)
        assertEquals(Color(0xFF0E1116), HorizonLight.ink)
        assertEquals(Color(0xFF4A5160), HorizonLight.text2)
        assertEquals(Color(0xFF656C7A), HorizonLight.text3)
        assertEquals(Color(0xFFC62828), HorizonLight.danger)
        assertEquals(Color(0xFF0B0C0F), HorizonDark.bg)
        assertEquals(Color(0xFF15171C), HorizonDark.surface1)
        assertEquals(Color(0xFF1E2128), HorizonDark.surface2)
        assertEquals(Color(0xFF292D36), HorizonDark.surface3)
        assertEquals(Color(0xFFF2F4F7), HorizonDark.ink)
        assertEquals(Color(0xFFB4BAC6), HorizonDark.text2)
        assertEquals(Color(0xFF8B92A0), HorizonDark.text3)
        assertEquals(Color(0xFFFF8A80), HorizonDark.danger)
        // pure black: the page is black, surface-1 steps down to the night page colour, the rest is unchanged
        val pb = HorizonDark.pureBlack()
        assertEquals(Color(0xFF000000), pb.bg)
        assertEquals(Color(0xFF0B0C0F), pb.surface1)
        assertEquals(HorizonDark.ink, pb.ink)
        assertEquals(HorizonDark.surface2, pb.surface2)
        // pure black does nothing to the light palette
        assertEquals(HorizonLight.bg, HorizonLight.pureBlack().bg)
    }

    /**
     * The off-state of the switch (surface-3 track, white thumb) is recorded, not asserted: its track against the page is
     * about 1.2:1 (light) and 1.4:1 (dark), below the 3:1 of WCAG 1.4.11. The state is also carried by the thumb position
     * and by the switch role for TalkBack. If this ever needs to pass, give the off track an outline.
     */
    @Test
    fun `switch off-state ratio is known`() {
        val light = ratio(HorizonLight.surface3, HorizonLight.bg)
        val dark = ratio(HorizonDark.surface3, HorizonDark.bg)
        assertTrue("light $light", light in 1.1..1.4)
        assertTrue("dark $dark", dark in 1.3..1.6)
    }
}
