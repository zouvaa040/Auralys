/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * "Share lyrics as image" studio: live preview + background, ratio, font, weight, alignment and colours,
 * with a one-tap reset to the defaults.
 */

package com.metrolist.music.ui.component

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.R
import com.metrolist.music.utils.ComposeToImage
import com.metrolist.music.utils.LyricsCardAlign
import com.metrolist.music.utils.LyricsCardBackground
import com.metrolist.music.utils.LyricsCardFont
import com.metrolist.music.utils.LyricsCardPalette
import com.metrolist.music.utils.LyricsCardRatio
import com.metrolist.music.utils.LyricsCardRenderer
import com.metrolist.music.utils.LyricsCardStyle
import com.metrolist.music.utils.LyricsCardWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LyricsShareStudio(
    txt: String,
    title: String,
    arts: String,
    thumbnailUrl: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current

    var style by remember { mutableStateOf(LyricsCardStyle()) }
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    var palette by remember { mutableStateOf<LyricsCardPalette?>(null) }
    val swatches = remember { mutableStateListOf<Color>() }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(thumbnailUrl) {
        if (thumbnailUrl == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val res = ImageLoader(context).execute(ImageRequest.Builder(context).data(thumbnailUrl).allowHardware(false).size(900).build())
                val bmp = res.image?.toBitmap()
                if (bmp != null) {
                    val p = Palette.from(bmp).maximumColorCount(16).generate()
                    val accent = p.getVibrantColor(p.getLightVibrantColor(p.getDominantColor(com.metrolist.music.utils.ShareCardColors.ACCENT)))
                    val dark = p.getDarkVibrantColor(p.getDarkMutedColor(com.metrolist.music.utils.ShareCardColors.DARK))
                    palette = LyricsCardPalette(accent, dark)
                    cover = bmp
                    swatches.clear()
                    swatches.addAll(
                        p.swatches.sortedByDescending { it.population }.map { Color(it.rgb) }.filter {
                            val hsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(it.toArgb(), hsv)
                            hsv[1] > 0.2f
                        }.take(5),
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load cover for the lyrics card")
            }
        }
    }

    // live preview: always the real renderer, just smaller
    LaunchedEffect(style, cover, palette) {
        delay(40)
        preview = try {
            LyricsCardRenderer.render(context, cover, palette, title, arts, txt, style, if (style.ratio == LyricsCardRatio.STORY) 540 else 720)
        } catch (e: Throwable) {
            Timber.e(e, "Lyrics card preview failed")
            preview
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .padding(horizontal = 14.dp, vertical = 24.dp)
                .fillMaxWidth()
                .heightIn(max = (configuration.screenHeightDp * 0.93f).dp)
                .glassOverlay(glassShape(32.dp, 18.dp), 0.dp),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.share_lyrics), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(12.dp))

                val pv = preview
                Box(
                    Modifier
                        .fillMaxWidth(if (style.ratio == LyricsCardRatio.STORY) 0.56f else 1f)
                        .aspectRatio(1f / style.ratio.heightFactor)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.Black.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (pv != null) Image(pv.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }

                // ---- background
                StudioTitle(stringResource(R.string.lyrics_studio_background))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        LyricsCardBackground.GLASS to R.string.lyrics_studio_glass,
                        LyricsCardBackground.SOLID to R.string.player_background_solid,
                        LyricsCardBackground.BLUR to R.string.player_background_blur,
                        LyricsCardBackground.GRADIENT to R.string.gradient,
                    ).forEach { (v, label) ->
                        FilterChip(selected = style.background == v, onClick = { style = style.copy(background = v) }, label = { Text(stringResource(label)) })
                    }
                }

                // ---- ratio
                StudioTitle(stringResource(R.string.lyrics_studio_ratio))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = style.ratio == LyricsCardRatio.SQUARE, onClick = { style = style.copy(ratio = LyricsCardRatio.SQUARE) }, label = { Text(stringResource(R.string.lyrics_studio_square)) })
                    FilterChip(selected = style.ratio == LyricsCardRatio.STORY, onClick = { style = style.copy(ratio = LyricsCardRatio.STORY) }, label = { Text(stringResource(R.string.lyrics_studio_story)) })
                }

                // ---- font (each chip is drawn in its own font)
                StudioTitle(stringResource(R.string.lyrics_studio_font))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LyricsCardFont.entries.forEach { f ->
                        val family = remember(f) { fontFamilyFor(context, f) }
                        FilterChip(
                            selected = style.font == f,
                            onClick = { style = style.copy(font = f) },
                            label = { Text(f.label, fontFamily = family, fontWeight = FontWeight.SemiBold) },
                        )
                    }
                }

                // ---- weight
                StudioTitle(stringResource(R.string.lyrics_studio_weight))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        LyricsCardWeight.REGULAR to (R.string.lyrics_studio_w_regular to FontWeight.Normal),
                        LyricsCardWeight.MEDIUM to (R.string.lyrics_studio_w_medium to FontWeight.Medium),
                        LyricsCardWeight.BOLD to (R.string.lyrics_studio_w_bold to FontWeight.Bold),
                        LyricsCardWeight.BLACK to (R.string.lyrics_studio_w_black to FontWeight.Black),
                    ).forEach { (w, p) ->
                        FilterChip(selected = style.weight == w, onClick = { style = style.copy(weight = w) }, label = { Text(stringResource(p.first), fontWeight = p.second) })
                    }
                }

                // ---- alignment
                StudioTitle(stringResource(R.string.lyrics_studio_align))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        LyricsCardAlign.LEFT to R.string.lyrics_studio_left,
                        LyricsCardAlign.CENTER to R.string.lyrics_studio_center,
                        LyricsCardAlign.RIGHT to R.string.lyrics_studio_right,
                    ).forEach { (a, label) ->
                        FilterChip(selected = style.align == a, onClick = { style = style.copy(align = a) }, label = { Text(stringResource(label)) })
                    }
                }

                // ---- colours
                if (style.background == LyricsCardBackground.SOLID) {
                    StudioTitle(stringResource(R.string.background_color))
                    Swatches(
                        colors = (swatches + listOf(Color(0xFF242424), Color(0xFF121212), Color.White, Color.Black, Color(0xFFF5F5F5))).distinct().take(9),
                        selected = style.backgroundColor?.let { Color(it) },
                        onPick = { style = style.copy(backgroundColor = it.toArgb()) },
                    )
                }
                StudioTitle(stringResource(R.string.text_color))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = style.textColor == null, onClick = { style = style.copy(textColor = null) }, label = { Text(stringResource(R.string.lyrics_studio_auto)) })
                    Swatches(
                        colors = (listOf(Color.White, Color.Black) + swatches).distinct().take(7),
                        selected = style.textColor?.let { Color(it) },
                        onPick = { style = style.copy(textColor = it.toArgb()) },
                    )
                }

                Spacer(Modifier.size(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        style = LyricsCardStyle()
                        Toast.makeText(context, context.getString(R.string.lyrics_studio_reset_done), Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.lyrics_studio_reset)) }
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val width = if (style.ratio == LyricsCardRatio.STORY) 1620 else 2160
                                    val image = LyricsCardRenderer.render(context, cover, palette, title, arts, txt, style, width)
                                    val uri = ComposeToImage.saveBitmapAsFile(context, image, "lyrics_${System.currentTimeMillis()}")
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "image/png"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            },
                                            context.getString(R.string.share_lyrics),
                                        ),
                                    )
                                    onDismiss()
                                } catch (e: Throwable) {
                                    Toast.makeText(context, context.getString(R.string.failed_to_create_image, e.message ?: ""), Toast.LENGTH_SHORT).show()
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.share)) }
                }
            }
        }
    }
}

@Composable
private fun StudioTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun Swatches(
    colors: List<Color>,
    selected: Color?,
    onPick: (Color) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { c ->
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c)
                    .border(2.dp, if (selected == c) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                    .clickable { onPick(c) },
            )
        }
    }
}

private fun fontFamilyFor(
    context: Context,
    f: LyricsCardFont,
): FontFamily =
    try {
        if (f.assetPath != null) FontFamily(Font(f.assetPath, context.assets)) else FontFamily.Default
    } catch (_: Throwable) {
        FontFamily.Default
    }
