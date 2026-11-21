/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/** Scale rules of the Horizon tokens: radius, spacing, type, glass tiers, motion, cover tint. */
class HorizonTokensTest {
    @Test
    fun `radius scale is strictly increasing and holds the seven report values`() {
        val values = HorizonRadius.all.map { it.value }
        assertEquals(listOf(4f, 8f, 12f, 16f, 24f, 28f), values)
        assertEquals(values.sorted(), values)
        assertEquals(values.distinct(), values)
    }

    @Test
    fun `snap picks the nearest token and leaves round and square alone`() {
        assertEquals(12.dp, HorizonRadius.snap(12.dp))
        assertEquals(24.dp, HorizonRadius.snap(22.dp))
        assertEquals(28.dp, HorizonRadius.snap(32.dp))
        assertEquals(28.dp, HorizonRadius.snap(36.dp))
        assertEquals(8.dp, HorizonRadius.snap(10.dp))
        assertEquals(16.dp, HorizonRadius.snap(18.dp))
        assertEquals(0.dp, HorizonRadius.snap(0.dp))
        assertEquals(50.dp, HorizonRadius.snap(50.dp))
        for (r in 1..47) {
            assertTrue("snap($r) is not a token", HorizonRadius.snap(r.dp) in HorizonRadius.all)
        }
    }

    @Test
    fun `spacing sits on the four point grid and the target is at least 48`() {
        for (s in HorizonSpace.all) assertEquals("$s", 0f, s.value % 4f, 0f)
        assertEquals(HorizonSpace.all.sortedBy { it.value }, HorizonSpace.all)
        assertTrue(HorizonSpace.Target.value >= 48f)
        assertEquals(20f, HorizonSpace.Gutter.value, 0f)
        assertEquals(64f, HorizonSpace.RowHeight.value, 0f)
        assertEquals(56f, HorizonSpace.SettingsRowHeight.value, 0f)
        assertEquals(52f, HorizonSpace.MenuRowHeight.value, 0f)
    }

    @Test
    fun `glass tiers hold the numbers of the recipe`() {
        with(HorizonGlass.G1) {
            assertEquals(20f, blurDp, 0f)
            assertEquals(0.5f, captureScale, 0f)
            assertEquals(1.6f, saturation, 0f)
            assertEquals(0.58f, veilLight.alpha, 0.005f)
            assertEquals(0.62f, veilDark.alpha, 0.005f)
        }
        with(HorizonGlass.G2) {
            assertEquals(12f, blurDp, 0f)
            assertEquals(0.33f, captureScale, 0f)
            assertEquals(1.4f, saturation, 0f)
            assertEquals(0.46f, veilLight.alpha, 0.005f)
            assertEquals(0.12f, veilDark.alpha, 0.005f)
        }
        with(HorizonGlass.G3) {
            assertEquals("a sheet opens in its own window and cannot sample the page", 0f, blurDp, 0f)
            assertEquals(1f, veilLight.alpha, 0f)
            assertEquals(1f, veilDark.alpha, 0f)
        }
        assertEquals(0.18f, HorizonGlass.NoBlurExtraAlpha, 0f)
        // blur and capture get cheaper (or equal) from bars to controls
        assertTrue(HorizonGlass.G1.blurDp > HorizonGlass.G2.blurDp)
        assertTrue(HorizonGlass.G1.captureScale > HorizonGlass.G2.captureScale)
        // the navigation bar already samples at the bar tier's capture scale
        assertEquals(HorizonGlass.G1.captureScale, com.metrolist.music.ui.component.backdrop.catalog.components.NavGlassScale, 0f)
    }

    @Test
    fun `elevation levels grow and dark pages get stronger shadows`() {
        val levels = listOf(HorizonElevation.E0, HorizonElevation.E1, HorizonElevation.E2, HorizonElevation.E3)
        assertEquals(levels.sortedBy { it.dp.value }, levels)
        assertEquals(0f, HorizonElevation.E0.dp.value, 0f)
        for (l in listOf(HorizonElevation.E1, HorizonElevation.E2, HorizonElevation.E3)) {
            assertTrue(HorizonElevation.spotAlpha(l, true) > HorizonElevation.spotAlpha(l, false))
        }
    }

    @Test
    fun `motion durations grow and press is the same everywhere`() {
        assertTrue(HorizonMotion.PressMs < HorizonMotion.StateMs)
        assertTrue(HorizonMotion.StateMs < HorizonMotion.EnterMs)
        assertTrue(HorizonMotion.EnterMs < HorizonMotion.HeroMs)
        assertEquals(0.97f, HorizonMotion.PressScale, 0f)
        assertTrue("no bounce above damping 0.7 for a press", HorizonMotion.PressDamping >= 0.7f)
    }

