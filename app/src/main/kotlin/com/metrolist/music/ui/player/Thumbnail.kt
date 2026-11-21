/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.metrolist.music.ui.component.glassRim
import com.metrolist.music.ui.component.glass
import com.metrolist.music.ui.theme.isGlassStyle
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import com.metrolist.music.ui.component.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.HidePlayerThumbnailKey
import com.metrolist.music.constants.PlayerBackgroundStyle
import com.metrolist.music.constants.PlayerBackgroundStyleKey
import com.metrolist.music.constants.PlayerHorizontalPadding
import com.metrolist.music.constants.SeekExtraSeconds
import com.metrolist.music.constants.SwipeThumbnailKey
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.listentogether.RoomRole
import com.metrolist.music.ui.component.CastButton
import com.metrolist.music.ui.component.MicroLabel
import com.metrolist.music.ui.component.editorialPanel
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.font.FontStyle
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.delay

/** Editorial 20dp screen margin around the cover panel. */
private val ThumbnailSidePadding = 20.dp

/**
 * Pre-calculated thumbnail dimensions to avoid repeated calculations during recomposition.
 * All values are computed once and cached.
 */
@Immutable
data class ThumbnailDimensions(
    val itemWidth: Dp,
    val containerSize: Dp,
    val thumbnailSize: Dp,
    val cornerRadius: Dp
)

/**
 * Cached media items data to prevent recalculation on every recomposition.
 */
@Immutable
data class MediaItemsData(
    val items: List<MediaItem>,
    val currentIndex: Int
)

/**
 * Calculate thumbnail dimensions once based on container size.
 * This function is marked as @Stable to indicate it produces stable results.
 * In landscape mode, uses the smaller dimension (height) to ensure square thumbnail fits.
 */
@Stable
private fun calculateThumbnailDimensions(
    containerWidth: Dp,
    containerHeight: Dp = containerWidth,
    horizontalPadding: Dp = ThumbnailSidePadding,
    cornerRadius: Dp = 16.dp,
    isLandscape: Boolean = false
): ThumbnailDimensions {
    // In landscape, use height as the constraining dimension for a square thumbnail
    val effectiveSize = if (isLandscape) {
        minOf(containerWidth, containerHeight) - (horizontalPadding * 2)
    } else {
        minOf(containerWidth - (horizontalPadding * 2), (containerHeight - 8.dp).coerceAtLeast(0.dp))
    }
    return ThumbnailDimensions(
        itemWidth = containerWidth,
        containerSize = containerWidth,
        thumbnailSize = effectiveSize,
        cornerRadius = cornerRadius
    )
}

/**
 * Get media items for the thumbnail carousel.
 * Calculates previous, current, and next items based on shuffle mode.
 */
@Stable
private fun getMediaItems(
    player: Player,
    swipeThumbnail: Boolean
): MediaItemsData {
    val timeline = player.currentTimeline
    val currentIndex = player.currentMediaItemIndex
    val shuffleModeEnabled = player.shuffleModeEnabled
    
    val currentMediaItem = try {
        player.currentMediaItem
    } catch (e: Exception) { null }
    
    val previousMediaItem = if (swipeThumbnail && !timeline.isEmpty) {
        val previousIndex = timeline.getPreviousWindowIndex(
            currentIndex,
            Player.REPEAT_MODE_OFF,
            shuffleModeEnabled
        )
        if (previousIndex != C.INDEX_UNSET) {
            try { player.getMediaItemAt(previousIndex) } catch (e: Exception) { null }
        } else null
    } else null

    val nextMediaItem = if (swipeThumbnail && !timeline.isEmpty) {
        val nextIndex = timeline.getNextWindowIndex(
            currentIndex,
            Player.REPEAT_MODE_OFF,
            shuffleModeEnabled
        )
        if (nextIndex != C.INDEX_UNSET) {
            try { player.getMediaItemAt(nextIndex) } catch (e: Exception) { null }
        } else null
    } else null

    val items = listOfNotNull(previousMediaItem, currentMediaItem, nextMediaItem)
    // [previous, current, next] in that order, previous and next only when there are some
    val currentMediaIndex =
        when {
            currentMediaItem == null -> -1
            previousMediaItem != null -> 1
            else -> 0
        }
    
    return MediaItemsData(items, currentMediaIndex)
}

