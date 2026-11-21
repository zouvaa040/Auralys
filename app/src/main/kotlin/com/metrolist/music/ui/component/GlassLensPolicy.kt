/**
 * Auralys - when the glass bends the picture behind it (the "lens"), and with which numbers.
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Plain Kotlin (no Android types) so every combination is unit-tested on the JVM.
 *
 * Kaca (glass style) has three levels of material:
 *  - LENS:  frost + refraction at rest (the bent, colour-fringed rim of iOS 26 and BitChord). Needs a RuntimeShader.
 *  - BLUR:  frost + rim highlight, no refraction. The heavy G1 frost, because without a lens the text behind the bar
 *           is only hidden by blur.
 *  - FLAT:  no sampling of the page at all (Datar, or no backdrop): the callers draw their translucent fallback.
 */
package com.metrolist.music.ui.component

enum class LensMode { LENS, BLUR, FLAT }

object GlassLensPolicy {
    /** First Android with RuntimeShader (AGSL). */
    const val SDK_SHADER = 33

    /** First Android with RenderEffect (the blur of the backdrop). */
    const val SDK_BLUR = 31

    /**
     * @param kaca the glass style is on ("Kaca"); Datar is always flat
     * @param backdropAvailable the app backdrop exists (glass blur switched on, Android 12+)
     * @param userSwitch Pengaturan > Tampilan > "Efek lensa kaca"
     * @param lowRam ActivityManager.isLowRamDevice
     * @param powerSave PowerManager.isPowerSaveMode
     * @param animScale the system animator duration scale (0 = animations off)
     * @param appVisible the app is on screen (lifecycle STARTED or later)
     * @param shaderFailed a lens shader failed to build once: stay on blur for the rest of the process
     */
    fun decide(
        sdk: Int,
        kaca: Boolean,
        backdropAvailable: Boolean,
        userSwitch: Boolean,
        lowRam: Boolean,
        powerSave: Boolean,
        animScale: Float,
        appVisible: Boolean = true,
        shaderFailed: Boolean = false,
    ): LensMode = when {
        !kaca -> LensMode.FLAT
        !backdropAvailable || sdk < SDK_BLUR -> LensMode.FLAT
        sdk < SDK_SHADER || shaderFailed -> LensMode.BLUR
        !userSwitch || lowRam || powerSave || !(animScale > 0f) || !appVisible -> LensMode.BLUR
        else -> LensMode.LENS
    }
}

/**
 * One glass surface's material, in dp at full resolution. [blurDp] is the Gaussian radius; the lens bends the outer
 * [lensHeightDp] of the surface by up to [lensAmountDp]; [depth] adds the radial term of the library (the corners
 * pull harder); [dispersion] 0..1 is the colour fringe of the library (0 = none).
 */
data class GlassLensRecipe(
    val blurDp: Float,
    val saturation: Float,
    val lensHeightDp: Float,
    val lensAmountDp: Float,
    val depth: Boolean,
    val dispersion: Float,
    /** Multiplies the alpha of the tier veil: a thinner veil lets the refracted picture through. */
    val veilScale: Float,
) {
    val hasLens: Boolean get() = lensHeightDp > 0f && lensAmountDp > 0f
}

object GlassLensRecipes {
    // ---- BitChord (reference): LiquidGlass.kt liquidGlass(): blur 8 dp, saturation 1.5, lens 24/24 dp,
    // depthEffect + chromaticAberration, veil 0.4 (FAFAFA / 121212).
    val BitChord = GlassLensRecipe(
        blurDp = 8f, saturation = 1.5f, lensHeightDp = 24f, lensAmountDp = 24f,
        depth = true, dispersion = 1f, veilScale = 1f,
    )

    /** Navigation bar (72 dp tall, a capsule) with the lens at rest. */
    val NavBar = GlassLensRecipe(
        blurDp = 10f, saturation = 1.5f, lensHeightDp = 24f, lensAmountDp = 24f,
        depth = true, dispersion = 1f, veilScale = 0.72f,
    )

