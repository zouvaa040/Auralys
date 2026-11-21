/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.ui.graphics.Color
import com.metrolist.music.constants.CoverShadeKey
import com.metrolist.music.extensions.toEnum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The pure parts of "Efek berbayang": the opacity table, the tint guard, the contrast floor for ink text on the
 * strongest wash of any cover, the ease curve, "no cover means no wash", the stored key and its defaults, and the routes
 * of the pages that own a header colour.
 */
class CoverShadeTest {
    private val levels = listOf(CoverShadeLevel.SOFT, CoverShadeLevel.STRONG)

    /** A page: its name, its palette and the flags the shade reads. */
    private class Mode(val name: String, val dark: Boolean, val pureBlack: Boolean, val palette: HorizonColors)

    private val modes = listOf(
        Mode("light", false, false, HorizonLight),
        Mode("dark", true, false, HorizonDark),
        Mode("pure black", true, true, HorizonDark.pureBlack()),
    )

    /** Covers of every kind: saturated primaries, a pastel, near-white, near-black, grey, a neon, a deep navy. */
    private val covers: Map<String, Color> = linkedMapOf(
        "red" to Color(0xFFFF0000),
        "blue" to Color(0xFF0000FF),
        "green" to Color(0xFF00FF00),
        "yellow" to Color(0xFFFFE600),
        "orange" to Color(0xFFF57C00),
        "purple" to Color(0xFF8E24AA),
        "teal" to Color(0xFF00897B),
        "pastel pink" to Color(0xFFF4A6C0),
        "near-white" to Color(0xFFF7F7F5),
        "white" to Color(0xFFFFFFFF),
        "near-black" to Color(0xFF0A0A0C),
        "black" to Color(0xFF000000),
        "grey" to Color(0xFF808080),
        "neon green" to Color(0xFF39FF14),
        "navy" to Color(0xFF0B1F4D),
    )

    // ---------------------------------------------------------------- opacity table

    @Test
    fun `opacity table, level by light dark and pure black`() {
        // Lembut (SOFT)
        assertEquals(0.16f, CoverShadeMath.topAlpha(CoverShadeLevel.SOFT, dark = false, pureBlack = false, flat = false), 1e-6f)
        assertEquals(0.22f, CoverShadeMath.topAlpha(CoverShadeLevel.SOFT, dark = true, pureBlack = false, flat = false), 1e-6f)
        assertEquals(0.18f, CoverShadeMath.topAlpha(CoverShadeLevel.SOFT, dark = true, pureBlack = true, flat = false), 1e-6f)
        // Kuat (STRONG)
        assertEquals(0.24f, CoverShadeMath.topAlpha(CoverShadeLevel.STRONG, dark = false, pureBlack = false, flat = false), 1e-6f)
        assertEquals(0.40f, CoverShadeMath.topAlpha(CoverShadeLevel.STRONG, dark = true, pureBlack = false, flat = false), 1e-6f)
        assertEquals(0.32f, CoverShadeMath.topAlpha(CoverShadeLevel.STRONG, dark = true, pureBlack = true, flat = false), 1e-6f)
        // Mati (OFF)
        for (m in modes) {
            assertEquals(0f, CoverShadeMath.topAlpha(CoverShadeLevel.OFF, m.dark, m.pureBlack, flat = false), 0f)
        }
    }

    @Test
    fun `Lembut stays in the asked ranges and Kuat is clearly stronger, between 1_45 and 1_85 times`() {
        val light = CoverShadeMath.topAlpha(CoverShadeLevel.SOFT, false, false, false)
        val dark = CoverShadeMath.topAlpha(CoverShadeLevel.SOFT, true, false, false)
        assertTrue("light Lembut $light", light in 0.14f..0.18f)
        assertTrue("dark Lembut $dark", dark in 0.20f..0.26f)
        for (m in modes) {
            val soft = CoverShadeMath.topAlpha(CoverShadeLevel.SOFT, m.dark, m.pureBlack, false)
            val strong = CoverShadeMath.topAlpha(CoverShadeLevel.STRONG, m.dark, m.pureBlack, false)
            assertTrue("${m.name}: Kuat/Lembut = ${strong / soft}", strong / soft in 1.45f..1.85f)
        }
    }

