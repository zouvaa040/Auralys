/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The Liquid Glass top bar is transparent, so a list scrolling under it ran straight into the title and the status bar
 * ("Beranda" printed over the song tiles). While content is under the bar this lays a frosted strip behind it that fades
 * out towards the bottom edge, so the title stays readable and there is no hard line or dark band.
 * It samples the app backdrop, so it must only wrap bars that live OUTSIDE the recorded page (the Scaffold top bar).
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.dp
import com.metrolist.music.ui.component.backdrop.drawBackdrop
import com.metrolist.music.ui.component.backdrop.effects.blur
import com.metrolist.music.ui.theme.LocalUiStyle
import com.metrolist.music.ui.theme.UiStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopFadeContainer(
    scrollBehavior: TopAppBarScrollBehavior?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        if (LocalUiStyle.current == UiStyle.GLASS && scrollBehavior != null) {
            val surface = MaterialTheme.colorScheme.surface
            val backdrop = LocalAppBackdrop.current
            val frosted = backdrop != null && LocalGlassBlurEnabled.current && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
            val fade = Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent)
            Box(
                Modifier
                    .matchParentSize()
                    // intensity follows how far content has slid under the bar; read in the graphics layer, so the
                    // scroll itself never recomposes anything
                    .graphicsLayer {
                        alpha = scrollBehavior.state.overlappedFraction.coerceIn(0f, 1f)
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(fade, blendMode = BlendMode.DstIn)
                    }
                    .then(
                        if (frosted) {
                            Modifier.drawBackdrop(
                                backdrop = backdrop!!,
                                shape = { RectangleShape },
                                // quarter-cost frost: captured at half size, blur radius scaled with it
                                effects = { blur(16.dp.toPx() * 0.5f) },
                                highlight = null,
                                shadow = null,
                                onDrawSurface = { drawRect(surface.copy(alpha = 0.34f)) },
                                backdropScale = 0.5f,
                            )
                        } else {
                            Modifier.background(surface.copy(alpha = 0.62f))
                        },
                    ),
            )
        }
        content()
    }
}
