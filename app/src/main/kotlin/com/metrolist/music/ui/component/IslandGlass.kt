/**
 * Auralys - Liquid glass of the Dynamic Island
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import com.metrolist.music.widget.LiquidGlass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The Liquid style of the Dynamic Island: the same material as the bottom navigation pill, in the limits of an overlay
 * window. The island lives in its own window, so it cannot sample the app or another app behind it. The cover stands in
 * for "what is behind the glass", and the recipe of the pill is run on it once, on a worker thread, into a bitmap:
 *
 *   vibrancy (saturation 1.5) -> blur -> lens at the edge -> veil
 *
 * The rim highlight, the top sheen and the shadow are drawn live (cheap, and they stay sharp while the card morphs).
 * Nothing here runs per frame. This object is plain Kotlin on int arrays, so the numbers are unit-tested without Android.
 */
internal object IslandGlass {
    // ---- the bottom navigation pill, the reference (LiquidBottomTabs.kt / AppNavigation.kt)
    /** The nav pill is 72 dp tall; its blur and lens are scaled by (island height / this). */
    const val NAV_HEIGHT_DP = 72f

    /** `blur(18.dp)` of the nav pill. */
    const val NAV_BLUR_DP = 18f

    /** `lens(24.dp, 24.dp)` of the nav pill: how deep the bent edge reaches, and how far it pulls the picture. */
    const val NAV_LENS_DP = 24f

    /** `vibrancy()` of the nav pill. */
    const val SATURATION = 1.5f

    /** The nav pill's container colour: 0xFF14121A at 0.68 in the dark theme, white at 0.60 in the light theme. */
    const val DARK_VEIL_RGB = 0x14121A
    const val LIGHT_VEIL_RGB = 0xFFFFFF
    const val DARK_VEIL_ALPHA = 0.68f
    const val LIGHT_VEIL_ALPHA = 0.60f

    // ---- how the island adapts the veil to the cover
    /** A cover that is dark already needs less veil: never below these. */
    private const val DARK_VEIL_MIN = 0.30f
    private const val LIGHT_VEIL_MIN = 0.34f

    /** Brightness (0..1, gamma space) the glass should reach under the veil: dark glass stays below, light glass above. */
    private const val DARK_TARGET = 0.34f
    private const val LIGHT_TARGET = 0.68f
    private const val DARK_VEIL_LUMA = 0.075f

    /** How far the lens pulls the picture at the very edge, relative to its depth (the nav pill uses 1.0). */
    private const val LENS_AMOUNT_K = 1.6f

    /** The blurred scene is drawn with a lightly blurred copy at the edge, so the bent cover stays readable. */
    private const val SHARP_SIGMA_DP = 1.1f

    /** Soft light just inside the lit edges, in the bitmap (glass thickness). */
    private const val INNER_GLOW_DARK = 0.15f
    private const val INNER_GLOW_LIGHT = 0.09f

    /** Stand-in scene when the track has no cover. */
    const val FALLBACK_SCENE = 0xFF59607A.toInt()

    // ---- bake size
    /** The bake runs at this fraction of the screen resolution (the nav pill's glass runs at 0.5 too). */
    const val BAKE_QUALITY = 0.5f
    const val MAX_BAKE_SIDE = 720
    const val MIN_BAKE_SIDE = 8

    /** Size of one baked skin in pixels, and the factor between its pixels and dp. */
    data class Dims(val w: Int, val h: Int, val radius: Float, val pxPerDp: Float)

    /**
     * Bake size for a pane of [wDp] x [hDp] with corner radius [cornerDp] on a screen of [density] px per dp: half the
     * screen resolution, never more than [MAX_BAKE_SIDE] on the long side, never smaller than [MIN_BAKE_SIDE].
     */
    fun bakeDims(wDp: Float, hDp: Float, cornerDp: Float, density: Float): Dims {
        val d = density.coerceAtLeast(0.5f)
        val longest = max(max(wDp, hDp), 1f) * d
        val q = min(BAKE_QUALITY, MAX_BAKE_SIDE / longest)
        val ppd = d * q
        val w = (wDp * ppd).roundToInt().coerceAtLeast(MIN_BAKE_SIDE)
        val h = (hDp * ppd).roundToInt().coerceAtLeast(MIN_BAKE_SIDE)
        return Dims(w, h, (cornerDp * ppd).coerceIn(0f, min(w, h) / 2f), ppd)
    }