/**
 * Get text color based on player background style.
 * Computed once per background style change.
 */
@Stable
@Composable
private fun getTextColor(playerBackground: PlayerBackgroundStyle): Color {
    // Editorial look is always flat: ink on paper regardless of the legacy background preference.
    return MaterialTheme.colorScheme.onBackground
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Thumbnail(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
    isPlayerExpanded: () -> Boolean = { true },
    isLandscape: Boolean = false,
    isListenTogetherGuest: Boolean = false,
    // Full-bleed cover: pages are full-width covers that reach the screen top (see PlayerHero.kt).
    hero: Boolean = false,
) {
    val heroOn = hero && !isLandscape
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current

    // Collect states
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val error by playerConnection.error.collectAsState()
    val queueTitle by playerConnection.queueTitle.collectAsStateWithLifecycle()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsStateWithLifecycle()
    val canSkipNext by playerConnection.canSkipNext.collectAsStateWithLifecycle()

    // Preferences - computed once
    // Disable swipe for Listen Together guests
    val swipeThumbnailPref by rememberPreference(SwipeThumbnailKey, true)
    val swipeThumbnail = swipeThumbnailPref && !isListenTogetherGuest
    val hidePlayerThumbnail by rememberPreference(HidePlayerThumbnailKey, false)
    // the flying cover only runs while this cover is on screen (not in landscape, not hidden; the lyrics view removes it)
    if (!isLandscape && !hidePlayerThumbnail) {
        androidx.compose.runtime.DisposableEffect(Unit) {
            val flight = PlayerFlights.main
            flight.coverShown = true
            onDispose { flight.coverShown = false }
        }
    }
        val playerBackground by rememberEnumPreference(
        key = PlayerBackgroundStyleKey,
        defaultValue = PlayerBackgroundStyle.DEFAULT
    )
    
    // Pre-calculate text color based on background style
    val textBackgroundColor = getTextColor(playerBackground)

    // Auralys video mode (video/ package): the Audio | Video pill and the silent picture over the cover.
    val videoController = com.metrolist.music.video.rememberVideoModeController(
        playerConnection = playerConnection,
        isPlayerExpanded = isPlayerExpanded,
        mediaMetadata = mediaMetadata,
        isListenTogetherGuest = isListenTogetherGuest,
        enabled = !hidePlayerThumbnail,
    )
    val videoState by videoController.state.collectAsState()

    
    // Grid state
    val thumbnailLazyGridState = rememberLazyGridState()
    
    // Calculate media items data - memoized
    val mediaItemsData by remember(
        playerConnection.player.currentMediaItemIndex,
        playerConnection.player.shuffleModeEnabled,
        swipeThumbnail,
        mediaMetadata
    ) {
        derivedStateOf {
            getMediaItems(playerConnection.player, swipeThumbnail)
        }
    }
    
    val mediaItems = mediaItemsData.items
    val currentMediaIndex = mediaItemsData.currentIndex
    // one key per place: the song's id, and "#2" on the second time the same song is in the list
    val itemKeys =
        remember(mediaItems) {
            val seen = HashMap<String, Int>()
            mediaItems.map { item ->
                val base = item.mediaId.ifEmpty { "unknown_${item.hashCode()}" }
                val n = seen[base] ?: 0
                seen[base] = n + 1
                if (n == 0) base else "$base#$n"
            }
        }

    // Snap behavior - created once per grid state
    val thumbnailSnapLayoutInfoProvider = remember(thumbnailLazyGridState) {
        ThumbnailSnapLayoutInfoProvider(
            lazyGridState = thumbnailLazyGridState,
            positionInLayout = { layoutSize, itemSize ->
                (layoutSize / 2f - itemSize / 2f)
            },
            velocityThreshold = 500f
        )
    }

    // Current item tracking - derived state for efficiency
    val currentItem by remember { derivedStateOf { thumbnailLazyGridState.firstVisibleItemIndex } }
    val itemScrollOffset by remember { derivedStateOf { thumbnailLazyGridState.firstVisibleItemScrollOffset } }

    // Handle swipe to change song
    LaunchedEffect(itemScrollOffset) {
        if (!thumbnailLazyGridState.isScrollInProgress || !swipeThumbnail || itemScrollOffset != 0 || currentMediaIndex < 0) return@LaunchedEffect

        if (currentItem > currentMediaIndex && canSkipNext) {
            playerConnection.player.seekToNext()
        } else if (currentItem < currentMediaIndex && canSkipPrevious) {
            playerConnection.player.seekToPreviousMediaItem()
        }
    }

    // Update position when song changes
    LaunchedEffect(mediaMetadata, canSkipPrevious, canSkipNext) {
        val index = maxOf(0, currentMediaIndex)
        if (index >= 0 && index < mediaItems.size) {
            try {
                thumbnailLazyGridState.animateScrollToItem(index)
            } catch (e: Exception) {
                thumbnailLazyGridState.scrollToItem(index)
            }
        }
    }

    LaunchedEffect(playerConnection.player.currentMediaItemIndex) {
        val index = mediaItemsData.currentIndex
        if (index >= 0 && index != currentItem) {
            thumbnailLazyGridState.scrollToItem(index)
        }
    }

    // Seek effect state
    var showSeekEffect by remember { mutableStateOf(false) }
    var seekDirection by remember { mutableStateOf("") }

    Box(
        modifier = modifier
    ) {
        // Error view
        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .padding(32.dp)
                .align(Alignment.Center),
        ) {
            error?.let { playbackError ->
                PlaybackError(
                    error = playbackError,
                    retry = playerConnection.player::prepare,
                )
            }
        }

        // Main thumbnail view
        AnimatedVisibility(
            visible = error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .fillMaxSize()
                .then(if (!isLandscape && !heroOn) Modifier.statusBarsPadding() else Modifier),
        ) {
          BoxWithConstraints(Modifier.fillMaxSize()) {
            val heroAspect = if (maxHeight > 0.dp) maxWidth / maxHeight else 1f
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (isLandscape) Arrangement.Center else Arrangement.Top
            ) {
                // Now Playing header - hide in landscape mode (the full-bleed cover draws it over the image)
                if (!isLandscape && !heroOn) {
                    ThumbnailHeader(
                        queueTitle = queueTitle,
                        albumTitle = mediaMetadata?.album?.title,
                        textColor = textBackgroundColor
                    )
                }
                
                // Thumbnail content
                BoxWithConstraints(
                    contentAlignment = Alignment.Center,
                    modifier = if (isLandscape) {
                        Modifier.weight(1f, false)
                    } else {
                        Modifier.fillMaxSize()
                    }
                ) {
                    // Calculate dimensions once per size change, considering landscape mode
                    val dimensions = remember(maxWidth, maxHeight, isLandscape) {
                        calculateThumbnailDimensions(
                            containerWidth = maxWidth,
                            containerHeight = maxHeight,
                            isLandscape = isLandscape
                        )
                    }

                    // Remember the onSeek callback to prevent recomposition
                    val onSeekCallback = remember {
                        { direction: String, showEffect: Boolean ->
                            seekDirection = direction
                            showSeekEffect = showEffect
                        }
                    }
                    
                    val animatedArtworkSlot: @Composable () -> Unit = {
                        AnimatedArtworkOverlay(mediaMetadata, isPlayerExpanded, Modifier.fillMaxSize())
                    }
                    // Video mode: the blurred cover is the overlay of the cover page (it fades with the cover), the picture is
                    // drawn above the fade (not faded), centred; on a square card both sit in the card
                    val videoBackdropSlot: @Composable () -> Unit = {
                        com.metrolist.music.video.VideoModeBackdrop(videoController, mediaMetadata?.thumbnailUrl)
                    }
                    val videoPictureSlot: @Composable () -> Unit = {
                        com.metrolist.music.video.VideoModePicture(videoController, hero = true)
                    }
                    val videoCardSlot: @Composable () -> Unit = {
                        com.metrolist.music.video.VideoModeSurface(videoController, mediaMetadata?.thumbnailUrl)
                    }
                    val videoWanted = videoState.pillVisible && videoState.mode == com.metrolist.music.video.VideoMode.VIDEO
                    // one decoder at a time: the motion artwork is not drawn while the video is wanted
                    val coverOverlaySlot =
                        when {
                            !videoWanted -> animatedArtworkSlot
                            heroOn -> videoBackdropSlot
                            else -> videoCardSlot
                        }
                    val coverForegroundSlot = if (videoWanted && heroOn) videoPictureSlot else null

                    // Derive scroll enabled state to prevent unnecessary recomposition
                    val isScrollEnabled by remember(swipeThumbnail) {
                        derivedStateOf { swipeThumbnail && isPlayerExpanded() }
                    }
                    
                    LazyHorizontalGrid(
                        state = thumbnailLazyGridState,
                        rows = GridCells.Fixed(1),
                        flingBehavior = rememberSnapFlingBehavior(thumbnailSnapLayoutInfoProvider),
                        userScrollEnabled = isScrollEnabled,
                        modifier = if (isLandscape) {
                            Modifier.size(dimensions.thumbnailSize + (ThumbnailSidePadding * 2))
                        } else {
                            Modifier.fillMaxSize()
                        }
                    ) {
                        itemsIndexed(
                            items = mediaItems,
                            key = { index, item -> itemKeys.getOrElse(index) { "place_$index" } },
                        ) { index, item ->
                            ThumbnailItem(
                                isCurrent = index == currentMediaIndex,
                                item = item,
                                dimensions = dimensions,
                                hidePlayerThumbnail = hidePlayerThumbnail,
                                textBackgroundColor = textBackgroundColor,
                                layoutDirection = layoutDirection,
                                onSeek = onSeekCallback,
                                playerConnection = playerConnection,
                                context = context,
                                isLandscape = isLandscape,
                                isListenTogetherGuest = isListenTogetherGuest,
                                currentMediaId = mediaMetadata?.id,
                                currentMediaThumbnail = mediaMetadata?.thumbnailUrl,
                                // Auralys: animated artwork over the current cover (off unless enabled).
                                animatedArtwork = if (index == currentMediaIndex && item.mediaId == mediaMetadata?.id) coverOverlaySlot else null,
                                foreground = if (index == currentMediaIndex && item.mediaId == mediaMetadata?.id) coverForegroundSlot else null,
                                hero = heroOn,
                            )
                        }
                    }
                }
            }
            if (heroOn) {
                HeroStatusScrim(
                    artworkUri = mediaMetadata?.thumbnailUrl,
                    boxAspect = heroAspect,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                ThumbnailHeader(
                    queueTitle = queueTitle,
                    albumTitle = mediaMetadata?.album?.title,
                    textColor = Color.White,
                    onCover = true,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                )
            }
          }
        }

        com.metrolist.music.video.VideoModePill(
            controller = videoController,
            hero = heroOn,
            modifier = Modifier.align(Alignment.BottomCenter),
            hidden = error != null,
        )

        // Seek effect
        LaunchedEffect(showSeekEffect) {
            if (showSeekEffect) {
                delay(1000)
                showSeekEffect = false
            }
        }

        AnimatedVisibility(
            visible = showSeekEffect,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            SeekEffectOverlay(seekDirection = seekDirection)
        }
    }
}

