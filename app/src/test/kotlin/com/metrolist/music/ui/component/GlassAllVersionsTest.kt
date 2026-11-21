/**
 * Auralys round 4.3.4 (E): the floating Kaca bar and its fold on every Android version.
 * Licensed under GPL-3.0 | See git history for contributors
 */
package com.metrolist.music.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.metrolist.music.constants.NavigationBarHeight
import com.metrolist.music.constants.SlimNavBarHeight
import com.metrolist.music.ui.component.motion.NavFoldGeometry
import com.metrolist.music.ui.theme.HorizonDark
import com.metrolist.music.ui.theme.HorizonLight
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassAllVersionsTest {
    // ------------------------------------------------------------------ policy, per Android version

    /** MainActivity: the app backdrop exists only in Kaca with "Blur kaca" on and RenderEffect (Android 12+). */
    private fun backdropFor(sdk: Int, kaca: Boolean, blurPref: Boolean) = kaca && blurPref && sdk >= 31

    /** The material written a second way, as a table of reasons. */
    private fun expectedMaterial(
        sdk: Int, blurPref: Boolean, lensSwitch: Boolean, lowRam: Boolean, power: Boolean, anim: Float, visible: Boolean,
    ): BarMaterial = when {
        sdk <= 30 -> BarMaterial.FROST // Android 8-11: no RenderEffect, nothing can be blurred
        !blurPref -> BarMaterial.FROST // "Blur kaca" off: nothing samples the page
        sdk <= 32 -> BarMaterial.BLUR // Android 12: blur, no RuntimeShader
        lensSwitch && !lowRam && !power && anim > 0f && visible -> BarMaterial.LENS
        else -> BarMaterial.BLUR
    }

    @Test
    fun `Kaca gets the Liquid bar on every Android version, Datar never`() {
        for (sdk in 26..36) {
            assertTrue("sdk $sdk", GlassBarPolicy.liquidBar(kaca = true))
            assertFalse("sdk $sdk", GlassBarPolicy.liquidBar(kaca = false))
        }
    }

    @Test
    fun `every combination per Android version gives the table material`() {
        var n = 0
        val seen = HashMap<Int, MutableSet<BarMaterial>>()
        for (sdk in 26..36)
            for (blurPref in listOf(true, false))
                for (lens in listOf(true, false))
                    for (lowRam in listOf(true, false))
                        for (power in listOf(true, false))
                            for (anim in listOf(0f, 1f))
                                for (visible in listOf(true, false)) {
                                    val backdrop = backdropFor(sdk, kaca = true, blurPref = blurPref)
                                    val mode = GlassLensPolicy.decide(sdk, true, backdrop, lens, lowRam, power, anim, visible)
                                    val got = GlassBarPolicy.material(mode, backdrop)
                                    val want = expectedMaterial(sdk, blurPref, lens, lowRam, power, anim, visible)
                                    assertEquals("sdk=$sdk blur=$blurPref lens=$lens lowRam=$lowRam power=$power anim=$anim visible=$visible", want, got)
                                    seen.getOrPut(sdk) { HashSet() }.add(got)
                                    n++
                                }
        assertEquals(11 * 2 * 2 * 2 * 2 * 2 * 2, n)
        // the bands, as the owner asked for them
        for (sdk in 26..30) assertEquals("sdk $sdk", setOf(BarMaterial.FROST), seen[sdk])
        for (sdk in 31..32) assertEquals("sdk $sdk", setOf(BarMaterial.FROST, BarMaterial.BLUR), seen[sdk])
        for (sdk in 33..36) assertEquals("sdk $sdk", setOf(BarMaterial.FROST, BarMaterial.BLUR, BarMaterial.LENS), seen[sdk])
    }

    @Test
    fun `no backdrop is always frost, whatever the lens policy says`() {
        for (mode in LensMode.entries) assertEquals(BarMaterial.FROST, GlassBarPolicy.material(mode, backdropAvailable = false))
        assertEquals(BarMaterial.FROST, GlassBarPolicy.material(LensMode.FLAT, backdropAvailable = true))
        assertEquals(BarMaterial.BLUR, GlassBarPolicy.material(LensMode.BLUR, backdropAvailable = true))
        assertEquals(BarMaterial.LENS, GlassBarPolicy.material(LensMode.LENS, backdropAvailable = true))
    }

    @Test
    fun `the fold needs Kaca, the setting and the bottom bar, and no Android version`() {
        for (collapse in listOf(true, false))
            for (kaca in listOf(true, false))
                for (shown in listOf(true, false))
                    for (rail in listOf(true, false)) {
                        val want = collapse && kaca && shown && !rail
                        assertEquals("collapse=$collapse kaca=$kaca shown=$shown rail=$rail", want, GlassBarPolicy.foldEnabled(collapse, kaca, shown, rail))
                    }
    }

    @Test
    fun `blur kaca is on by default from Android 13 on phones with room for it`() {
        for (sdk in 26..36)
            for (lowRam in listOf(true, false))
                for (heap in listOf(64, 128, 191, 192, 256, 512)) {
                    val want = sdk >= 33 && !lowRam && heap >= 192
                    assertEquals("sdk=$sdk lowRam=$lowRam heap=$heap", want, GlassBarPolicy.blurDefault(sdk, lowRam, heap))
                }
        assertFalse("Android 11 has no RenderEffect", GlassBarPolicy.blurDefault(30, false, 512))
        // review 2026-11-12 finding 17: frost stays the default look on Android 12 (the user can still switch the blur on)
        assertFalse("Android 12 keeps frost by default", GlassBarPolicy.blurDefault(31, false, 512))
        assertFalse("Android 12L keeps frost by default", GlassBarPolicy.blurDefault(32, false, 512))
        assertTrue("Android 13 defaults to the blur", GlassBarPolicy.blurDefault(33, false, 256))
    }

    // ------------------------------------------------------------------ fold geometry and tap areas

    private val mini = 64f // MiniPlayerHeight
    private val spacing = 8f // MiniPlayerBottomSpacing

    /** Everything in dp, measured upwards from the bottom edge of the screen. */
    private class Rect(val left: Float, val right: Float, val bottom: Float, val top: Float) {
        val cy get() = (bottom + top) / 2f
    }

    /** The folded pill: AppNavigation pads it BAR_TOP_DP below the top of the nav slot (inset + navPadding tall). */
    private fun pill(inset: Float, navPadding: Float, slim: Boolean): Rect {
        val size = NavFoldGeometry.pillSizeDp(slim)
        val top = inset + navPadding - NavFoldGeometry.BAR_TOP_DP
        return Rect(NavFoldGeometry.BAR_SIDE_DP, NavFoldGeometry.BAR_SIDE_DP + size, top - size, top)
    }

    /**
     * The mini player capsule at fold progress [p]: top-aligned at the top of the collapsed sheet (inset + navPadding +
     * spacing + mini above the bottom), the whole sheet dropped by the shift of Player.kt (== navFoldDropDp). The sheet is
     * moved in its graphics layer BEFORE its pointer input, so this rect is also where the capsule is tapped.
     */
    private fun capsule(inset: Float, navPadding: Float, slim: Boolean, p: Float, width: Float, miniShift: Float): Rect {
        val pillDp = NavFoldGeometry.pillSizeDp(slim)
        val sheetTop = inset + navPadding + spacing + mini
        val drop = navFoldDropDp(miniShift, pillDp, mini) * p
        val top = sheetTop - drop
        val h = NavFoldGeometry.miniHeightDp(p, pillDp, mini)
        return Rect(NavFoldGeometry.miniStartDp(p, pillDp), width - NavFoldGeometry.miniEndDp(p), top - h, top)
    }

    @Test
    fun `folded, the mini player sits exactly beside the pill, for both bar sizes and any system inset`() {
        for (slim in listOf(false, true))
            for (inset in listOf(0f, 15f, 24f, 48f))
                for (width in listOf(360f, 411f, 600f)) {
                    val navPadding = if (slim) SlimNavBarHeight.value else NavigationBarHeight.value
                    val pillDp = NavFoldGeometry.pillSizeDp(slim)
                    val shift = NavFoldGeometry.miniShiftDp(mini, spacing, pillDp)
                    val pr = pill(inset, navPadding, slim)
                    val c = capsule(inset, navPadding, slim, 1f, width, shift)
                    val tag = "slim=$slim inset=$inset width=$width"
                    assertEquals(tag, pr.top, c.top, 0.001f)
                    assertEquals(tag, pr.bottom, c.bottom, 0.001f)
                    assertEquals(tag, pr.cy, c.cy, 0.001f)
                    assertEquals("$tag: gap to the pill", NavFoldGeometry.PILL_GAP_DP, c.left - pr.right, 0.001f)
                    assertEquals("$tag: right margin like the bar", NavFoldGeometry.BAR_SIDE_DP, width - c.right, 0.001f)
                    assertTrue("$tag: capsule has room", c.right - c.left > 120f)
                }
    }

    @Test
    fun `open, the mini player is where it always was`() {
        for (slim in listOf(false, true)) {
            val pillDp = NavFoldGeometry.pillSizeDp(slim)
            val navPadding = if (slim) SlimNavBarHeight.value else NavigationBarHeight.value
            val c = capsule(24f, navPadding, slim, 0f, 411f, NavFoldGeometry.miniShiftDp(mini, spacing, pillDp))
            assertEquals(12f, c.left, 0f)
            assertEquals(411f - 12f, c.right, 0f)
            assertEquals(mini, c.top - c.bottom, 0f)
            assertEquals(24f + navPadding + spacing + mini, c.top, 0f)
        }
    }

    @Test
    fun `both Liquid bars fit their slot with 2 dp to spare (finding 18)`() {
        // AppNavigation pads the bar BAR_TOP_DP below the slot's top; the slot is inset + navPadding tall, so the bar must end above the inset
        for (slim in listOf(false, true)) {
            val navPadding = if (slim) SlimNavBarHeight.value else NavigationBarHeight.value
            val used = NavFoldGeometry.BAR_TOP_DP + NavFoldGeometry.pillSizeDp(slim)
            assertEquals("slim=$slim: 2 dp under the bar, like the normal bar", 2f, navPadding - used, 0.001f)
            // with no system inset the bar is not squeezed below its own height
            assertTrue("slim=$slim", navPadding - NavFoldGeometry.BAR_TOP_DP >= NavFoldGeometry.pillSizeDp(slim))
        }
        // the Datar slim bar (52 dp) keeps room as well
        assertTrue(SlimNavBarHeight.value >= 6f + 52f)
    }

    @Test
    fun `the sheet drop is the same for both bars and lands the capsule top on the bar top`() {
        for (slim in listOf(false, true)) {
            val pillDp = NavFoldGeometry.pillSizeDp(slim)
            val drop = navFoldDropDp(NavFoldGeometry.miniShiftDp(mini, spacing, pillDp), pillDp, mini)
            assertEquals("spacing + mini player + bar top padding", spacing + mini + NavFoldGeometry.BAR_TOP_DP, drop, 0.001f)
        }
    }

    @Test
    fun `the old shift formula left the folded mini player 4 dp (normal) and 8 dp (slim) too high`() {
        // before 4.3.4: navPadding + spacing + mini / 2 - (6 + pill / 2)
        fun oldShift(navPadding: Float, pillDp: Float) = navPadding + spacing + mini / 2f - (6f + pillDp / 2f)
        for ((slim, expectedError) in listOf(false to 4f, true to 8f)) {
            val navPadding = if (slim) 64f else 80f
            val pr = pill(24f, navPadding, slim)
            val c = capsule(24f, navPadding, slim, 1f, 411f, oldShift(navPadding, NavFoldGeometry.pillSizeDp(slim)))
            assertEquals("slim=$slim", expectedError, c.cy - pr.cy, 0.001f)
        }
    }

    @Test
    fun `once the bar is gone the capsule clears the pill`() {
        for (slim in listOf(false, true)) {
            val pillDp = NavFoldGeometry.pillSizeDp(slim)
            // the bar is removed at p >= 0.9 (LiquidNavBar barGone); the pill grows 0.72 -> 1 around the start edge
            for (p in listOf(0.9f, 0.95f, 1f, 1.05f)) {
                val pillRight = NavFoldGeometry.BAR_SIDE_DP + pillDp * (0.72f + 0.28f * p.coerceAtMost(1.08f))
                assertTrue("slim=$slim p=$p", NavFoldGeometry.miniStartDp(p, pillDp) >= pillRight - 0.001f)
            }
        }
    }

    // ------------------------------------------------------------------ the frost material

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return maxOf(la, lb) / minOf(la, lb)
    }

    private fun over(fg: Color, bg: Color): Color {
        val a = fg.alpha
        return Color(fg.red * a + bg.red * (1 - a), fg.green * a + bg.green * (1 - a), fg.blue * a + bg.blue * (1 - a), 1f)
    }

    @Test
    fun `tab icons and labels stay readable on the frost over any page`() {
        val pages = listOf(Color.Black, Color.White, Color(0xFF808080), Color(0xFF6A4FD0), Color(0xFFE0508A), Color(0xFF2F8FE0), Color(0xFFF2D06A))
        for (dark in listOf(false, true)) {
            val t = if (dark) HorizonDark else HorizonLight
            for (page in pages) {
                val body = GlassFrostLook.bodyOver(page, t.surface1, dark)
                val label = t.ink
                val muted = over(t.ink.copy(alpha = 0.62f), body) // NavMutedAlpha
                assertTrue("dark=$dark page=$page label ${contrast(label, body)}", contrast(label, body) >= 4.5f)
                assertTrue("dark=$dark page=$page muted icon ${contrast(muted, body)}", contrast(muted, body) >= 3f)
            }
        }
    }

    @Test
    fun `rows sliding under the frost show through at most 3 percent of their contrast`() {
        // without a blur the show-through is sharp; at 10 % and at 5 % it was still readable in the Robolectric renders
        for (dark in listOf(false, true)) assertTrue(GlassFrostLook.showThrough(dark) <= 0.03f + 1e-6f)
    }

    @Test
    fun `the frost reads as a raised pane, lighter than the page by night, an edge by day`() {
        val night = GlassFrostLook.bodyOver(HorizonDark.bg, HorizonDark.surface1, dark = true)
        assertTrue("night body must be lighter than the page", night.luminance() > HorizonDark.bg.luminance())
        assertTrue(GlassFrostLook.EDGE_LIGHT > 0f && GlassFrostLook.ELEVATION_DP > 0f)
        assertEquals(3, GlassFrostLook.RIM_LIGHT.size)
        assertTrue("bevel: top-left brighter than the middle", GlassFrostLook.RIM_LIGHT[0] > GlassFrostLook.RIM_LIGHT[1] && GlassFrostLook.RIM_DARK[0] > GlassFrostLook.RIM_DARK[1])
    }

    // ------------------------------------------------------------------ wiring (sources)

    private fun source(path: String): String {
        val root = listOf("src/main/kotlin", "app/src/main/kotlin").map { File(it) }.first { it.isDirectory }
        return File(root, path).readText()
    }

    @Test
    fun `no SDK gate is left between Kaca and the Liquid bar or the fold`() {
        val nav = source("com/metrolist/music/ui/component/AppNavigation.kt")
        assertTrue(nav.contains("GlassBarPolicy.liquidBar(kaca = LocalUiStyle.current == UiStyle.GLASS)"))
        assertFalse("the old Android 13 gate is back", nav.contains("appBackdrop != null && android.os.Build.VERSION.SDK_INT >= 33"))
        assertFalse("the old fallback bar is back", nav.contains("GlassLensBar("))
        assertFalse("no SDK check in the nav bar file", nav.contains("SDK_INT"))
        // both the bar and the pill take the shared padding, so the fold maths and the drawing agree
        assertEquals(2, Regex("""NavFoldGeometry\.BAR_TOP_DP\.dp""").findAll(nav).count())
        val main = source("com/metrolist/music/MainActivity.kt")
        assertTrue(main.contains("GlassBarPolicy.foldEnabled(collapseNavOnScroll, uiStyle == UiStyle.GLASS, shouldShowNavigationBar, showRail)"))
        assertTrue(main.contains("NavFoldGeometry.miniShiftDp(MiniPlayerHeight.value, MiniPlayerBottomSpacing.value, navFold.pillSizeDp)"))
        assertTrue("the backdrop rule the table above assumes", main.contains("val glassBlurOn = glassBlurPref && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S"))
        assertTrue(main.contains("LocalAppBackdrop provides (if (uiStyle == UiStyle.GLASS && glassBlurOn) appBackdrop else null)"))
        val mini = source("com/metrolist/music/ui/player/MiniPlayer.kt")
        assertTrue(mini.contains("NavFoldGeometry.miniStartDp(p, pillSizeDp)"))
        assertTrue(mini.contains("NavFoldGeometry.miniEndDp(p)"))
        assertTrue(mini.contains("NavFoldGeometry.miniHeightDp(p, pillSizeDp, MiniPlayerHeight.value)"))
        val player = source("com/metrolist/music/ui/player/Player.kt")
        assertTrue("Player.kt drop == navFoldDropDp", player.contains("(it.miniShiftDp - (it.pillSizeDp - MiniPlayerHeight.value) / 2f).dp.toPx() } * it.followProgress"))
        val sheet = source("com/metrolist/music/ui/component/BottomSheet.kt")
        val shiftAt = sheet.indexOf("translationY = y + collapsedShiftPx()")
        val pointerAt = sheet.indexOf(".pointerInput(state, isExpandable)")
        assertTrue("the drop must move the drag/tap area too (graphicsLayer before pointerInput)", shiftAt in 1 until pointerAt)
    }

    @Test
    fun `the frost replaces the old no-blur fallbacks, and the tabs never sample without a backdrop`() {
        val glass = source("com/metrolist/music/ui/component/GlassComponents.kt")
        assertTrue(glass.contains("return this.glassFrost(shape)"))
        assertTrue(glass.contains("GlassBarPolicy.blurDefault(android.os.Build.VERSION.SDK_INT, am.isLowRamDevice, am.memoryClass)"))
        val tabs = source("com/metrolist/music/ui/component/backdrop/catalog/components/LiquidBottomTabs.kt")
        assertTrue(tabs.contains("backdrop: Backdrop?,"))
        assertTrue("the frost bar uses the frost material", tabs.contains(".glassFrost(CapsuleShape, frostColors!!"))
        assertTrue("no copy of the tabs without a backdrop", tabs.contains("val tabsBackdrop = if (frost) null else rememberLayerBackdrop()"))
        assertTrue(tabs.contains("Modifier.frostPuck(dampedDragAnimation, isLightTheme)"))
        val nav = source("com/metrolist/music/ui/component/AppNavigation.kt")
        assertTrue("the folded pill has a frost branch", nav.contains("Modifier.glassFrost(FoldedPillShape)"))
        val frost = source("com/metrolist/music/ui/component/GlassFrost.kt")
        assertFalse("the frost must not clip with a path (jagged on Android 8 GL)", frost.contains("clipPath("))
        assertFalse("the frost samples nothing", frost.contains("drawBackdrop(") || frost.contains("renderEffect") || frost.contains("BlurEffect") || frost.contains("SDK_INT"))
        assertTrue("brushes are built per size, not per frame", frost.contains("drawWithCache {") && frost.contains("onDrawWithContent {"))
        assertTrue("content (press sheen, ripple) clipped to the shape like the backdrop node", frost.contains(".clip(shape)"))
    }

    @Test
    fun `the collapse switch is offered in Kaca on every Android version, the lens switch stays Android 13+`() {
        val settings = source("com/metrolist/music/ui/screens/settings/AppearanceSettings.kt")
        val at = settings.indexOf("R.string.collapse_nav_on_scroll_title")
        val rowStart = settings.lastIndexOf("add(", at)
        val guard = settings.substring(settings.lastIndexOf("if (", rowStart), rowStart)
        assertTrue(guard, guard.contains("uiStyle == UiStyle.GLASS"))
        assertFalse(guard, guard.contains("SDK_INT") || guard.contains("glassBlurEnabled"))
        val lensAt = settings.indexOf("R.string.glass_lens_title")
        val lensRow = settings.lastIndexOf("add(", lensAt)
        assertTrue(settings.substring(settings.lastIndexOf("if (", lensRow), lensRow).contains("SDK_INT >= 33"))
    }
}