    /** Navigation bar without a lens: the heavy G1 frost of before. */
    val NavBarBlur = GlassLensRecipe(
        blurDp = 20f, saturation = 1.5f, lensHeightDp = 0f, lensAmountDp = 0f,
        depth = false, dispersion = 0f, veilScale = 1f,
    )

    /** Mini player (64 dp tall) with the lens at rest. Its frost stays at the 8 dp it always had. */
    val MiniPlayer = GlassLensRecipe(
        blurDp = 8f, saturation = 1.6f, lensHeightDp = 18f, lensAmountDp = 22f,
        depth = true, dispersion = 1f, veilScale = 0.78f,
    )

    /** The press puck of the nav bar: only while it is held or dragged (BitChord's pill is flat at rest too). */
    const val PUCK_LENS_HEIGHT_DP = 12f
    const val PUCK_LENS_AMOUNT_DP = 14f

    fun navBar(mode: LensMode): GlassLensRecipe = if (mode == LensMode.LENS) NavBar else NavBarBlur
}

/**
 * Round 4.3.4: the floating Kaca bar and its fold on EVERY Android version (minSdk 26), not only Android 13+.
 *
 * What a band of Android can do (see GLASS-ALLVERSIONS-434.md):
 *  - 33+ (Android 13+): RenderEffect + RuntimeShader: frost, lens and directional rim ([LensMode.LENS]).
 *  - 31-32 (Android 12): RenderEffect, no RuntimeShader: frost, a plain rim and the shadow, no lens ([LensMode.BLUR]).
 *  - 26-30 (Android 8-11): no RenderEffect, so the page cannot be blurred: [BarMaterial.FROST], a translucent body with
 *    a sheen, a bevelled rim and an elevation shadow, drawn without sampling the page (no extra pass at all).
 * The fold itself is layout and graphics-layer work only, so it never depends on the Android version.
 */
enum class BarMaterial { LENS, BLUR, FROST }

object GlassBarPolicy {
    /** Kaca always gets the floating Liquid bar (any Android); Datar keeps its flat pill bar. */
    fun liquidBar(kaca: Boolean): Boolean = kaca

    /**
     * The material of the Liquid bar, the folded pill and the mini player: a surface samples the page only when the app
     * backdrop exists (Kaca + "Blur kaca" + Android 12+), otherwise it is frost.
     */
    fun material(mode: LensMode, backdropAvailable: Boolean): BarMaterial = when {
        !backdropAvailable || mode == LensMode.FLAT -> BarMaterial.FROST
        mode == LensMode.LENS -> BarMaterial.LENS
        else -> BarMaterial.BLUR
    }

    /**
     * Fold on scroll (the bar shrinks to a pill and the mini player drops beside it): Kaca, the setting on, the bottom bar
     * on screen. No Android version, no blur and no lens requirement any more.
     */
    fun foldEnabled(collapsePref: Boolean, kaca: Boolean, bottomBarShown: Boolean, rail: Boolean): Boolean =
        collapsePref && kaca && bottomBarShown && !rail

    /**
     * Default of "Blur kaca" (the user can still change it): on from Android 13 on phones that are not low-RAM and give
     * the app a heap class of at least 192 MB, as before round 4.3.4. Android 12 has RenderEffect too and the user can
     * switch the blur on there, but it stays off by default (frost is the default look on 12): the blur recipe was never
     * seen on a real Android 12 phone, and a Robolectric probe on sdk 31 showed sharp text through a faint haze
     * (review 2026-11-12, finding 17).
     */
    const val BLUR_DEFAULT_MIN_HEAP_MB = 192

    /** First Android where "Blur kaca" is on by default. */
    const val BLUR_DEFAULT_MIN_SDK = 33

    fun blurDefault(sdk: Int, lowRam: Boolean, memoryClassMb: Int): Boolean =
        sdk >= BLUR_DEFAULT_MIN_SDK && !lowRam && memoryClassMb >= BLUR_DEFAULT_MIN_HEAP_MB
}
