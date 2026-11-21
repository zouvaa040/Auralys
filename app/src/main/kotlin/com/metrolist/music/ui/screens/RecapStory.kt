/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Full-screen, story-style Recap viewer: a handful of animated cards with a segmented progress bar
 * (tap right/left to move, hold to pause, auto-advance). Each card gets a gradient taken from its cover.
 * Animations only touch draw/layer state, so nothing is allocated per frame while a card is on screen.
 */

package com.metrolist.music.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.R
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.component.pressScale
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.viewmodels.RecapData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private enum class StoryPage { MINUTES, TOP_SONG, TOP_ARTIST, TOP_SONGS, TOP_ARTISTS, OUTRO }

private const val STORY_PAGE_MS = 5000

@Immutable
private data class StoryColors(
    val top: Color,
    val bottom: Color,
    val blob: Color,
)

/** Hand-picked fallbacks so a card without artwork still gets its own mood. */
private val FallbackColors =
    listOf(
        StoryColors(Color(0xFF7A2E8E), Color(0xFF1A0B24), Color(0xFFFF7EB3)),
        StoryColors(Color(0xFFB4462B), Color(0xFF240C07), Color(0xFFFFB86B)),
        StoryColors(Color(0xFF1F5FA8), Color(0xFF07142A), Color(0xFF6FD3FF)),
        StoryColors(Color(0xFF2E7D5B), Color(0xFF06190F), Color(0xFFB8F27C)),
        StoryColors(Color(0xFF5B3CC4), Color(0xFF0E0828), Color(0xFF9EA8FF)),
        StoryColors(Color(0xFFC0392B), Color(0xFF1C0606), Color(0xFFFFD166)),
    )

private fun paletteColors(bitmap: android.graphics.Bitmap, fallback: StoryColors): StoryColors {
    val p = Palette.from(bitmap).maximumColorCount(16).generate()
    val fb = fallback.top.toArgb()
    val vibrant = p.getVibrantColor(p.getDominantColor(fb))
    val deep = p.getDarkMutedColor(p.getDarkVibrantColor(ColorUtils.blendARGB(vibrant, android.graphics.Color.BLACK, 0.6f)))
    val light = p.getLightVibrantColor(p.getLightMutedColor(ColorUtils.blendARGB(vibrant, android.graphics.Color.WHITE, 0.35f)))
    return StoryColors(
        top = Color(ColorUtils.blendARGB(vibrant, android.graphics.Color.BLACK, 0.22f)),
        bottom = Color(ColorUtils.blendARGB(deep, android.graphics.Color.BLACK, 0.55f)),
        blob = Color(light),
    )
}

@Composable
fun RecapStoryDialog(
    data: RecapData,
    title: String,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        RecapStory(data = data, title = title, onDismiss = onDismiss, onShare = onShare)
    }
}

