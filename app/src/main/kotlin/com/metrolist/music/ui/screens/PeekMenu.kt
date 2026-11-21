/**
 * Auralys - the "peek" of a home song card (a long press lifts the card and opens a small menu next to it, like the context
 * menu of an iPhone)
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import android.content.Intent
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.local.isLocalSong
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.LocalSyncUtils
import com.metrolist.music.R
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.ui.component.LocalIosMotion
import com.metrolist.music.ui.component.iosAppear
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.menu.AddToPlaylistDialog
import com.metrolist.music.ui.theme.isGlassStyle
import com.metrolist.music.ui.utils.resize
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------------------------------
// What a peek is made of
// ---------------------------------------------------------------------------------------------------------------------

/** One row of the peek menu (public: the public DailyDiscoverTile takes a [PeekSource]). */
class PeekAction(
    @DrawableRes val icon: Int,
    val label: String,
    val onClick: () -> Unit,
)

/**
 * What a song card needs to peek. The lambdas are only composed while the peek (or the add-to-playlist dialog) is on screen, so a
 * card that is never long-pressed costs nothing: no database flow, no listen-together read.
 *
 * @property actions the quick actions of the song, wired to the same calls the song menus use; [onAddToPlaylist] opens the same
 * add-to-playlist dialog the menus open (see [addToPlaylist])
 * @property addToPlaylist the add-to-playlist dialog of this song, shown after the peek has closed
 * @property onMore opens the full menu, exactly what a long press did before the peek
 */
class PeekSource(
    val actions: @Composable (onAddToPlaylist: () -> Unit) -> List<PeekAction>,
    val addToPlaylist: @Composable (onDismiss: () -> Unit) -> Unit,
    val onMore: () -> Unit,
)

/** The peek of one card: open or not, where the card is. [shape] is the card's own shape (for its shadow). */
@Stable
internal class PeekHost(
    val source: PeekSource?,
    val shape: Shape,
) {
    var open by mutableStateOf(false)
    var showAddToPlaylist by mutableStateOf(false)

    /** The card's rect in its window, kept by [peekAnchor] (a plain field: it changes with every scroll frame). */
    var bounds: Rect = Rect.Zero

    /** Where the card's window starts on the screen, read when the peek opens. */
    var originX: Int = 0
    var originY: Int = 0
}

@Composable
internal fun rememberPeekHost(
    source: PeekSource?,
    shape: Shape,
): PeekHost = remember(source) { PeekHost(source, shape) }

/**
 * Keeps the card's rect. Put it OUTSIDE the press scale of the card (first in the chain), so the squash of the press that starts a
 * long press is not in the rect.
 */
internal fun Modifier.peekAnchor(host: PeekHost): Modifier =
    if (host.source == null) {
        this
    } else {
        this.onGloballyPositioned { host.bounds = it.boundsInWindow() }
    }

/**
 * The long press of a card: the peek when the card has one and its place is known, the old long press ([fallback]) otherwise. The
 * peek gives the same light haptic the long press gave.
 */
