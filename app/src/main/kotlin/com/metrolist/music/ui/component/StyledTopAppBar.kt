/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Drop-in replacement for Material3's TopAppBar (same parameter names): in the Liquid Glass style the
 * bar is transparent so the ambient glow of the page shows through instead of a flat black strip.
 *
 * The bar never changes its own container colour while scrolling (M3 would jump to `surface`, white in a light theme).
 * Instead a scrim in the page colour is drawn BEHIND the bar and fades in with the scroll position (idea after BitChord's
 * top fade, GPL-3.0): see [TopBarScrollState]. Glass: a translucent top-down gradient. Editorial: the same colour as the page.
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.music.ui.theme.LocalCoverShade
import com.metrolist.music.ui.theme.LocalUiStyle
import com.metrolist.music.ui.theme.UiStyle
import com.metrolist.music.ui.theme.drawCoverShade

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun styledTopAppBarColors(ownScrim: Boolean, shaded: Boolean): TopAppBarColors =
    if (LocalUiStyle.current == UiStyle.GLASS || ownScrim || shaded) {
        // no tint at all: the bar is the page itself, so the glow runs uninterrupted from the status bar down
        TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        )
    } else {
        // the page colour in both states, so a scroll behaviour can never tint the bar (M3's default scrolled colour is surfaceContainer)
        val surface = MaterialTheme.colorScheme.surface
        TopAppBarDefaults.topAppBarColors(
            containerColor = surface,
            scrolledContainerColor = surface,
        )
    }

/**
 * @param fade 0..1, how strongly the scrim behind the bar shows. Read in a graphics layer only. Null = follow the page's
 *   scroll position ([LocalTopBarScroll]) in the Glass style, and no scrim in the Editorial style (its bar is already the page colour).
 *   A screen with its own header (the artist hero) passes its own value; then the bar is transparent in every style.
 * @param autoScrim false for a bar that has its own fade container (the main tabs' bar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    expandedHeight: Dp = TopAppBarDefaults.TopAppBarExpandedHeight,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    fade: (() -> Float)? = null,
    autoScrim: Boolean = true,
) {
    val glass = LocalUiStyle.current == UiStyle.GLASS
    // "Efek berbayang" in the Datar style: the bar stays opaque (rows slide under it), but it paints the page colour AND the
    // wash itself, with the same brush and origin as the app background, so the bar and the page are one surface
    val shade = LocalCoverShade.current
    val shadedBar = shade.active && !glass
    val shadePageHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
    val tracker = LocalTopBarScroll.current
    val density = LocalDensity.current
    val startPx = with(density) { 8.dp.toPx() }
    val rampPx = with(density) { 44.dp.toPx() }
    val pageFade: (() -> Float)? =
        fade ?: if (autoScrim && glass && tracker != null) {
            remember(tracker, startPx, rampPx) { { topBarFade(tracker.offsetPx, startPx, rampPx) } }
        } else {
            null
        }

    val bar: @Composable (Modifier) -> Unit = { m ->
        androidx.compose.material3.TopAppBar(
            title = title,
            modifier = m,
            navigationIcon = navigationIcon,
            actions = actions,
            expandedHeight = expandedHeight,
            windowInsets = windowInsets,
            colors = colors ?: styledTopAppBarColors(ownScrim = fade != null, shaded = shadedBar),
            scrollBehavior = scrollBehavior,
        )
    }

    val surface = MaterialTheme.colorScheme.surface
    if (pageFade == null) {
        if (shadedBar) {
            Box(modifier.drawBehind { drawRect(surface); drawCoverShade(shade, shadePageHeightPx) }) { bar(Modifier) }
        } else {
            bar(modifier)
        }
        return
    }

    val scrim =
        remember(glass, surface) {
            if (glass) {
                // translucent and fading out towards the bottom edge: no hard line under the bar
                Brush.verticalGradient(
                    0f to surface.copy(alpha = 0.86f),
                    0.65f to surface.copy(alpha = 0.58f),
                    1f to Color.Transparent,
                )
            } else {
                Brush.verticalGradient(listOf(surface, surface))
            }
        }
    Box(modifier) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = pageFade() }
                .background(scrim),
        )
        bar(Modifier)
    }
}