    @Test
    fun `pure black uses a lower opacity than dark, the flat style a lower one than glass`() {
        for (level in levels) {
            assertTrue(
                CoverShadeMath.topAlpha(level, true, true, false) < CoverShadeMath.topAlpha(level, true, false, false),
            )
            for (m in modes) {
                val full = CoverShadeMath.topAlpha(level, m.dark, m.pureBlack, flat = false)
                val flat = CoverShadeMath.topAlpha(level, m.dark, m.pureBlack, flat = true)
                assertEquals(full * CoverShadeMath.FlatFactor, flat, 1e-6f)
                assertTrue(flat < full)
            }
        }
    }

    @Test
    fun `the levels are ordered and reach about half of the screen`() {
        for (m in modes) {
            val off = CoverShadeMath.topAlpha(CoverShadeLevel.OFF, m.dark, m.pureBlack, false)
            val soft = CoverShadeMath.topAlpha(CoverShadeLevel.SOFT, m.dark, m.pureBlack, false)
            val strong = CoverShadeMath.topAlpha(CoverShadeLevel.STRONG, m.dark, m.pureBlack, false)
            assertTrue(off < soft && soft < strong)
        }
        assertEquals(0f, CoverShadeMath.reach(CoverShadeLevel.OFF), 0f)
        assertTrue(CoverShadeMath.reach(CoverShadeLevel.SOFT) in 0.45f..0.60f)
        assertTrue(CoverShadeMath.reach(CoverShadeLevel.STRONG) in 0.45f..0.60f)
        assertTrue(CoverShadeMath.reach(CoverShadeLevel.SOFT) < CoverShadeMath.reach(CoverShadeLevel.STRONG))
    }

    // ---------------------------------------------------------------- tint guard

    private fun saturation(c: Color): Float {
        val hi = maxOf(c.red, c.green, c.blue)
        val lo = minOf(c.red, c.green, c.blue)
        val l = (hi + lo) / 2f
        val d = hi - lo
        return if (d == 0f) 0f else d / (1f - abs(2f * l - 1f))
    }

    @Test
    fun `the tint is held to a lightness, saturation and luminance band`() {
        for ((name, cover) in covers) {
            val light = CoverShadeMath.tint(cover, dark = false)
            val dark = CoverShadeMath.tint(cover, dark = true)
            assertTrue("$name light: saturation ${saturation(light)}", saturation(light) <= 0.60f + 0.02f)
            assertTrue("$name dark: saturation ${saturation(dark)}", saturation(dark) <= 0.60f + 0.02f)
            assertTrue("$name light: luminance ${CoverShadeMath.relativeLuminance(light)}", CoverShadeMath.relativeLuminance(light) >= 0.12f - 0.01f)
            assertTrue("$name dark: luminance ${CoverShadeMath.relativeLuminance(dark)}", CoverShadeMath.relativeLuminance(dark) <= 0.22f + 0.01f)
        }
    }

    @Test
    fun `a grey cover stays grey and a coloured one keeps its hue`() {
        val grey = CoverShadeMath.tint(Color(0xFF808080), dark = false)
        assertEquals(grey.red, grey.green, 0.01f)
        assertEquals(grey.green, grey.blue, 0.01f)
        val blue = CoverShadeMath.tint(Color(0xFF1E4FD8), dark = true)
        assertTrue("blue stays blue", blue.blue > blue.red && blue.blue > blue.green)
        val red = CoverShadeMath.tint(Color(0xFFE53935), dark = false)
        assertTrue("red stays red", red.red > red.green && red.red > red.blue)
    }

