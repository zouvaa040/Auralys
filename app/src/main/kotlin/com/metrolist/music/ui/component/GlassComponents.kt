/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Surface primitives shared by both visual styles (Horizon design, DESIGN-V2.md 2.6).
 *
 *  - UiStyle.GLASS ("Kaca"): floating parts are neutral glass: [glassBlur] = tier G1 (bars), [glass] = tier G2
 *    (controls), [glassOverlay] = tier G3 (sheets, dialogs: opaque, they cannot sample the page).
 *  - UiStyle.EDITORIAL ("Datar"): the very same call sites render flat and opaque: no blur, no veil, no hairline box.
 *  - CONTENT is never glass: [glassCard] is a flat surface-1 block in both styles (no border, no shadow, no sheen).
 *
 * Rules of thumb:
 *  - [glass] is cheap: use it for controls (lots of them).
 *  - [glassBlur] samples the backdrop: use it ONLY on a few large, fixed elements (bottom bar, mini player),
 *    never per list item.
 */

package com.metrolist.music.ui.component

import com.metrolist.music.ui.component.backdrop.effects.blur
import com.metrolist.music.ui.component.backdrop.effects.colorControls
import com.metrolist.music.ui.component.backdrop.drawBackdrop
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.getValue
import kotlin.math.roundToInt
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.music.ui.theme.HorizonGlass
import com.metrolist.music.ui.theme.HorizonLight
import com.metrolist.music.ui.theme.HorizonRadius
import com.metrolist.music.ui.theme.LocalCoverTint
import com.metrolist.music.ui.theme.LocalUiStyle
import com.metrolist.music.ui.theme.UiStyle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** The shared backdrop sampled by every blurred glass element. Null = blur not available. */
val LocalHazeState: ProvidableCompositionLocal<HazeState?> = staticCompositionLocalOf { null }

/** User switch (Appearance settings). When false every glass surface falls back to cheap translucency. */
val LocalGlassBlurEnabled = compositionLocalOf { true }

val GlassShape = RoundedCornerShape(24.dp)
val GlassShapeLarge = RoundedCornerShape(28.dp)

@Composable
private fun isGlass(): Boolean = LocalUiStyle.current == UiStyle.GLASS

