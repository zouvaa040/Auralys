/*
 * Vendored from Kyant0/AndroidLiquidGlass (kmp branch)
 * https://github.com/Kyant0/AndroidLiquidGlass — Copyright 2025 Kyant0, Apache License 2.0
 * app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt
 *
 * Not published as a library — ported straight from the catalog demo app that
 * showcases Kyant0/backdrop, source-only. Package renamed, imports repointed at
 * this app's vendored com.metrolist.music.ui.component.backdrop, and
 * com.kyant.shapes.Capsule swapped for RoundedCornerShape(percent = 50) (the
 * lens effect only supports CornerBasedShape here — see GlassEffect.kt).
 *
 * Used directly by SearchScreen's Explore/Suggestions/Album pill selector.
 * This app's own FloatingTabBar/FloatingSideBar (selection puck sync between
 * phone bar and tablet sidebar, InteractiveHighlight, GlassEffectConfig-driven,
 * shared-element transitions, search-tab morphing) still handles primary nav —
 * this component is for smaller in-screen segmented pills.
 */
package com.metrolist.music.ui.component.backdrop.catalog.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.metrolist.music.ui.component.backdrop.Backdrop
import com.metrolist.music.ui.component.backdrop.backdrops.layerBackdrop
import com.metrolist.music.ui.component.backdrop.backdrops.rememberCombinedBackdrop
import com.metrolist.music.ui.component.backdrop.backdrops.rememberLayerBackdrop
import com.metrolist.music.ui.component.backdrop.catalog.utils.DampedDragAnimation
import com.metrolist.music.ui.component.backdrop.catalog.utils.InteractiveHighlight
import com.metrolist.music.ui.component.backdrop.drawBackdrop
import com.metrolist.music.ui.component.backdrop.effects.blur
import com.metrolist.music.ui.component.GlassLensRecipes
import com.metrolist.music.ui.component.LensMode
import com.metrolist.music.ui.component.applyGlassRecipe
import com.metrolist.music.ui.component.lensGuarded
import com.metrolist.music.ui.component.thinned
import com.metrolist.music.ui.component.backdrop.effects.vibrancy
import com.metrolist.music.ui.component.backdrop.highlight.Highlight
import com.metrolist.music.ui.component.backdrop.shadow.InnerShadow
import com.metrolist.music.ui.component.backdrop.shadow.Shadow
import com.metrolist.music.ui.component.BarMaterial
import com.metrolist.music.ui.component.GlassBarPolicy
import com.metrolist.music.ui.component.glassFrost
import com.metrolist.music.ui.component.inset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * The nav pill's glass is captured and blurred at half size (BitChord runs all its glass at a third): the blur hides the
 * lost detail and the effect cost drops to about a quarter while a list scrolls underneath. Not used for the press puck.
 */
internal const val NavGlassScale = 0.5f

/** Lift of the frost puck while it is held (no backdrop: an elevation shadow stands in for the library's blurred one). */
private val FrostPuckElevation = 10.dp