    @Test
    fun `grey, white and black covers earn little of the opacity and saturated ones all of it`() {
        for (name in listOf("grey", "white", "black", "near-white", "near-black")) {
            assertEquals("$name", 0.4f, CoverShadeMath.weight(covers.getValue(name)), 0.02f)
        }
        for (name in listOf("red", "blue", "green", "yellow", "neon green")) {
            assertEquals("$name", 1f, CoverShadeMath.weight(covers.getValue(name)), 1e-6f)
        }
        // a muted colour is in between, and the weight never goes down when the colour gets stronger
        var last = 0f
        for (chroma in 0..20) {
            val v = chroma / 20f * 0.6f
            val w = CoverShadeMath.weight(Color(0.5f + v / 2, 0.5f - v / 2, 0.5f))
            assertTrue("weight $w after $last", w >= last - 1e-6f)
            last = w
        }
    }

    // ---------------------------------------------------------------- contrast on the strongest wash

    private class Worst {
        var ink = 99f
        var text2 = 99f
        var text3 = 99f
        var inkAt = ""
        var text2At = ""
        var text3At = ""
    }

    private fun worstCase(level: CoverShadeLevel, mode: Mode, flat: Boolean = false, weightOne: Boolean = true): Worst {
        val worst = Worst()
        val p = mode.palette
        for ((name, cover) in covers) {
            val tint = CoverShadeMath.tint(cover, mode.dark)
            val w = if (weightOne) 1f else CoverShadeMath.weight(cover)
            val top = CoverShadeMath.topAlpha(level, mode.dark, mode.pureBlack, flat) * w
            // the strongest point of the wash is its top edge (the status bar and the title)
            val page = CoverShadeMath.washedAt(p.bg, tint, top, 0f)
            val ink = CoverShadeMath.contrast(p.ink, page)
            val t2 = CoverShadeMath.contrast(p.text2, page)
            val t3 = CoverShadeMath.contrast(p.text3, page)
            if (ink < worst.ink) { worst.ink = ink; worst.inkAt = name }
            if (t2 < worst.text2) { worst.text2 = t2; worst.text2At = name }
            if (t3 < worst.text3) { worst.text3 = t3; worst.text3At = name }
        }
        return worst
    }

    @Test
    fun `ink text keeps 7 to 1 and secondary text 4 point 5 to 1 on the strongest wash of every cover`() {
        for (level in levels) {
            for (m in modes) {
                val w = worstCase(level, m)
                assertTrue("$level ${m.name}: ink ${w.ink} on ${w.inkAt}", w.ink >= 7f)
                assertTrue("$level ${m.name}: text-2 ${w.text2} on ${w.text2At}", w.text2 >= 4.5f)
            }
        }
    }

    @Test
    fun `contrast table of the worst cover (printed for the work log)`() {
        for (level in levels) {
            for (m in modes) {
                val w = worstCase(level, m)
                println("CONTRAST $level ${m.name}: ink %.1f (${w.inkAt}), text-2 %.2f (${w.text2At}), text-3 %.2f (${w.text3At})".format(w.ink, w.text2, w.text3))
                val real = worstCase(level, m, weightOne = false)
                println("CONTRAST-WEIGHTED $level ${m.name}: ink %.1f, text-2 %.2f, text-3 %.2f".format(real.ink, real.text2, real.text3))
            }
        }
        assertTrue(true)
    }

    @Test
    fun `hints keep 3 to 1 on the strongest wash and 3 point 5 to 1 with Lembut`() {
        for (m in modes) {
            val soft = worstCase(CoverShadeLevel.SOFT, m)
            val strong = worstCase(CoverShadeLevel.STRONG, m)
            assertTrue("Lembut ${m.name}: text-3 ${soft.text3} on ${soft.text3At}", soft.text3 >= 3.5f)
            assertTrue("Kuat ${m.name}: text-3 ${strong.text3} on ${strong.text3At}", strong.text3 >= 3.0f)
        }
    }

    @Test
    fun `the page without a wash is the reference, a wash never lifts ink above it`() {
        for (m in modes) {
            val bare = CoverShadeMath.contrast(m.palette.ink, m.palette.bg)
            val w = worstCase(CoverShadeLevel.STRONG, m)
            assertTrue("${m.name}: bare page $bare, washed ${w.ink}", w.ink <= bare + 1e-3f)
        }
    }