@Composable
private fun RecapStory(
    data: RecapData,
    title: String,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
) {
    val context = LocalContext.current
    val pages =
        remember(data) {
            buildList {
                add(StoryPage.MINUTES)
                if (data.songs.isNotEmpty()) add(StoryPage.TOP_SONG)
                if (data.artists.isNotEmpty()) add(StoryPage.TOP_ARTIST)
                if (data.songs.size >= 2) add(StoryPage.TOP_SONGS)
                if (data.artists.size >= 2) add(StoryPage.TOP_ARTISTS)
                add(StoryPage.OUTRO)
            }
        }
    val coverFor: (StoryPage) -> String? = { page ->
        when (page) {
            StoryPage.MINUTES, StoryPage.TOP_SONG, StoryPage.OUTRO -> data.songs.firstOrNull()?.thumbnailUrl
            StoryPage.TOP_ARTIST -> data.artists.firstOrNull()?.thumbnailUrl
            StoryPage.TOP_SONGS -> (data.songs.getOrNull(1) ?: data.songs.firstOrNull())?.thumbnailUrl
            StoryPage.TOP_ARTISTS -> (data.artists.getOrNull(1) ?: data.artists.firstOrNull())?.thumbnailUrl
        }
    }

    // cover url -> colours, filled in once in the background
    val paletteByUrl by produceState(initialValue = emptyMap<String, StoryColors>(), data) {
        val urls = pages.mapNotNull(coverFor).distinct()
        for (url in urls) {
            val colors =
                withContext(Dispatchers.Default) {
                    try {
                        val bmp =
                            context.imageLoader
                                .execute(
                                    ImageRequest
                                        .Builder(context)
                                        .data(url.resize(256, 256))
                                        .size(256)
                                        .allowHardware(false)
                                        .build(),
                                ).image
                                ?.toBitmap()
                        bmp?.let { paletteColors(it, FallbackColors[0]) }
                    } catch (_: Exception) {
                        null
                    }
                }
            if (colors != null) value = value + (url to colors)
        }
    }

    var page by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    val currentDismiss by rememberUpdatedState(onDismiss)
    val lastIndex = pages.lastIndex

    val progress = remember(page) { Animatable(0f) }
    LaunchedEffect(page, paused) {
        if (paused) return@LaunchedEffect
        val remaining = ((1f - progress.value) * STORY_PAGE_MS).toInt().coerceAtLeast(0)
        progress.animateTo(1f, tween(durationMillis = remaining, easing = LinearEasing))
        if (page < lastIndex) page += 1
    }

    val currentPage = pages[page.coerceIn(0, lastIndex)]
    val target = coverFor(currentPage)?.let { paletteByUrl[it] } ?: FallbackColors[page % FallbackColors.size]
    val top = animateColorAsState(target.top, tween(700), label = "story_top")
    val bottom = animateColorAsState(target.bottom, tween(700), label = "story_bottom")
    val blob = animateColorAsState(target.blob, tween(700), label = "story_blob")

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .storyBackground(top, bottom, blob)
                .pointerInput(lastIndex) {
                    detectTapGestures(
                        onPress = {
                            paused = true
                            tryAwaitRelease()
                            paused = false
                        },
                        onTap = { offset ->
                            if (offset.x < size.width * 0.3f) {
                                if (page > 0) page -= 1
                            } else if (page < lastIndex) {
                                page += 1
                            } else {
                                currentDismiss()
                            }
                        },
                    )
                },
    ) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val dir = if (targetState >= initialState) 1 else -1
                (slideInHorizontally(tween(420, easing = FastOutSlowInEasing)) { w -> dir * w / 5 } + fadeIn(tween(380))) togetherWith
                    (slideOutHorizontally(tween(420, easing = FastOutSlowInEasing)) { w -> -dir * w / 5 } + fadeOut(tween(260)))
            },
            label = "recap_story",
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(start = 28.dp, end = 28.dp, top = 64.dp, bottom = 32.dp),
            ) {
                StoryPageContent(pages[index.coerceIn(0, lastIndex)], data, title, onShare)
            }
        }

        // ---- segmented progress + close
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().height(3.dp),
            ) {
                for (i in pages.indices) {
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.28f))
                                .drawBehind {
                                    val f =
                                        when {
                                            i < page -> 1f
                                            i == page -> progress.value
                                            else -> 0f
                                        }
                                    if (f > 0f) drawRect(Color.White, size = Size(size.width * f, size.height))
                                },
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.app_logo),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.16f))
                            .clickable { currentDismiss() },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.close),
                        contentDescription = stringResource(R.string.auralys_recap_close),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberBlobPhase(): State<Float> {
    val transition = rememberInfiniteTransition(label = "recap_blobs")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Restart),
        label = "recap_blob_phase",
    )
}