@Composable
internal fun rememberPeekLongClick(
    host: PeekHost,
    fallback: () -> Unit,
): () -> Unit {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val latestFallback by rememberUpdatedState(fallback)
    return remember(host) {
        {
            if (host.source == null || host.bounds.width <= 0f || host.bounds.height <= 0f) {
                latestFallback()
            } else {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                host.originX = location[0]
                host.originY = location[1]
                host.open = true
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// The overlay
// ---------------------------------------------------------------------------------------------------------------------

private const val PEEK_LIFT = 0.06f
private val PeekMenuWidth = 230.dp
private val PeekRowHeight = 46.dp
private val PeekMenuPadding = 6.dp
private val PeekMenuShape = RoundedCornerShape(20.dp)

/**
 * The peek itself, drawn in a full-screen window above the app: the screen dims, the card lifts (scale 1 -> 1.06, a soft
 * shadow) at its own place, and the menu opens next to it (below when it fits, else above), growing out of the card's edge.
 * [card] draws the SAME card again at the lifted place; [onCardClick] is the card's click (a tap on the lifted card does it and
 * closes the peek). Also hosts the add-to-playlist dialog the "add to playlist" row asks for.
 */
@Composable
internal fun PeekOverlay(
    host: PeekHost,
    onCardClick: () -> Unit,
    card: @Composable (Modifier, () -> Unit) -> Unit,
) {
    val source = host.source ?: return
    if (host.showAddToPlaylist) source.addToPlaylist { host.showAddToPlaylist = false }
    if (host.open) PeekDialog(host, source, onCardClick, card)
}

@Composable
private fun PeekDialog(
    host: PeekHost,
    source: PeekSource,
    onCardClick: () -> Unit,
    card: @Composable (Modifier, () -> Unit) -> Unit,
) {
    val ios = LocalIosMotion.current
    val glass = isGlassStyle
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var container by remember { mutableStateOf(IntSize.Zero) }
    var dialogOrigin by remember { mutableStateOf(IntOffset.Zero) }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, spring(dampingRatio = if (ios) 0.66f else 0.8f, stiffness = 420f))
    }

    // closes with the opening played backwards, then runs [after] (what a row or a tap on the card asked for)
    fun close(after: (() -> Unit)? = null) {
        if (closing) return
        closing = true
        scope.launch {
            progress.animateTo(0f, tween(150))
            host.open = false
            after?.invoke()
        }
    }

    val rows = source.actions { host.showAddToPlaylist = true } + PeekAction(R.drawable.more_horiz, stringResource(R.string.peek_more), source.onMore)

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        SideEffect {
            // our own scrim, not the window's dim
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setDimAmount(0f)
        }
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { container = it }
                .onGloballyPositioned {
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    dialogOrigin = IntOffset(location[0], location[1])
                },
        ) {
            // ---- the dim
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() },
            )

            // ---- the card, lifted at its own place
            val b = host.bounds
            val cardLeft = b.left + host.originX - dialogOrigin.x
            val cardTop = b.top + host.originY - dialogOrigin.y
            Box(
                Modifier
                    .offset { IntOffset(cardLeft.roundToInt(), cardTop.roundToInt()) }
                    .size(with(density) { b.width.toDp() }, with(density) { b.height.toDp() })
                    .graphicsLayer {
                        val t = progress.value
                        val s = 1f + PEEK_LIFT * t
                        scaleX = s
                        scaleY = s
                        shadowElevation = 28.dp.toPx() * t.coerceIn(0f, 1f)
                        shape = host.shape
                        clip = false
                        ambientShadowColor = Color.Black
                        spotShadowColor = Color.Black
                    },
            ) {
                card(Modifier) { close(onCardClick) }
            }

            // ---- the menu, next to the card
            val menuWidth = with(density) { PeekMenuWidth.toPx() }
            val menuHeight = with(density) { (PeekRowHeight * rows.size + PeekMenuPadding * 2).toPx() }
            val margin = with(density) { 16.dp.toPx() }
            val gap = with(density) { 10.dp.toPx() }
            val topInset = WindowInsets.systemBars.getTop(density).toFloat()
            val bottomInset = WindowInsets.systemBars.getBottom(density).toFloat()
            val extraY = b.height * PEEK_LIFT / 2f
            val belowTop = cardTop + b.height + extraY + gap
            val fitsBelow = belowTop + menuHeight <= container.height - bottomInset - margin
            val rawTop = if (fitsBelow) belowTop else cardTop - extraY - gap - menuHeight
            val minTop = topInset + margin
            val maxTop = (container.height - bottomInset - margin - menuHeight).coerceAtLeast(minTop)
            val menuTop = rawTop.coerceIn(minTop, maxTop)
            val menuLeft = cardLeft.coerceIn(margin, (container.width - menuWidth - margin).coerceAtLeast(margin))
            val pivotX = ((cardLeft + b.width / 2f - menuLeft) / menuWidth).coerceIn(0f, 1f)
            val pivotY = if (fitsBelow) 0f else 1f
            val ink = MaterialTheme.colorScheme.onSurface
            Column(
                Modifier
                    .offset { IntOffset(menuLeft.roundToInt(), menuTop.roundToInt()) }
                    .width(PeekMenuWidth)
                    .graphicsLayer {
                        val t = progress.value
                        val s = 0.6f + 0.4f * t
                        scaleX = s
                        scaleY = s
                        alpha = ((t - 0.05f) / 0.5f).coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(pivotX, pivotY)
                        shadowElevation = 16.dp.toPx() * t.coerceIn(0f, 1f)
                        shape = PeekMenuShape
                        clip = false
                    }.clip(PeekMenuShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(0.5.dp, ink.copy(alpha = 0.10f), PeekMenuShape)
                    .padding(vertical = PeekMenuPadding),
            ) {
                rows.forEachIndexed { index, action ->
                    PeekRow(
                        action = action,
                        glass = glass,
                        divider = index > 0,
                        modifier = Modifier.iosAppear(index),
                        onClick = { close(action.onClick) },
                    )
                }
            }
        }
    }
}

