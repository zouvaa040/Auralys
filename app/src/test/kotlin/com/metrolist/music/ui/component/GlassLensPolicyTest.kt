package com.metrolist.music.ui.component

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassLensPolicyTest {
    /** The rules of GlassLensPolicy written a second way, as a plain truth table of reasons. */
    private fun expected(
        sdk: Int, kaca: Boolean, backdrop: Boolean, user: Boolean, lowRam: Boolean, power: Boolean,
        anim: Float, visible: Boolean, failed: Boolean,
    ): LensMode {
        if (!kaca) return LensMode.FLAT
        if (!backdrop) return LensMode.FLAT
        if (sdk < 31) return LensMode.FLAT
        // from here on there is a backdrop: frost is possible
        val shaderOk = sdk >= 33 && !failed
        val phoneOk = !lowRam && !power && anim > 0f && visible
        return if (shaderOk && user && phoneOk) LensMode.LENS else LensMode.BLUR
    }

    @Test
    fun `every combination of the nine inputs gives the table answer`() {
        var n = 0
        var lens = 0
        var blur = 0
        var flat = 0
        for (sdk in listOf(26, 30, 31, 32, 33, 34, 36))
            for (kaca in listOf(true, false))
                for (backdrop in listOf(true, false))
                    for (user in listOf(true, false))
                        for (lowRam in listOf(true, false))
                            for (power in listOf(true, false))
                                for (anim in listOf(0f, 0.5f, 1f))
                                    for (visible in listOf(true, false))
                                        for (failed in listOf(true, false)) {
                                            val got = GlassLensPolicy.decide(sdk, kaca, backdrop, user, lowRam, power, anim, visible, failed)
                                            val want = expected(sdk, kaca, backdrop, user, lowRam, power, anim, visible, failed)
                                            assertEquals(
                                                "sdk=$sdk kaca=$kaca backdrop=$backdrop user=$user lowRam=$lowRam power=$power anim=$anim visible=$visible failed=$failed",
                                                want, got,
                                            )
                                            n++
                                            when (got) {
                                                LensMode.LENS -> lens++
                                                LensMode.BLUR -> blur++
                                                LensMode.FLAT -> flat++
                                            }
                                        }
        assertEquals(7 * 2 * 2 * 2 * 2 * 2 * 3 * 2 * 2, n)
        assertTrue("lens never reached", lens > 0)
        assertTrue("blur never reached", blur > 0)
        assertTrue("flat never reached", flat > 0)
    }

    private fun lensWith(
        sdk: Int = 34, kaca: Boolean = true, backdrop: Boolean = true, user: Boolean = true, lowRam: Boolean = false,
        power: Boolean = false, anim: Float = 1f, visible: Boolean = true, failed: Boolean = false,
    ) = GlassLensPolicy.decide(sdk, kaca, backdrop, user, lowRam, power, anim, visible, failed)

    @Test
    fun `a normal phone in Kaca with the switch on gets the lens`() {
        assertEquals(LensMode.LENS, lensWith())
        assertEquals(LensMode.LENS, lensWith(sdk = 33))
    }

    @Test
    fun `Datar is flat whatever the phone can do`() {
        assertEquals(LensMode.FLAT, lensWith(kaca = false))
        assertEquals(LensMode.FLAT, lensWith(kaca = false, user = true, sdk = 36))
    }

    @Test
    fun `without a backdrop or below Android 12 the glass is flat`() {
        assertEquals(LensMode.FLAT, lensWith(backdrop = false))
        assertEquals(LensMode.FLAT, lensWith(sdk = 30))
        assertEquals(LensMode.FLAT, lensWith(sdk = 26))
    }

    @Test
    fun `Android 12 has the frost but no shader`() {
        assertEquals(LensMode.BLUR, lensWith(sdk = 31))
        assertEquals(LensMode.BLUR, lensWith(sdk = 32))
    }

    @Test
    fun `each guard alone drops the lens to blur`() {
        assertEquals("switch off", LensMode.BLUR, lensWith(user = false))
        assertEquals("low RAM", LensMode.BLUR, lensWith(lowRam = true))
        assertEquals("battery saver", LensMode.BLUR, lensWith(power = true))
        assertEquals("animations off", LensMode.BLUR, lensWith(anim = 0f))
        assertEquals("app not visible", LensMode.BLUR, lensWith(visible = false))
        assertEquals("shader failed", LensMode.BLUR, lensWith(failed = true))
    }

    @Test
    fun `a negative or NaN animation scale counts as off`() {
        assertEquals(LensMode.BLUR, lensWith(anim = -1f))
        assertEquals(LensMode.BLUR, lensWith(anim = Float.NaN))
    }

    @Test
    fun `a small animation scale still gets the lens`() {
        assertEquals(LensMode.LENS, lensWith(anim = 0.5f))
        assertEquals(LensMode.LENS, lensWith(anim = 10f))
    }

    // ------------------------------------------------------------------ the recipes

    @Test
    fun `the nav bar recipe is BitChord's lens with a calmer frost and a thinner veil`() {
        val n = GlassLensRecipes.NavBar
        val b = GlassLensRecipes.BitChord
        assertEquals(b.lensHeightDp, n.lensHeightDp, 0f)
        assertEquals(b.lensAmountDp, n.lensAmountDp, 0f)
        assertTrue(n.depth)
        assertTrue(n.dispersion > 0f)
        assertTrue("the frost must stay light enough to show the bend", n.blurDp <= 12f)
        assertTrue("thinner than G1", n.veilScale < 1f && n.veilScale > 0.5f)
    }

    @Test
    fun `the lens is shallower than half the bar so the middle is never bent`() {
        assertTrue(GlassLensRecipes.NavBar.lensHeightDp < 72f / 2f)
        assertTrue(GlassLensRecipes.MiniPlayer.lensHeightDp < 64f / 2f)
        assertTrue(GlassLensRecipes.PUCK_LENS_HEIGHT_DP < 63f / 2f)
    }

    @Test
    fun `no lens means the heavy frost of before`() {
        assertEquals(GlassLensRecipes.NavBarBlur, GlassLensRecipes.navBar(LensMode.BLUR))
        assertEquals(GlassLensRecipes.NavBarBlur, GlassLensRecipes.navBar(LensMode.FLAT))
        assertEquals(GlassLensRecipes.NavBar, GlassLensRecipes.navBar(LensMode.LENS))
        assertFalse(GlassLensRecipes.NavBarBlur.hasLens)
        assertTrue(GlassLensRecipes.NavBar.hasLens)
        assertEquals(20f, GlassLensRecipes.NavBarBlur.blurDp, 0f) // HorizonGlass.G1.blurDp
        assertNotEquals(GlassLensRecipes.NavBar.blurDp, GlassLensRecipes.NavBarBlur.blurDp)
    }

    @Test
    fun `the thinned veil never goes above the tier veil`() {
        for (r in listOf(GlassLensRecipes.NavBar, GlassLensRecipes.MiniPlayer, GlassLensRecipes.NavBarBlur)) {
            assertTrue(r.veilScale in 0f..1f)
        }
    }

    // ------------------------------------------------------------------ wiring

    private fun source(path: String): String {
        val roots = listOf("src/main/kotlin", "app/src/main/kotlin")
        val root = roots.map { File(it) }.first { it.isDirectory }
        return File(root, path).readText()
    }

    private fun res(path: String): String {
        val roots = listOf("src/main/res", "app/src/main/res")
        val root = roots.map { File(it) }.first { it.isDirectory }
        return File(root, path).readText()
    }

    @Test
    fun `the nav bar, the folded pill and the mini player are wired to the policy`() {
        val tabs = source("com/metrolist/music/ui/component/backdrop/catalog/components/LiquidBottomTabs.kt")
        assertTrue(tabs.contains("applyGlassRecipe(barRecipe, NavGlassScale)"))
        assertTrue("the puck lens must be gated", tabs.contains("if (lensOn)"))
        assertFalse("no raw lens() call may be left in the nav bar", Regex("""(?<![A-Za-z])lens\(""").containsMatchIn(tabs))
        val nav = source("com/metrolist/music/ui/component/AppNavigation.kt")
        assertTrue(nav.contains("rememberGlassLensMode()"))
        assertTrue(nav.contains("lensMode = lensMode"))
        assertFalse("the folded pill must not call lens() itself", nav.contains("lens(24f.dp.toPx() * s"))
        val mini = source("com/metrolist/music/ui/player/MiniPlayer.kt")
        assertTrue(mini.contains("glassBlur(miniShape, restLens = true)"))
        val glass = source("com/metrolist/music/ui/component/GlassComponents.kt")
        assertTrue(glass.contains("rememberGlassLensMode()"))
        assertFalse("liquidGlass must not call the library lens directly", Regex("""(?<![A-Za-z])lens\(""").containsMatchIn(glass))
    }

    @Test
    fun `the switch exists once, is Kaca-only, has no description line, and is in the search index`() {
        val settings = source("com/metrolist/music/ui/screens/settings/AppearanceSettings.kt")
        assertEquals(1, Regex("""R\.string\.glass_lens_title""").findAll(settings).count())
        val start = settings.indexOf("R.string.glass_lens_title")
        val rowStart = settings.lastIndexOf("add(", start)
        val guard = settings.substring(settings.lastIndexOf("if (", rowStart), rowStart)
        assertTrue("shown only in Kaca: $guard", guard.contains("UiStyle.GLASS") && guard.contains("SDK_INT >= 33"))
        val row = settings.substring(rowStart, settings.indexOf("onClick = { onGlassLensEnabledChange", start))
        assertFalse("no description under the switch", row.contains("description ="))
        val index = source("com/metrolist/music/ui/screens/settings/SettingsSearchIndex.kt")
        assertTrue(index.contains("SettingsSearchEntry(R.string.glass_lens_title, null,"))
        val key = source("com/metrolist/music/constants/PreferenceKeys.kt")
        assertTrue(key.contains("""val GlassLensKey = booleanPreferencesKey("glassLens")"""))
    }

    @Test
    fun `the strings exist in English and Indonesian and use the plain wording`() {
        val en = res("values/auralys_glass.xml")
        val id = res("values-in/auralys_glass.xml")
        assertTrue(en.contains("""name="glass_lens_title">Glass lens effect<"""))
        assertTrue(id.contains("""name="glass_lens_title">Efek lensa kaca<"""))
        for (banned in listOf("kalau", "gak", "nggak", "banget")) assertFalse(banned, id.contains(banned))
    }
}