/** Vertical gradient + three slowly drifting colour blobs. Brushes are rebuilt only when the colours change. */
@Composable
private fun Modifier.storyBackground(
    top: State<Color>,
    bottom: State<Color>,
    blob: State<Color>,
): Modifier {
    val phase = rememberBlobPhase()
    return this.drawWithCache {
        val t = top.value
        val b = bottom.value
        val c = blob.value
        val bg = Brush.verticalGradient(listOf(t, b))
        val r1 = size.maxDimension * 0.50f
        val r2 = size.maxDimension * 0.42f
        val r3 = size.maxDimension * 0.36f
        val b1 = Brush.radialGradient(listOf(c.copy(alpha = 0.55f), Color.Transparent), center = Offset.Zero, radius = r1)
        val b2 = Brush.radialGradient(listOf(t.copy(alpha = 0.85f), Color.Transparent), center = Offset.Zero, radius = r2)
        val b3 = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.12f), Color.Transparent), center = Offset.Zero, radius = r3)
        val shade = Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f))
        onDrawBehind {
            drawRect(bg)
            val a = phase.value * 2f * PI.toFloat()
            translate(size.width * (0.22f + 0.14f * cos(a)), size.height * (0.22f + 0.07f * sin(a))) {
                drawCircle(b1, radius = r1, center = Offset.Zero)
            }
            translate(size.width * (0.82f + 0.10f * sin(a + 1.3f)), size.height * (0.55f + 0.09f * cos(a + 0.7f))) {
                drawCircle(b2, radius = r2, center = Offset.Zero)
            }
            translate(size.width * (0.35f + 0.18f * cos(a * 2f + 2.1f)), size.height * (0.88f + 0.05f * sin(a + 2.6f))) {
                drawCircle(b3, radius = r3, center = Offset.Zero)
            }
            drawRect(shade)
        }
    }
}

// ---------------------------------------------------------------- cards

private val White80 = Color.White.copy(alpha = 0.8f)
private val White65 = Color.White.copy(alpha = 0.65f)

@Composable
private fun StoryPageContent(
    page: StoryPage,
    data: RecapData,
    title: String,
    onShare: () -> Unit,
) {
    when (page) {
        StoryPage.MINUTES -> MinutesCard(data, title)
        StoryPage.TOP_SONG -> TopSongCard(data)
        StoryPage.TOP_ARTIST -> TopArtistCard(data)
        StoryPage.TOP_SONGS -> TopSongsCard(data)
        StoryPage.TOP_ARTISTS -> TopArtistsCard(data)
        StoryPage.OUTRO -> OutroCard(title, onShare)
    }
}

@Composable
private fun StoryLabel(text: String, delay: Int = 0) {
    Text(
        text = text,
        color = White80,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun StoryHeadline(text: String, delay: Int, maxLines: Int = 2, center: Boolean = false) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
    )
}

@Composable
private fun MinutesCard(data: RecapData, title: String) {
    val nf = remember { NumberFormat.getIntegerInstance() }
    val count = remember { Animatable(0f) }
    LaunchedEffect(data.totalMinutes) {
        count.animateTo(data.totalMinutes.toFloat(), tween(1800, delayMillis = 250, easing = FastOutSlowInEasing))
    }
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        StoryLabel(title)
        Spacer(Modifier.height(28.dp))
        StoryLabel(stringResource(R.string.auralys_recap_story_listened_for), 80)
        Text(
            text = nf.format(count.value.toLong()),
            color = Color.White,
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp, lineHeight = 92.sp),
            fontWeight = FontWeight.Black,
            maxLines = 1,
        )
        StoryHeadline(stringResource(R.string.auralys_recap_story_minutes), 220, maxLines = 1)
        Spacer(Modifier.height(28.dp))
        Text(
            text =
                stringResource(R.string.auralys_recap_songs_count, nf.format(data.songCount)) + "  ·  " +
                    stringResource(R.string.auralys_recap_artists_count, nf.format(data.artistCount)),
            color = White80,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun StoryCover(
    url: String?,
    size: Dp,
    circle: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = if (circle) CircleShape else RoundedCornerShape(24.dp)
    val px = (size.value * 2.5f).toInt()
    AsyncImage(
        model = url?.resize(px, px),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
            modifier
                .size(size)
                .shadow(24.dp, shape, clip = false)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.12f))
                .border(if (circle) 3.dp else 1.dp, Color.White.copy(alpha = if (circle) 0.7f else 0.25f), shape),
    )
}