/**
 * Editorial header: tracked kicker ("NOW PLAYING") over an italic serif context title,
 * with the cast button on the trailing edge.
 */
@Composable
private fun ThumbnailHeader(
    queueTitle: String?,
    albumTitle: String?,
    textColor: Color,
    modifier: Modifier = Modifier,
    // Drawn over the full-bleed cover: white text with a soft shadow.
    onCover: Boolean = false,
) {
    val listenTogetherManager = LocalListenTogetherManager.current
    val listenTogetherRoleState = listenTogetherManager?.role?.collectAsStateWithLifecycle(initialValue = RoomRole.NONE)
    val kicker = when (listenTogetherRoleState?.value) {
        RoomRole.HOST -> "Hosting Listen Together"
        RoomRole.GUEST -> "Listening Together"
        else -> stringResource(R.string.now_playing)
    }
    val playingFrom = queueTitle ?: albumTitle
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = ThumbnailSidePadding, end = ThumbnailSidePadding - 8.dp, top = 8.dp, bottom = 12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            MicroLabel(text = kicker, color = if (onCover) textColor else MaterialTheme.colorScheme.primary)
            if (!playingFrom.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = playingFrom,
                    color = if (onCover) textColor else MaterialTheme.colorScheme.onSurface,
                    style = if (onCover) {
                        MaterialTheme.typography.titleLarge.copy(
                            shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 1f), 6f),
                        )
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                    fontStyle = FontStyle.Italic,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee()
                )
            }
        }
        CastButton(tintColor = if (onCover) textColor else MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Individual thumbnail item in the carousel.
 */
@Composable
private fun ThumbnailItem(
    item: MediaItem,
    dimensions: ThumbnailDimensions,
    hidePlayerThumbnail: Boolean,
    textBackgroundColor: Color,
    layoutDirection: LayoutDirection,
    onSeek: (String, Boolean) -> Unit,
    playerConnection: com.metrolist.music.playback.PlayerConnection,
    context: android.content.Context,
    isLandscape: Boolean = false,
    isListenTogetherGuest: Boolean = false,
    currentMediaId: String? = null,
    currentMediaThumbnail: String? = null,
    animatedArtwork: (@Composable () -> Unit)? = null,
    foreground: (@Composable () -> Unit)? = null,
    hero: Boolean = false,
    // the place of the current song in the list (the same song can also be the previous or the next one)
    isCurrent: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val incrementalSeekSkipEnabled by rememberPreference(SeekExtraSeconds, defaultValue = false)
    var skipMultiplier by remember { mutableIntStateOf(1) }
    var lastTapTime by remember { mutableLongStateOf(0L) }

    Box(
        modifier = modifier
            .then(
                if (isLandscape) {
                    Modifier.size(dimensions.thumbnailSize + (ThumbnailSidePadding * 2))
                } else {
                    Modifier
                        .width(dimensions.itemWidth)
                        .fillMaxSize()
                }
            )
            .then(if (hero) Modifier else Modifier.padding(horizontal = ThumbnailSidePadding))
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        if (isListenTogetherGuest) return@detectTapGestures

                        val currentPosition = playerConnection.player.currentPosition
                        val duration = playerConnection.player.duration

                        val now = System.currentTimeMillis()
                        if (incrementalSeekSkipEnabled && now - lastTapTime < 1000) {
                            skipMultiplier++
                        } else {
                            skipMultiplier = 1
                        }
                        lastTapTime = now

                        val skipAmount = 5000 * skipMultiplier

                        val isLeftSide = (layoutDirection == LayoutDirection.Ltr && offset.x < size.width / 2) ||
                                (layoutDirection == LayoutDirection.Rtl && offset.x > size.width / 2)

                        if (isLeftSide) {
                            playerConnection.player.seekTo((currentPosition - skipAmount).coerceAtLeast(0))
                            onSeek(context.getString(R.string.seek_backward_dynamic, skipAmount / 1000), true)
                        } else {
                            playerConnection.player.seekTo((currentPosition + skipAmount).coerceAtMost(duration))
                            onSeek(context.getString(R.string.seek_forward_dynamic, skipAmount / 1000), true)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
      if (hero) {
        HeroCoverPage(
            artworkUri = if (item.mediaId == currentMediaId && !currentMediaThumbnail.isNullOrBlank()) {
                currentMediaThumbnail
            } else {
                item.mediaMetadata.artworkUri?.toString()
            },
            modifier = if (isCurrent && item.mediaId == currentMediaId) Modifier.flightBig(radiusDp = 0f, masked = true) else Modifier,
            overlay = animatedArtwork,
            foreground = foreground,
        )
      } else {
        Box(
            modifier = Modifier
                .size(dimensions.thumbnailSize)
                .then(if (isCurrent && item.mediaId == currentMediaId) Modifier.flightBigAlpha() else Modifier)
                .then(
                    if (isGlassStyle) {
                        Modifier
                            .shadow(
                                elevation = 28.dp,
                                shape = RoundedCornerShape(32.dp),
                                ambientColor = Color.Black.copy(alpha = 0.45f),
                                spotColor = Color.Black.copy(alpha = 0.45f),
                            )
                            .clip(RoundedCornerShape(32.dp))
                            .border(1.dp, glassRim(), RoundedCornerShape(32.dp))
                    } else {
                        val sleeveInk = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        val sleeveRadius = dimensions.cornerRadius
                        Modifier
                            .drawBehind {
                                drawRoundRect(
                                    color = sleeveInk,
                                    topLeft = Offset(0f, 2.dp.toPx()),
                                    size = size,
                                    cornerRadius = CornerRadius(sleeveRadius.toPx()),
                                )
                            }
                            .editorialPanel(RoundedCornerShape(dimensions.cornerRadius))
                            .padding(12.dp)
                    },
                )
        ) {
          Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isCurrent && item.mediaId == currentMediaId) Modifier.flightBigBounds(radiusDp = if (isGlassStyle) 32f else 12f, masked = false) else Modifier)
                .then(if (isGlassStyle) Modifier else Modifier.clip(RoundedCornerShape(12.dp)))
          ) {
            if (hidePlayerThumbnail) {
                HiddenThumbnailPlaceholder(textBackgroundColor = textBackgroundColor)
            } else {
                val artworkUriToUse = if (item.mediaId == currentMediaId && !currentMediaThumbnail.isNullOrBlank()) {
                    currentMediaThumbnail
                } else {
                    item.mediaMetadata.artworkUri?.toString()
                }

                ThumbnailImage(artworkUri = artworkUriToUse)
                animatedArtwork?.invoke()
            }
            
            // Landscape has no header row, keep the cast button reachable on the panel
            if (isLandscape) {
                CastButton(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    tintColor = textBackgroundColor
                )
            }
          }
        }
      }
    }
}

