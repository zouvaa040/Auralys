/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import com.metrolist.music.ui.component.ambientGlow
import android.annotation.SuppressLint
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import com.metrolist.music.ui.component.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.metrolist.music.ui.component.Slider
import androidx.compose.material3.SnackbarDuration
import com.metrolist.music.ui.component.AuralysSnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.metrolist.music.ui.component.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.ListItemHeight
import com.metrolist.music.constants.PlayerBackgroundStyle
import com.metrolist.music.constants.QueueEditLockKey
import com.metrolist.music.constants.UseNewPlayerDesignKey
import com.metrolist.music.extensions.getQueueWindows
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.move
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.listentogether.RoomRole
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.PlayerShuffle
import com.metrolist.music.playback.queues.QueueDefaults
import com.metrolist.music.ui.component.ActionPromptDialog
import com.metrolist.music.ui.component.BottomSheet
import com.metrolist.music.ui.component.BottomSheetState
import com.metrolist.music.ui.component.LocalBottomSheetPageState
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.MediaMetadataListItem
import com.metrolist.music.ui.component.MicroLabel
import com.metrolist.music.ui.component.editorialPanel
import com.metrolist.music.ui.component.hairlineBottom
import com.metrolist.music.ui.component.glass
import com.metrolist.music.ui.component.glassTopShape
import com.metrolist.music.ui.component.glassShape
import com.metrolist.music.ui.theme.isGlassStyle
import com.metrolist.music.ui.component.glassBlur
import com.metrolist.music.ui.component.glassRim
import com.metrolist.music.ui.component.hairlineTop
import com.metrolist.music.ui.component.iosAppear
import com.metrolist.music.ui.component.pressScale
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.utils.ArtistNameAliases
import com.metrolist.music.utils.joinToArtistString
import coil3.compose.AsyncImage
import android.content.Intent
import androidx.compose.ui.layout.ContentScale
import com.metrolist.music.ui.menu.PlayerMenu
import com.metrolist.music.ui.menu.QueueMenu
import com.metrolist.music.ui.menu.SelectionMediaMetadataMenu
import com.metrolist.music.local.isLocalSong
import com.metrolist.music.ui.utils.ShowMediaInfo
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.roundToInt
import com.metrolist.music.constants.SleepTimerDefaultKey
import android.widget.Toast
import androidx.compose.runtime.derivedStateOf
import com.metrolist.music.constants.SleepTimerFadeOutKey
import com.metrolist.music.constants.SleepTimerStopAfterCurrentSongKey
import androidx.compose.runtime.derivedStateOf
import com.metrolist.music.ui.component.Button