@Composable
private fun isDarkSurface(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/**
 * Radius of a surface. The two hints are the radius a call site wants in each style; the result is snapped to the
 * Horizon radius scale (4, 8, 12, 16, 24, 28), so no ad-hoc radius survives.
 */
@Composable
fun glassShape(glassRadius: Dp = 24.dp, editorialRadius: Dp = 12.dp): RoundedCornerShape =
    RoundedCornerShape(HorizonRadius.snap(if (isGlass()) glassRadius else editorialRadius))

/** Same, for sheets that only round their top corners. */
@Composable
fun glassTopShape(glassRadius: Dp = 32.dp, editorialRadius: Dp = 16.dp): RoundedCornerShape {
    val r = HorizonRadius.snap(if (isGlass()) glassRadius else editorialRadius)
    return RoundedCornerShape(topStart = r, topEnd = r)
}

/** Translucent fill of a glass control (tier G2). */
@Composable
fun glassFill(strong: Boolean = false): Color {
    val dark = isDarkSurface()
    return if (dark) Color.White.copy(alpha = if (strong) 0.18f else HorizonGlass.G2.veilDark.alpha)
    else Color.White.copy(alpha = if (strong) 0.72f else HorizonGlass.G2.veilLight.alpha)
}

/** Bright specular rim: strong at the top-left, fading, small glint bottom-right. */
@Composable
fun glassRim(): Brush {
    val glass = isGlass()
    val dark = isDarkSurface()
    val outline = MaterialTheme.colorScheme.outlineVariant
    return remember(glass, dark, outline) {
        if (!glass) {
            Brush.linearGradient(listOf(outline, outline))
        } else {
            val hi = Color.White.copy(alpha = if (dark) 0.48f else 0.95f)
            val lo = Color.White.copy(alpha = if (dark) 0.05f else 0.25f)
            val glint = Color.White.copy(alpha = if (dark) 0.22f else 0.65f)
            Brush.linearGradient(
                colors = listOf(hi, lo, lo, glint),
                start = Offset(0f, 0f),
                end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
            )
        }
    }
}

/**
 * Glass control (tier G2): translucent fill and a rim, no sheen. On a light page a faint ink tone gives the pane a
 * body (white on white would vanish). Datar: a flat surface-2 fill with no border.
 */
@Composable
fun Modifier.glass(
    shape: Shape = GlassShape,
    strong: Boolean = false,
): Modifier {
    if (!isGlass()) {
        return this
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    }
    val dark = isDarkSurface()
    val fill = glassFill(strong)
    return this
        .clip(shape)
        .background(if (dark) Color.Transparent else HorizonLight.ink.copy(alpha = 0.07f))
        .background(fill)
        .glassRimBorder(shape)
}

/**
 * Bar glass (tier G1) for the few large floating parts: blurs whatever is behind (through the vendored backdrop
 * engine) and adds the rim. Falls back to [glass] when blur is unavailable or switched off. Datar: an opaque
 * surface-1 pane.
 */
@Composable
fun Modifier.glassBlur(
    shape: Shape = GlassShape,
    blurRadius: Dp = 16.dp,
    strong: Boolean = true,
    // true for the few small bars (the mini player) that show the lens at rest, see [GlassLensRecipes.MiniPlayer]
    restLens: Boolean = false,
): Modifier {
    val scheme = MaterialTheme.colorScheme
    if (!isGlass()) {
        return this
            .clip(shape)
            .background(scheme.surfaceContainer.compositeOver(scheme.surface))
    }
    val backdrop = LocalAppBackdrop.current
    if (backdrop == null || !LocalGlassBlurEnabled.current || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
        // no backdrop (Android 8-11, or "Blur kaca" off): the frost material, the same one the nav bar draws, so the
        // mini player and the bar still look like one piece of glass (GlassFrost.kt)
        return this.glassFrost(shape)
    }
    return this.liquidGlass(backdrop, shape, blurRadius, strong, restLens)
}

/** The app content that real (refracting) glass surfaces sample. Null = not available, surfaces fall back to translucency. */
val LocalAppBackdrop = staticCompositionLocalOf<com.metrolist.music.ui.component.backdrop.backdrops.LayerBackdrop?> { null }

private const val GLASS_MIN_RESOLUTION = 0.35f

/**
 * iOS-26-style liquid glass, built on the vendored Kyant0 backdrop engine (Apache-2.0, as used by Convx):
 * the content behind the surface is captured, saturated, lightly blurred and (Android 13+) refracted through a
 * rounded-rect lens, then finished with a specular rim and a soft shadow.
 */
@Composable
fun Modifier.liquidGlass(
    backdrop: com.metrolist.music.ui.component.backdrop.backdrops.LayerBackdrop,
    shape: Shape,
    blurRadius: Dp,
    strong: Boolean,
    restLens: Boolean = false,
): Modifier {
    val dark = isDarkSurface()
    val tier = if (strong) HorizonGlass.G1 else HorizonGlass.G2
    val density = androidx.compose.ui.platform.LocalDensity.current
    // The lens level of this phone: LENS (refraction), BLUR (frost only, no shader). FLAT never gets here.
    val mode = rememberGlassLensMode()
    // a bar with a lens at rest (the mini player) uses its own recipe; every other surface keeps its frost + plain lens
    val recipe = if (restLens && mode == LensMode.LENS) GlassLensRecipes.MiniPlayer else null
    // surfaces that carry text (strong) get a real frost so rows scrolling underneath stay unreadable behind it
    val blurDp = recipe?.blurDp ?: if (strong) (blurRadius.value / 2f).coerceIn(2f, 8f) else (blurRadius.value / 4f).coerceIn(1f, 6f)
    val resolution = (1f - (blurDp / 2.5f).coerceIn(0f, 1f) * (1f - GLASS_MIN_RESOLUTION)).coerceAtLeast(GLASS_MIN_RESOLUTION)
    val blurPx = with(density) { blurDp.dp.toPx() } * resolution
    val lensHeightPx = with(density) { 16.dp.toPx() } * resolution
    val lensAmountPx = with(density) { 22.dp.toPx() } * resolution
    val shapeBlock: () -> Shape = remember(shape) { { shape } }
    val effects: com.metrolist.music.ui.component.backdrop.BackdropEffectScope.() -> Unit = remember(blurPx, lensHeightPx, lensAmountPx, tier, mode, recipe, resolution) {
        {
            if (recipe != null) {
                applyGlassRecipe(recipe, resolution)
            } else {
                colorControls(saturation = tier.saturation)
                blur(blurPx)
                if (mode == LensMode.LENS) lensGuarded(lensHeightPx, lensAmountPx)
            }
        }
    }
    val tint = tier.veil(dark).thinned(recipe?.veilScale ?: 1f)
    val rimColor = Color.White.copy(alpha = if (dark) 0.60f else 0.95f)
    val highlight: () -> com.metrolist.music.ui.component.backdrop.highlight.Highlight? = remember(rimColor) {
        {
            com.metrolist.music.ui.component.backdrop.highlight.Highlight(
                width = 0.9.dp,
                style = com.metrolist.music.ui.component.backdrop.highlight.HighlightStyle.Default(color = rimColor, angle = 45f),
            )
        }
    }
    val surface: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = remember(tint) {
        {
            drawRect(tint)
        }
    }
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = shapeBlock,
        effects = effects,
        highlight = highlight,
        onDrawSurface = surface,
        backdropScale = resolution,
    )
}

