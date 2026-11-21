/**
 * Auralys: full-bleed player cover ("Sampul layar penuh").
 *
 * The cover fills the width, starts at the very top of the screen (behind the status bar) and its
 * lower part dissolves into the player background with an alpha mask (not a coloured overlay, so it
 * works over any background in both UI styles and both themes). A short scrim keeps the status bar
 * icons readable; its strength follows how bright the top of the cover is.
 * Idea from BitChord's now-playing screen (GPLv3 like Auralys), re-implemented in Auralys' own style.
 * The maths lives in PlayerHeroMath.kt (unit tested).
 */

package com.metrolist.music.ui.player

import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One carousel page of the full-bleed player: the cover, centre-cropped, bottom faded out. [overlay] is drawn on the cover
 * and fades with it; [foreground] is drawn above the page and is NOT faded (the video picture).
 */
@Composable
fun HeroCoverPage(
    artworkUri: String?,
    modifier: Modifier = Modifier,
    overlay: (@Composable () -> Unit)? = null,
    foreground: (@Composable () -> Unit)? = null,
) {
    // Settles from slightly zoomed-in; the page is clipped by its parent so no edge ever shows.
    val settle = remember(artworkUri) { Animatable(1.03f) }
    LaunchedEffect(artworkUri) { settle.animateTo(1f, tween(220)) }

    val context = LocalContext.current
    val request =
        remember(artworkUri) {
            ImageRequest
                .Builder(context)
                .data(artworkUri)
                .crossfade(180)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .build()
        }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    // The mask is drawn inside an offscreen layer so DstIn only touches this page.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithCache {
                        val brush =
                            Brush.verticalGradient(
                                colorStops =
                                    heroMaskStops()
                                        .map { (pos, alpha) -> pos to Color.Black.copy(alpha = alpha) }
                                        .toTypedArray(),
                                startY = 0f,
                                endY = size.height,
                            )
                        onDrawWithContent {
                            drawContent()
                            drawRect(brush = brush, blendMode = BlendMode.DstIn)
                        }
                    },
        ) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = settle.value
                            scaleY = settle.value
                        },
            )
            overlay?.invoke()
        }
        foreground?.invoke()
    }
}

/** Dark gradient behind the status bar and the header so white icons and text stay readable. */
@Composable
fun HeroStatusScrim(
    artworkUri: String?,
    boxAspect: Float,
    modifier: Modifier = Modifier,
) {
    val luminance by rememberHeroTopLuminance(artworkUri, boxAspect)
    val peak by animateFloatAsState(heroScrimAlpha(luminance), tween(300), label = "heroScrim")
    val topInset = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(topInset + 72.dp)
                .drawBehind {
                    val alphas = heroScrimAlphas(peak, 8)
                    drawRect(
                        Brush.verticalGradient(
                            colorStops =
                                Array(alphas.size) { i ->
                                    (i / (alphas.size - 1).toFloat()) to Color.Black.copy(alpha = alphas[i])
                                },
                        ),
                    )
                },
    )
}

private val topLuminanceCache = LruCache<String, Float>(48)

/** Luminance of the top band of the visible cover, decoded once at 64 px and cached by url. */
@Composable
private fun rememberHeroTopLuminance(
    url: String?,
    boxAspect: Float,
): State<Float> {
    val context = LocalContext.current
    val aspectKey = (boxAspect * 10f).toInt()
    val key = if (url == null) null else "$url#$aspectKey"
    return produceState(initialValue = key?.let { topLuminanceCache.get(it) } ?: 0.35f, key) {
        if (key == null || url == null) return@produceState
        topLuminanceCache.get(key)?.let {
            value = it
            return@produceState
        }
        val lum =
            withContext(Dispatchers.IO) {
                runCatching {
                    val result =
                        context.imageLoader.execute(
                            ImageRequest
                                .Builder(context)
                                .data(url)
                                .size(64)
                                .allowHardware(false)
                                .build(),
                        )
                    val bitmap = result.image?.toBitmap() ?: return@runCatching null
                    val w = bitmap.width
                    val h = bitmap.height
                    val pixels = IntArray(w * h)
                    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                    heroTopBandLuminance(pixels, w, h, boxAspect)
                }.getOrNull()
            }
        if (lum != null) {
            topLuminanceCache.put(key, lum)
            value = lum
        }
    }
}