    @Test
    fun `the flat style is never harder to read than glass`() {
        for (level in levels) {
            for (m in modes) {
                val glass = worstCase(level, m, flat = false)
                val flat = worstCase(level, m, flat = true)
                assertTrue(flat.ink >= glass.ink - 1e-3f && flat.text2 >= glass.text2 - 1e-3f && flat.text3 >= glass.text3 - 1e-3f)
            }
        }
    }

    // ---------------------------------------------------------------- the curve

    @Test
    fun `the ease starts at one, ends at zero and never rises`() {
        assertEquals(1f, CoverShadeMath.falloff(0f), 1e-6f)
        assertEquals(0f, CoverShadeMath.falloff(1f), 1e-6f)
        assertEquals(0.5f, CoverShadeMath.falloff(0.5f), 1e-6f)
        var last = 1f
        for (i in 1..200) {
            val v = CoverShadeMath.falloff(i / 200f)
            assertTrue("falloff rose at $i", v <= last + 1e-6f)
            last = v
        }
        // flat at both ends: no hard start under the status bar, no visible line where the wash ends
        assertTrue(CoverShadeMath.falloff(0.03f) > 0.99f)
        assertTrue(CoverShadeMath.falloff(0.97f) < 0.01f)
    }

    @Test
    fun `the gradient has many stops of one colour and only the alpha eases`() {
        val tint = CoverShadeMath.tint(Color(0xFF1E4FD8), dark = false)
        val stops = CoverShadeMath.stops(tint, 0.16f)
        assertTrue("stops: ${stops.size}", stops.size >= 12)
        assertEquals(0f, stops.first().first, 0f)
        assertEquals(1f, stops.last().first, 0f)
        assertEquals(0.16f, stops.first().second.alpha, 1e-3f)
        assertEquals(0f, stops.last().second.alpha, 1e-6f)
        var lastPos = -1f
        var lastAlpha = 1f
        for ((pos, c) in stops) {
            assertTrue(pos > lastPos)
            assertTrue("alpha rose", c.alpha <= lastAlpha + 1e-6f)
            // the same rgb in every stop: a stop to Transparent (black) would pass through a grey in the middle
            assertEquals(tint.red, c.red, 1e-6f)
            assertEquals(tint.green, c.green, 1e-6f)
            assertEquals(tint.blue, c.blue, 1e-6f)
            lastPos = pos
            lastAlpha = c.alpha
        }
        // no step between two neighbours is larger than a few percent of the top opacity: no corner, no band to see
        for (i in 1 until stops.size) {
            val step = stops[i - 1].second.alpha - stops[i].second.alpha
            assertTrue("step $i: $step", step <= 0.16f * 0.16f)
        }
    }

    @Test
    fun `where the wash ends it is exactly the page`() {
        for (m in modes) {
            val tint = CoverShadeMath.tint(Color(0xFFE53935), m.dark)
            val end = CoverShadeMath.washedAt(m.palette.bg, tint, 0.4f, 1f)
            assertEquals(m.palette.bg.red, end.red, 1e-6f)
            assertEquals(m.palette.bg.green, end.green, 1e-6f)
            assertEquals(m.palette.bg.blue, end.blue, 1e-6f)
        }
    }

    // ---------------------------------------------------------------- no cover, no wash

    @Test
    fun `no cover means no wash`() {
        val none = CoverShade.fixed(CoverShadeLevel.SOFT, cover = null, dark = false, pureBlack = false, flat = false)
        assertEquals(0f, none.strengthNow, 0f)
        val off = CoverShade.fixed(CoverShadeLevel.OFF, cover = Color(0xFFE53935), dark = false, pureBlack = false, flat = false)
        assertEquals(0f, off.strengthNow, 0f)
        assertFalse("Mati is not active: pages keep painting their own background", off.active)
        assertEquals(0f, CoverShade.Off.strengthNow, 0f)
        assertFalse(CoverShade.Off.active)
    }

