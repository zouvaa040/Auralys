/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The header of every page that is about one thing with a cover: playlist, album, artist (Horizon A7, DESIGN-V2.md 3.1).
 *
 *  - the cover is full width and reaches the top edge of the screen, behind the status bar and the top bar;
 *  - its lower half dissolves into the page colour, so there is no card, no border, no shadow and no boxed picture;
 *  - when the colour mode is "Sampul", the colour of the cover itself washes softly into the first screenful of the page
 *    (colour only from the cover, never on a control);
 *  - a large plain title, one meta line, then one row of actions: an ink pill and neutral glass circles.
 *
 * It is a lazy list item: place it first in the list. The list must use the player-aware insets as its content padding
 * (every page does), because the cover is pulled up by exactly that top padding.
 */

package com.metrolist.music.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.theme.LocalCoverTint
import com.metrolist.music.ui.theme.LocalCoverTintEnabled
import com.metrolist.music.ui.theme.LocalHorizon
import com.metrolist.music.ui.theme.clampCoverTint
import com.metrolist.music.ui.theme.coverWashAlpha
import com.metrolist.music.ui.theme.extractCoverTint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The numbers of the header, kept apart so they can be unit tested. */
object HeroMath {
    /** On a phone the cover is as tall as the screen is wide; on a tablet it stops growing here. */
    const val MaxCoverDp = 440f

    /** How far the title block climbs into the faded bottom of the cover. */
    const val TitleOverlapDp = 72f

    /** The wash of the cover colour below the cover fades out over this height. */
    const val WashDp = 240f

    fun coverHeightDp(widthDp: Float): Float = minOf(widthDp, MaxCoverDp)

    /** Where the top bar starts to get its own scrim (dp of scroll), and how long the ramp is. */
    const val BarFadeStartDp = 180f
    const val BarFadeRampDp = 110f
}

/** Moves a child up by [amount] and tells the parent it is that much shorter: the child overlaps what is above it. */
private fun Modifier.pullUp(amount: Dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val px = amount.roundToPx().coerceIn(0, placeable.height)
        layout(placeable.width, placeable.height - px) { placeable.place(0, -px) }
    }

private val tintCache =
    object : LinkedHashMap<String, Int>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > 48
    }
private const val NoTint = 0

/**
 * The colour of the cover at [url], clamped (lightness 0.35 to 0.55, saturation at most 0.55), for the wash. Null while it
 * is being computed, when the cover has no usable colour, or when the colour mode is not "Sampul" (unless [ignoreMode]: a
 * card that is coloured by its own cover whatever the app's colour mode is, such as the home song card).
 */
@Composable
fun rememberCoverTintOf(
    url: String?,
    ignoreMode: Boolean = false,
): Color? {
    if ((!LocalCoverTintEnabled.current && !ignoreMode) || url == null) return null
    val context = LocalContext.current
    var tint by remember(url) { mutableStateOf(tintCache[url]?.let { if (it == NoTint) null else Color(it) }) }
    LaunchedEffect(url) {
        if (tintCache.containsKey(url)) return@LaunchedEffect
        val picked =
            withContext(Dispatchers.IO) {
                runCatching {
                    val result =
                        context.imageLoader.execute(
                            ImageRequest.Builder(context).data(url).size(128).allowHardware(false).build(),
                        )
                    result.image?.toBitmap()?.extractCoverTint()?.let(::clampCoverTint)
                }.getOrNull()
            }
        tintCache[url] = picked?.toArgb() ?: NoTint
        tint = picked
    }
    return tint
}

/**
 * 0 while the header is on screen, 1 once the list has scrolled past most of it: drives the scrim behind the top bar (pass
 * it as `fade` to the page's top bar). Read inside graphics layers only, so scrolling never recomposes.
 */
@Composable
fun rememberHeroBarFade(
    state: LazyListState,
    alwaysOn: () -> Boolean = { false },
): () -> Float {
    val density = LocalDensity.current
    val startPx = with(density) { HeroMath.BarFadeStartDp.dp.toPx() }
    val rampPx = with(density) { HeroMath.BarFadeRampDp.dp.toPx() }
    val force by rememberUpdatedState(alwaysOn)
    return remember(state, startPx, rampPx) {
        {
            heroBarFade(force(), state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset.toFloat(), startPx, rampPx)
        }
    }
}

/**
 * The one number behind the top bar of a hero page: the scrim behind the bar AND the title in the bar both read it, so they
 * appear together and the title fades in instead of switching on (REVIEW-2026-11-07 F8). 1 when [forced] (searching) or when
 * the header item has left the list.
 */
fun heroBarFade(
    forced: Boolean,
    firstVisibleItemIndex: Int,
    scrollOffsetPx: Float,
    startPx: Float,
    rampPx: Float,
): Float = if (forced || firstVisibleItemIndex > 0) 1f else topBarFade(scrollOffsetPx, startPx, rampPx)

/**
 * @param coverUrl the picture; or [cover] to draw something else (a 2x2 mosaic, a picked image). Neither: a plain panel.
 * @param coverOverlay controls over the cover (the "edit cover" button); the box is the whole cover, the argument is [topInset].
 * @param tint the colour for the wash when the caller already knows it; null = the colour of [coverUrl] is read.
 * @param topInset the top padding of the list (status bar + top bar): the cover starts above it, at the screen edge.
 * @param actions the row under the title: one [HeroPlayButton] with weight 1 and up to three [HeroIconButton].
 */