@Composable
private fun StoryPill(text: String, delay: Int) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier =
            Modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.18f))
                .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun TopSongCard(data: RecapData) {
    val song = data.songs.firstOrNull() ?: return
    val nf = remember { NumberFormat.getIntegerInstance() }
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
    ) {
        StoryLabel(stringResource(R.string.auralys_recap_story_top_song))
        Spacer(Modifier.height(24.dp))
        StoryCover(song.thumbnailUrl, 260.dp, circle = false)
        Spacer(Modifier.height(28.dp))
        StoryHeadline(song.title, 200, center = true)
        Spacer(Modifier.height(6.dp))
        Text(
            text = song.artist,
            color = White80,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(18.dp))
        StoryPill(stringResource(R.string.auralys_recap_story_plays, nf.format(song.plays)), 340)
    }
}

@Composable
private fun TopArtistCard(data: RecapData) {
    val artist = data.artists.firstOrNull() ?: return
    val nf = remember { NumberFormat.getIntegerInstance() }
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
    ) {
        StoryLabel(stringResource(R.string.auralys_recap_story_top_artist))
        Spacer(Modifier.height(24.dp))
        StoryCover(artist.thumbnailUrl, 240.dp, circle = true)
        Spacer(Modifier.height(28.dp))
        StoryHeadline(artist.name, 200, center = true)
        Spacer(Modifier.height(18.dp))
        StoryPill(stringResource(R.string.auralys_recap_story_minutes_together, nf.format(artist.minutes)), 300)
    }
}

@Composable
private fun StoryListRow(
    rank: Int,
    url: String?,
    circle: Boolean,
    title: String,
    subtitle: String,
    delay: Int,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = rank.toString(),
            color = if (rank == 1) Color.White else White65,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            modifier = Modifier.width(36.dp),
        )
        AsyncImage(
            model = url?.resize(150, 150),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(if (circle) CircleShape else RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.12f)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = White65,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TopSongsCard(data: RecapData) {
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        StoryHeadline(stringResource(R.string.auralys_recap_story_top5_songs), 0)
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            data.songs.take(5).forEachIndexed { i, s ->
                StoryListRow(i + 1, s.thumbnailUrl, circle = false, title = s.title, subtitle = s.artist, delay = 120 + i * 90)
            }
        }
    }
}

@Composable
private fun TopArtistsCard(data: RecapData) {
    val nf = remember { NumberFormat.getIntegerInstance() }
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        StoryHeadline(stringResource(R.string.auralys_recap_story_top5_artists), 0)
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            data.artists.take(5).forEachIndexed { i, a ->
                StoryListRow(
                    i + 1,
                    a.thumbnailUrl,
                    circle = true,
                    title = a.name,
                    subtitle = stringResource(R.string.auralys_recap_minutes_short, nf.format(a.minutes)),
                    delay = 120 + i * 90,
                )
            }
        }
    }
}

@Composable
private fun OutroCard(
    title: String,
    onShare: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
    ) {
        Image(
            painter = painterResource(R.drawable.app_logo),
            contentDescription = null,
            modifier = Modifier.size(104.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Auralys",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(36.dp))
        StoryHeadline(stringResource(R.string.auralys_recap_story_thanks), 160, center = true)
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.auralys_recap_story_thanks_sub),
            color = White80,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            color = White65,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier =
                Modifier
                    .pressScale(0.95f)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(onClick = onShare)
                    .padding(horizontal = 28.dp, vertical = 14.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.share),
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.auralys_recap_share),
                color = Color.Black,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