    @Test
    fun `the type scale has eleven roles and follows the report`() {
        val roles = HorizonScale.roles
        assertEquals(11, roles.size)
        assertEquals(11, roles.map { it.name }.distinct().size)
        fun r(role: HorizonRole, size: Float, line: Float, weight: Int, tracking: Float) {
            assertEquals("${role.name} size", size, role.sizeSp, 0f)
            assertEquals("${role.name} line", line, role.lineSp, 0f)
            assertEquals("${role.name} weight", weight, role.weight.weight)
            assertEquals("${role.name} tracking", tracking, role.trackingSp, 0f)
        }
        r(HorizonScale.Display, 34f, 38f, 800, -0.3f)
        r(HorizonScale.PageTitle, 28f, 34f, 700, -0.2f)
        r(HorizonScale.Section, 20f, 26f, 700, 0f)
        r(HorizonScale.RowTitle, 16f, 22f, 600, 0f)
        r(HorizonScale.RowTitleSmall, 14f, 20f, 600, 0f)
        r(HorizonScale.Body, 16f, 24f, 400, 0f)
        r(HorizonScale.Secondary, 14f, 20f, 500, 0f)
        r(HorizonScale.Caption, 12f, 16f, 500, 0f)
        r(HorizonScale.Button, 15f, 20f, 600, 0f)
        r(HorizonScale.Chip, 13f, 16f, 600, 0f)
        r(HorizonScale.NavLabel, 11f, 14f, 600, 0f)
        for (role in roles + HorizonScale.Headline) {
            assertTrue("${role.name}: minimum text size is 11 sp", role.sizeSp >= 11f)
            assertTrue("${role.name}: line height is at least 1.1 x the size", role.lineSp >= role.sizeSp * 1.1f)
            assertTrue("${role.name}: no tracking above zero", role.trackingSp <= 0f)
            assertTrue("${role.name}: weight inside the font axis 200..800", role.weight.weight in 200..800)
        }
    }

    @Test
    fun `typography fills every Material slot from the scale, one family, no italics`() {
        val t = horizonTypography()
        val slots = mapOf(
            "displayLarge" to t.displayLarge, "displayMedium" to t.displayMedium, "displaySmall" to t.displaySmall,
            "headlineLarge" to t.headlineLarge, "headlineMedium" to t.headlineMedium, "headlineSmall" to t.headlineSmall,
            "titleLarge" to t.titleLarge, "titleMedium" to t.titleMedium, "titleSmall" to t.titleSmall,
            "bodyLarge" to t.bodyLarge, "bodyMedium" to t.bodyMedium, "bodySmall" to t.bodySmall,
            "labelLarge" to t.labelLarge, "labelMedium" to t.labelMedium, "labelSmall" to t.labelSmall,
        )
        assertEquals(15, slots.size)
        for ((name, style) in slots) {
            assertEquals("$name family", HorizonFontFamily, style.fontFamily)
            assertTrue("$name italic", style.fontStyle != FontStyle.Italic)
            assertTrue("$name size", style.fontSize.value >= 11f)
            assertTrue("$name tracking", style.letterSpacing.value <= 0f)
        }
        // every role is carried by the slots the report names
        for (role in HorizonScale.roles) {
            for (slot in role.slots) {
                val style = slots.getValue(slot)
                assertEquals("${role.name} -> $slot size", role.sizeSp, style.fontSize.value, 0f)
                assertEquals("${role.name} -> $slot weight", role.weight.weight, style.fontWeight?.weight)
            }
        }
        // the caption has digits of equal width (the font has the tnum feature)
        assertEquals("tnum", t.bodySmall.fontFeatureSettings)
    }

    @Test
    fun `the scale gives hierarchy by size, and the headings are heavier than the body`() {
        val t = horizonTypography()
        assertTrue(t.displayLarge.fontSize.value > t.headlineLarge.fontSize.value)
        assertTrue(t.headlineLarge.fontSize.value > t.headlineSmall.fontSize.value)
        assertTrue(t.headlineSmall.fontSize.value > t.titleMedium.fontSize.value)
        assertTrue(t.titleMedium.fontSize.value >= t.bodyLarge.fontSize.value)
        assertTrue(t.bodyLarge.fontSize.value > t.bodyMedium.fontSize.value)
        assertTrue(t.bodyMedium.fontSize.value > t.bodySmall.fontSize.value)
        assertTrue(t.bodySmall.fontSize.value > t.labelSmall.fontSize.value)
        assertTrue(t.headlineSmall.fontWeight!!.weight > t.bodyLarge.fontWeight!!.weight)
        assertTrue(t.titleMedium.fontWeight!!.weight > t.bodyMedium.fontWeight!!.weight)
    }

