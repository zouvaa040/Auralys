/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import androidx.activity.compose.BackHandler
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.metrolist.music.ui.component.IconButton
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.MutableState
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.AlertDialog
import com.metrolist.music.ui.component.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.metrolist.music.ui.component.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Player.STATE_ENDED
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import com.metrolist.music.LocalNavController
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.CropAlbumArtDefault
import com.metrolist.music.constants.CropAlbumArtKey
import com.metrolist.music.constants.MiniPlayerHeight
import com.metrolist.music.constants.PlayerHeroCoverKey
import com.metrolist.music.constants.DarkModeKey
import com.metrolist.music.constants.HidePlayerThumbnailKey
import com.metrolist.music.constants.HideStatusBarOnFullscreenKey
import com.metrolist.music.constants.KeepScreenOn
import com.metrolist.music.constants.PlayerBackgroundStyle
import com.metrolist.music.constants.PlayerBackgroundStyleKey
import com.metrolist.music.constants.PlayerButtonsStyle
import com.metrolist.music.constants.PlayerButtonsStyleKey
import com.metrolist.music.constants.PlayerHorizontalPadding
import com.metrolist.music.constants.QueuePeekHeight
import com.metrolist.music.constants.SleepTimerDefaultKey
import com.metrolist.music.constants.SleepTimerFadeOutKey
import com.metrolist.music.constants.SleepTimerStopAfterCurrentSongKey
import com.metrolist.music.constants.SliderStyle
import com.metrolist.music.constants.SliderStyleKey
import com.metrolist.music.constants.SquigglySliderKey
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.constants.UseNewPlayerDesignKey
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.togglePlayPause
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.listentogether.RoomRole
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.component.BottomSheet
import com.metrolist.music.ui.component.BottomSheetState
import com.metrolist.music.ui.component.LocalBottomSheetPageState
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.LambdaPlayheadSource
import com.metrolist.music.ui.component.Lyrics
import com.metrolist.music.ui.component.LyricsPlayheadSource
import com.metrolist.music.ui.component.PlayerSliderTrack
import com.metrolist.music.ui.component.ResizableIconButton
import com.metrolist.music.ui.component.SquigglySlider
import com.metrolist.music.ui.component.sheetReveal
import com.metrolist.music.ui.component.pressScale
import com.metrolist.music.ui.component.glass
import com.metrolist.music.ui.component.hairlineTop
import com.metrolist.music.ui.component.MicroLabel
import androidx.compose.animation.core.Spring
import com.metrolist.music.ui.theme.isGlassStyle
import com.metrolist.music.ui.component.popWhen
import com.metrolist.music.ui.component.WavySlider
import com.metrolist.music.ui.component.rememberBottomSheetState
import com.metrolist.music.ui.menu.PlayerMenu
import com.metrolist.music.ui.screens.settings.DarkMode
import com.metrolist.music.ui.theme.PlayerColorExtractor
import com.metrolist.music.ui.theme.PlayerSliderColors
import com.metrolist.music.ui.utils.ShowMediaInfo
import com.metrolist.music.ui.utils.ShowOffsetDialog
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.safeDataStoreEdit
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt
import com.metrolist.music.ui.component.Icon as MIcon
import com.metrolist.music.constants.SleepTimerDefaultKey
import com.metrolist.music.constants.SleepTimerFadeOutKey
import com.metrolist.music.constants.SleepTimerStopAfterCurrentSongKey