    @Test
    fun `a cover gives the table opacity times its weight`() {
        val red = Color(0xFFFF0000)
        val shade = CoverShade.fixed(CoverShadeLevel.STRONG, red, dark = true, pureBlack = false, flat = false)
        assertTrue(shade.active)
        assertEquals(0.40f, shade.strengthNow, 1e-6f)
        val grey = CoverShade.fixed(CoverShadeLevel.STRONG, Color(0xFF808080), dark = true, pureBlack = false, flat = false)
        assertEquals(0.40f * 0.4f, grey.strengthNow, 0.01f)
        assertEquals(0.58f, shade.reachNow, 1e-6f)
    }

    // ---------------------------------------------------------------- the stored key

    @Test
    fun `the key defaults to Lembut for new installs and updates, and survives odd values`() {
        assertEquals(CoverShadeLevel.SOFT, CoverShadeLevel.DEFAULT)
        assertEquals("coverShade", CoverShadeKey.name)
        // absent (an update from a build without the setting, or a fresh install)
        assertEquals(CoverShadeLevel.SOFT, (null as String?).toEnum(CoverShadeLevel.DEFAULT))
        // an unknown or damaged value falls back too
        assertEquals(CoverShadeLevel.SOFT, "BRIGHT".toEnum(CoverShadeLevel.DEFAULT))
        assertEquals(CoverShadeLevel.SOFT, "".toEnum(CoverShadeLevel.DEFAULT))
        // every level round-trips by name
        for (level in CoverShadeLevel.entries) {
            assertEquals(level, level.name.toEnum(CoverShadeLevel.DEFAULT))
        }
        assertEquals(setOf("OFF", "SOFT", "STRONG"), CoverShadeLevel.entries.map { it.name }.toSet())
    }

    // ---------------------------------------------------------------- pages with their own header colour

    private val root: File =
        listOf("src/main/kotlin", "app/src/main/kotlin")
            .map { File(it, "com/metrolist/music") }
            .firstOrNull { it.isDirectory }
            ?: error("sources not found from ${File(".").absolutePath}")

    private fun src(path: String) = File(root, path).readText()

