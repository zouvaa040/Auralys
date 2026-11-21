/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.library

import com.metrolist.music.ui.component.glass
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.local.LocalMusicPermission
import com.metrolist.music.local.LocalSongSort
import com.metrolist.music.local.LocalSongSortDescendingKey
import com.metrolist.music.local.LocalSongSortKey
import com.metrolist.music.local.LocalSongsViewModel
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.DraggableScrollbar
import com.metrolist.music.ui.component.EmptyPlaceholder
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.RowMoreButton
import com.metrolist.music.ui.component.SongListItem
import com.metrolist.music.ui.component.SortHeader
import com.metrolist.music.ui.component.TopAppBar
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.component.pressScale
import com.metrolist.music.ui.menu.SongMenu
import com.metrolist.music.ui.theme.styledShape
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private tailrec fun Context.activityOrNull(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activityOrNull()
        else -> null
    }

/** "Di perangkat": the songs found on the phone, with the permission, the scan and the usual song rows. */
@Composable
fun LocalSongsScreen(
    navController: NavController,
    viewModel: LocalSongsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val count by viewModel.count.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()

    val (sort, onSortChange) = rememberEnumPreference(LocalSongSortKey, LocalSongSort.NAME)
    val (sortDescending, onSortDescendingChange) = rememberPreference(LocalSongSortDescendingKey, false)

    // permission: asked only here, and only when the button is pressed
    val permission = remember { LocalMusicPermission.permissionFor() }
    var granted by remember { mutableStateOf(LocalMusicPermission.isGranted(context)) }
    var blockedForGood by rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            granted = ok
            blockedForGood =
                !ok && (context.activityOrNull()?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } ?: true)
        }
    // coming back from the system settings
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    granted = LocalMusicPermission.isGranted(context)
                    if (granted) blockedForGood = false
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // opening the screen (or getting the permission) looks for new files
    LaunchedEffect(granted) { if (granted) viewModel.scan() }

    val listTitle = stringResource(R.string.local_music_title)

    fun play(startId: String?, shuffled: Boolean) {
        if (songs.isEmpty()) return
        val snapshot = songs
        scope.launch {
            val items =
                withContext(Dispatchers.Default) {
                    snapshot.map { it.toMediaItem() }
                }
            // 4.3.4: "Acak" = shuffle ON from a random song, "Putar" = in order, a tapped song keeps the shuffle state
            val queue =
                when {
                    shuffled -> ListQueue.shuffled(title = listTitle, items = items)
                    startId == null -> ListQueue(title = listTitle, items = items, shuffle = false)
                    else ->
                        ListQueue(
                            title = listTitle,
                            items = items,
                            startIndex = items.indexOfFirst { it.mediaId == startId }.coerceAtLeast(0),
                        )
                }
            playerConnection.playQueue(queue)
        }
    }

    val lazyListState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            if (!granted) {
                item(key = "permission") {
                    PermissionCard(
                        blockedForGood = blockedForGood,
                        onGrant = { launcher.launch(permission) },
                        onOpenSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                    )
                }
            } else {
                item(key = "header") {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(
                            text = pluralStringResource(R.plurals.n_song, count, count),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        val status =
                            when {
                                scanState.scanning -> stringResource(R.string.local_scanning, scanState.progress)
                                scanState.failed -> stringResource(R.string.local_scan_failed)
                                scanState.lastResult?.let { it.added + it.removed } ?: 0 > 0 ->
                                    stringResource(R.string.local_scan_changes, scanState.lastResult!!.added, scanState.lastResult!!.removed)
                                else -> null
                            }
                        if (status != null) {
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Surface(
                                onClick = { play(null, shuffled = false) },
                                enabled = songs.isNotEmpty(),
                                color = MaterialTheme.colorScheme.primary,
                                shape = androidx.compose.foundation.shape.CircleShape,
                                modifier = Modifier.weight(1f).height(48.dp).pressScale(),
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.play),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(
                                        text = stringResource(R.string.play),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                            RoundAction(R.drawable.shuffle, R.string.shuffle, enabled = songs.isNotEmpty()) { play(null, shuffled = true) }
                            RoundAction(R.drawable.refresh, R.string.local_scan, enabled = !scanState.scanning) { viewModel.scan() }
                        }
                    }
                }

                if (songs.isNotEmpty()) {
                    item(key = "sort") {
                        Row(modifier = Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            SortHeader(
                                sortType = sort,
                                sortDescending = sortDescending,
                                onSortTypeChange = onSortChange,
                                onSortDescendingChange = onSortDescendingChange,
                                sortTypeText = { type ->
                                    when (type) {
                                        LocalSongSort.NAME -> R.string.sort_by_name
                                        LocalSongSort.ARTIST -> R.string.sort_by_artist
                                        LocalSongSort.DATE_ADDED -> R.string.sort_by_create_date
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else if (!scanState.scanning) {
                    item(key = "empty") {
                        EmptyPlaceholder(icon = R.drawable.music_note, text = stringResource(R.string.local_empty), modifier = Modifier)
                    }
                }

                items(songs, key = { it.id }) { song ->
                    SongListItem(
                        song = song,
                        isActive = song.id == mediaMetadata?.id,
                        isPlaying = isPlaying,
                        showInLibraryIcon = false,
                        showDownloadIcon = false,
                        trailingContent = {
                            RowMoreButton(
                                onClick = {
                                    menuState.show {
                                        SongMenu(originalSong = song, onDismiss = menuState::dismiss)
                                    }
                                },
                            )
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (song.id == mediaMetadata?.id) {
                                        playerConnection.togglePlayPause()
                                    } else {
                                        play(song.id, shuffled = false)
                                    }
                                },
                    )
                }
            }
        }

        DraggableScrollbar(
            modifier =
                Modifier
                    .padding(LocalPlayerAwareWindowInsets.current.union(WindowInsets.ime).asPaddingValues())
                    .align(Alignment.CenterEnd),
            scrollState = lazyListState,
            headerItems = 2,
        )

        TopAppBar(
            title = { Text(text = listTitle, style = MaterialTheme.typography.titleLarge) },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                    Icon(painter = painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
        )
    }
}

@Composable
private fun RoundAction(
    icon: Int,
    description: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = androidx.compose.ui.graphics.Color.Transparent,
        shape = androidx.compose.foundation.shape.CircleShape,
        modifier = Modifier.size(48.dp).pressScale().glass(androidx.compose.foundation.shape.CircleShape),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                painter = painterResource(icon),
                contentDescription = stringResource(description),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun PermissionCard(
    blockedForGood: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.music_note),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.local_permission_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(if (blockedForGood) R.string.local_permission_blocked else R.string.local_permission_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(24.dp))
        Surface(
            onClick = if (blockedForGood) onOpenSettings else onGrant,
            color = MaterialTheme.colorScheme.primary,
            shape = androidx.compose.foundation.shape.CircleShape,
            modifier = Modifier.height(48.dp).pressScale(),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 28.dp).fillMaxHeight()) {
                Text(
                    text = stringResource(if (blockedForGood) R.string.local_open_settings else R.string.local_permission_grant),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
