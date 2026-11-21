/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.search

import androidx.compose.foundation.layout.Spacer
import com.metrolist.music.ui.component.LocalIosMotion
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import com.metrolist.music.ui.component.glass
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.metrolist.music.ui.component.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.metrolist.music.ui.theme.pageBackgroundColor
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.metrolist.music.ui.component.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import com.metrolist.music.LocalNavController
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.utils.YouTubeUrlParser
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalIsPlayerExpanded
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.PauseSearchHistoryKey
import com.metrolist.music.constants.SearchSource
import com.metrolist.music.constants.SearchSourceKey
import com.metrolist.music.db.entities.SearchHistory
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.component.SearchSourceSwitch
import com.metrolist.music.ui.component.hairlineBottom
import com.metrolist.music.utils.SearchRoutes
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    pureBlack: Boolean,
    savedStateHandle: SavedStateHandle,
) {
    val navController = LocalNavController.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val playerConnection = LocalPlayerConnection.current
    val isPlayerExpanded = LocalIsPlayerExpanded.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var isHandlingScrollToTop by remember { mutableStateOf(false) }

    val scrollToTopCount by savedStateHandle.getStateFlow("scrollToTopCount", 0).collectAsStateWithLifecycle(initialValue = 0)

    var lastHandledCount by rememberSaveable { mutableIntStateOf(0) }
    // The keyboard no longer pops up by itself when the Search tab opens; it only comes up when the field is tapped,
    // or when the Search tab is tapped again while already on it (handled below).
    LaunchedEffect(scrollToTopCount) {
        if (scrollToTopCount > lastHandledCount) {
            lastHandledCount = scrollToTopCount
            isHandlingScrollToTop = true

            kotlinx.coroutines.delay(100)

            if (!isPlayerExpanded) {
                focusManager.clearFocus(force = true)
                kotlinx.coroutines.delay(50)
                try {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                } catch (e: Exception) {
                }
            }

            kotlinx.coroutines.delay(500)
            isHandlingScrollToTop = false
        }
    }

    var searchSource by rememberEnumPreference(SearchSourceKey, SearchSource.ONLINE)
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    val pauseSearchHistory by rememberPreference(PauseSearchHistoryKey, defaultValue = false)

    fun handleSearch(searchQuery: String) {
        if (searchQuery.isEmpty()) {
            return
        }

        focusManager.clearFocus()

        when (val parsedUrl = YouTubeUrlParser.parse(searchQuery)) {
            is YouTubeUrlParser.ParsedUrl.Video -> {
                playerConnection?.playQueue(
                    YouTubeQueue(
                        WatchEndpoint(videoId = parsedUrl.id),
                    ),
                )
            }

            is YouTubeUrlParser.ParsedUrl.Playlist -> {
                navController.navigate("online_playlist/${parsedUrl.id}")
            }

            is YouTubeUrlParser.ParsedUrl.Album -> {
                navController.navigate("album/MPREb_${parsedUrl.id}")
            }

            is YouTubeUrlParser.ParsedUrl.Artist -> {
                navController.navigate("artist/${parsedUrl.id}")
            }

            null -> {
                navController.navigate(SearchRoutes.resultRoute(searchQuery))
            }
        }

        if (!pauseSearchHistory) {
            coroutineScope.launch(Dispatchers.IO) {
                database.query {
                    insert(SearchHistory(query = searchQuery))
                }
            }
        }
    }

    val onSearch: (String) -> Unit = { searchQuery -> handleSearch(searchQuery) }

    val onSearchFromSuggestion: (String) -> Unit = { searchQuery -> handleSearch(searchQuery) }

    Scaffold(
        topBar = {
            // One symmetric header: the same 20 dp side margins as the YouTube | Perangkat switch under it (and the same
            // horizontal insets), the back arrow INSIDE the pill on the left and the mic (the clear button while there is text)
            // INSIDE it on the right, both slots 40 dp. It used to be a back arrow outside the field and nothing on the right.
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(pageBackgroundColor(pureBlack))
                        .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                        .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
            ) {
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder =
                        stringResource(
                            when (searchSource) {
                                SearchSource.LOCAL -> R.string.search_device
                                SearchSource.ONLINE -> R.string.search_yt_music
                            },
                        ),
                    onSearch = { onSearch(query.text) },
                    onBack = { navController.navigateUp() },
                    onClear = { query = TextFieldValue("") },
                    onMic = { navController.navigate("recognition") },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                )
            }
        },
        containerColor = pageBackgroundColor(pureBlack),
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .padding(top = paddingValues.calculateTopPadding())
                    .fillMaxSize(),
        ) {
            // Always visible so the user can see where the search runs: YouTube or the songs on this phone.
            SearchSourceSwitch(
                options =
                    listOf(
                        stringResource(R.string.search_source_youtube),
                        stringResource(R.string.search_source_device),
                    ),
                selectedIndex = if (searchSource == SearchSource.ONLINE) 0 else 1,
                onSelect = { index ->
                    searchSource = if (index == 0) SearchSource.ONLINE else SearchSource.LOCAL
                },
                modifier =
                    Modifier
                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                        .padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            when (searchSource) {
                SearchSource.LOCAL -> {
                    LocalSearchScreen(
                        query = query.text,
                        onDismiss = { navController.navigateUp() },
                        pureBlack = pureBlack,
                    )
                }

                SearchSource.ONLINE -> {
                    OnlineSearchScreen(
                        query = query.text,
                        onQueryChange = { query = it },
                        onSearch = onSearchFromSuggestion,
                        onDismiss = { /* Don't dismiss when searching from suggestions */ },
                        pureBlack = pureBlack,
                    )
                }
            }
            }
        }
    }

    // Handle lifecycle events to manage keyboard visibility
    DisposableEffect(lifecycleOwner, isPlayerExpanded) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        if (isHandlingScrollToTop) return@LifecycleEventObserver
                        // Always hide keyboard when resuming if player is expanded
                        if (isPlayerExpanded) {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }
                    }

                    Lifecycle.Event.ON_PAUSE -> {
                        if (isHandlingScrollToTop) return@LifecycleEventObserver
                        // Clear focus when pausing to prevent keyboard from showing on resume
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }

                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)

        // Initial check - hide keyboard if player is expanded
        if (isPlayerExpanded) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