@Composable
fun HeroHeader(
    title: String,
    modifier: Modifier = Modifier,
    coverUrl: String? = null,
    cover: (@Composable BoxScope.() -> Unit)? = null,
    coverOverlay: (@Composable BoxScope.(topInset: Dp) -> Unit)? = null,
    meta: String? = null,
    subtitle: (@Composable () -> Unit)? = null,
    description: (@Composable () -> Unit)? = null,
    tint: Color? = null,
    topInset: Dp = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateTopPadding(),
    actions: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val page = scheme.surface
    val dark = LocalHorizon.current.dark
    val washTint = if (LocalCoverTintEnabled.current) tint ?: rememberCoverTintOf(coverUrl) ?: LocalCoverTint.current else null
    // the page colour with the wash on it: where the cover ends and the page begins they are the same colour
    val washColor = if (washTint == null) page else lerp(page, washTint, coverWashAlpha(dark))

    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().pullUp(topInset)) {
            val coverHeight = HeroMath.coverHeightDp(maxWidth.value).dp
            val bottomFade =
                remember(washColor) {
                    Brush.verticalGradient(
                        0.40f to washColor.copy(alpha = 0f),
                        0.70f to washColor.copy(alpha = 0.62f),
                        0.88f to washColor.copy(alpha = 0.94f),
                        1f to washColor,
                    )
                }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(coverHeight)
                    .drawWithContent {
                        drawContent()
                        drawRect(bottomFade)
                    },
            ) {
                when {
                    cover != null -> cover()
                    coverUrl != null ->
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    else ->
                        Box(
                            Modifier.fillMaxSize().background(scheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.queue_music),
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(72.dp),
                            )
                        }
                }
                // the status bar and the top bar sit on a soft page-coloured fade, so their icons read on any cover
                val topFade =
                    remember(page) {
                        Brush.verticalGradient(listOf(page.copy(alpha = 0.80f), page.copy(alpha = 0.40f), page.copy(alpha = 0f)))
                    }
                Box(
                    Modifier.fillMaxWidth().height(topInset + 20.dp).align(Alignment.TopCenter).background(topFade),
                )
                coverOverlay?.invoke(this, topInset)
            }
        }

        val overlap = HeroMath.TitleOverlapDp.dp
        val wash = HeroMath.WashDp.dp
        Column(
            Modifier
                .fillMaxWidth()
                .pullUp(overlap)
                .drawBehind {
                    // below the cover: the wash fades from its colour to the page, reaching the page colour exactly where the
                    // header ends (no edge above the first row); at most WashDp long
                    val top = overlap.toPx()
                    val h = minOf(wash.toPx(), size.height - top)
                    if (h > 0f && washColor != page) {
                        drawRect(
                            brush = Brush.verticalGradient(listOf(washColor, page), startY = top, endY = top + h),
                            topLeft = Offset(0f, top),
                            size = Size(size.width, h),
                        )
                    }
                }.padding(horizontal = 20.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.displayMedium,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Spacer(Modifier.height(6.dp))
                it()
            }
            if (!meta.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            description?.let {
                Spacer(Modifier.height(8.dp))
                it()
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

/** The one primary action of a page: an ink pill (48 dp). Pass `Modifier.weight(1f)` to fill the row. */
@Composable
fun HeroPlayButton(
    label: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = HeroPillButton(label = label, onClick = onClick, modifier = modifier, icon = icon)

/**
 * A 48 dp pill. [filled] = ink fill with a page-coloured label; otherwise a transparent pill with an ink outline (the
 * "follow" button of an artist before it is pressed).
 */
@Composable
fun HeroPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    filled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (filled) scheme.primary else Color.Transparent,
        contentColor = if (filled) scheme.onPrimary else scheme.onSurface,
        border = if (filled) null else BorderStroke(1.5.dp, scheme.onSurface.copy(alpha = 0.55f)),
        modifier = modifier.height(48.dp).pressScale(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text = label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * A secondary action: a neutral glass circle (48 dp) with a bare ink icon. State is shown by the filled icon variant
 * (pass the filled drawable when [active]), never by a colour.
 */
@Composable
fun HeroIconButton(
    @DrawableRes icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(48.dp)
                .pressScale()
                .glass(CircleShape)
                .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** The creator line under a playlist title: a small round avatar and the name in ink. Pass [onClick] to make it a link. */
@Composable
fun HeroAuthorRow(
    name: String,
    avatarUrl: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(24.dp).clip(CircleShape),
            )
        }
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A round control over a cover (play on a tile): a dark veil with a white glyph, readable on any picture and in any theme.
 * 48 dp (the minimum touch target). [onLongClick]: a long press that starts on the disc does what a long press on the tile
 * does (the tile's menu); without it the disc would swallow the long press (REVIEW-2026-11-07 F11).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnCoverButton(
    @DrawableRes icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 22.dp,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.42f))
                .then(
                    if (onLongClick == null) {
                        Modifier.clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
                    } else {
                        Modifier.combinedClickable(
                            role = Role.Button,
                            onClickLabel = contentDescription,
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                    },
                ),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}