/**
 * Surface for things that open in their OWN window (dialogs, bottom sheets, menus): tier G3. Those windows cannot
 * sample the page behind them, so there is no blur: an opaque surface-1 pane with 6 percent of the cover colour (when
 * "Sampul" is on and a cover plays) and a 1dp highlight along the top edge (Kaca). Same fill in both styles.
 */
@Composable
fun Modifier.glassOverlay(
    shape: Shape = GlassShapeLarge,
    @Suppress("UNUSED_PARAMETER") blurRadius: Dp = 0.dp,
): Modifier {
    val scheme = MaterialTheme.colorScheme
    val dark = isDarkSurface()
    val tint = LocalCoverTint.current
    val tier = HorizonGlass.G3
    val topHighlight = Color.White.copy(alpha = if (dark) tier.rimDarkTop else tier.rimLightTop)
    return this
        .clip(shape)
        .background(scheme.surfaceContainer.compositeOver(scheme.surface))
        .then(if (tint != null) Modifier.background(tint.copy(alpha = 0.06f)) else Modifier)
        .then(
            if (isGlass()) {
                Modifier.drawBehind { drawRect(topHighlight, size = Size(size.width, tier.rimWidth.toPx())) }
            } else {
                Modifier
            },
        )
}

/** Material3 ListItem container: transparent in the glass style (its default is the opaque surface colour). */
@Composable
fun glassListItemColors(): androidx.compose.material3.ListItemColors =
    androidx.compose.material3.ListItemDefaults.colors(
        containerColor = if (isGlass()) Color.Transparent else Color.Unspecified,
    )

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(
        red = red * a + background.red * (1 - a),
        green = green * a + background.green * (1 - a),
        blue = blue * a + background.blue * (1 - a),
        alpha = 1f,
    )
}

/** Convenience container: glass card. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShape,
    strong: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(shape, strong), content = content)
}

/**
 * Horizon: the page is flat. The colour blooms of the old glass style are gone (colour belongs to covers only, see
 * [coverWash]); kept as a no-op so the call sites need no edit.
 */
@Composable
fun Modifier.ambientGlow(): Modifier = this

/**
 * Content card (settings groups, info panels): a flat surface-1 block, no border, no shadow, no sheen, no blur.
 * Glass is for controls and navigation, never for content. Same in both styles. [lift] is ignored (kept for call sites).
 */
@Composable
fun Modifier.glassCard(shape: Shape = GlassShape, @Suppress("UNUSED_PARAMETER") lift: Boolean = true): Modifier =
    this
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainer)

/**
 * Filled control for the primary action: a flat pill in [tint] (ink by default). No gloss, no gradient, no glow: on
 * an ink fill a lighter-to-darker gradient would look like grey chrome.
 */
@Composable
fun Modifier.glassTinted(
    shape: Shape = GlassShape,
    tint: Color = MaterialTheme.colorScheme.primary,
): Modifier = this.clip(shape).background(tint)


/**
 * Blur is the most expensive effect: default it off on low-memory devices (the user can still switch it on). Default on
 * from Android 13, see [GlassBarPolicy.blurDefault]; Android 12 can switch it on but defaults to the frost material.
 */
fun glassBlurDefault(context: android.content.Context): Boolean {
    val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        ?: return false
    return GlassBarPolicy.blurDefault(android.os.Build.VERSION.SDK_INT, am.isLowRamDevice, am.memoryClass)
}


/** Header/sticky-row fill: translucent in the glass style (the page glow stays visible), solid otherwise. */
/** A section header that sticks to the top while scrolling in the editorial style and scrolls away in glass (pinned glass headers need a backing colour, which looks like a band over the glow). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun androidx.compose.foundation.lazy.LazyListScope.pinnedHeader(
    glass: Boolean,
    content: @Composable androidx.compose.foundation.lazy.LazyItemScope.() -> Unit,
) {
    if (glass) {
        item { content() }
    } else {
        stickyHeader { content() }
    }
}

/**
 * Background for a list header that [pinnedHeader] pins. Only the editorial style pins headers, so only it paints one.
 */
@Composable
fun Modifier.stickyHeaderBackground(): Modifier {
    // glass pages do not pin their headers (see [pinnedHeader]), so there is nothing to paint behind them
    return if (LocalUiStyle.current == UiStyle.GLASS) this else this.background(MaterialTheme.colorScheme.surface)
}

/** Whether the glass surfaces should use their dark appearance (vendored backdrop components read this). */
@Composable
fun glassUsesDarkAppearance(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f