@SuppressLint("UnrememberedMutableState")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Queue(
    state: BottomSheetState,
    playerBottomSheetState: BottomSheetState,
    modifier: Modifier = Modifier,
    background: Color,
    onBackgroundColor: Color,
    TextBackgroundColor: Color,
    textButtonColor: Color,
    iconButtonColor: Color,
    pureBlack: Boolean,
    showInlineLyrics: Boolean,
    playerBackground: PlayerBackgroundStyle = PlayerBackgroundStyle.DEFAULT,
    onToggleLyrics: () -> Unit = {},
) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val clipboardManager = LocalClipboard.current
    val menuState = LocalMenuState.current
    val sleepTimerDefaultSetTemplate = stringResource(R.string.sleep_timer_default_set)
    val bottomSheetPageState = LocalBottomSheetPageState.current

    // Listen Together state (reactive)
    val listenTogetherManager = LocalListenTogetherManager.current
    val listenTogetherRoleState = listenTogetherManager?.role?.collectAsStateWithLifecycle(initialValue = com.metrolist.music.listentogether.RoomRole.NONE)
    val isListenTogetherGuest = listenTogetherRoleState?.value == RoomRole.GUEST

    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val repeatMode by playerConnection.repeatMode.collectAsStateWithLifecycle()

    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()

    val currentFormat by playerConnection.currentFormat.collectAsStateWithLifecycle(initialValue = null)

    val selectedSongs = remember { mutableStateListOf<MediaMetadata>() }
    val selectedItems = remember { mutableStateListOf<Timeline.Window>() }

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
    val castIsPlaying by castHandler?.castIsPlaying?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }

    var inSelectMode by rememberSaveable { mutableStateOf(false) }
    val selection =
        rememberSaveable(
            saver =
                listSaver<MutableList<String>, String>(
                    save = { it.toList() },
                    restore = { it.toMutableStateList() },
                ),
        ) { mutableStateListOf() }
    val onExitSelectionMode = {
        inSelectMode = false
        selection.clear()
    }
    if (inSelectMode) {
        BackHandler(onBack = onExitSelectionMode)
    }

    // 4.3.4: unlocked unless the listener locked it (a saved choice is kept). Locked by default, a swipe did nothing and
    // users found no way to remove a song ("lagu yang sudah diputar tidak bisa dihapus"); both swipes have Undo.
    var locked by rememberPreference(QueueEditLockKey, defaultValue = QueueDefaults.QUEUE_LOCKED)
    var showClearQueueDialog by rememberSaveable { mutableStateOf(false) }
    var showRemovePlayedDialog by rememberSaveable { mutableStateOf(false) }

    val (useNewPlayerDesign, onUseNewPlayerDesignChange) =
        rememberPreference(
            UseNewPlayerDesignKey,
            defaultValue = true,
        )

    val snackbarHostState = remember { SnackbarHostState() }
    var dismissJob: Job? by remember { mutableStateOf(null) }

    val coroutineScope = rememberCoroutineScope()
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    val sleepTimerDefault by rememberPreference(SleepTimerDefaultKey, 30f)
    var sleepTimerValue by remember { mutableFloatStateOf(sleepTimerDefault) }
    val isAtDefault by remember {
        derivedStateOf { sleepTimerValue.roundToInt() == sleepTimerDefault.roundToInt() }
    }
    LaunchedEffect(sleepTimerDefault) { sleepTimerValue = sleepTimerDefault }
    val sleepTimerStopAfterCurrentSong by rememberPreference(SleepTimerStopAfterCurrentSongKey, false)
    val sleepTimerFadeOut by rememberPreference(SleepTimerFadeOutKey, false)
    val sleepTimerEnabled = remember(
        playerConnection.service.sleepTimer?.triggerTime,
        playerConnection.service.sleepTimer?.pauseWhenSongEnd
    ) {
        playerConnection.service.sleepTimer?.isActive ?: false
    }
    var sleepTimerTimeLeft by remember { mutableLongStateOf(0L) }

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

    BottomSheet(
        state = state,
        modifier = modifier,
        background = {
            Box(Modifier.fillMaxSize().background(Color.Unspecified))
        },
        collapsedContent = {
            // The action row of the player, as on Apple Music: four bold icons, no container; the open one sits on a soft rounded square
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.systemBars.only(
                                WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                            ),
                        ).padding(horizontal = 16.dp, vertical = 6.dp)
                        .height(56.dp),
            ) {
                QueueStripAction(
                    icon = R.drawable.ios_bar_lyrics,
                    iconFill = R.drawable.ios_bar_lyrics_fill,
                    label = stringResource(R.string.lyrics),
                    isActive = showInlineLyrics,
                    onClick = { onToggleLyrics() },
                    haptic = if (showInlineLyrics) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                    modifier = Modifier.weight(1f).iosAppear(0),
                )
                QueueStripAction(
                    icon = R.drawable.ios_bar_queue,
                    label = stringResource(R.string.queue),
                    isActive = false,
                    onClick = { state.expandSoft() },
                    modifier = Modifier.weight(1f).iosAppear(1),
                )
                // A phone song has no YouTube link to share.
                if (mediaMetadata?.isLocalSong != true) {
                    QueueStripAction(
                        icon = R.drawable.ios_bar_share,
                        label = stringResource(R.string.share),
                        isActive = false,
                        onClick = {
                            mediaMetadata?.id?.let { id ->
                                val intent =
                                    Intent().apply {
                                        action = Intent.ACTION_SEND
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=$id")
                                    }
                                context.startActivity(Intent.createChooser(intent, null))
                            }
                        },
                        modifier = Modifier.weight(1f).iosAppear(2),
                    )
                }
                QueueStripAction(
                    icon = R.drawable.ios_bar_sleep,
                    iconFill = R.drawable.ios_bar_sleep_fill,
                    label =
                        if (sleepTimerEnabled) {
                            makeTimeString(sleepTimerTimeLeft)
                        } else {
                            stringResource(R.string.sleep_timer)
                        },
                    isActive = sleepTimerEnabled,
                    enabled = !isListenTogetherGuest,
                    onClick = {
                        if (!isListenTogetherGuest) {
                            if (sleepTimerEnabled) {
                                playerConnection.service.sleepTimer?.clear()
                            } else {
                                showSleepTimerDialog = true
                            }
                        }
                    },
                    showLabel = sleepTimerEnabled,
                    haptic = if (sleepTimerEnabled) HapticFeedbackType.ToggleOff else HapticFeedbackType.ContextClick,
                    modifier = Modifier.weight(1f).iosAppear(if (mediaMetadata?.isLocalSong != true) 3 else 2),
                )
            }

            if (showSleepTimerDialog) {
                ActionPromptDialog(
                    titleBar = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.sleep_timer),
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                    },
                    onDismiss = { showSleepTimerDialog = false },
                    onConfirm = {
                        showSleepTimerDialog = false
                        playerConnection.service.sleepTimer?.start(
                            minute = sleepTimerValue.roundToInt(),
                            stopAfterCurrentSong = sleepTimerStopAfterCurrentSong,
                            fadeOut = sleepTimerFadeOut,
                        )
                    },
                    onCancel = {
                        showSleepTimerDialog = false
                    },
                    onReset = {
                        sleepTimerValue = sleepTimerDefault
                    },
                    content = {
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

                            Spacer(Modifier.height(16.dp))

                            Slider(
                                value = sleepTimerValue,
                                onValueChange = { sleepTimerValue = it },
                                valueRange = 5f..120f,
                                steps = (120 - 5) / 5 - 1,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Spacer(Modifier.height(8.dp))

                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (isAtDefault) {
                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
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
                                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary,
                                        ),
                                    ) {
                                        Text(stringResource(R.string.set_as_default))
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            coroutineScope.launch {
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

                                OutlinedButton(
                                    onClick = {
                                        showSleepTimerDialog = false
                                        playerConnection.service.sleepTimer?.start(
                                            minute = -1,
                                        )
                                    },
                                ) {
                                    Text(stringResource(R.string.end_of_song))
                                }
                            }
                        }
                    },
                )
            }
        },
    ) {
        val queueTitle by playerConnection.queueTitle.collectAsStateWithLifecycle()
        val queueWindows by playerConnection.queueWindows.collectAsStateWithLifecycle()
        val automix by playerConnection.service.automixItems.collectAsStateWithLifecycle()
        val mutableQueueWindows = remember { mutableStateListOf<Timeline.Window>() }
        val queueLength =
            remember(queueWindows) {
                queueWindows.sumOf { it.mediaItem.metadata!!.duration }
            }

        val coroutineScope = rememberCoroutineScope()

        // texts and snackbar of the row swipes (the same host and job as the old remove-with-Undo, so only one is shown)
        val playedNextMsg = stringResource(R.string.auralys_queue_played_next)
        val isCurrentMsg = stringResource(R.string.auralys_queue_is_current)
        val alreadyNextMsg = stringResource(R.string.auralys_queue_already_next)
        val undoStr = stringResource(R.string.undo)
        val lockedMsg = stringResource(R.string.auralys_queue_locked)
        val unlockStr = stringResource(R.string.auralys_queue_unlock)
        var lastLockHintAt by remember { mutableStateOf<Long?>(null) }
        fun showQueueSnackbar(
            message: String,
            actionLabel: String? = null,
            onAction: () -> Unit = {},
        ) {
            dismissJob?.cancel()
            dismissJob =
                coroutineScope.launch {
                    val result =
                        snackbarHostState.showSnackbar(
                            message = message,
                            actionLabel = actionLabel,
                            duration = SnackbarDuration.Short,
                        )
                    if (result == SnackbarResult.ActionPerformed) onAction()
                }
        }

        val headerItems = 1
        val lazyListState = rememberLazyListState()
        var dragInfo by remember { mutableStateOf<Pair<Int, Int>?>(null) }

        val currentPlayingUid =
            remember(currentWindowIndex, queueWindows) {
                if (currentWindowIndex in queueWindows.indices) {
                    queueWindows[currentWindowIndex].uid
                } else {
                    null
                }
            }

        val reorderableState =
            rememberReorderableLazyListState(
                lazyListState = lazyListState,
                scrollThresholdPadding =
                    WindowInsets.systemBars
                        .add(
                            WindowInsets(
                                top = ListItemHeight,
                                bottom = ListItemHeight,
                            ),
                        ).asPaddingValues(),
            ) { from, to ->
                val currentDragInfo = dragInfo
                dragInfo =
                    if (currentDragInfo == null) {
                        from.index to to.index
                    } else {
                        currentDragInfo.first to to.index
                    }

                val safeFrom = (from.index - headerItems).coerceIn(0, mutableQueueWindows.lastIndex)
                val safeTo = (to.index - headerItems).coerceIn(0, mutableQueueWindows.lastIndex)

                mutableQueueWindows.move(safeFrom, safeTo)
            }

        LaunchedEffect(reorderableState.isAnyItemDragging) {
            if (!reorderableState.isAnyItemDragging) {
                dragInfo?.let { (from, to) ->
                    val safeFrom = (from - headerItems).coerceIn(0, queueWindows.lastIndex)
                    val safeTo = (to - headerItems).coerceIn(0, queueWindows.lastIndex)

                    if (!playerConnection.player.shuffleModeEnabled) {
                        playerConnection.player.moveMediaItem(safeFrom, safeTo)
                    } else {
                        // QueueShuffleOrder: songs added later still go after the waiting ones (4.3.4); a stale list
                        // (the queue changed during the drag) is not applied
                        PlayerShuffle.setPlayOrder(
                            playerConnection.player,
                            queueWindows
                                .map { it.firstPeriodIndex }
                                .toMutableList()
                                .move(safeFrom, safeTo)
                                .toIntArray(),
                        )
                    }
                    dragInfo = null
                }
            }
        }

        LaunchedEffect(queueWindows) {
            mutableQueueWindows.apply {
                clear()
                addAll(queueWindows)
            }
        }

        // Scroll to the playing song when it changes (or shuffle is switched), not when an edit above it (a removed row,
        // a row swiped to "play next") only shifts its index: that would throw the list to the top while the user swipes.
        val shuffleOnForScroll by playerConnection.shuffleModeEnabled.collectAsStateWithLifecycle()
        var scrolledTo by remember { mutableStateOf<Pair<Any?, Boolean>?>(null) }
        LaunchedEffect(mutableQueueWindows, currentWindowIndex, currentPlayingUid, shuffleOnForScroll) {
            val target = currentPlayingUid to shuffleOnForScroll
            if (currentWindowIndex != -1 && target != scrolledTo) {
                scrolledTo = target
                lazyListState.scrollToItem(currentWindowIndex)
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(
                        if (isGlassStyle) {
                            Modifier
                                .clip(glassTopShape(32.dp, 0.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .ambientGlow()
                        } else {
                            Modifier.glassBlur(glassTopShape(32.dp, 0.dp))
                        },
                    ),
        ) {
            LazyColumn(
                state = lazyListState,
                contentPadding =
                    WindowInsets.systemBars
                        .add(
                            WindowInsets(
                                top = ListItemHeight + 8.dp,
                                bottom = ListItemHeight + 8.dp,
                            ),
                        ).asPaddingValues(),
                modifier = Modifier.nestedScroll(state.preUpPostDownNestedScrollConnection),
            ) {
                item(key = "queue_top_spacer") {
                    Spacer(
                        modifier =
                            Modifier
                                .animateContentSize()
                                .height(if (inSelectMode) 48.dp else 0.dp),
                    )
                }

                itemsIndexed(
                    items = mutableQueueWindows,
                    key = { _, item -> item.uid.hashCode() },
                    contentType = { _, _ -> "queue_track" },
                ) { index, window ->
                    ReorderableItem(
                        state = reorderableState,
                        key = window.uid.hashCode(),
                    ) {
                        val currentItem by rememberUpdatedState(window)
                        val isActive = window.uid == currentPlayingUid

                        // swipe right = "Putar berikutnya" (the row moves to right after the playing song), left = remove.
                        // Off for Listen Together guests, in selection mode and while the queue is locked.
                        val rightAction =
                            QueueSwipeLogic.rightAction(index, currentWindowIndex, isListenTogetherGuest, locked, inSelectMode)
                        val leftEnabled = QueueSwipeLogic.leftEnabled(isListenTogetherGuest, locked, inSelectMode)
                        // a locked queue is not changed by a swipe, but the swipe says why (not for guests, not while selecting)
                        val lockedHint = QueueSwipeLogic.lockedHint(isListenTogetherGuest, locked, inSelectMode)
                        val onSwipeLockedHint: () -> Unit = {
                            val now = SystemClock.elapsedRealtime()
                            if (QueueSwipeLogic.hintAllowed(now, lastLockHintAt)) {
                                lastLockHintAt = now
                                // the same preference and the same assignment as the lock icon in the header
                                showQueueSnackbar(lockedMsg, unlockStr) { locked = false }
                            }
                        }

                        val onSwipePlayNext: () -> Unit = {
                            val player = playerConnection.player
                            val uid = currentItem.uid
                            val slot = player.playNextInQueue(uid)
                            if (slot != null) {
                                showQueueSnackbar(playedNextMsg, undoStr) { player.undoPlayNext(uid, slot) }
                            }
                        }
                        val onSwipeRightRejected: (QueueSwipeLogic.RightAction) -> Unit = { action ->
                            showQueueSnackbar(if (action == QueueSwipeLogic.RightAction.IsCurrent) isCurrentMsg else alreadyNextMsg)
                        }
                        val onSwipeRemove: () -> Unit = {
                            val player = playerConnection.player
                            val uid = currentItem.uid
                            val target = player.getQueueWindows().firstOrNull { it.uid == uid }
                            if (target != null) {
                                // remember the neighbours so Undo puts the song back in its place, also with shuffle on
                                val slot = player.slotOfRow(uid)
                                val item = target.mediaItem
                                player.removeMediaItem(target.firstPeriodIndex)
                                showQueueSnackbar(
                                    context.getString(R.string.auralys_queue_removed, item.metadata?.title ?: ""),
                                    undoStr,
                                ) { player.reinsertInQueue(item, slot) }
                            }
                        }

                        val onCheckedChange: (Boolean) -> Unit = {
                            if (it) {
                                selection.add(window.mediaItem.mediaId)
                            } else {
                                selection.remove(window.mediaItem.mediaId)
                            }
                        }

                        val content: @Composable () -> Unit = {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.animateItem(),
                            ) {
                                QueueTrackRow(
                                    index = index + 1,
                                    mediaMetadata = window.mediaItem.metadata!!,
                                    isActive = isActive,
                                    isPlaying = isPlaying && isActive,
                                    trailingContent = {
                                        if (inSelectMode) {
                                            Checkbox(
                                                checked = window.mediaItem.mediaId in selection,
                                                onCheckedChange = onCheckedChange,
                                            )
                                        } else {
                                            if (!isListenTogetherGuest) {
                                                IconButton(
                                                    onClick = {
                                                        menuState.show {
                                                            QueueMenu(
                                                                mediaMetadata = window.mediaItem.metadata!!,
                                                                playerBottomSheetState = playerBottomSheetState,
                                                                onShowDetailsDialog = {
                                                                    window.mediaItem.mediaId.let {
                                                                        bottomSheetPageState.show {
                                                                            ShowMediaInfo(it)
                                                                        }
                                                                    }
                                                                },
                                                                onDismiss = menuState::dismiss,
                                                                // any row but the playing one, with Undo, locked or not
                                                                onRemoveFromQueue = if (isActive) null else onSwipeRemove,
                                                            )
                                                        }
                                                    },
                                                ) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.more_vert),
                                                        contentDescription = null,
                                                    )
                                                }
                                            }
                                            if (!locked && !isListenTogetherGuest) {
                                                IconButton(
                                                    onClick = { },
                                                    modifier = Modifier.draggableHandle(),
                                                ) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.drag_handle),
                                                        contentDescription = null,
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .then(if (isGlassStyle) Modifier else Modifier.background(background))
                                            .combinedClickable(
                                                onClick = {
                                                    if (inSelectMode) {
                                                        onCheckedChange(window.mediaItem.mediaId !in selection)
                                                    } else if (!isListenTogetherGuest) {
                                                        if (index == currentWindowIndex) {
                                                            if (isCasting) {
                                                                if (castIsPlaying) {
                                                                    castHandler?.pause()
                                                                } else {
                                                                    castHandler?.play()
                                                                }
                                                            } else {
                                                                playerConnection.togglePlayPause()
                                                            }
                                                        } else {
                                                            if (isCasting) {
                                                                val mediaId = window.mediaItem.mediaId
                                                                val navigated = castHandler?.navigateToMediaIfInQueue(mediaId) ?: false
                                                                if (!navigated) {
                                                                    playerConnection.player.seekToDefaultPosition(window.firstPeriodIndex)
                                                                }
                                                            } else {
                                                                playerConnection.player.seekToDefaultPosition(
                                                                    window.firstPeriodIndex,
                                                                )
                                                                playerConnection.player.playWhenReady = true
                                                            }
                                                        }
                                                    }
                                                },
                                                onLongClick = {
                                                    if (!inSelectMode) {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        inSelectMode = true
                                                        onCheckedChange(true)
                                                    }
                                                },
                                            ),
                                )
                            }
                        }

                        QueueSwipeRow(
                            rightAction = rightAction,
                            leftEnabled = leftEnabled,
                            onPlayNext = onSwipePlayNext,
                            onRemove = onSwipeRemove,
                            onRightRejected = onSwipeRightRejected,
                            lockedHint = lockedHint,
                            onLockedHint = onSwipeLockedHint,
                        ) {
                            content()
                        }
                    }
                }

                if (automix.isNotEmpty()) {
                    item(key = "automix_divider") {
                        Text(
                            text = stringResource(R.string.similar_content),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier =
                                Modifier
                                    .padding(start = 20.dp, top = 20.dp, bottom = 8.dp)
                                    .animateItem(),
                        )
                    }

                    itemsIndexed(
                        items = automix,
                        key = { _, it -> it.mediaId },
                        contentType = { _, _ -> "automix_track" },
                    ) { index, item ->
                        Row(
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            QueueTrackRow(
                                index = null,
                                mediaMetadata = item.metadata!!,
                                trailingContent = {
                                    if (!isListenTogetherGuest) {
                                        IconButton(
                                            onClick = {
                                                playerConnection.service.playNextAutomix(
                                                    item,
                                                    index,
                                                )
                                            },
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.playlist_play),
                                                contentDescription = null,
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                playerConnection.service.addToQueueAutomix(
                                                    item,
                                                    index,
                                                )
                                            },
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.queue_music),
                                                contentDescription = null,
                                            )
                                        }
                                    }
                                },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {},
                                            onLongClick = {
                                                menuState.show {
                                                    QueueMenu(
                                                        mediaMetadata = item.metadata!!,
                                                        playerBottomSheetState = playerBottomSheetState,
                                                        onShowDetailsDialog = {
                                                            item.mediaId.let {
                                                                bottomSheetPageState.show {
                                                                    ShowMediaInfo(it)
                                                                }
                                                            }
                                                        },
                                                        onDismiss = menuState::dismiss,
                                                    )
                                                }
                                            },
                                        ).animateItem(),
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier =
                Modifier
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { }
                    .background((if (pureBlack) Color.Black else background).copy(alpha = if (isGlassStyle) 0.78f else 1f))
                    .windowInsetsPadding(
                        WindowInsets.systemBars
                            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    ),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .height(ListItemHeight)
                        .padding(start = 20.dp, end = 12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.queue),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!queueTitle.isNullOrBlank()) {
                        Text(
                            text = queueTitle.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = !inSelectMode,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                ) {
                    Row {
                        // 4.3.4: the songs above the playing one (heard in this pass) go in one tap, locked or not
                        IconButton(
                            enabled = !isListenTogetherGuest && currentWindowIndex > 0,
                            onClick = { showRemovePlayedDialog = true },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.history),
                                contentDescription = stringResource(R.string.auralys_queue_remove_played),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            enabled = !isListenTogetherGuest && queueWindows.size > 1,
                            onClick = { showClearQueueDialog = true },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.clear_all),
                                contentDescription = stringResource(R.string.auralys_queue_clear),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { locked = !locked },
                            modifier = Modifier.padding(horizontal = 6.dp),
                        ) {
                            Icon(
                                painter = painterResource(if (locked) R.drawable.lock else R.drawable.lock_open),
                                contentDescription = null,
                                tint = if (locked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    if (showClearQueueDialog) {
                        AlertDialog(
                            onDismissRequest = { showClearQueueDialog = false },
                            title = { Text(stringResource(R.string.auralys_queue_clear)) },
                            text = { Text(stringResource(R.string.auralys_queue_clear_message)) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showClearQueueDialog = false
                                        // Keep the song that is playing, drop everything before and after it.
                                        val player = playerConnection.player
                                        val current = player.currentMediaItemIndex
                                        if (current >= 0) {
                                            if (current + 1 < player.mediaItemCount) {
                                                player.removeMediaItems(current + 1, player.mediaItemCount)
                                            }
                                            if (current > 0) player.removeMediaItems(0, current)
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.auralys_queue_clear_confirm))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showClearQueueDialog = false }) {
                                    Text(stringResource(R.string.auralys_queue_clear_cancel))
                                }
                            },
                        )
                    }
                    if (showRemovePlayedDialog) {
                        val playedCount = currentWindowIndex.coerceAtLeast(0)
                        AlertDialog(
                            onDismissRequest = { showRemovePlayedDialog = false },
                            title = { Text(stringResource(R.string.auralys_queue_remove_played)) },
                            text = {
                                Text(pluralStringResource(R.plurals.auralys_queue_remove_played_message, playedCount, playedCount))
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showRemovePlayedDialog = false
                                        // every song before the playing one in the PLAY order (shuffle aware)
                                        val removed = PlayerShuffle.removePlayed(playerConnection.player)
                                        if (removed > 0) {
                                            showQueueSnackbar(
                                                context.resources.getQuantityString(R.plurals.auralys_queue_removed_played, removed, removed),
                                            )
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.auralys_queue_remove_played_confirm))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRemovePlayedDialog = false }) {
                                    Text(stringResource(R.string.auralys_queue_clear_cancel))
                                }
                            },
                        )
                    }
                }

                Text(
                    text =
                        pluralStringResource(
                            R.plurals.n_song,
                            queueWindows.size,
                            queueWindows.size,
                        ) + " \u2022 " + makeTimeString(queueLength * 1000L),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            AnimatedVisibility(
                visible = inSelectMode,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                val selectedSongs =
                    remember(selection.toList(), mutableQueueWindows) {
                        mutableQueueWindows
                            .filter { it.mediaItem.mediaId in selection }
                            .mapNotNull { it.mediaItem.metadata }
                    }
                val selectedItems =
                    remember(selection.toList(), mutableQueueWindows) {
                        mutableQueueWindows.filter { it.mediaItem.mediaId in selection }
                    }
                val count = selection.size
                Row(
                    modifier =
                        Modifier
                            .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onExitSelectionMode,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = null,
                        )
                    }
                    Text(
                        text = pluralStringResource(R.plurals.n_selected, count, count),
                        modifier = Modifier.weight(1f),
                    )
                    Checkbox(
                        checked = count == mutableQueueWindows.size && count > 0,
                        onCheckedChange = {
                            if (count == mutableQueueWindows.size) {
                                selection.clear()
                            } else {
                                selection.clear()
                                mutableQueueWindows.forEach {
                                    selection.add(it.mediaItem.mediaId)
                                }
                            }
                        },
                    )
                    IconButton(
                        enabled = count > 0 && !isListenTogetherGuest,
                        onClick = {
                            // Highest index first so the remaining indices stay valid while removing.
                            selectedItems
                                .map { it.firstPeriodIndex }
                                .sortedDescending()
                                .forEach { playerConnection.player.removeMediaItem(it) }
                            onExitSelectionMode()
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.delete),
                            contentDescription = stringResource(R.string.remove_from_queue),
                        )
                    }
                    IconButton(
                        enabled = count > 0,
                        onClick = {
                            menuState.show {
                                SelectionMediaMetadataMenu(
                                    songSelection = selectedSongs,
                                    onDismiss = menuState::dismiss,
                                    clearAction = onExitSelectionMode,
                                    currentItems = selectedItems,
                                )
                            }
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert),
                            contentDescription = null,
                            tint = LocalContentColor.current,
                        )
                    }
                }
            }
        }

        val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsStateWithLifecycle()

        Box(
            modifier =
                Modifier
                    .background((if (pureBlack) Color.Black else background).copy(alpha = if (isGlassStyle) 0.78f else 1f))
                    .fillMaxWidth()
                    .height(
                        ListItemHeight +
                            WindowInsets.systemBars
                                .asPaddingValues()
                                .calculateBottomPadding(),
                    ).align(Alignment.BottomCenter)
                    .clickable {
                        state.collapseSoft()
                    }.windowInsetsPadding(
                        WindowInsets.systemBars
                            .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    ).padding(12.dp),
        ) {
            IconButton(
                enabled = !isListenTogetherGuest,
                modifier = Modifier.align(Alignment.CenterStart),
                onClick = {
                    coroutineScope
                        .launch {
                            lazyListState.animateScrollToItem(
                                if (playerConnection.player.shuffleModeEnabled) playerConnection.player.currentMediaItemIndex else 0,
                            )
                        }.invokeOnCompletion {
                            playerConnection.player.shuffleModeEnabled =
                                !playerConnection.player.shuffleModeEnabled
                        }
                },
            ) {
                val baseAlpha = if (shuffleModeEnabled) 1f else 0.5f
                val finalAlpha = if (!isListenTogetherGuest) baseAlpha else 0.3f
                Icon(
                    painter = painterResource(R.drawable.shuffle),
                    contentDescription = null,
                    modifier = Modifier.alpha(finalAlpha),
                )
            }

            Icon(
                painter = painterResource(R.drawable.expand_more),
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center),
            )

            IconButton(
                enabled = !isListenTogetherGuest,
                modifier = Modifier.align(Alignment.CenterEnd),
                onClick = playerConnection.player::toggleRepeatMode,
            ) {
                val baseAlpha = if (repeatMode == Player.REPEAT_MODE_OFF) 0.5f else 1f
                val finalAlpha = if (!isListenTogetherGuest) baseAlpha else 0.3f
                Icon(
                    painter =
                        painterResource(
                            when (repeatMode) {
                                Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL -> R.drawable.repeat
                                Player.REPEAT_MODE_ONE -> R.drawable.repeat_one
                                else -> throw IllegalStateException()
                            },
                        ),
                    contentDescription = null,
                    modifier = Modifier.alpha(finalAlpha),
                )
            }
        }

        AuralysSnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .padding(
                        bottom =
                            ListItemHeight +
                                WindowInsets.systemBars
                                    .asPaddingValues()
                                    .calculateBottomPadding(),
                    ).align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun PlayerQueueButton(
    icon: Int,
    onClick: () -> Unit,
    isActive: Boolean,
    enabled: Boolean = true,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    text: String? = null,
    textButtonColor: Color,
    iconButtonColor: Color,
    iconSize: androidx.compose.ui.unit.Dp,
    textBackgroundColor: Color,
    playerBackground: PlayerBackgroundStyle,
) {
    val buttonModifier =
        Modifier
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)

    val alphaFactor = if (enabled) 1f else 0.35f

    val appliedModifier =
        if (isActive) {
            modifier.then(buttonModifier.background(textButtonColor)).alpha(alphaFactor)
        } else {
            modifier
                .then(
                    buttonModifier.border(
                        width = 1.dp,
                        color = textButtonColor.copy(alpha = 0.3f),
                        shape = shape,
                    ),
                ).alpha(alphaFactor)
        }

    Box(
        modifier = appliedModifier,
        contentAlignment = Alignment.Center,
    ) {
        if (text != null) {
            Text(
                text = text,
                color = iconButtonColor.copy(alpha = if (enabled) 1f else 0.6f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .basicMarquee(),
            )
        } else {
            val baseTint =
                if (isActive) {
                    iconButtonColor
                } else {
                    when (playerBackground) {
                        PlayerBackgroundStyle.BLUR, PlayerBackgroundStyle.GRADIENT -> {
                            Color.White
                        }

                        PlayerBackgroundStyle.DEFAULT -> {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        }
                    }
                }
            val finalTint = if (enabled) baseTint else baseTint.copy(alpha = 0.5f)
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = finalTint,
            )
        }
    }
}