    @Test
    fun `cover tint is held to lightness 0 35 to 0 55 and saturation 0 55`() {
        fun hsl(c: Color): Triple<Float, Float, Float> {
            val hi = maxOf(c.red, c.green, c.blue)
            val lo = minOf(c.red, c.green, c.blue)
            val l = (hi + lo) / 2f
            val d = hi - lo
            val s = if (d == 0f) 0f else d / (1f - abs(2f * l - 1f))
            val h = when {
                d == 0f -> 0f
                hi == c.red -> (((c.green - c.blue) / d) % 6f).let { if (it < 0) it + 6f else it }
                hi == c.green -> (c.blue - c.red) / d + 2f
                else -> (c.red - c.green) / d + 4f
            } * 60f
            return Triple(h, s, l)
        }
        val samples = listOf(
            Color(0xFF00FF40), Color(0xFFFF0090), Color(0xFFFFFF00), Color(0xFF0000FF), Color(0xFFFFFFFF), Color(0xFF000000),
            Color(0xFFF8D7E0), Color(0xFF1B0A2A), Color(0xFFC2623F), Color(0xFF808080), Color(0xFF6AD1F2), Color(0xFF2F8FE0),
        )
        for (c in samples) {
            val (h0, s0, _) = hsl(c)
            val tint = clampCoverTint(c)
            val (h, s, l) = hsl(tint)
            assertTrue("$c: lightness $l", l in CoverTintMinLightness - 0.005f..CoverTintMaxLightness + 0.005f)
            assertTrue("$c: saturation $s", s <= CoverTintMaxSaturation + 0.005f)
            assertEquals("alpha is kept", 1f, tint.alpha, 0f)
            // the hue survives for coloured covers
            if (s0 > 0.1f && s > 0.1f) {
                val dh = abs(h - h0).let { if (it > 180f) 360f - it else it }
                assertTrue("$c: hue moved by $dh", dh < 4f)
            }
        }
        // grey stays grey
        val grey = clampCoverTint(Color(0xFF808080))
        assertEquals(grey.red, grey.green, 0.01f)
        assertEquals(grey.green, grey.blue, 0.01f)
        // a colour already inside the band is kept
        val ok = Color(0xFF3A7BB0)
        val kept = clampCoverTint(ok)
        assertEquals(ok.red, kept.red, 0.02f)
        assertEquals(ok.green, kept.green, 0.02f)
        assertEquals(ok.blue, kept.blue, 0.02f)
    }

    @Test
    fun `wash is stronger on dark pages and the mini player share is 12 percent`() {
        assertEquals(0.16f, coverWashAlpha(false), 0f)
        assertEquals(0.28f, coverWashAlpha(true), 0f)
        assertEquals(0.12f, MiniPlayerTintShare, 0f)
    }

    @Test
    fun `the built-in colour stays a marker value and is not the brand colour any more`() {
        assertEquals(Color(0xFFC2623F), BuiltInThemeColor)
        assertEquals(BuiltInThemeColor, DefaultThemeColor)
        // nothing in the neutral palettes is the terracotta or the cream
        for (p in listOf(HorizonLight, HorizonDark)) {
            assertNotEquals(BuiltInThemeColor, p.ink)
            assertNotEquals(Color(0xFFFDF8F5), p.bg)
        }
    }
}

/** Theme wiring needs Android resources (the font), so it runs under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HorizonFontResourceTest {
    @Test
    fun `the Plus Jakarta Sans resource is bundled with its licence`() {
        val font = com.metrolist.music.R.font.plus_jakarta_sans
        assertTrue(font != 0)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.resources.openRawResource(font).use { stream ->
            val head = ByteArray(4)
            assertEquals(4, stream.read(head))
            // TrueType: 0x00010000
            assertEquals(listOf(0, 1, 0, 0), head.map { it.toInt() and 0xFF })
        }
        val licence = context.assets.open("licenses/PlusJakartaSans-OFL.txt").bufferedReader().use { it.readText() }
        assertTrue(licence.contains("SIL OPEN FONT LICENSE Version 1.1"))
        assertTrue(licence.contains("Plus Jakarta Sans Project Authors"))
    }
}