/** Editorial 20dp screen margin for the full player. */
private val PlayerSidePadding = 20.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetPlayer(
    state: BottomSheetState,
    navController: NavController,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
) {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val menuState = LocalMenuState.current
    val sleepTimerDefaultSetTemplate = stringResource(R.string.sleep_timer_default_set)
    val copiedTitleStr = stringResource(R.string.copied_title)
    val copiedArtistStr = stringResource(R.string.copied_artist)
    val bottomSheetPageState = LocalBottomSheetPageState.current
    val playerConnection = LocalPlayerConnection.current ?: return

    val (useNewPlayerDesign, onUseNewPlayerDesignChange) =
        rememberPreference(
            UseNewPlayerDesignKey,
            defaultValue = true,
        )
    val (hidePlayerThumbnail, onHidePlayerThumbnailChange) = rememberPreference(HidePlayerThumbnailKey, false)
    val (hideStatusBarOnFullscreen) = rememberPreference(HideStatusBarOnFullscreenKey, false)
    val cropAlbumArt by rememberPreference(CropAlbumArtKey, CropAlbumArtDefault)

    var showInlineLyrics by rememberSaveable {
        mutableStateOf(false)
    }

    // Full-bleed cover (setting "Sampul layar penuh"): portrait only, never with a hidden cover.
    val heroCoverPref by rememberPreference(PlayerHeroCoverKey, true)
    val heroLayout =
        heroCoverPref && !hidePlayerThumbnail &&
            LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    val heroActive = heroLayout && !showInlineLyrics

    var isFullScreen by rememberSaveable {
        mutableStateOf(false)
    }

    val playerBackground = PlayerBackgroundStyle.DEFAULT
    val playerButtonsStyle = PlayerButtonsStyle.PRIMARY

    val glassUi = isGlassStyle
    val isSystemInDarkTheme = isSystemInDarkTheme()
    val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    val useDarkTheme =
        remember(darkTheme, isSystemInDarkTheme) {
            if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
        }

    val shouldUseDarkButtonColors =
        remember(playerBackground, useDarkTheme) {
            when (playerBackground) {
                PlayerBackgroundStyle.BLUR, PlayerBackgroundStyle.GRADIENT -> true
                PlayerBackgroundStyle.DEFAULT -> useDarkTheme
            }
        }

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val isKeepScreenOn by rememberPreference(KeepScreenOn, false)
    val keepScreenOn = isPlaying && isKeepScreenOn

    DisposableEffect(playerBackground, state.isExpanded, useDarkTheme, keepScreenOn, isFullScreen, hideStatusBarOnFullscreen, heroActive) {
        val window = (context as? android.app.Activity)?.window
        if (window != null && state.isExpanded) {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)

            when (playerBackground) {
                PlayerBackgroundStyle.BLUR, PlayerBackgroundStyle.GRADIENT -> {
                    insetsController.isAppearanceLightStatusBars = false
                }

                PlayerBackgroundStyle.DEFAULT -> {
                    // The full-bleed cover has a dark scrim at the top: light icons whatever the theme.
                    insetsController.isAppearanceLightStatusBars = !useDarkTheme && !heroActive
                }
            }

            if (isFullScreen && hideStatusBarOnFullscreen) {
                insetsController.hide(WindowInsetsCompat.Type.statusBars())
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                insetsController.show(WindowInsetsCompat.Type.statusBars())
            }

            if (keepScreenOn && state.isExpanded) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        onDispose {
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.isAppearanceLightStatusBars = !useDarkTheme
                insetsController.show(WindowInsetsCompat.Type.statusBars())
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    BackHandler(enabled = state.isExpanded) {
        state.collapseSoft()
    }

    val onBackgroundColor =
        when (playerBackground) {
            PlayerBackgroundStyle.DEFAULT -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.onSurface
        }
    val useBlackBackground =
        remember(isSystemInDarkTheme, darkTheme, pureBlack) {
            val useDarkTheme =
                if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
            useDarkTheme && pureBlack
        }

    val playbackState by playerConnection.playbackState.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle(initialValue = null)
    val automix by playerConnection.service.automixItems.collectAsStateWithLifecycle()
    val repeatMode by playerConnection.repeatMode.collectAsStateWithLifecycle()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsStateWithLifecycle()
    val canSkipNext by playerConnection.canSkipNext.collectAsStateWithLifecycle()
    val isMuted by playerConnection.isMuted.collectAsStateWithLifecycle()
    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsStateWithLifecycle()

    val sliderStyle by rememberEnumPreference(SliderStyleKey, SliderStyle.DEFAULT)
    val squigglySlider by rememberPreference(SquigglySliderKey, defaultValue = false)

    // Listen Together state (reactive)
    val listenTogetherManager = LocalListenTogetherManager.current
    val listenTogetherRoleState = listenTogetherManager?.role?.collectAsStateWithLifecycle(initialValue = RoomRole.NONE)
    val isListenTogetherGuest = listenTogetherRoleState?.value == RoomRole.GUEST

    // Cast state - safely access castConnectionHandler to prevent crashes during service lifecycle changes
    val castHandler =
        remember(playerConnection) {
            try {
                playerConnection.service.castConnectionHandler
            } catch (e: Exception) {
                null
            }
        }
    val isCasting by castHandler?.isCasting?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val castPosition by castHandler?.castPosition?.collectAsStateWithLifecycle() ?: remember { mutableLongStateOf(0L) }
    val castDuration by castHandler?.castDuration?.collectAsStateWithLifecycle() ?: remember { mutableLongStateOf(0L) }
    val castIsPlaying by castHandler?.castIsPlaying?.collectAsState() ?: remember { mutableStateOf(false) }
    // while casting the lyrics follow the cast device (the phone's own player stands still then)
    val castPlayhead =
        remember(castHandler) {
            // buffering counts as standing still: the cast position does not move then
            castHandler?.let { h -> LambdaPlayheadSource(position = { h.castPosition.value }, playing = { h.castIsPlaying.value && !h.castIsBuffering.value }) }
        }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(state.isExpanded) {
        if (state.isExpanded) {
            delay(100)
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
                // Ignore if focus request fails
            }
        }
    }

    // Use Cast state when casting, otherwise local player
    val effectiveIsPlaying = if (isCasting) castIsPlaying else isPlaying

    // Use State objects for position/duration to pass to MiniPlayer without causing recomposition
    // These states persist across playback state changes to ensure continuous progress updates.
    // Seed from the player's current values so re-entering composition on resume shows the real
    // time immediately instead of flashing 0:00 until the first poll fires. runCatching guards the
    // player-not-ready race; the poll loop corrects duration if it isn't known yet.
    val positionState = remember { mutableLongStateOf(runCatching { playerConnection.player.currentPosition }.getOrDefault(0L)) }
    val durationState = remember {
        mutableLongStateOf(
            (mediaMetadata?.duration?.takeIf { it > 0 }?.toLong()?.times(1000L))
                ?: runCatching { playerConnection.player.duration }.getOrDefault(0L).coerceAtLeast(0L),
        )
    }

    // Convenience accessors for local use
    var position by positionState
    var duration by durationState

    val effectivePosition by remember {
        derivedStateOf {
            if (isCasting) {
                castPosition
            } else {
                position
            }
        }
    }

    val sliderPositionState = remember { mutableStateOf<Long?>(null) }
    var sliderPosition by sliderPositionState
    // a held slider value belongs to the song it was held on
    LaunchedEffect(mediaMetadata?.id) { sliderPosition = null }
    // Track when we last manually set position to avoid Cast overwriting it
    var lastManualSeekTime by remember { mutableLongStateOf(0L) }

    if (!canSkipNext && automix.isNotEmpty()) {
        playerConnection.service.addToQueueAutomix(automix[0], 0)
    }

    val TextBackgroundColor by animateColorAsState(
        targetValue =
            when (playerBackground) {
                PlayerBackgroundStyle.DEFAULT -> MaterialTheme.colorScheme.onBackground
                PlayerBackgroundStyle.BLUR -> Color.White
                PlayerBackgroundStyle.GRADIENT -> Color.White
            },
        label = "TextBackgroundColor",
    )

    val icBackgroundColor by animateColorAsState(
        targetValue =
            when (playerBackground) {
                PlayerBackgroundStyle.DEFAULT -> MaterialTheme.colorScheme.surface
                PlayerBackgroundStyle.BLUR -> Color.Black
                PlayerBackgroundStyle.GRADIENT -> Color.Black
            },
        label = "icBackgroundColor",
    )

    val (textButtonColor, iconButtonColor) =
        when {
            playerBackground == PlayerBackgroundStyle.BLUR ||
                playerBackground == PlayerBackgroundStyle.GRADIENT -> {
                when (playerButtonsStyle) {
                    PlayerButtonsStyle.DEFAULT -> {
                        Pair(Color.White, Color.Black)
                    }

                    PlayerButtonsStyle.PRIMARY -> {
                        Pair(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.onPrimary,
                        )
                    }

                    PlayerButtonsStyle.TERTIARY -> {
                        Pair(
                            MaterialTheme.colorScheme.tertiary,
                            MaterialTheme.colorScheme.onTertiary,
                        )
                    }
                }
            }

            else -> {
                when (playerButtonsStyle) {
                    PlayerButtonsStyle.DEFAULT -> {
                        if (useDarkTheme) {
                            Pair(Color.White, Color.Black)
                        } else {
                            Pair(Color.Black, Color.White)
                        }
                    }

                    PlayerButtonsStyle.PRIMARY -> {
                        Pair(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.onPrimary,
                        )
                    }

                    PlayerButtonsStyle.TERTIARY -> {
                        Pair(
                            MaterialTheme.colorScheme.tertiary,
                            MaterialTheme.colorScheme.onTertiary,
                        )
                    }
                }
            }
        }

    // Separate colors for Previous/Next buttons in PRIMARY/TERTIARY modes
    val (sideButtonContainerColor, sideButtonContentColor) =
        when {
            playerBackground == PlayerBackgroundStyle.BLUR ||
                playerBackground == PlayerBackgroundStyle.GRADIENT -> {
                when (playerButtonsStyle) {
                    PlayerButtonsStyle.DEFAULT -> {
                        Pair(
                            Color.White.copy(alpha = 0.2f),
                            Color.White,
                        )
                    }

                    PlayerButtonsStyle.PRIMARY -> {
                        Pair(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    PlayerButtonsStyle.TERTIARY -> {
                        Pair(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }

            else -> {
                when (playerButtonsStyle) {
                    PlayerButtonsStyle.DEFAULT -> {
                        Pair(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    PlayerButtonsStyle.PRIMARY -> {
                        Pair(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    PlayerButtonsStyle.TERTIARY -> {
                        Pair(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }

    val download by LocalDownloadUtil.current
        .getDownload(mediaMetadata?.id ?: "")
        .collectAsStateWithLifecycle(initialValue = null)

    val sleepTimerEnabled =
        remember(
            playerConnection.service.sleepTimer?.triggerTime,
            playerConnection.service.sleepTimer?.pauseWhenSongEnd,
        ) {
            playerConnection.service.sleepTimer?.isActive ?: false
        }

    var sleepTimerTimeLeft by remember {
        mutableLongStateOf(0L)
    }

    LaunchedEffect(sleepTimerEnabled) {
        if (sleepTimerEnabled) {
            while (isActive) {
                sleepTimerTimeLeft =
                    if (playerConnection.service.sleepTimer?.pauseWhenSongEnd == true) {
                        playerConnection.player.duration - playerConnection.player.currentPosition
                    } else {
                        (playerConnection.service.sleepTimer?.triggerTime ?: 0L) - System.currentTimeMillis()
                    }
                delay(1000L)
            }
        }
    }

    val scope = rememberCoroutineScope()
    var showSleepTimerDialog by remember {
        mutableStateOf(false)
    }

    val sleepTimerDefault by rememberPreference(SleepTimerDefaultKey, 30f)
    var sleepTimerValue by remember { mutableFloatStateOf(sleepTimerDefault) }
    val isAtDefault by remember {
        derivedStateOf { sleepTimerValue.roundToInt() == sleepTimerDefault.roundToInt() }
    }
    LaunchedEffect(sleepTimerDefault) { sleepTimerValue = sleepTimerDefault }
    val sleepTimerStopAfterCurrentSong by rememberPreference(SleepTimerStopAfterCurrentSongKey, false)
    val sleepTimerFadeOut by rememberPreference(SleepTimerFadeOutKey, false)


    if (showSleepTimerDialog) {
        AlertDialog(
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { showSleepTimerDialog = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.bedtime),
                    contentDescription = null,
                )
            },
            title = { Text(stringResource(R.string.sleep_timer)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSleepTimerDialog = false
                        playerConnection.service.sleepTimer?.start(
                            minute = sleepTimerValue.roundToInt(),
                            stopAfterCurrentSong = sleepTimerStopAfterCurrentSong,
                            fadeOut = sleepTimerFadeOut,
                        )
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSleepTimerDialog = false },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text =
                            pluralStringResource(
                                R.plurals.minute,
                                sleepTimerValue.roundToInt(),
                                sleepTimerValue.roundToInt(),
                            ),
                        style = MaterialTheme.typography.bodyLarge,
                    )

                    com.metrolist.music.ui.component.Slider(
                        value = sleepTimerValue,
                        onValueChange = { sleepTimerValue = it },
                        valueRange = 5f..120f,
                        steps = (120 - 5) / 5 - 1,
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (isAtDefault) {
                            FilledIconButton(
                                onClick = {
                                    scope.launch {
                                        context.safeDataStoreEdit { settings ->
                                            settings[SleepTimerDefaultKey] = sleepTimerValue
                                        }
                                    }
                                    Toast.makeText(
                                        context,
                                        String.format(sleepTimerDefaultSetTemplate, sleepTimerValue.roundToInt()),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) {
                                Text(stringResource(R.string.set_as_default))
                            }
                        } else {
                            OutlinedIconButton(
                                onClick = {
                                    scope.launch {
                                        context.safeDataStoreEdit { settings ->
                                            settings[SleepTimerDefaultKey] = sleepTimerValue
                                        }
                                    }
                                    Toast.makeText(
                                        context,
                                        String.format(sleepTimerDefaultSetTemplate, sleepTimerValue.roundToInt()),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            ) {
                                Text(stringResource(R.string.set_as_default))
                            }
                        }

                        OutlinedIconButton(
                            onClick = {
                                showSleepTimerDialog = false
                                playerConnection.service.sleepTimer?.start(minute = -1)
                            },
                        ) {
                            Text(stringResource(R.string.end_of_song))
                        }
                    }
                }
            },
        )
    }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    // Position update - only for local playback
    // When casting, we use castPosition directly to avoid sync issues
    // Use isPlaying instead of playbackState to ensure continuous updates during playback
    LaunchedEffect(isPlaying, isCasting) {
        if (!isCasting && isPlaying) {
            while (isActive) {
                delay(100) // Update more frequently for smoother progress bar
                if (sliderPosition == null) { // Only update if user isn't dragging
                    position = playerConnection.player.currentPosition
                    // Don't clobber a valid (metadata-derived) duration with 0/UNSET mid-resolve.
                    playerConnection.player.duration.takeIf { it > 0 }?.let { duration = it }
                }
            }
        }
    }

    // Also update position when playback state changes (e.g., song change, seek)
    LaunchedEffect(playbackState, mediaMetadata?.id) {
        if (!isCasting) {
            position = playerConnection.player.currentPosition
            // Prefer the song's known duration (from metadata, available instantly from the restored
            // queue) so the slider range is right even when restored paused / before the stream
            // resolves; fall back to the player's duration once it is known.
            duration = (mediaMetadata?.duration?.takeIf { it > 0 }?.toLong()?.times(1000L))
                ?: playerConnection.player.duration
        }
    }

    // Auto-switch from repeat one to repeat all when song ends naturally
    var previousMediaId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(playbackState, mediaMetadata?.id) {
        val currentId = mediaMetadata?.id

        // Only switch from REPEAT_ONE to REPEAT_ALL when playback naturally ended
        // (i.e., the player transitioned to ENDED state and then moved to next track).
        // Do NOT switch on manual skips.
        if (currentId != null &&
            currentId != previousMediaId &&
            previousMediaId != null &&
            playbackState == Player.STATE_ENDED &&
            repeatMode == Player.REPEAT_MODE_ONE &&
            !isListenTogetherGuest) {
            playerConnection.player.setRepeatMode(Player.REPEAT_MODE_ALL)
        }

        previousMediaId = currentId
    }

    // When casting, use Cast position/duration directly
    // But wait a bit after manual seeks to let Cast catch up
    LaunchedEffect(isCasting, castPosition, castDuration) {
        if (isCasting && sliderPosition == null) {
            val timeSinceManualSeek = System.currentTimeMillis() - lastManualSeekTime
            if (timeSinceManualSeek > 1500) {
                // Only update from Cast if we haven't manually seeked recently
                position = castPosition
                if (castDuration > 0) duration = castDuration
            }
        }
    }

    val dismissedBound = QueuePeekHeight + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

    val queueSheetState =
        rememberBottomSheetState(
            dismissedBound = dismissedBound,
            expandedBound = state.expandedBound,
            collapsedBound = dismissedBound + 1.dp,
            initialAnchor = 1,
        )

    val bottomSheetBackgroundColor =
        if (useBlackBackground) {
            Color.Black
        } else {
            MaterialTheme.colorScheme.surface
        }

    // Liquid Glass fold: the mini player drops next to the folded nav pill. The whole collapsed sheet drops with it,
    // so its tap area is where the mini player is drawn and not where it used to be.
    val navFoldState = com.metrolist.music.ui.component.motion.LocalNavFold.current
    val foldDensity = LocalDensity.current

    androidx.compose.runtime.SideEffect { PlayerFlights.main.sheet = state }

    BottomSheet(
        state = state,
        modifier = modifier,
        premium = true,
        overlayContent = { PlayerFlightOverlay(PlayerFlights.main, state) },
        collapsedShiftPx = {
            // The folded capsule is top-aligned in the mini player box and grows downwards (MiniPlayer.kt), so its centre is
            // (pill - box) / 2 lower than when it was centred: drop the sheet that much less to stay level with the pill.
            navFoldState?.let {
                with(foldDensity) { (it.miniShiftDp - (it.pillSizeDp - MiniPlayerHeight.value) / 2f).dp.toPx() } * it.followProgress
            } ?: 0f
        },
        background = {
            if (glassUi) {
                PlayerCoverBackground(
                    thumbnailUrl = mediaMetadata?.thumbnailUrl,
                    baseColor = bottomSheetBackgroundColor,
                    dark = useDarkTheme || useBlackBackground,
                    visibleProvider = { state.progress > 0.02f },
                )
            } else {
                Box(Modifier.fillMaxSize().background(bottomSheetBackgroundColor))
            }
        },
        onDismiss =
            if (!isListenTogetherGuest) {
                {
                    playerConnection.service.clearAutomix()
                    playerConnection.player.stop()
                    playerConnection.player.clearMediaItems()
                }
            } else {
                null
            },
        collapsedContent = {
            MiniPlayer(
                positionState = positionState,
                durationState = durationState,
                onClick = { state.expandSoft() },
            )
        },
    ) {
        val controlsContent: @Composable ColumnScope.(MediaMetadata) -> Unit = { mediaMetadata ->
            val inkColor = MaterialTheme.colorScheme.onSurface
            val mutedInk = MaterialTheme.colorScheme.onSurfaceVariant

            if (!glassUi) {
                val kicker = mediaMetadata.album?.title
                if (!kicker.isNullOrBlank()) {
                    MicroLabel(
                        text = kicker,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().padding(start = PlayerSidePadding, end = PlayerSidePadding, bottom = 4.dp),
                    )
                }
            }

            // ---- Title block: serif title + sans artist line + heart -------------------------------
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PlayerSidePadding),
            ) {
                AnimatedContent(
                    targetState = showInlineLyrics,
                    label = "ThumbnailAnimation",
                ) { showLyrics ->
                    if (showLyrics) {
                        Row {
                            if (hidePlayerThumbnail) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(ThumbnailCornerRadius))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.small_icon),
                                        contentDescription = null,
                                        modifier =
                                            Modifier
                                                .size(32.dp),
                                        tint = mutedInk,
                                    )
                                }
                            } else {
                                AsyncImage(
                                    model = mediaMetadata.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = if (cropAlbumArt) ContentScale.Crop else ContentScale.Fit,
                                    modifier =
                                        Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(ThumbnailCornerRadius)),
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                    } else {
                        Spacer(modifier = Modifier.width(0.dp))
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    AnimatedContent(
                        targetState = mediaMetadata.title,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "",
                    ) { title ->
                        Text(
                            text = title,
                            style = if (glassUi) MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.headlineMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = inkColor,
                            modifier =
                                Modifier
                                    .basicMarquee(iterations = 1, initialDelayMillis = 3000, velocity = 30.dp)
                                    .combinedClickable(
                                        enabled = true,
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                        onClick = {
                                            val albumId = mediaMetadata.album?.id
                                                ?: currentSong?.album?.id
                                                ?: currentSong?.song?.albumId
                                            if (albumId != null) {
                                                navController.navigate("album/$albumId")
                                                state.collapseSoft()
                                            }
                                        },
                                        onLongClick = {
                                            val clip = ClipData.newPlainText(copiedTitleStr, title)
                                            clipboardManager.setPrimaryClip(clip)
                                            Toast
                                                .makeText(context, copiedTitleStr, Toast.LENGTH_SHORT)
                                                .show()
                                        },
                                    ),
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (mediaMetadata.explicit) MIcon.Explicit()

                        if (mediaMetadata.artists.any { it.name.isNotBlank() }) {
                            val annotatedString =
                                buildAnnotatedString {
                                    mediaMetadata.artists.forEachIndexed { index, artist ->
                                        val tag = "artist_${artist.id.orEmpty()}"
                                        pushStringAnnotation(tag = tag, annotation = artist.id.orEmpty())
                                        withStyle(SpanStyle(color = if (glassUi) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else mutedInk)) {
                                            append(artist.name)
                                        }
                                        pop()
                                        if (index != mediaMetadata.artists.lastIndex) append(", ")
                                    }
                                }

                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .basicMarquee(iterations = 1, initialDelayMillis = 3000, velocity = 30.dp)
                                        .padding(end = 12.dp),
                            ) {
                                var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                                var clickOffset by remember { mutableStateOf<Offset?>(null) }
                                Text(
                                    text = annotatedString,
                                    style = MaterialTheme.typography.titleMedium.copy(color = if (glassUi) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else mutedInk, fontWeight = if (glassUi) FontWeight.Medium else null),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    onTextLayout = { layoutResult = it },
                                    modifier =
                                        Modifier
                                            .pointerInput(Unit) {
                                                awaitPointerEventScope {
                                                    while (true) {
                                                        val event = awaitPointerEvent()
                                                        val tapPosition = event.changes.firstOrNull()?.position
                                                        if (tapPosition != null) {
                                                            clickOffset = tapPosition
                                                        }
                                                    }
                                                }
                                            }.combinedClickable(
                                                enabled = true,
                                                indication = null,
                                                interactionSource = remember { MutableInteractionSource() },
                                                onClick = {
                                                    val tapPosition = clickOffset
                                                    val layout = layoutResult
                                                    if (tapPosition != null && layout != null) {
                                                        val offset = layout.getOffsetForPosition(tapPosition)
                                                        annotatedString
                                                            .getStringAnnotations(offset, offset)
                                                            .firstOrNull()
                                                            ?.let { ann ->
                                                                val artistId = ann.item
                                                                if (artistId.isNotBlank()) {
                                                                    navController.navigate("artist/$artistId")
                                                                    state.collapseSoft()
                                                                }
                                                            }
                                                    }
                                                },
                                                onLongClick = {
                                                    val clip =
                                                        ClipData.newPlainText(
                                                            copiedArtistStr,
                                                            annotatedString,
                                                        )
                                                    clipboardManager.setPrimaryClip(clip)
                                                    Toast
                                                        .makeText(
                                                            context,
                                                            copiedArtistStr,
                                                            Toast.LENGTH_SHORT,
                                                        ).show()
                                                },
                                            ),
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedContent(targetState = showInlineLyrics, label = "LikeButton") { showLyrics ->
                        if (showLyrics) {
                            val currentLyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { isFullScreen = !isFullScreen }) {
                                    Icon(
                                        painter = painterResource(if (isFullScreen) R.drawable.fullscreen_exit else R.drawable.fullscreen),
                                        contentDescription = null,
                                        tint = inkColor,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        menuState.show {
                                            com.metrolist.music.ui.menu.LyricsMenu(
                                                lyricsProvider = { currentLyrics },
                                                songProvider = { currentSong?.song },
                                                mediaMetadataProvider = { mediaMetadata },
                                                onDismiss = menuState::dismiss,
                                                onShowOffsetDialog = {
                                                    bottomSheetPageState.show {
                                                        ShowOffsetDialog(
                                                            songProvider = { currentSong?.song },
                                                        )
                                                    }
                                                },
                                            )
                                        }
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.more_horiz),
                                        contentDescription = null,
                                        tint = inkColor,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                        } else {
                            // For episodes, show saved state (inLibrary); for songs, show liked state
                            val isEpisode = currentSong?.song?.isEpisode == true
                            val isFavorite = if (isEpisode) currentSong?.song?.inLibrary != null else currentSong?.song?.liked == true
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = playerConnection::toggleLike,
                                    modifier = if (glassUi) Modifier.size(42.dp).pressScale(0.9f).glass(CircleShape) else Modifier.pressScale(0.9f),
                                ) {
                                    Icon(
                                        painter =
                                            painterResource(
                                                if (isFavorite) {
                                                    R.drawable.favorite
                                                } else {
                                                    R.drawable.favorite_border
                                                },
                                            ),
                                        contentDescription = null,
                                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else inkColor,
                                        modifier = Modifier.size(if (glassUi) 22.dp else 26.dp).popWhen(isFavorite),
                                    )
                                }
                                if (glassUi) Spacer(Modifier.width(8.dp))
                                PlayerMoreMenuButton(
                                    mediaMetadata = mediaMetadata,
                                    state = state,
                                    glassUi = glassUi,
                                    textButtonColor = textButtonColor,
                                    iconButtonColor = iconButtonColor,
                                )
                            }
                        }
                    }
                }
            }

            // ---- The line being sung, small, without opening the lyrics (a tap opens them) -------------
            PlayerMiniLyric(
                mediaMetadata = mediaMetadata,
                visible = !showInlineLyrics,
                ink = inkColor,
                modifier = Modifier.padding(horizontal = PlayerSidePadding),
                playhead = if (isCasting) castPlayhead else null,
                onClick = { showInlineLyrics = true },
            )
            // (the 20 dp that used to follow here is owned by PlayerMiniLyric now: it closes while the line is shown)

            // ---- Progress: isolated so 10Hz position ticks never recompose the title/controls -------
            PlayerProgressSection(
                sliderStyle = sliderStyle,
                squigglySlider = squigglySlider,
                positionProvider = { effectivePosition },
                durationProvider = { duration },
                sliderPositionState = sliderPositionState,
                enabled = !isListenTogetherGuest,
                isPlaying = effectiveIsPlaying,
                horizontalPadding = PlayerSidePadding,
                glassUi = glassUi,
                onSeek = { target ->
                    if (isCasting) {
                        castHandler?.seekTo(target)
                        lastManualSeekTime = System.currentTimeMillis()
                    } else {
                        playerConnection.player.seekTo(target)
                    }
                    position = target
                },
            )

            Spacer(Modifier.height(16.dp))

            AnimatedVisibility(
                visible = !isFullScreen,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                // ---- Transport: shuffle, prev, big terracotta play, next, repeat ---------------------
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PlayerSidePadding)
                            .hairlineTop()
                            .padding(top = if (glassUi) 0.dp else 8.dp),
                ) {
                    val guestAlpha = if (isListenTogetherGuest) 0.38f else 1f

                    val shuffleTint by animateColorAsState(
                        targetValue = if (shuffleModeEnabled) MaterialTheme.colorScheme.primary else mutedInk.copy(alpha = guestAlpha),
                        animationSpec = tween(200),
                        label = "shuffleTint",
                    )
                    val repeatTint by animateColorAsState(
                        targetValue = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else mutedInk.copy(alpha = guestAlpha),
                        animationSpec = tween(200),
                        label = "repeatTint",
                    )

                    IconButton(
                        onClick = { playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled },
                        enabled = !isListenTogetherGuest,
                        modifier = Modifier.pressScale(0.92f),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.shuffle),
                            contentDescription = stringResource(R.string.shuffle),
                            tint = shuffleTint,
                            modifier = Modifier.size(26.dp),
                        )
                    }

                    IconButton(
                        onClick = playerConnection::seekToPrevious,
                        enabled = canSkipPrevious && !isListenTogetherGuest,
                        modifier = if (glassUi) Modifier.size(64.dp).pressScale(0.86f) else Modifier.pressScale(0.92f),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.skip_previous),
                            contentDescription = null,
                            tint = if (canSkipPrevious && !isListenTogetherGuest) inkColor else inkColor.copy(alpha = 0.38f),
                            modifier = Modifier.size(if (glassUi) 42.dp else 34.dp),
                        )
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .pressScale(if (glassUi) 0.88f else 0.92f)
                                .size(if (glassUi) 72.dp else 76.dp)
                                .clip(if (glassUi) CircleShape else RoundedCornerShape(16.dp))
                                .then(if (glassUi) Modifier else Modifier.background(MaterialTheme.colorScheme.primary))
                                .clickable {
                                    if (isListenTogetherGuest) {
                                        playerConnection.toggleMute()
                                        return@clickable
                                    }
                                    if (isCasting) {
                                        if (castIsPlaying) {
                                            castHandler?.pause()
                                        } else {
                                            castHandler?.play()
                                        }
                                    } else if (playbackState == STATE_ENDED) {
                                        playerConnection.player.seekTo(0, 0)
                                        playerConnection.player.playWhenReady = true
                                    } else {
                                        playerConnection.togglePlayPause()
                                    }
                                }
                                .focusRequester(focusRequester),
                    ) {
                        val playGlyph =
                            if (isListenTogetherGuest) {
                                if (isMuted) R.drawable.volume_off else R.drawable.volume_up
                            } else if (playbackState == STATE_ENDED) {
                                R.drawable.replay
                            } else if (effectiveIsPlaying) {
                                R.drawable.pause
                            } else {
                                R.drawable.play
                            }
                        AnimatedContent(
                            targetState = playGlyph,
                            transitionSpec = {
                                (fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.8f)) togetherWith
                                    (fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.8f))
                            },
                            label = "playPauseGlyph",
                        ) { glyph ->
                        Icon(
                            painter = painterResource(glyph),
                            contentDescription =
                                if (isListenTogetherGuest) {
                                    if (isMuted) stringResource(R.string.unmute) else stringResource(R.string.mute)
                                } else {
                                    if (effectiveIsPlaying) stringResource(R.string.player_pause) else stringResource(R.string.play)
                                },
                            tint = if (glassUi) inkColor else MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(if (glassUi) 56.dp else 38.dp),
                        )
                        }
                    }

                    IconButton(
                        onClick = playerConnection::seekToNext,
                        enabled = canSkipNext && !isListenTogetherGuest,
                        modifier = if (glassUi) Modifier.size(64.dp).pressScale(0.86f) else Modifier.pressScale(0.92f),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.skip_next),
                            contentDescription = null,
                            tint = if (canSkipNext && !isListenTogetherGuest) inkColor else inkColor.copy(alpha = 0.38f),
                            modifier = Modifier.size(if (glassUi) 42.dp else 34.dp),
                        )
                    }

                    IconButton(
                        onClick = { playerConnection.player.toggleRepeatMode() },
                        enabled = !isListenTogetherGuest,
                        modifier = Modifier.pressScale(0.92f),
                    ) {
                        Icon(
                            painter =
                                painterResource(
                                    when (repeatMode) {
                                        Player.REPEAT_MODE_ONE -> R.drawable.repeat_one
                                        else -> R.drawable.repeat
                                    },
                                ),
                            contentDescription = null,
                            tint = repeatTint,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(if (glassUi) 14.dp else 10.dp))
            PlayerVolumeRow(horizontalPadding = PlayerSidePadding + 4.dp)
        }

        when (LocalConfiguration.current.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                // Calculate vertical padding like OuterTune
                val density = LocalDensity.current
                val verticalPadding =
                    max(
                        WindowInsets.systemBars.getTop(density),
                        WindowInsets.systemBars.getBottom(density),
                    )
                val verticalPaddingDp = with(density) { verticalPadding.toDp() }
                val verticalWindowInsets = WindowInsets(left = 0.dp, top = verticalPaddingDp, right = 0.dp, bottom = verticalPaddingDp)

                Row(
                    modifier =
                        Modifier
                            .windowInsetsPadding(
                                WindowInsets.systemBars.only(WindowInsetsSides.Horizontal).add(verticalWindowInsets),
                            ).padding(bottom = 24.dp)
                            .fillMaxSize(),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .weight(1f)
                                .nestedScroll(state.preUpPostDownNestedScrollConnection),
                    ) {
                        // Remember lambdas to prevent unnecessary recomposition
                        val currentSliderPosition by rememberUpdatedState(sliderPosition)
                        val sliderPositionProvider = remember { { currentSliderPosition } }
                        val isExpandedProvider = remember(state) { { state.isExpanded } }
                        AnimatedContent(
                            targetState = showInlineLyrics,
                            label = "Lyrics",
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                        ) { showLyrics ->
                            if (showLyrics) {
                                // the lyrics read the player every frame themselves; the slider only while it is held
                                // (before: the progress bar's position, refreshed every 100 ms, so the sweep moved in steps)
                                InlineLyricsView(
                                    mediaMetadata = mediaMetadata,
                                    showLyrics = showLyrics,
                                    sliderPositionProvider = sliderPositionProvider,
                                    playhead = if (isCasting) castPlayhead else null,
                                )
                            } else {
                                Thumbnail(
                                    sliderPositionProvider = sliderPositionProvider,
                                    modifier = Modifier.animateContentSize(),
                                    isPlayerExpanded = isExpandedProvider,
                                    isLandscape = true,
                                    isListenTogetherGuest = isListenTogetherGuest,
                                )
                            }
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier =
                            Modifier
                                .weight(if (showInlineLyrics) 0.65f else 1f, false)
                                .animateContentSize()
                                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top)),
                    ) {
                        Spacer(Modifier.weight(1f))

                        mediaMetadata?.let {
                            controlsContent(it)
                        }

                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            else -> {
                val bottomPadding by animateDpAsState(
                    targetValue = if (isFullScreen) 0.dp else queueSheetState.collapsedBound,
                    label = "bottomPadding",
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier =
                        Modifier
                            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                            .padding(bottom = bottomPadding)
                            .animateContentSize(),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        // the cover settles into place as the player opens (the rest of the player only fades in with the sheet)
                        modifier = Modifier.weight(1f).sheetReveal(state, 0.04f, 0.60f, rise = 0.dp, scaleFrom = 0.94f, fade = false),
                    ) {
                        // Remember lambdas to prevent unnecessary recomposition
                        val currentSliderPosition by rememberUpdatedState(sliderPosition)
                        val sliderPositionProvider = remember { { currentSliderPosition } }
                        val isExpandedProvider = remember(state) { { state.isExpanded } }
                        AnimatedContent(
                            targetState = showInlineLyrics,
                            label = "Lyrics",
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                        ) { showLyrics ->
                            if (showLyrics) {
                                // the lyrics read the player every frame themselves; the slider only while it is held
                                // (before: the progress bar's position, refreshed every 100 ms, so the sweep moved in steps)
                                InlineLyricsView(
                                    mediaMetadata = mediaMetadata,
                                    showLyrics = showLyrics,
                                    sliderPositionProvider = sliderPositionProvider,
                                    playhead = if (isCasting) castPlayhead else null,
                                )
                            } else {
                                Thumbnail(
                                    sliderPositionProvider = sliderPositionProvider,
                                    modifier = Modifier.nestedScroll(state.preUpPostDownNestedScrollConnection),
                                    isPlayerExpanded = isExpandedProvider,
                                    isListenTogetherGuest = isListenTogetherGuest,
                                    hero = heroLayout,
                                )
                            }
                        }
                    }

                    mediaMetadata?.let {
                        controlsContent(it)
                    }

                    Spacer(Modifier.height(30.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = !isFullScreen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            Queue(
                state = queueSheetState,
                playerBottomSheetState = state,
                background =
                    if (useBlackBackground) {
                        Color.Black
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                onBackgroundColor = onBackgroundColor,
                TextBackgroundColor = TextBackgroundColor,
                textButtonColor = textButtonColor,
                iconButtonColor = iconButtonColor,
                pureBlack = pureBlack,
                showInlineLyrics = showInlineLyrics,
                playerBackground = playerBackground,
                onToggleLyrics = {
                    showInlineLyrics = !showInlineLyrics
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InlineLyricsView(
    mediaMetadata: MediaMetadata?,
    showLyrics: Boolean,
    sliderPositionProvider: () -> Long?,
    playhead: LyricsPlayheadSource? = null,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val currentLyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    val queueWindows by playerConnection.queueWindows.collectAsStateWithLifecycle(initialValue = emptyList())
    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsStateWithLifecycle(initialValue = -1)
    val context = LocalContext.current
    val database = LocalDatabase.current
    val lyricsHelper =
        remember(context) {
            EntryPointAccessors
                .fromApplication(context.applicationContext, com.metrolist.music.di.LyricsHelperEntryPoint::class.java)
                .lyricsHelper()
        }
    val online by lyricsHelper.online.collectAsStateWithLifecycle()

    var appInForeground by remember {
        mutableStateOf(
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )
    }
    DisposableEffect(Unit) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer =
            LifecycleEventObserver { _, _ ->
                appInForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val nextMetadata =
        remember(queueWindows, currentWindowIndex) {
            if (currentWindowIndex >= 0 && currentWindowIndex + 1 < queueWindows.size) {
                queueWindows[currentWindowIndex + 1].mediaItem.metadata
            } else {
                null
            }
        }

    // The row of the song on screen (the flow may still hold the previous song's row for a moment): none yet, a stored
    // "not found", or lyrics.
    val row = currentLyrics?.takeIf { mediaMetadata != null && it.id == mediaMetadata.id }
    val rowKind =
        when {
            row == null -> 0
            row.lyrics == LyricsEntity.LYRICS_NOT_FOUND -> 1
            else -> 2
        }
    // the song has no lyrics row and the sources could not be reached: nothing was stored, the view says so
    var noConnection by remember(mediaMetadata?.id) { mutableStateOf(false) }

    // The lyrics of the song on screen, through LyricsHelper's rules (nothing is stored when the sources could not be
    // reached, found lyrics are never replaced by "not found"). This is the one host of both lyrics views, so it runs:
    //  - the lookup of a song without a lyrics row (tried again while the view is open and the phone offline);
    //  - the once-per-run retry of a song stored as "no lyrics" (it never ran before: review 2026-11-12, finding 4);
    //  - the 4.3.4 re-check of lyrics an older version stored, replaced only by better ones (finding 1).
    LaunchedEffect(mediaMetadata?.id, rowKind, online) {
        val meta = mediaMetadata ?: return@LaunchedEffect
        delay(500)
        val current = currentLyrics?.takeIf { it.id == meta.id }
        try {
            when {
                current == null -> {
                    var attempt = 0
                    while (isActive) {
                        val result = withContext(Dispatchers.IO) { lyricsHelper.lookupAndStore(meta, com.metrolist.music.lyrics.LyricsStore.Mode.FILL) }
                        noConnection = result == com.metrolist.music.lyrics.LyricsStore.Result.NO_CONNECTION
                        if (!noConnection) break
                        // the network coming back restarts this effect at once (online is a key); a network that is up
                        // but does not carry the lookup is tried again slowly
                        delay(if (attempt++ < 2) 10_000L else 30_000L)
                    }
                }
                current.lyrics == LyricsEntity.LYRICS_NOT_FOUND -> withContext(Dispatchers.IO) { lyricsHelper.retryNotFound(meta) }
                else -> withContext(Dispatchers.IO) { lyricsHelper.recheckIfListed(meta, current) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    // Prefetch lyrics for the next queue item only while the lyrics pane is visible, the app is in the
    // foreground, and the current track's lyrics row has finished loading (avoids competing with the
    // active fetch). Lyrics an older version stored for it are checked again then, before it plays.
    LaunchedEffect(
        nextMetadata?.id,
        showLyrics,
        appInForeground,
        mediaMetadata?.id,
        currentLyrics,
    ) {
        if (!showLyrics || !appInForeground || nextMetadata == null) return@LaunchedEffect
        val loadedForCurrent =
            currentLyrics?.let { lyrics ->
                mediaMetadata == null || lyrics.id == mediaMetadata.id
            } == true
        if (mediaMetadata != null && !loadedForCurrent) return@LaunchedEffect
        val nextId = nextMetadata.id
        delay(400)
        if (!showLyrics || !appInForeground || !isActive) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val existing = database.lyrics(nextId).first()
                when {
                    existing == null -> lyricsHelper.lookupAndStore(nextMetadata, com.metrolist.music.lyrics.LyricsStore.Mode.FILL)
                    existing.lyrics != LyricsEntity.LYRICS_NOT_FOUND -> lyricsHelper.recheckIfListed(nextMetadata, existing)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (row == null && noConnection) {
            // no lyrics stored and no connection (before: a spinner for ever, or "not found" stored for good)
            Text(
                text = stringResource(R.string.error_no_internet_connection),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        } else {
            // Loading and "not found" are drawn by the lyrics views themselves: their empty state has "Choose source"
            // and "Try again", and the lyrics menu's "Lyrics source" needs them on screen (review 2026-11-12, finding 4).
            ProvideTextStyle(
                value =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    ),
            ) {
                Lyrics(
                    sliderPositionProvider = sliderPositionProvider,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    showLyrics = showLyrics,
                    playhead = playhead,
                )
            }
        }
    }
}

@Composable
private fun PlayerMoreMenuButton(
    mediaMetadata: MediaMetadata,
    state: BottomSheetState,
    glassUi: Boolean,
    textButtonColor: Color,
    iconButtonColor: Color,
) {
    val menuState = LocalMenuState.current
    val bottomSheetPageState = LocalBottomSheetPageState.current

    IconButton(
        modifier = if (glassUi) Modifier.size(42.dp).glass(CircleShape) else Modifier,
        onClick = {
            menuState.show {
                PlayerMenu(
                    mediaMetadata = mediaMetadata,
                    playerBottomSheetState = state,
                    onShowDetailsDialog = {
                        mediaMetadata.id.let {
                            bottomSheetPageState.show {
                                ShowMediaInfo(it)
                            }
                        }
                    },
                    onDismiss = menuState::dismiss,
                )
            }
        },
    ) {
        Icon(
            painter = painterResource(R.drawable.more_vert),
            contentDescription = stringResource(R.string.more_options),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(if (glassUi) 22.dp else 24.dp),
        )
    }
}

/**
 * Thin editorial progress bar + elapsed / remaining labels.
 * Lives in its own composable so the high-frequency position reads only recompose this subtree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerProgressSection(
    sliderStyle: SliderStyle,
    squigglySlider: Boolean,
    positionProvider: () -> Long,
    durationProvider: () -> Long,
    sliderPositionState: MutableState<Long?>,
    enabled: Boolean,
    isPlaying: Boolean,
    horizontalPadding: Dp,
    glassUi: Boolean,
    onSeek: (Long) -> Unit,
) {
    val duration = durationProvider()
    val dragPosition = sliderPositionState.value
    val shownPosition = dragPosition ?: positionProvider()
    val rangeEnd = if (duration == C.TIME_UNSET) 0f else duration.toFloat()
    val valueRange = 0f..rangeEnd

    val primary = MaterialTheme.colorScheme.primary
    val dragging = dragPosition != null
    val glassTrackHeight by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (dragging) 8.dp else 4.dp,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "glassTrackHeight",
    )
    val thumbScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "thumbScale",
    )
    val sliderColors =
        SliderDefaults.colors(
            thumbColor = primary,
            activeTrackColor = if (glassUi) MaterialTheme.colorScheme.onSurface else primary,
            activeTickColor = primary,
            inactiveTrackColor = if (glassUi) Color.White.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outlineVariant,
            disabledThumbColor = primary,
            disabledActiveTrackColor = primary,
            disabledInactiveTrackColor = if (glassUi) Color.White.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outlineVariant,
        )

    val onChange: (Float) -> Unit = {
        if (enabled) sliderPositionState.value = it.toLong()
    }
    val onFinished: () -> Unit = {
        // let go (or the drag was cancelled): seek when allowed, and always stop holding the position; a held value
        // kept the progress bar and the lyrics standing still (a Listen Together guest's slider turns disabled)
        if (enabled) sliderPositionState.value?.let { onSeek(it) }
        sliderPositionState.value = null
    }

    when (sliderStyle) {
        SliderStyle.DEFAULT -> {
            val sliderValue = shownPosition.toFloat()
            val sliderState =
                remember(valueRange) {
                    SliderState(value = sliderValue, trackRange = valueRange)
                }
            sliderState.value = sliderValue
            Slider(
                state = sliderState,
                onValueChange = onChange,
                onValueChangeFinished = onFinished,
                enabled = enabled,
                colors = sliderColors,
                thumb = {
                    Box(
                        Modifier
                            .size(if (glassUi) 18.dp else 12.dp)
                            .graphicsLayer {
                                if (glassUi) {
                                    scaleX = thumbScale
                                    scaleY = thumbScale
                                }
                            }
                            .clip(CircleShape)
                            .background(if (glassUi) Color.White else primary),
                    )
                },
                track = { state ->
                    PlayerSliderTrack(
                        sliderState = state,
                        colors = sliderColors,
                        trackHeight = if (glassUi) glassTrackHeight else 2.dp,
                    )
                },
                modifier = Modifier.padding(horizontal = horizontalPadding),
            )
        }

        SliderStyle.WAVY -> {
            if (squigglySlider) {
                SquigglySlider(
                    value = shownPosition.toFloat(),
                    valueRange = valueRange,
                    onValueChange = onChange,
                    onValueChangeFinished = onFinished,
                    enabled = enabled,
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                    colors = sliderColors,
                    isPlaying = isPlaying,
                )
            } else {
                WavySlider(
                    value = shownPosition.toFloat(),
                    valueRange = valueRange,
                    onValueChange = onChange,
                    onValueChangeFinished = onFinished,
                    enabled = enabled,
                    colors = sliderColors,
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                    isPlaying = isPlaying,
                )
            }
        }

        SliderStyle.SLIM -> {
            val sliderValue = shownPosition.toFloat()
            val sliderState =
                remember(valueRange) {
                    SliderState(value = sliderValue, trackRange = valueRange)
                }
            sliderState.value = sliderValue
            Slider(
                state = sliderState,
                onValueChange = onChange,
                onValueChangeFinished = onFinished,
                enabled = enabled,
                thumb = { Spacer(modifier = Modifier.size(0.dp)) },
                track = { state ->
                    PlayerSliderTrack(
                        sliderState = state,
                        colors = sliderColors,
                        trackHeight = if (glassUi) glassTrackHeight else 2.dp,
                    )
                },
                modifier = Modifier.padding(horizontal = horizontalPadding),
            )
        }
    }

    Spacer(Modifier.height(if (glassUi) 2.dp else 4.dp))

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = if (glassUi) horizontalPadding + 4.dp else horizontalPadding),
    ) {
        Text(
            text = makeTimeString(shownPosition),
            style = if (glassUi) {
                MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
            } else {
                MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
            },
            color = if (glassUi) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )

        Text(
            text =
                if (duration != C.TIME_UNSET && duration > 0) {
                    "-" + makeTimeString((duration - shownPosition).coerceAtLeast(0L))
                } else {
                    ""
                },
            style = if (glassUi) {
                MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
            } else {
                MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
            },
            color = if (glassUi) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Heavily blurred, darkened copy of the current cover: the one place blur-on-image is allowed.
 * The sheet already fades this background by progress; we additionally skip composing the image
 * while the sheet is (almost) collapsed so dragging stays cheap.
 */
@Composable
private fun PlayerCoverBackground(
    thumbnailUrl: String?,
    baseColor: Color,
    dark: Boolean,
    visibleProvider: () -> Boolean,
) {
    val visible by remember(visibleProvider) { derivedStateOf(visibleProvider) }
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(baseColor)) {
        if (visible && thumbnailUrl != null) {
            // Blurred once on the CPU and cached (see CoverBlur.kt), not a live RenderEffect that the GPU
            // re-runs for every frame the window draws.
            val blurred by rememberBlurredCover(thumbnailUrl)
            androidx.compose.animation.Crossfade(
                targetState = blurred,
                animationSpec = tween(600),
                label = "coverBackground",
            ) { bitmap ->
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = 1.35f
                                    scaleY = 1.35f
                                },
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (dark) {
                            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.40f), Color.Black.copy(alpha = 0.62f)))
                        } else {
                            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.50f), Color.White.copy(alpha = 0.70f)))
                        },
                    ),
            )
        }
    }
}