/**
 * One action of the player's dock, as on Apple Music: a bold iOS-pen icon and nothing around it, every glyph drawn in the same
 * optical box and stroke (res/drawable/ios_bar_*) so the four read as one size, in four equal columns so the dock is symmetric
 * whether the share action is there or not. The open one sits on a soft glass rounded square (never a capsule) that springs in
 * from a smaller size with a small overshoot; its icon pops once and, where there is a [iconFill], a soft fill of the glyph
 * comes in under the outline (the iOS "fill" variant). With iOS animations on the springs are looser (more overshoot) and the
 * dock's actions rise in one after the other (Modifier.iosAppear at the call site). A tap gives the feel of what it did
 * ([haptic]: a switch click when it turns on or off, a light click when it only opens something). A label is shown only where it
 * carries information (the time left of the sleep timer).
 */
@Composable
private fun QueueStripAction(
    @androidx.annotation.DrawableRes icon: Int,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @androidx.annotation.DrawableRes iconFill: Int? = null,
    enabled: Boolean = true,
    showLabel: Boolean = false,
    haptic: HapticFeedbackType = HapticFeedbackType.ContextClick,
) {
    val feel = LocalHapticFeedback.current
    val ios = com.metrolist.music.ui.component.LocalIosMotion.current
    val ink = MaterialTheme.colorScheme.onSurface
    val base = if (isActive) ink else ink.copy(alpha = 0.66f)
    val tint by androidx.compose.animation.animateColorAsState(
        targetValue = if (enabled) base else base.copy(alpha = 0.38f),
        animationSpec = androidx.compose.animation.core.tween(200),
        label = "stripTint",
    )
    // read inside graphicsLayer only: the plate, the pop and the fill never recompose the row, they only redraw
    val plateScale =
        androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (isActive) 1f else 0.68f,
            animationSpec = androidx.compose.animation.core.spring(dampingRatio = if (ios) 0.46f else 0.62f, stiffness = 380f),
            label = "stripPlateScale",
        )
    val plateAlpha =
        androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (isActive) 1f else 0f,
            animationSpec = androidx.compose.animation.core.tween(if (isActive) 140 else 110),
            label = "stripPlateAlpha",
        )
    val iconPop =
        androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (isActive) 1.1f else 1f,
            animationSpec = androidx.compose.animation.core.spring(dampingRatio = if (ios) 0.28f else 0.36f, stiffness = 420f),
            label = "stripIconPop",
        )
    val fillAlpha =
        androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (isActive) 1f else 0f,
            animationSpec = androidx.compose.animation.core.tween(220),
            label = "stripIconFill",
        )
    val plateShape = RoundedCornerShape(14.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxHeight()
                .clickable(
                    enabled = enabled,
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    onClick = {
                        feel.performHapticFeedback(haptic)
                        onClick()
                    },
                ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .pressScale(0.9f)
                    .defaultMinSize(minWidth = 56.dp, minHeight = 44.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            scaleX = plateScale.value
                            scaleY = plateScale.value
                            alpha = plateAlpha.value
                        }.background(Brush.verticalGradient(listOf(ink.copy(alpha = 0.20f), ink.copy(alpha = 0.09f))), plateShape)
                        .border(0.5.dp, ink.copy(alpha = 0.14f), plateShape),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 10.dp),
            ) {
                // the icons of this row are drawn in the iOS pen on purpose (res/drawable/ios_bar_*), whatever the icon family is
                Box(
                    modifier =
                        Modifier
                            .size(28.dp)
                            .graphicsLayer {
                                scaleX = iconPop.value
                                scaleY = iconPop.value
                            },
                ) {
                    if (iconFill != null) {
                        Icon(
                            painter = painterResource(id = iconFill),
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(28.dp).graphicsLayer { alpha = fillAlpha.value * 0.30f },
                        )
                    }
                    Icon(
                        painter = painterResource(id = icon),
                        contentDescription = label,
                        tint = tint,
                        modifier = Modifier.size(28.dp),
                    )
                }
                if (showLabel) {
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                        color = tint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Flat numbered queue row: index | cover | title + artist | duration | trailing. Edge to edge, no rule underneath.
 * The playing row sits on surface-2 and shows the animated bars (or the play glyph) instead of its number: no accent.
 */
@Composable
internal fun QueueTrackRow(
    index: Int?,
    mediaMetadata: MediaMetadata,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    isPlaying: Boolean = false,
    trailingContent: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val artistNameAliases = com.metrolist.music.LocalArtistNameAliases.current
    val ink = MaterialTheme.colorScheme.onSurface
    val coverShape = RoundedCornerShape(com.metrolist.music.constants.ListThumbnailCornerRadius)
    val activeFill by androidx.compose.animation.animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(200),
        label = "queueRowFill",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .height(ListItemHeight)
                .background(activeFill)
                .padding(start = 20.dp, end = 8.dp),
    ) {
        Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
            if (isActive) {
                if (isPlaying) {
                    com.metrolist.music.ui.component.PlayingIndicator(
                        color = ink,
                        modifier = Modifier.size(16.dp),
                        bars = 3,
                        barWidth = 3.dp,
                        cornerRadius = 1.dp,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = null,
                        tint = ink,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else if (index != null) {
                Text(
                    text = index.toString().padStart(2, '0'),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        val thumbnailUrl = remember(mediaMetadata.thumbnailUrl) { mediaMetadata.thumbnailUrl?.resize(132, 132) }
        AsyncImage(
            model = thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(coverShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        )

        Column(
            verticalArrangement = Arrangement.Center,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
        ) {
            Text(
                text = mediaMetadata.title,
                style = MaterialTheme.typography.titleSmall,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    mediaMetadata.artists.joinToArtistString(" ${stringResource(R.string.and)} ") {
                        ArtistNameAliases.resolve(artistNameAliases, it.id, it.name)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = makeTimeString(mediaMetadata.duration * 1000L),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp),
        )

        trailingContent()
    }
}