    @Test
    fun `the header routes are the routes of the screens that use the hero header`() {
        val nav = src("ui/screens/NavigationBuilder.kt")
        for (route in CoverShadeMath.HeaderRoutes) {
            assertTrue("the route $route is not in NavigationBuilder.kt", nav.contains("\"$route\""))
            assertTrue(CoverShadeMath.pageOwnsHeader(route))
        }
        // one route per screen that calls HeroHeader( (the definition itself does not count)
        val users = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("    HeroHeader(") || it.readText().contains("                HeroHeader(") || it.readText().contains("                    HeroHeader(") }
            .map { it.name }.toSet()
        assertEquals("screens with a hero header: $users", users.size, CoverShadeMath.HeaderRoutes.size)
        // the list pages of an artist and the main tabs have none
        for (route in listOf("home", "search", "library", "you", "settings", "artist/{artistId}/songs", "artist/{artistId}/albums", "stats", null)) {
            assertFalse("route $route", CoverShadeMath.pageOwnsHeader(route))
        }
    }

    // ---------------------------------------------------------------- guards on the sources

    @Test
    fun `the wash is drawn from the shell and the bars only, never per page`() {
        val allowed = setOf("MainActivity.kt", "ui/component/StyledTopAppBar.kt", "ui/theme/CoverShade.kt", "ui/screens/settings/CoverShadeCard.kt")
        val callers = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path.replace('\\', '/') to it.readText() }
            .filter { (name, text) -> name !in allowed && (text.contains("drawCoverShade(") || text.contains(".coverShade(")) }
            .map { it.first }.toList()
        assertTrue("pages that draw the wash themselves: $callers", callers.isEmpty())
        val main = src("MainActivity.kt")
        assertTrue("the root box draws the wash", main.contains("graphicsLayer { }.coverShade(coverShade, shadePageHeightPx)"))
        assertTrue("the recorded glass layer draws the wash", main.contains("drawCoverShade(coverShade, shadePageHeightPx)"))
        assertTrue("one extraction of the cover colour feeds the shade", main.contains("val needsCoverColor = coverTintEnabled || coverShadeLevel != CoverShadeLevel.OFF"))
        assertTrue("the theme gets the level", main.contains("shadeLevel = coverShadeLevel"))
    }

    @Test
    fun `the search layers that lie over the browse grid are opaque`() {
        // bug report 2026-11-07: transparent layers let the grid, the suggestions and the results show through each other
        for (f in listOf("ui/screens/search/OnlineSearchScreen.kt", "ui/screens/search/OnlineSearchResult.kt", "ui/screens/search/LocalSearchScreen.kt")) {
            assertTrue("$f must use occludingPageBackground for the layer it draws over another one", src(f).contains(".occludingPageBackground("))
        }
    }

    @Test
    fun `the shade is read in the draw phase and has no accent`() {
        val text = src("ui/theme/CoverShade.kt")
        for (banned in listOf("colorScheme.primary", "colorScheme.secondary", "colorScheme.tertiary", "isGlassStyle", ".uppercase(", "Serif")) {
            assertFalse("CoverShade.kt has $banned", text.contains(banned))
        }
        // the animated states are read inside paint(), which only a draw scope calls
        val paintStart = text.indexOf("internal fun paint(")
        val paint = text.substring(paintStart, text.indexOf("companion object", paintStart))
        assertTrue(paint.contains("strength.value") && paint.contains("tint.value") && paint.contains("reach.value"))
        val remember = text.substring(text.indexOf("fun rememberCoverShade("))
        assertFalse("the animated value is read while composing", Regex("""\b(strength|tint|reach)\.value\b""").containsMatchIn(remember.replace("strength.value < CoverShadeMath.MinStrength", "")))
        // a song change never recomposes a page: the local is not static
        assertTrue(text.contains("val LocalCoverShade = compositionLocalOf"))
        // the cross-fade is 700 ms
        assertEquals(700, CoverShadeMath.CrossfadeMs)
    }

    @Test
    fun `controls stay neutral, the shade is not read by a control file`() {
        for (f in listOf("ui/component/AppNavigation.kt", "ui/component/ChipsRow.kt", "ui/component/SearchSourceSwitch.kt", "ui/component/GlassSwitch.kt", "ui/component/Material3SettingsGroup.kt")) {
            val text = src(f)
            assertFalse("$f reads the cover shade", text.contains("LocalCoverShade") || text.contains("CoverShade"))
        }
    }

    @Test
    fun `the setting is on the theme page, in the settings search and in both languages`() {
        assertTrue(src("ui/screens/settings/ThemeScreen.kt").contains("CoverShadeCard("))
        assertTrue(src("ui/screens/settings/SettingsSearchIndex.kt").contains("R.string.auralys_cover_shade_title"))
        val res = File(root.parentFile.parentFile.parentFile.parentFile, "res")
        for (lang in listOf("values", "values-in")) {
            val xml = File(res, "$lang/auralys_cover_shade.xml").readText()
            for (name in listOf("title", "off", "soft", "strong")) {
                assertTrue("$lang is missing auralys_cover_shade_$name", xml.contains("name=\"auralys_cover_shade_$name\""))
            }
        }
        val id = File(res, "values-in/auralys_cover_shade.xml").readText()
        assertTrue(id.contains(">Efek berbayang<") && id.contains(">Mati<") && id.contains(">Lembut<") && id.contains(">Kuat<"))
        // the Indonesian wording rule: plain, no "kalau", no slang
        assertFalse(id.contains("kalau", ignoreCase = true))
    }

    @Test
    fun `the migration does not touch the setting`() {
        val text = src("ui/theme/DesignMigration.kt")
        assertFalse("DesignMigration writes the shade key", text.contains("CoverShade"))
        assertNull(null as String?) // the key is simply absent after the migration (see DesignMigrationTest)
    }
}