@Composable
fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    // null = no app backdrop (Android 8-11, or "Blur kaca" off): the bar is drawn in the frost material (GlassFrost.kt)
    // with the same gestures, puck and fold; nothing samples the page
    backdrop: Backdrop?,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    // Nav-bar pill defaults; pass a caller color/height to blend into a
    // specific screen (e.g. that screen's own hero tint) instead.
    containerColor: Color? = null,
    accent: Color? = null,
    height: Dp = 64.dp,
    // LENS: the bar bends the picture behind it at rest (BitChord-like); BLUR: frost only, no shader (see GlassLensPolicy)
    lensMode: LensMode = LensMode.BLUR,
    // The navigation bar: choosing a tab builds a whole page, which takes one long frame of the main thread (100-250 ms on a phone that
    // has not compiled the app yet). A tap then goes bubble swells -> page is built (nothing is moving, so the long frame costs a pause
    // but no hitch) -> bubble glides while the page fades in. [routeIndex] is the tab whose page is on screen.
    routeIndex: (() -> Int)? = null,
    content: @Composable RowScope.() -> Unit
) {
    // GlassBarPolicy.material: the bar samples the page only with a backdrop and a lens mode that is not FLAT; else frost
    val glassBackdrop: Backdrop? =
        if (backdrop != null && GlassBarPolicy.material(lensMode, backdropAvailable = true) != BarMaterial.FROST) backdrop else null
    val frost = glassBackdrop == null
    val frostColors = if (frost) com.metrolist.music.ui.component.rememberFrostColors() else null
    val isLightTheme = !com.metrolist.music.ui.component.glassUsesDarkAppearance()
    // no accent by default: ink (the tab under the puck stays neutral)
    val accentColor =
        accent ?: if (isLightTheme) com.metrolist.music.ui.theme.HorizonLight.ink
        else com.metrolist.music.ui.theme.HorizonDark.ink
    val barRecipe = remember(lensMode) { GlassLensRecipes.navBar(lensMode) }
    val lensOn = lensMode == LensMode.LENS
    val resolvedContainerColor = (containerColor ?: (
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.4f)
        else Color(0xFF121212).copy(0.4f)
    )).thinned(barRecipe.veilScale)
    val innerHeight = height * 0.875f

    // the copy of the tabs that the puck refracts; the frost bar has no lens, so it needs neither the copy nor its layer
    val tabsBackdrop = if (frost) null else rememberLayerBackdrop()

    BoxWithConstraints(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
        }

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember(selectedTabIndex) {
            mutableIntStateOf(selectedTabIndex())
        }
        // set when a drag ends on another tab: that path keeps its own order (the puck is already there, the finger placed it)
        val selectionFromDrag = remember { BooleanArray(1) }
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    selectionFromDrag[0] = targetIndex != currentIndex
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(1f, 300f, 0.5f)
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }
        LaunchedEffect(selectedTabIndex) {
            snapshotFlow { selectedTabIndex() }
                .collectLatest { index ->
                    currentIndex = index
                }
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    val fromDrag = selectionFromDrag[0]
                    selectionFromDrag[0] = false
                    val buildsPage = routeIndex != null && !fromDrag && index != routeIndex()
                    if (buildsPage) {
                        // A tap on another tab. The page is built in one long frame (100-250 ms of the main thread on a phone that has not
                        // compiled the app yet, 30-70 ms when it has). Round 14 let the puck leave first, so that frame landed in the
                        // middle of the glide and froze it (owner's videos 2026-11-17 and 2026-11-18). Now the bubble swells where it is
                        // (two cheap frames, the press the tap would show anyway), the page is built while nothing moves, and only
                        // then the bubble glides, together with the page's fade-in: a pause before the motion, no hitch inside it.
                        // A newer tap during these frames replaces this one (collectLatest).
                        dampedDragAnimation.press()
                        withFrameNanos { }
                        onTabSelected(index)
                        // the frame that builds the page is the next one; the one after it has started when this returns
                        withFrameNanos { }
                        withFrameNanos { }
                        dampedDragAnimation.animateToValue(index.toFloat())
                    } else {
                        dampedDragAnimation.animateToValue(index.toFloat())
                        // The puck leaves first: building the new page takes 100-200 ms of the main thread, and it used to start in the
                        // very frame of the tap, so the bar froze first and then jumped (owner's video 2026-11-17). Two frames later the
                        // puck is already on its way; a newer tap during these frames replaces this one (collectLatest).
                        withFrameNanos { }
                        withFrameNanos { }
                        onTabSelected(index)
                    }
                }
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, offset ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        // the bar swells a little while the puck is held (both materials)
        val barSwell: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = remember(dampedDragAnimation) {
            {
                val progress = dampedDragAnimation.pressProgress
                val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                scaleX = scale
                scaleY = scale
            }
        }
        Row(
            Modifier
                .graphicsLayer {
                    translationX = panelOffset
                }
                .then(
                    if (glassBackdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = glassBackdrop,
                            shape = { RoundedCornerShape(percent = 50) },
                            effects = {
                                // LENS: BitChord-like frost (10 dp) + refraction at rest, depth + colour fringe, thinner veil: a heavier
                                // frost blurred the bent rim away, which is why the old bar looked like blur only.
                                // BLUR: the heavy G1 frost (20 dp), because without a lens only the blur hides the list text.
                                // (pixel sizes are scaled with backdropScale: the effects run on a half-size capture)
                                applyGlassRecipe(barRecipe, NavGlassScale)
                            },
                            backdropScale = NavGlassScale,
                            layerBlock = barSwell,
                            onDrawSurface = { drawRect(resolvedContainerColor) }
                        )
                    } else {
                        // FROST (no backdrop): same capsule, swell and press sheen, a material that samples nothing. glassFrost
                        // clips what follows (the press sheen) to the capsule, as the backdrop node's own clipped layer does.
                        Modifier
                            .graphicsLayer(barSwell)
                            .glassFrost(CapsuleShape, frostColors!!, com.metrolist.music.ui.component.GlassFrostLook.ELEVATION_DP.dp)
                    }
                )
                .then(interactiveHighlight.modifier)
                .height(height)
                .fillMaxWidth()
                .padding(4f.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )

        if (glassBackdrop != null && tabsBackdrop != null) CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .drawBackdrop(
                        backdrop = glassBackdrop,
                        shape = { RoundedCornerShape(percent = 50) },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(8f.dp.toPx() * NavGlassScale)
                            if (lensOn) {
                                lensGuarded(
                                    24f.dp.toPx() * progress * NavGlassScale,
                                    24f.dp.toPx() * progress * NavGlassScale
                                )
                            }
                        },
                        backdropScale = NavGlassScale,
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        onDrawSurface = { drawRect(resolvedContainerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(innerHeight)
                    .fillMaxWidth()
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .then(
                    if (glassBackdrop != null && tabsBackdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = rememberCombinedBackdrop(glassBackdrop, tabsBackdrop),
                            shape = { RoundedCornerShape(percent = 50) },
                            effects = {
                                val progress = dampedDragAnimation.pressProgress
                                // BitChord's press lens (12 / 14 dp, colour fringe); only while held or dragged, like BitChord's pill
                                if (lensOn) {
                                    lensGuarded(
                                        GlassLensRecipes.PUCK_LENS_HEIGHT_DP.dp.toPx() * progress,
                                        GlassLensRecipes.PUCK_LENS_AMOUNT_DP.dp.toPx() * progress,
                                        dispersion = 1f
                                    )
                                }
                            },
                            highlight = {
                                val progress = dampedDragAnimation.pressProgress
                                Highlight.Default.copy(alpha = progress)
                            },
                            shadow = {
                                val progress = dampedDragAnimation.pressProgress
                                Shadow(alpha = progress)
                            },
                            innerShadow = {
                                val progress = dampedDragAnimation.pressProgress
                                InnerShadow(
                                    radius = 8f.dp * progress,
                                    alpha = progress
                                )
                            },
                            layerBlock = {
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                                val velocity = dampedDragAnimation.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            },
                            onDrawSurface = {
                                val progress = dampedDragAnimation.pressProgress
                                drawRect(
                                    if (isLightTheme) Color.Black.copy(0.1f)
                                    else Color.White.copy(0.1f),
                                    alpha = 1f - progress
                                )
                                drawRect(Color.Black.copy(alpha = 0.03f * progress))
                            }
                        )
                    } else {
                        // FROST: no lens and no copy of the tabs to refract. The puck rests as the flat indicator and, while
                        // held, lifts into a clear pane with a rim, a sheen and a real shadow.
                        Modifier.frostPuck(dampedDragAnimation, isLightTheme)
                    }
                )
                .height(innerHeight)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}