/**
 * Placeholder shown when thumbnail is hidden.
 */
@Composable
private fun HiddenThumbnailPlaceholder(
    textBackgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.small_icon),
            contentDescription = stringResource(R.string.hide_player_thumbnail),
            tint = textBackgroundColor.copy(alpha = 0.7f),
            modifier = Modifier.size(120.dp)
        )
    }
}

/**
 * Actual thumbnail image. It always fills the card (centre crop): 16:9 video thumbnails
 * used to be letterboxed inside the square with grey bars (idea from BitChord, which always crops).
 * Square art is unaffected. The card behind the image is transparent, no grey fill.
 */
@Composable
private fun ThumbnailImage(
    artworkUri: String?,
    modifier: Modifier = Modifier
) {
    // Settles from slightly zoomed-in so the card edge never shows through (the parent clips).
    val settle = remember(artworkUri) { androidx.compose.animation.core.Animatable(1.03f) }
    LaunchedEffect(artworkUri) {
        settle.animateTo(1f, androidx.compose.animation.core.tween(220))
    }
    val context = LocalContext.current
    val request = remember(artworkUri) {
        ImageRequest.Builder(context)
            .data(artworkUri)
            .crossfade(180)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = settle.value
                scaleY = settle.value
            }
    )
}

/**
 * Seek effect overlay showing seek direction.
 */
@Composable
private fun SeekEffectOverlay(
    seekDirection: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = seekDirection,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = modifier
            .glass(RoundedCornerShape(if (isGlassStyle) 16.dp else 8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}
