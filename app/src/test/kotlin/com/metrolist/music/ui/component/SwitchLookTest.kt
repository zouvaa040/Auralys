/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.metrolist.music.ui.theme.HorizonDark
import com.metrolist.music.ui.theme.HorizonLight
import com.metrolist.music.ui.theme.asGlass
import com.metrolist.music.ui.theme.horizonColorScheme
import com.metrolist.music.ui.theme.pureBlack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The off state of the switch must be readable on every page: light, dark and pure black, Netral / Sampul (the Horizon
 * tokens) and Kustom (a seeded scheme) in the Kaca style (translucent containers) and the Datar style (opaque ones)
 * (REVIEW-2026-11-07 F5). The seeded schemes are written out by hand with the values a tonal palette gives; Kaca is the
 * real [asGlass] of the theme.
 */
class SwitchLookTest {
    private fun channel(v: Float): Double {
        val c = v.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun lum(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    /** WCAG contrast of [fg] laid over [bg] against [bg]. */
    private fun ratio(
        fg: Color,
        bg: Color,
    ): Double {
        val a = lum(fg.compositeOver(bg))
        val b = lum(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    /** Contrast of two opaque colours. */
    private fun between(
        a: Color,
        b: Color,
    ): Double = (maxOf(lum(a), lum(b)) + 0.05) / (minOf(lum(a), lum(b)) + 0.05)

    private class Combo(
        val name: String,
        val scheme: ColorScheme,
        val dark: Boolean,
    )

    private val combos: List<Combo> by lazy {
        buildList {
            // Netral and Sampul: the Horizon tokens (Kaca and Datar share them)
            add(Combo("Netral light", horizonColorScheme(HorizonLight), false))
            add(Combo("Netral dark", horizonColorScheme(HorizonDark), true))
            add(Combo("Netral pure black", horizonColorScheme(HorizonDark.pureBlack()), true))
            // Kustom: seeded schemes
            val seededLight =
                listOf(
                    lightColorScheme(
                        primary = Color(0xFF8D4E00), onPrimary = Color(0xFFFFFFFF), surface = Color(0xFFFFF8F4), background = Color(0xFFFFF8F4),
                        onSurface = Color(0xFF201A16), surfaceContainerHighest = Color(0xFFEDE0D9),
                    ),
                    lightColorScheme(
                        primary = Color(0xFF2F5FA8), onPrimary = Color(0xFFFFFFFF), surface = Color(0xFFF8F9FF), background = Color(0xFFF8F9FF),
                        onSurface = Color(0xFF191C20), surfaceContainerHighest = Color(0xFFE0E2E9),
                    ),
                )
            val seededDark =
                listOf(
                    darkColorScheme(
                        primary = Color(0xFFFFB86B), onPrimary = Color(0xFF4A2800), surface = Color(0xFF17120E), background = Color(0xFF17120E),
                        onSurface = Color(0xFFEDE0D8), surfaceContainerHighest = Color(0xFF3B332E),
                    ),
                    darkColorScheme(
                        primary = Color(0xFFA8C8FF), onPrimary = Color(0xFF003062), surface = Color(0xFF111318), background = Color(0xFF111318),
                        onSurface = Color(0xFFE2E2E9), surfaceContainerHighest = Color(0xFF33353A),
                    ),
                )
            seededLight.forEachIndexed { i, s ->
                add(Combo("Kustom light $i Datar", s, false))
                add(Combo("Kustom light $i Kaca", s.asGlass(false), false))
            }
            seededDark.forEachIndexed { i, s ->
                add(Combo("Kustom dark $i Datar", s, true))
                add(Combo("Kustom dark $i Kaca", s.asGlass(true), true))
                add(Combo("Kustom dark $i Datar pure black", s.pureBlack(true), true))
                add(Combo("Kustom dark $i Kaca pure black", s.asGlass(true).pureBlack(true), true))
            }
        }
    }

    private fun off(c: Combo) =
        switchLook(false, c.dark, c.scheme.primary, c.scheme.onPrimary, c.scheme.onSurface, c.scheme.surface, c.scheme.surfaceContainerHighest)

    private fun on(c: Combo) =
        switchLook(true, c.dark, c.scheme.primary, c.scheme.onPrimary, c.scheme.onSurface, c.scheme.surface, c.scheme.surfaceContainerHighest)

    @Test
    fun `the off track is opaque in every combination`() {
        for (c in combos) assertEquals("${c.name}: track alpha", 1f, off(c).track.alpha, 0f)
    }

    @Test
    fun `the 1 dp outline of the off track reads at 3 to 1 on the page and on the grouped row`() {
        for (c in combos) {
            val outline = off(c).outline
            assertEquals("${c.name}: the outline is ink", c.scheme.onSurface.red, outline.red, 0f)
            val page = c.scheme.surface
            val group = c.scheme.surfaceContainer.compositeOver(page)
            assertTrue("${c.name}: outline on the page ${ratio(outline, page)}", ratio(outline, page) >= 3.0)
            assertTrue("${c.name}: outline on the group ${ratio(outline, group)}", ratio(outline, group) >= 3.0)
        }
    }

    @Test
    fun `the off thumb is told from the off track`() {
        for (c in combos) {
            val l = off(c)
            val r = between(l.thumb, l.track)
            assertTrue("${c.name}: thumb on track $r", r >= 1.2)
        }
    }

    @Test
    fun `the state is not carried by the thumb alone - the off track is told from the page too`() {
        for (c in combos) {
            val l = off(c)
            // the track or its outline stands out from the page; here: the outline (tested above) AND a track that is not the page
            assertTrue("${c.name}: the track is the page colour", between(l.track, c.scheme.surface) > 1.05)
        }
    }

    @Test
    fun `Netral keeps its old look - surface-3 track and the white or grey-white thumb`() {
        for (c in combos.filter { it.name.startsWith("Netral") }) {
            val l = off(c)
            assertEquals(c.name, c.scheme.surfaceContainerHighest, l.track)
            assertEquals(c.name, if (c.dark) SwitchThumbOffDark else SwitchThumbOffLight, l.thumb)
        }
    }

    @Test
    fun `on is an ink or seed track with a readable thumb`() {
        for (c in combos) {
            val l = on(c)
            assertEquals(c.scheme.primary, l.track)
            assertEquals(c.scheme.onPrimary, l.thumb)
            assertTrue("${c.name}: on thumb on track ${between(l.thumb, l.track)}", between(l.thumb, l.track) >= 3.0)
            assertEquals("${c.name}: the on outline is the track itself", l.track, l.outline)
        }
    }

    @Test
    fun `the translucent Kaca container alone would have made a near-white track under a white thumb`() {
        // the bug of REVIEW-2026-11-07 F5: Kustom + Kaca on a light page, the container slot used directly
        val kaca = combos.first { it.name == "Kustom light 0 Kaca" }
        assertTrue("the slot is translucent", kaca.scheme.surfaceContainerHighest.alpha < 1f)
        val oldTrack = kaca.scheme.surfaceContainerHighest.compositeOver(kaca.scheme.surface)
        assertTrue("old thumb on old track ${between(SwitchThumbOffLight, oldTrack)}", between(SwitchThumbOffLight, oldTrack) < 1.2)
        // and now: the tinted opaque track keeps the thumb readable
        assertTrue(between(off(kaca).thumb, off(kaca).track) >= 1.2)
    }
}