    /** Standard deviation of the frost in dp: the nav pill's 18 dp, scaled by the island's height and the "blur" setting (70 = the pill). */
    fun frostSigmaDp(heightDp: Float, blur: Int): Float =
        NAV_BLUR_DP * min(1f, heightDp / NAV_HEIGHT_DP) * (0.3f + blur.coerceIn(0, 100) / 100f)

    /** Depth of the bent edge in dp: the nav pill's 24 dp, never more than a third of the island's height. */
    fun lensHeightDp(heightDp: Float): Float = min(NAV_LENS_DP, heightDp / 3f)

    // ---- veil
    /** Alpha of the veil over a scene of brightness [scene] (0..1): as little as keeps the ink readable, between the limits. */
    fun veilAlpha(dark: Boolean, scene: Float): Float {
        val l = scene.coerceIn(0f, 1f)
        return if (dark) {
            val need = if (l > DARK_TARGET) (l - DARK_TARGET) / (l - DARK_VEIL_LUMA) else 0f
            need.coerceIn(DARK_VEIL_MIN, DARK_VEIL_ALPHA)
        } else {
            val need = if (l < LIGHT_TARGET) (LIGHT_TARGET - l) / (1f - l) else 0f
            need.coerceIn(LIGHT_VEIL_MIN, LIGHT_VEIL_ALPHA)
        }
    }

    fun veilRgb(dark: Boolean): Int = if (dark) DARK_VEIL_RGB else LIGHT_VEIL_RGB

    // ---- rim and sheen, drawn live: the numbers of glassRim() / glassSheen()
    class Rim(val hi: Float, val lo: Float, val glint: Float)

    /** Two-sided rim at 45 degrees: bright where the light enters (top-left), a glint where it leaves (bottom-right). */
    fun rim(dark: Boolean, shine: Int): Rim {
        val k = 0.35f + 0.65f * shine.coerceIn(0, 100) / 100f
        return if (dark) Rim(0.48f * k, 0.05f * k, 0.22f * k) else Rim(0.95f * k, 0.25f * k, 0.65f * k)
    }

    /** Alpha at the top of the soft sheen that fades out at half the height. */
    fun sheen(dark: Boolean, shine: Int): Float {
        val k = 0.4f + 0.6f * shine.coerceIn(0, 100) / 100f
        return (if (dark) 0.10f else 0.35f) * k
    }