/**
 * The search field of the search page: a glass pill (a flat surface-2 pill in "Datar") 48 dp high. With [onBack] and/or
 * [onClear] / [onMic] it is the symmetric header: a 40 dp back slot on the left and a 40 dp slot on the right (the mic while
 * the field is empty, the clear button while there is text, swapped with a small scale and fade), with the search icon and
 * the hint between them; without them it is the plain pill (search icon on the left). Pass the focus requester in [modifier].
 *
 * iOS animations (LocalIosMotion): the pill grows 1.2 percent with a spring and gets a brighter hairline while it has the
 * focus, and the right slot swaps with a bouncier spring. Both run in graphicsLayer / drawBehind (no recomposition per frame).
 */
@Composable
internal fun SearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    onMic: (() -> Unit)? = null,
) {
    val ios = LocalIosMotion.current
    val ink = MaterialTheme.colorScheme.onSurface
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusScale =
        animateFloatAsState(
            targetValue = if (ios && focused) 1.012f else 1f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = 380f),
            label = "searchFocusScale",
        )
    val focusRim =
        animateFloatAsState(
            targetValue = if (ios && focused) 0.24f else 0f,
            animationSpec = tween(180),
            label = "searchFocusRim",
        )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        interactionSource = interaction,
        modifier =
            modifier
                .then(
                    if (ios) {
                        Modifier.graphicsLayer {
                            scaleX = focusScale.value
                            scaleY = focusScale.value
                        }
                    } else {
                        Modifier
                    },
                ).height(48.dp)
                .glass(CircleShape)
                .drawBehind {
                    val alpha = focusRim.value
                    if (alpha > 0.001f) {
                        val w = 1.dp.toPx()
                        drawRoundRect(
                            color = ink.copy(alpha = alpha),
                            topLeft = Offset(w / 2f, w / 2f),
                            size = Size(size.width - w, size.height - w),
                            cornerRadius = CornerRadius((size.height - w) / 2f),
                            style = Stroke(w),
                        )
                    }
                }.padding(horizontal = 4.dp),
        textStyle =
            MaterialTheme.typography.titleMedium.copy(
                color = ink,
                fontSize = 16.sp,
            ),
        cursorBrush = SolidColor(ink),
        singleLine = true,
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    SearchPillSlot(onClick = onBack, icon = R.drawable.arrow_back, description = stringResource(R.string.dismiss))
                    Spacer(Modifier.width(2.dp))
                } else {
                    Spacer(Modifier.width(10.dp))
                }
                Icon(
                    painter = painterResource(R.drawable.search),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty()) {
                        Text(
                            text = placeholder,
                            style =
                                MaterialTheme.typography.titleMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 16.sp,
                                ),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (onClear != null || onMic != null) {
                    AnimatedContent(
                        targetState = value.text.isNotEmpty(),
                        transitionSpec = {
                            if (ios) {
                                (fadeIn(spring(dampingRatio = 0.7f, stiffness = 420f)) + scaleIn(spring(dampingRatio = 0.5f, stiffness = 420f), initialScale = 0.4f)) togetherWith
                                    (fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.4f))
                            } else {
                                (fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.8f)) togetherWith
                                    (fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.8f))
                            }
                        },
                        label = "searchTrailingSlot",
                    ) { hasText ->
                        if (hasText && onClear != null) {
                            SearchPillSlot(onClick = onClear, icon = R.drawable.close, description = stringResource(R.string.hub_search_clear))
                        } else if (onMic != null) {
                            SearchPillSlot(onClick = onMic, icon = R.drawable.mic, description = stringResource(R.string.you_recognize))
                        } else {
                            Spacer(Modifier.size(40.dp))
                        }
                    }
                } else {
                    Spacer(Modifier.width(10.dp))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

/** A 40 dp round button inside the pill, the icon 22 dp in the ink colour: the left and the right slot are the same piece. */
@Composable
private fun SearchPillSlot(
    onClick: () -> Unit,
    @androidx.annotation.DrawableRes icon: Int,
    description: String,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}