private val CapsuleShape = RoundedCornerShape(percent = 50)

/**
 * The selection puck of the frost bar. Everything that moves is read in the graphics layer or the draw phase (pressProgress,
 * scale, velocity), so a press or a drag never recomposes; the brushes and the inset outline are built once per size.
 */
private fun Modifier.frostPuck(drag: DampedDragAnimation, isLightTheme: Boolean): Modifier =
    this
        .graphicsLayer {
            val progress = drag.pressProgress
            scaleX = drag.scaleX
            scaleY = drag.scaleY
            val velocity = drag.velocity / 10f
            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
            shadowElevation = FrostPuckElevation.toPx() * progress
            shape = CapsuleShape
            clip = false
            ambientShadowColor = Color.Black.copy(alpha = 0.22f)
            spotShadowColor = Color.Black.copy(alpha = 0.30f)
        }
        .drawWithCache {
            val outline = CapsuleShape.createOutline(size, layoutDirection, this)
            val rimPx = 1.dp.toPx()
            val inner = outline.inset(rimPx / 2f)
            val rimStroke = Stroke(width = rimPx)
            val rimBrush = Brush.linearGradient(
                0f to Color.White.copy(alpha = if (isLightTheme) 0.95f else 0.55f),
                0.5f to Color.White.copy(alpha = if (isLightTheme) 0.25f else 0.08f),
                1f to Color.White.copy(alpha = if (isLightTheme) 0.70f else 0.30f),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
            // the puck is drawn OVER the tabs (no lens copy of them here), so the held pane stays thin: a thick one washed the
            // icon under it out in the renders
            val sheen = Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (isLightTheme) 0.40f else 0.12f),
                0.45f to Color.White.copy(alpha = 0f),
            )
            val rest = if (isLightTheme) Color.Black.copy(0.1f) else Color.White.copy(0.1f)
            val held = if (isLightTheme) Color.White.copy(0.08f) else Color.White.copy(0.05f)
            onDrawBehind {
                val progress = drag.pressProgress
                // at rest: the same flat indicator the glass bar draws under the selected tab
                if (progress < 1f) drawOutline(outline, rest, alpha = 1f - progress)
                if (progress > 0.001f) {
                    drawOutline(outline, held, alpha = progress)
                    drawOutline(outline, sheen, alpha = progress)
                    drawOutline(inner, rimBrush, alpha = progress, style = rimStroke)
                }
            }
        }