    /**
     * Weight of the expanded skin at morph fraction [k] (0 = compact pill, 1 = open card). The compact skin is always drawn
     * opaque underneath and the expanded one fades in on top, so the card is never see-through half way.
     */
    fun expandedWeight(k: Float): Float {
        val t = k.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ---- ink
    /** Linear luminance of the dark ink (0xFF16151A). */
    private const val INK_LUMINANCE = 0.008f

    /**
     * True when dark ink reads better than white on a glass whose darkest tenth is [p10] and lightest tenth is [p90]
     * (linear luminance): the one with the better worst-case contrast ratio wins.
     */
    fun darkInkWins(p10: Float, p90: Float): Boolean {
        val white = 1.05f / (p90.coerceIn(0f, 1f) + 0.05f)
        val dark = (p10.coerceIn(0f, 1f) + 0.05f) / (INK_LUMINANCE + 0.05f)
        return dark > white
    }

    // ---- the bake
    /** A baked glass: non-premultiplied ARGB (every pixel opaque) and how bright it is where the text sits. */
    class Baked(val pixels: IntArray, val w: Int, val h: Int, val p10: Float, val p90: Float, val mean: Float)

    /**
     * Bakes the glass for a pane of [dims] over the cover [art] (`artW` x `artH` ARGB pixels, or null for the stand-in
     * scene). [heightDp] is the pane's height in dp (the blur and the lens scale with it), [blur] the 0..100 setting.
     * [stop] is polled between the steps so a stale bake ends early; the result is then null.
     */
    fun bake(
        art: IntArray?,
        artW: Int,
        artH: Int,
        dims: Dims,
        heightDp: Float,
        dark: Boolean,
        blur: Int,
        stop: () -> Boolean = { false },
    ): Baked? {
        val w = dims.w
        val h = dims.h
        val raw =
            if (art != null && artW > 0 && artH > 0 && art.size >= artW * artH) {
                cropScene(art, artW, artH, w, h)
            } else {
                LiquidGlass.syntheticScene(w, h, FALLBACK_SCENE)
            }
        if (stop()) return null
        val scene = LiquidGlass.blur(raw, w, h, frostSigmaDp(heightDp, blur) * dims.pxPerDp / SIGMA_PER_RADIUS)
        if (stop()) return null
        val sharp = LiquidGlass.blur(raw, w, h, SHARP_SIGMA_DP * dims.pxPerDp / SIGMA_PER_RADIUS)
        if (stop()) return null
        val refH = (lensHeightDp(heightDp) * dims.pxPerDp).coerceAtMost(min(w, h) / 2f)
        val alpha = veilAlpha(dark, LiquidGlass.luma(scene))
        val params =
            LiquidGlass.Params(
                width = w,
                height = h,
                inset = 0f,
                radius = dims.radius,
                refractionHeight = refH,
                refractionAmount = refH * LENS_AMOUNT_K,
                saturation = SATURATION,
                veils = intArrayOf(argb(alpha, veilRgb(dark))),
                bodyAlpha = 1f,
                highlightAlpha = 0f,
                highlightWidth = 1f,
                glowAlpha = if (dark) INNER_GLOW_DARK else INNER_GLOW_LIGHT,
                glowWidth = refH * 0.55f,
                shadowAlpha = 0f,
            )
        val pixels = LiquidGlass.render(scene, params, sharp)
        if (stop()) return null
        fillCorners(pixels, w, h, dims.radius)
        val tone = measure(pixels, w, h)
        return Baked(pixels, w, h, tone[0], tone[1], tone[2])
    }

    /** The blur of [LiquidGlass.blur] has a standard deviation of about 0.6 x its radius. */
    private const val SIGMA_PER_RADIUS = 0.6f

    // ---- shadow
    private const val SHADOW_SIGMA_DP = 5f
    private const val SHADOW_DROP_DP = 3f
    const val SHADOW_DARK = 0.50f
    const val SHADOW_LIGHT = 0.24f

    /**
     * Soft shadow of a pane of [dims]: its outline in the middle of a transparent frame [padPx] wide, moved down a little
     * and blurred. Black, the alpha in the top byte, `(w + 2 pad) x (h + 2 pad)` pixels. Baked with the glass, so the
     * island never runs a blur while it is on screen.
     */
    fun bakeShadow(
        dims: Dims,
        padPx: Int,
        dark: Boolean,
    ): IntArray {
        val sw = dims.w + padPx * 2
        val sh = dims.h + padPx * 2
        val cx = sw / 2f
        val cy = sh / 2f + SHADOW_DROP_DP * dims.pxPerDp
        val hx = dims.w / 2f
        val hy = dims.h / 2f
        val r = dims.radius.coerceIn(0f, min(hx, hy))
        // the outline goes into the colour planes, so the box blur of LiquidGlass can soften it
        val mask =
            IntArray(sw * sh) { i ->
                val sd = sdRoundedRect(i % sw + 0.5f - cx, i / sw + 0.5f - cy, hx, hy, r)
                val v = ((0.5f - sd).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        val soft = LiquidGlass.blur(mask, sw, sh, SHADOW_SIGMA_DP * dims.pxPerDp / SIGMA_PER_RADIUS)
        val strength = if (dark) SHADOW_DARK else SHADOW_LIGHT
        return IntArray(sw * sh) { i -> ((soft[i] and 0xFF) * strength + 0.5f).toInt().coerceIn(0, 255) shl 24 }
    }

    private fun sdRoundedRect(
        x: Float,
        y: Float,
        hx: Float,
        hy: Float,
        r: Float,
    ): Float {
        val qx = abs(x) - (hx - r)
        val qy = abs(y) - (hy - r)
        return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - r
    }

    /** Width of the free frame around a pane for its shadow, in baked pixels. */
    fun shadowPadPx(
        padDp: Float,
        dims: Dims,
    ): Int = ceil(padDp * dims.pxPerDp).toInt().coerceAtLeast(1)

    private fun argb(alpha: Float, rgb: Int): Int = ((alpha * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

    /** The cover scaled to fill [w] x [h] and cropped in the middle (like ContentScale.Crop), opaque ARGB. */
    fun cropScene(
        art: IntArray,
        aw: Int,
        ah: Int,
        w: Int,
        h: Int,
    ): IntArray {
        val scale = max(w / aw.toFloat(), h / ah.toFloat())
        val ox = (aw - w / scale) / 2f
        val oy = (ah - h / scale) / 2f
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val sy = oy + (y + 0.5f) / scale - 0.5f
            val y0 = floor(sy).toInt()
            val ty = sy - y0
            val ya = y0.coerceIn(0, ah - 1)
            val yb = (y0 + 1).coerceIn(0, ah - 1)
            for (x in 0 until w) {
                val sx = ox + (x + 0.5f) / scale - 0.5f
                val x0 = floor(sx).toInt()
                val tx = sx - x0
                val xa = x0.coerceIn(0, aw - 1)
                val xb = (x0 + 1).coerceIn(0, aw - 1)
                val c00 = art[ya * aw + xa]
                val c10 = art[ya * aw + xb]
                val c01 = art[yb * aw + xa]
                val c11 = art[yb * aw + xb]
                out[y * w + x] = (0xFF shl 24) or (lerp4(c00, c10, c01, c11, tx, ty, 16) shl 16) or (lerp4(c00, c10, c01, c11, tx, ty, 8) shl 8) or lerp4(c00, c10, c01, c11, tx, ty, 0)
            }
        }
        return out
    }

    private fun lerp4(
        c00: Int,
        c10: Int,
        c01: Int,
        c11: Int,
        tx: Float,
        ty: Float,
        shift: Int,
    ): Int {
        val top = ((c00 shr shift) and 0xFF) * (1f - tx) + ((c10 shr shift) and 0xFF) * tx
        val bottom = ((c01 shr shift) and 0xFF) * (1f - tx) + ((c11 shr shift) and 0xFF) * tx
        return (top * (1f - ty) + bottom * ty + 0.5f).toInt().coerceIn(0, 255)
    }

    /**
     * The baked pane is cut to its rounded corners, but the island's clip changes radius while it morphs and the skin is
     * stretched; a transparent corner would then show as a hole. Every pixel outside the arc takes the nearest pixel on
     * the arc and every pixel becomes opaque, so the skin is a plain filled rectangle and the live clip does the rounding.
     */
    fun fillCorners(
        px: IntArray,
        w: Int,
        h: Int,
        radius: Float,
    ) {
        val r = min(radius, min(w, h) / 2f)
        if (r >= 2f) {
            val span = ceil(r).toInt().coerceAtMost(min(w, h) / 2)
            for (corner in 0 until 4) {
                val left = corner % 2 == 0
                val top = corner < 2
                val cx = if (left) r else w - r
                val cy = if (top) r else h - r
                val x0 = if (left) 0 else w - span
                val y0 = if (top) 0 else h - span
                for (y in y0 until y0 + span) {
                    for (x in x0 until x0 + span) {
                        val dx = x + 0.5f - cx
                        val dy = y + 0.5f - cy
                        // only the quarter that faces the corner lies outside the arc
                        if ((left && dx > 0f) || (!left && dx < 0f) || (top && dy > 0f) || (!top && dy < 0f)) continue
                        val len = hypot(dx, dy)
                        if (len <= r - 0.75f || len < 1e-4f) continue
                        val k = (r - 1.5f) / len
                        val sx = (cx + dx * k).toInt().coerceIn(0, w - 1)
                        val sy = (cy + dy * k).toInt().coerceIn(0, h - 1)
                        px[y * w + x] = px[sy * w + sx]
                    }
                }
            }
        }
        for (i in px.indices) px[i] = px[i] or (0xFF shl 24)
    }

    private val Linear = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }

    /** Linear luminance (0..1) of an opaque ARGB colour. */
    fun luminance(c: Int): Float = 0.2126f * Linear[(c shr 16) and 0xFF] + 0.7152f * Linear[(c shr 8) and 0xFF] + 0.0722f * Linear[c and 0xFF]

    /**
     * Brightness of the part of the glass where the text sits (the middle, away from the lens edge) as `[p10, p90, mean]`
     * in linear luminance.
     */
    fun measure(
        px: IntArray,
        w: Int,
        h: Int,
    ): FloatArray {
        val xa = (w * 0.15f).toInt().coerceIn(0, w - 1)
        val xb = (w * 0.85f).toInt().coerceIn(xa + 1, w)
        val ya = (h * 0.2f).toInt().coerceIn(0, h - 1)
        val yb = (h * 0.8f).toInt().coerceIn(ya + 1, h)
        val bins = IntArray(256)
        var sum = 0.0
        var n = 0
        for (y in ya until yb) {
            for (x in xa until xb) {
                val l = luminance(px[y * w + x])
                bins[(sqrt(l) * 255f).toInt().coerceIn(0, 255)]++
                sum += l
                n++
            }
        }
        if (n == 0) return floatArrayOf(0f, 0f, 0f)
        fun at(fraction: Float): Float {
            val target = (n * fraction).toInt()
            var seen = 0
            for (i in 0 until 256) {
                seen += bins[i]
                if (seen > target) {
                    val v = i / 255f
                    return v * v
                }
            }
            return 1f
        }
        return floatArrayOf(at(0.10f), at(0.90f), (sum / n).toFloat())
    }

    /** Key of one baked skin pair: the cover, both sizes, the appearance and the blur. */
    fun cacheKey(
        art: String,
        compact: Dims,
        expanded: Dims,
        dark: Boolean,
        blur: Int,
    ): String = "$art|${compact.w}x${compact.h}r${compact.radius.roundToInt()}|${expanded.w}x${expanded.h}r${expanded.radius.roundToInt()}|${if (dark) 'd' else 'l'}|b$blur"
}

/**
 * The glass of the compact pill and of the expanded card, their shadows (a frame of [IslandGlass.shadowPadPx] around each
 * pane) and the ink that reads on both.
 */
@Immutable
internal class IslandGlassSkin(
    val compact: ImageBitmap,
    val expanded: ImageBitmap,
    val shadowCompact: ImageBitmap,
    val shadowExpanded: ImageBitmap,
    val darkInk: Boolean,
)

/** The last few skins, so showing the island again (or a card that opens again) does not bake again. */
internal object IslandGlassCache {
    private val map =
        object : LinkedHashMap<String, IslandGlassSkin>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IslandGlassSkin>?): Boolean = size > 4
        }

    @Synchronized
    fun get(key: String): IslandGlassSkin? = map[key]

    @Synchronized
    fun put(
        key: String,
        skin: IslandGlassSkin,
    ) {
        map[key] = skin
    }
}

private fun imageOf(
    pixels: IntArray,
    w: Int,
    h: Int,
): ImageBitmap {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    return bitmap.asImageBitmap()
}

/**
 * Bakes the skins for the island on a worker thread and returns the newest one (null until the first is ready, and while
 * the cover is still loading). A change of size, blur or appearance bakes again after a short pause, so dragging a slider
 * does not bake at every step. The old skin stays in place until the new one is ready.
 *
 * [artworkKey] is the cover's url; null with a null [artwork] means the track has no cover. A key without a picture means
 * the picture is on its way, so nothing is baked yet.
 */
@Composable
internal fun rememberIslandGlassSkin(
    enabled: Boolean,
    artwork: ImageBitmap?,
    artworkKey: String?,
    compactWDp: Float,
    compactHDp: Float,
    compactCornerDp: Float,
    expandedWDp: Float,
    expandedHDp: Float,
    expandedCornerDp: Float,
    shadowPadDp: Float,
    dark: Boolean,
    blur: Int,
): State<IslandGlassSkin?> {
    val density = LocalDensity.current.density
    val compact = remember(compactWDp, compactHDp, compactCornerDp, density) { IslandGlass.bakeDims(compactWDp, compactHDp, compactCornerDp, density) }
    val expanded = remember(expandedWDp, expandedHDp, expandedCornerDp, density) { IslandGlass.bakeDims(expandedWDp, expandedHDp, expandedCornerDp, density) }
    val art =
        when {
            artworkKey != null -> artworkKey
            artwork != null -> "img" + System.identityHashCode(artwork)
            else -> "none"
        }
    val key = remember(art, compact, expanded, dark, blur) { IslandGlass.cacheKey(art, compact, expanded, dark, blur) }
    val out = remember { mutableStateOf<IslandGlassSkin?>(null) }
    val waiting = artwork == null && artworkKey != null
    LaunchedEffect(key, enabled, waiting) {
        if (!enabled) {
            out.value = null
            return@LaunchedEffect
        }
        if (waiting) return@LaunchedEffect
        IslandGlassCache.get(key)?.let {
            out.value = it
            return@LaunchedEffect
        }
        if (out.value != null) delay(90)
        val skin =
            try {
                withContext(Dispatchers.Default) {
                    bakeSkin(artwork, compact, expanded, compactHDp, expandedHDp, shadowPadDp, dark, blur) { !isActive }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                null // out of memory or a driver quirk: the island keeps its plain veil
            }
        if (skin != null) {
            IslandGlassCache.put(key, skin)
            out.value = skin
        }
    }
    return out
}

private fun bakeSkin(
    artwork: ImageBitmap?,
    compact: IslandGlass.Dims,
    expanded: IslandGlass.Dims,
    compactHDp: Float,
    expandedHDp: Float,
    shadowPadDp: Float,
    dark: Boolean,
    blur: Int,
    stop: () -> Boolean,
): IslandGlassSkin? {
    var pixels: IntArray? = null
    var aw = 0
    var ah = 0
    if (artwork != null) {
        try {
            aw = artwork.width
            ah = artwork.height
            pixels = IntArray(aw * ah).also { artwork.readPixels(it, 0, 0, aw, ah) }
        } catch (_: Throwable) {
            pixels = null // a hardware bitmap cannot be read: the stand-in scene is used
        }
    }
    val c = IslandGlass.bake(pixels, aw, ah, compact, compactHDp, dark, blur, stop) ?: return null
    val e = IslandGlass.bake(pixels, aw, ah, expanded, expandedHDp, dark, blur, stop) ?: return null
    val darkInk = IslandGlass.darkInkWins(min(c.p10, e.p10), max(c.p90, e.p90))
    val cPad = IslandGlass.shadowPadPx(shadowPadDp, compact)
    val ePad = IslandGlass.shadowPadPx(shadowPadDp, expanded)
    val cShadow = IslandGlass.bakeShadow(compact, cPad, dark)
    val eShadow = IslandGlass.bakeShadow(expanded, ePad, dark)
    return IslandGlassSkin(
        compact = imageOf(c.pixels, c.w, c.h),
        expanded = imageOf(e.pixels, e.w, e.h),
        shadowCompact = imageOf(cShadow, compact.w + cPad * 2, compact.h + cPad * 2),
        shadowExpanded = imageOf(eShadow, expanded.w + ePad * 2, expanded.h + ePad * 2),
        darkInk = darkInk,
    )
}