/** One row: iOS context-menu style in the glass look (title left, icon right), icon left in Datar. Pressed = a soft ink fill. */
@Composable
private fun PeekRow(
    action: PeekAction,
    glass: Boolean,
    divider: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .fillMaxWidth()
            .height(PeekRowHeight)
            .background(if (pressed) ink.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        if (divider) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(ink.copy(alpha = 0.12f)),
            )
        }
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!glass) {
                Icon(painter = painterResource(action.icon), contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
            }
            Text(
                text = action.label,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (glass) {
                Spacer(Modifier.width(12.dp))
                Icon(painter = painterResource(action.icon), contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// The quick actions, wired to the calls the song menus use (YouTubeSongMenu.kt, SongMenu.kt)
// ---------------------------------------------------------------------------------------------------------------------

/** The peek of a YouTube song: the same calls as YouTubeSongMenu. [onMore] opens that full menu. */
internal fun ytSongPeekSource(
    song: SongItem,
    onMore: () -> Unit,
): PeekSource =
    PeekSource(
        actions = { onAddToPlaylist -> ytSongPeekActions(song, onAddToPlaylist) },
        addToPlaylist = { onDismiss ->
            val database = LocalDatabase.current
            AddToPlaylistDialog(
                isVisible = true,
                onGetSong = {
                    database.withTransaction {
                        insert(song.toMediaMetadata())
                    }
                    listOf(song.id)
                },
                onGetSongIds = { listOf(song.id) },
                onDismiss = onDismiss,
            )
        },
        onMore = onMore,
    )

@Composable
private fun ytSongPeekActions(
    song: SongItem,
    onAddToPlaylist: () -> Unit,
): List<PeekAction> {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current ?: return emptyList()
    val syncUtils = LocalSyncUtils.current
    val listenTogether = LocalListenTogetherManager.current
    val librarySong by database.song(song.id).collectAsStateWithLifecycle(initialValue = null)
    val isGuest = listenTogether?.isInRoom == true && !listenTogether.isHost
    val liked = librarySong?.song?.liked == true
    val playNext = stringResource(R.string.play_next)
    val addToQueue = stringResource(R.string.add_to_queue)
    val addToPlaylist = stringResource(R.string.add_to_playlist)
    val share = stringResource(R.string.share)
    val viewArtist = stringResource(R.string.view_artist)
    val likeLabel = stringResource(if (liked) R.string.peek_unlike else R.string.peek_like)
    // a podcast episode has no artist page and its like is "save for later" (a different action): not offered here
    val isPodcast = song.album?.let { !it.id.startsWith("MPREb_") } ?: false
    val artistIds = song.artists.mapNotNull { it.id }

    return buildList {
        if (!isGuest) {
            add(
                PeekAction(R.drawable.playlist_play, playNext) {
                    playerConnection.playNext(song.copy(thumbnail = song.thumbnail.resize(544, 544)).toMediaItem())
                },
            )
            add(PeekAction(R.drawable.queue_music, addToQueue) { playerConnection.addToQueue(song.toMediaItem()) })
        }
        add(PeekAction(R.drawable.playlist_add, addToPlaylist, onAddToPlaylist))
        if (!song.isEpisode) {
            add(
                PeekAction(if (liked) R.drawable.favorite else R.drawable.favorite_border, likeLabel) {
                    database.transaction {
                        val current = librarySong
                        val s: SongEntity
                        if (current == null) {
                            insert(song.toMediaMetadata(), SongEntity::toggleLike)
                            s = song.toMediaMetadata().toSongEntity().let(SongEntity::toggleLike)
                        } else {
                            s = current.song.toggleLike()
                            update(s)
                        }
                        syncUtils.likeSong(s)
                    }
                },
            )
        }
        add(
            PeekAction(R.drawable.share, share) {
                val intent =
                    Intent().apply {
                        action = Intent.ACTION_SEND
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, song.shareLink)
                    }
                context.startActivity(Intent.createChooser(intent, null))
            },
        )
        if (artistIds.size == 1 && !isPodcast) {
            add(PeekAction(R.drawable.artist, viewArtist) { navController.navigate("artist/${artistIds[0]}") })
        }
    }
}

/** The peek of a song of the library: the same calls as SongMenu. [onMore] opens that full menu. */
internal fun localSongPeekSource(
    song: Song,
    onMore: () -> Unit,
): PeekSource =
    PeekSource(
        actions = { onAddToPlaylist -> localSongPeekActions(song, onAddToPlaylist) },
        addToPlaylist = { onDismiss ->
            AddToPlaylistDialog(
                isVisible = true,
                onGetSong = { listOf(song.id) },
                onGetSongIds = { listOf(song.id) },
                onDismiss = onDismiss,
            )
        },
        onMore = onMore,
    )

@Composable
private fun localSongPeekActions(
    original: Song,
    onAddToPlaylist: () -> Unit,
): List<PeekAction> {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current ?: return emptyList()
    val syncUtils = LocalSyncUtils.current
    val listenTogether = LocalListenTogetherManager.current
    val songState = database.song(original.id).collectAsStateWithLifecycle(initialValue = original)
    val song = songState.value ?: original
    val isGuest = listenTogether?.isInRoom == true && !listenTogether.isHost
    val liked = song.song.liked
    val playNext = stringResource(R.string.play_next)
    val addToQueue = stringResource(R.string.add_to_queue)
    val addToPlaylist = stringResource(R.string.add_to_playlist)
    val share = stringResource(R.string.share)
    val viewArtist = stringResource(R.string.view_artist)
    val likeLabel = stringResource(if (liked) R.string.peek_unlike else R.string.peek_like)

    return buildList {
        if (!isGuest) {
            add(PeekAction(R.drawable.playlist_play, playNext) { playerConnection.playNext(song.toMediaItem()) })
            add(PeekAction(R.drawable.queue_music, addToQueue) { playerConnection.addToQueue(song.toMediaItem()) })
        }
        add(PeekAction(R.drawable.playlist_add, addToPlaylist, onAddToPlaylist))
        if (!song.song.isEpisode) {
            add(
                PeekAction(if (liked) R.drawable.favorite else R.drawable.favorite_border, likeLabel) {
                    val s = song.song.toggleLike()
                    database.query { update(s) }
                    syncUtils.likeSong(s)
                },
            )
        }
        // a song from the phone has no YouTube link
        if (!song.isLocalSong) {
            add(
                PeekAction(R.drawable.share, share) {
                    val intent =
                        Intent().apply {
                            action = Intent.ACTION_SEND
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${song.id}")
                        }
                    context.startActivity(Intent.createChooser(intent, null))
                },
            )
        }
        if (!song.song.isEpisode && song.orderedArtists.size == 1) {
            add(PeekAction(R.drawable.artist, viewArtist) { navController.navigate("artist/${song.orderedArtists[0].id}") })
        }
    }
}
