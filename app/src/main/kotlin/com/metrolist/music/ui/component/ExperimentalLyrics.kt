/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.annotation.SuppressLint
import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.AiProviderKey
import com.metrolist.music.constants.AiSystemPromptKey
import com.metrolist.music.constants.DeeplApiKey
import com.metrolist.music.constants.DeeplFormalityKey
import com.metrolist.music.constants.LyricsBlurKey
import com.metrolist.music.constants.LyricsClickKey
import com.metrolist.music.constants.LyricsLineSpacingKey
import com.metrolist.music.constants.LyricsRomanizeAsMainKey
import com.metrolist.music.constants.LyricsRomanizeCyrillicByLineKey
import com.metrolist.music.constants.LyricsRomanizeList
import com.metrolist.music.constants.LyricsTextPositionKey
import com.metrolist.music.constants.LyricsTextSizeKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.OpenRouterBaseUrlKey
import com.metrolist.music.constants.OpenRouterDefaultBaseUrl
import com.metrolist.music.constants.OpenRouterDefaultModel
import com.metrolist.music.constants.OpenRouterModelKey
import com.metrolist.music.constants.RespectAgentPositioningKey
import com.metrolist.music.constants.ShowIntervalIndicatorKey
import com.metrolist.music.constants.TranslateLanguageKey
import com.metrolist.music.constants.TranslateModeKey
import com.metrolist.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsTiming
import com.metrolist.music.lyrics.LyricsTranslationHelper
import com.metrolist.music.lyrics.LyricsUiEvents
import com.metrolist.music.lyrics.LyricsUtils.findActiveLineIndices
import com.metrolist.music.lyrics.lyricsTextLooksSynced
import com.metrolist.music.ui.component.shimmer.ShimmerHost
import com.metrolist.music.ui.component.shimmer.TextPlaceholder
import com.metrolist.music.ui.screens.settings.LyricsPosition
import com.metrolist.music.ui.screens.settings.defaultList
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.LyricsSourceViewModel
import com.metrolist.music.viewmodels.LyricsViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/** After the user's last touch the view follows the song again after this long (the time a manual browse lasts). */
private const val LYRICS_PREVIEW_TIME = 8000L

/** Rows further than this from the anchor look the same (faintest, no blur, no word machinery): the distance a row is given stops here, so it does not recompose when the song moves on. */
internal const val LYRICS_DISTANCE_CAP = 5

/** The new lyrics fade in over this long instead of popping in. */
private const val LYRICS_FADE_IN_MS = 220

private val NoEdgeStep: () -> Int = { 0 }
private val NoSizeReport: (Int) -> Unit = {}

/** How the rows are drawn: the user's lyric settings, gathered so a row gets one stable value. */
@Immutable
internal data class LyricsListStyle(
    val textPosition: LyricsPosition,
    val textSizeSp: Float,
    val lineSpacing: Float,
    val respectAgentPositioning: Boolean,
    val romanizeAsMain: Boolean,
    val enabledLanguages: List<String>,
    val romanizeLyrics: Boolean,
    val motion: LyricsMotionEnv,
    val ink: Color,
)

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class
)
@SuppressLint("StringFormatInvalid")
@Composable
fun ExperimentalLyrics(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
    showLyrics: Boolean,
    playhead: LyricsPlayheadSource? = null,
    lyricsViewModel: LyricsViewModel = hiltViewModel(),
    sourceViewModel: LyricsSourceViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val database = LocalDatabase.current
    val context = LocalContext.current
    val listenTogetherManager = LocalListenTogetherManager.current
    val isGuest = listenTogetherManager?.isInRoom == true && !listenTogetherManager.isHost

    val lyricsTextPosition by rememberEnumPreference(LyricsTextPositionKey, DEFAULT_LYRICS_POSITION)
    val changeLyrics by rememberPreference(LyricsClickKey, true)
    val romanizeLyricsList = rememberPreference(LyricsRomanizeList, "")
    val romanizeAsMain by rememberPreference(LyricsRomanizeAsMainKey, false)
    val romanizeCyrillicByLine by rememberPreference(LyricsRomanizeCyrillicByLineKey, false)
    val respectAgentPositioning by rememberPreference(RespectAgentPositioningKey, true)
    val showIntervalIndicator by rememberPreference(ShowIntervalIndicatorKey, true)
    val lyricsBlur by rememberPreference(LyricsBlurKey, true)
    val userTextSize by rememberPreference(LyricsTextSizeKey, 24f)
    val userLineSpacing by rememberPreference(LyricsLineSpacingKey, 1.2f)
    // What this phone can afford: blur needs API 31, is off on low-RAM phones, in battery saver and without animations.
    val motionEnv = rememberLyricsMotionEnv(lyricsBlur)
    val typeSizeSp = LyricsMotion.TEXT_SP * LyricsMotion.sizeScale(userTextSize)
    val typeLineSpacing = LyricsMotion.LINE_HEIGHT_RATIO * LyricsMotion.spacingScale(userLineSpacing)

    // AI Translation Preferences
    val openRouterApiKey by rememberPreference(OpenRouterApiKey, "")
    val deeplApiKey by rememberPreference(DeeplApiKey, "")
    val aiProvider by rememberPreference(AiProviderKey, "Google")
    val openRouterBaseUrl by rememberPreference(OpenRouterBaseUrlKey, OpenRouterDefaultBaseUrl)
    val openRouterModel by rememberPreference(OpenRouterModelKey, OpenRouterDefaultModel)
    val translateLanguage by rememberPreference(TranslateLanguageKey, com.metrolist.music.constants.DefaultTranslateLanguage)
    val translateMode by rememberPreference(TranslateModeKey, "Literal")
    val deeplFormality by rememberPreference(DeeplFormalityKey, "default")
    val aiSystemPrompt by rememberPreference(AiSystemPromptKey, "")

    val scope = rememberCoroutineScope()

    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val translationStatus by LyricsTranslationHelper.status.collectAsStateWithLifecycle()
    val currentLyricsEntity by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    var lastValidLyricsEntity by remember { mutableStateOf<com.metrolist.music.db.entities.LyricsEntity?>(null) }

    LaunchedEffect(currentLyricsEntity) {
        if (currentLyricsEntity != null) {
            lastValidLyricsEntity = currentLyricsEntity
        }
    }

    val lyricsEntity = remember(currentLyricsEntity, translationStatus) {
        if (currentLyricsEntity != null) {
            currentLyricsEntity
        } else if (translationStatus is LyricsTranslationHelper.TranslationStatus.Translating || translationStatus is LyricsTranslationHelper.TranslationStatus.Success) {
            lastValidLyricsEntity
        } else {
            null
        }
    }
    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle(initialValue = null)
    val lyrics = remember(lyricsEntity) { lyricsEntity?.lyrics?.trim() }

    val enabledLanguages = remember(romanizeLyricsList.value) {
        if (romanizeLyricsList.value.isEmpty()) {
            defaultList
        } else {
            romanizeLyricsList.value.split(",").map { entry ->
                val (lang, checked) = entry.split(":")
                Pair(lang, checked.toBoolean())
            }
        }.filter { it.second }.map { it.first }
    }

    val lines by lyricsViewModel.lines.collectAsStateWithLifecycle()
    val mergedLyricsList by lyricsViewModel.mergedLyricsList.collectAsStateWithLifecycle()
    val processedLyrics by lyricsViewModel.processedLyrics.collectAsStateWithLifecycle()

    // The source picker and the timing bar: opened from the "Lyrics from X" chip, the empty state, or the lyrics menu.
    var showSources by remember { mutableStateOf(false) }
    var showTiming by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        LyricsUiEvents.listen(LyricsUiEvents.Event.OPEN_SOURCES)
        LyricsUiEvents.listen(LyricsUiEvents.Event.OPEN_TIMING)
        onDispose {
            LyricsUiEvents.stopListening(LyricsUiEvents.Event.OPEN_SOURCES)
            LyricsUiEvents.stopListening(LyricsUiEvents.Event.OPEN_TIMING)
        }
    }
    LaunchedEffect(Unit) {
        LyricsUiEvents.events.collect { event ->
            when (event) {
                LyricsUiEvents.Event.OPEN_SOURCES -> showSources = true
                LyricsUiEvents.Event.OPEN_TIMING -> showTiming = true
            }
        }
    }
    LaunchedEffect(mediaMetadata?.id) { showTiming = false }
    // (the once-per-run retry of a song stored as "no lyrics" and the 4.3.4 re-check run in InlineLyricsView, the one
    // host of both lyrics views)
    val retrying by sourceViewModel.retrying.collectAsStateWithLifecycle()
    val saveOffset: (Int) -> Unit = { value ->
        currentSong?.song?.let { song -> database.query { upsert(song.copy(lyricsOffset = LyricsTiming.clamp(value))) } }
    }

    LaunchedEffect(lyrics, enabledLanguages, romanizeCyrillicByLine, showIntervalIndicator) {
        lyricsViewModel.processLyrics(lyrics, enabledLanguages, romanizeCyrillicByLine, showIntervalIndicator)
    }

    val isSynced = remember(lyrics) { lyricsTextLooksSynced(lyrics) }
    // timing only means something for synced lyrics: asked for unsynced ones it says so, and it never stays armed for
    // the next lyrics (review 2026-11-12: "Waktu lirik" did nothing and popped up later)
    val timingUnsynced = stringResource(R.string.auralys_lyrics_timing_unsynced)
    LaunchedEffect(showTiming, isSynced, lyrics) {
        if (showTiming && !isSynced) {
            if (lyrics != null) Toast.makeText(context, timingUnsynced, Toast.LENGTH_SHORT).show()
            showTiming = false
        }
    }

    DisposableEffect(Unit) {
        LyricsTranslationHelper.setCompositionActive(true)
        onDispose {
            LyricsTranslationHelper.setCompositionActive(false)
            LyricsTranslationHelper.cancelTranslation()
        }
    }

    LaunchedEffect(lines, lyricsEntity, translateLanguage, translateMode) {
        if (lines.isNotEmpty() && lyricsEntity != null) {
            LyricsTranslationHelper.loadTranslationsFromDatabase(
                lyrics = lines,
                lyricsEntity = lyricsEntity,
                targetLanguage = translateLanguage,
                mode = translateMode
            )
        }
    }

    LaunchedEffect(
        showLyrics,
        lines,
        aiProvider,
        openRouterApiKey,
        deeplApiKey,
        openRouterBaseUrl,
        openRouterModel,
        translateLanguage,
        translateMode,
        deeplFormality,
        aiSystemPrompt,
        currentSong,
        database
    ) {
        LyricsTranslationHelper.manualTrigger.collectLatest {
            val effectiveApiKey = if (aiProvider == "Google") "keyless" else if (aiProvider == "DeepL") deeplApiKey else openRouterApiKey
            if (showLyrics && lines.isNotEmpty() && effectiveApiKey.isNotBlank()) {
                LyricsTranslationHelper.translateLyrics(
                    lyrics = lines,
                    targetLanguage = translateLanguage,
                    apiKey = openRouterApiKey,
                    baseUrl = openRouterBaseUrl,
                    model = openRouterModel,
                    mode = translateMode,
                    scope = scope,
                    context = context,
                    provider = aiProvider,
                    deeplApiKey = deeplApiKey,
                    deeplFormality = deeplFormality,
                    useStreaming = true,
                    songId = currentSong?.id ?: "",
                    database = database,
                    systemPrompt = aiSystemPrompt,
                )
            } else if (effectiveApiKey.isBlank()) {
                Toast.makeText(context, context.getString(R.string.ai_api_key_required), Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(lines) {
        LyricsTranslationHelper.clearTranslationsTrigger.collectLatest {
            lines.forEach { it.translatedTextFlow.value = null }
        }
    }

    // Lyrics: ink on the page; the colour comes from the page behind (cover tint), never from an accent of its own.
    val expressiveAccent = MaterialTheme.colorScheme.onSurface

    var showProgressDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showColorPickerDialog by remember { mutableStateOf(false) }
    var shareDialogData by remember { mutableStateOf<Triple<String, String, String>?>(null) }

    // The playback position comes from the player itself, every frame (the slider only while it is held); when the
    // music plays elsewhere (cast) the caller hands its own playhead.
    val playerPlayhead = rememberPlayerPlayhead(playerConnection)
    val source = playhead ?: playerPlayhead

    DisposableEffect(showLyrics) {
        val activity = context as? Activity
        if (showLyrics) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier.fillMaxSize().padding(bottom = 12.dp)
    ) {
        LyricsTranslationHeader(
            status = translationStatus,
            modifier = Modifier.zIndex(1f).padding(top = 56.dp)
        )

        // Lyrics text that was read and holds nothing to show (only tags or blank lines) used to leave an empty view.
        val nothingToShow = lyrics != null && lyrics != LYRICS_NOT_FOUND && processedLyrics == lyrics && mergedLyricsList.isEmpty()
        if (lyrics == LYRICS_NOT_FOUND || nothingToShow) {
            val meta = mediaMetadata
            val offline = stringResource(R.string.error_no_internet_connection)
            val stillNone = stringResource(R.string.lyrics_not_found)
            LyricsNotFoundState(
                onChooseSource = if (meta != null && !isGuest) ({ showSources = true }) else null,
                onRetry =
                    if (meta != null && !isGuest) {
                        {
                            sourceViewModel.retryNow(meta) { result ->
                                when (result) {
                                    com.metrolist.music.lyrics.LyricsStore.Result.NO_CONNECTION -> Toast.makeText(context, offline, Toast.LENGTH_SHORT).show()
                                    com.metrolist.music.lyrics.LyricsStore.Result.STORED -> Unit
                                    else -> Toast.makeText(context, stillNone, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else {
                        null
                    },
                retrying = retrying,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (lyrics == null && (translationStatus is LyricsTranslationHelper.TranslationStatus.Idle || translationStatus is LyricsTranslationHelper.TranslationStatus.Error)) {
             Column(modifier = Modifier.padding(top = 100.dp)) {
                 ShimmerHost { repeat(10) { Box(contentAlignment = when (lyricsTextPosition) {
                     LyricsPosition.LEFT -> Alignment.CenterStart; LyricsPosition.CENTER -> Alignment.Center; else -> Alignment.CenterEnd
                 }, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) { TextPlaceholder() } } }
             }
        } else {
            val style =
                LyricsListStyle(
                    textPosition = lyricsTextPosition,
                    textSizeSp = typeSizeSp,
                    lineSpacing = typeLineSpacing,
                    respectAgentPositioning = respectAgentPositioning,
                    romanizeAsMain = romanizeAsMain,
                    enabledLanguages = enabledLanguages,
                    romanizeLyrics = currentSong?.romanizeLyrics == true,
                    motion = motionEnv,
                    ink = expressiveAccent,
                )
            val provider = lyricsEntity?.provider?.takeIf { it != "Unknown" && it != "Manual" }
            LyricsListView(
                lines = lines,
                mergedLyricsList = mergedLyricsList,
                lyricsText = processedLyrics,
                // the text is still being read, or the lyrics on hand are the last song's (a new song's row is on its way)
                frozen = processedLyrics != lyrics || (lyricsEntity != null && mediaMetadata != null && lyricsEntity.id != mediaMetadata?.id),
                playhead = source,
                sliderPositionProvider = sliderPositionProvider,
                lyricsOffset = currentSong?.song?.lyricsOffset ?: 0,
                style = style,
                providerName = provider,
                // a chip: a tap opens the source picker (BitChord's "Lyrics by" line works the same way)
                onChipClick = { if (!isGuest) showSources = true },
                canSeek = changeLyrics && !isGuest,
                songDurationMs = { runCatching { playerConnection.player.duration }.getOrDefault(0L) },
                onSeek = { playerConnection.seekTo(it) },
                showTiming = showTiming && isSynced,
                // timing bar open: the tapped line is the one being sung now
                onTimingTap = { lineTime, position -> saveOffset(LyricsTiming.forTappedLine(lineTime, position)) },
                onShare = { text ->
                    shareDialogData = Triple(text, mediaMetadata?.title ?: "", mediaMetadata?.artists?.joinToString { it.name } ?: "")
                    showShareDialog = true
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (showTiming && isSynced) {
            LyricsTimingBar(
                offsetMs = currentSong?.song?.lyricsOffset ?: 0,
                onChange = saveOffset,
                onDone = { showTiming = false },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).zIndex(2f),
            )
        }
    }

    val sheetMeta = mediaMetadata
    if (showSources && sheetMeta != null) {
        LyricsSourceSheet(mediaMetadata = sheetMeta, shownProvider = lyricsEntity?.provider, shownLyrics = lyricsEntity?.lyrics, onDismiss = { showSources = false })
    }

    if (showProgressDialog) {
        BasicAlertDialog(onDismissRequest = {}) {
            Card(shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Box(Modifier.padding(32.dp)) { Text(stringResource(R.string.generating_image) + "\n" + stringResource(R.string.please_wait)) }
            }
        }
    }

    if (showShareDialog && shareDialogData != null) {
        val (txt, title, arts) = shareDialogData!!
        LyricsShareDialog(
            txt = txt, title = title, arts = arts, songId = mediaMetadata?.id ?: "",
            onDismiss = { showShareDialog = false },
            onShareAsImage = {
                showShareDialog = false
                showColorPickerDialog = true
            }
        )
    }

    if (showColorPickerDialog && shareDialogData != null) {
        val (txt, title, arts) = shareDialogData!!
        LyricsShareStudio(
            txt = txt, title = title, arts = arts, thumbnailUrl = mediaMetadata?.thumbnailUrl,
            onDismiss = { showColorPickerDialog = false },
        )
    }
}

/** The line to show first for lyrics that just came in: the last main line that has started at [effectiveMs] (or the top). */
internal fun startedLineIndex(lines: List<LyricsEntry>, effectiveMs: Long): Int {
    var found = 0
    for ((i, line) in lines.withIndex()) {
        if (line.time > effectiveMs) break
        if (!line.isBackground) found = i
    }
    return found
}

/** The row (index in [list]) that shows lyric line [line]: the line itself, or the gap after it when the line is blank. */
internal fun listIndexOfLine(list: List<LyricsListItem>, line: Int): Int =
    list.indexOfFirst {
        (it is LyricsListItem.Line && it.index == line) || (it is LyricsListItem.Indicator && it.afterLineIndex == line)
    }.coerceAtLeast(0)

/**
 * The scrolling lyrics: the rows, the glide to the line being sung, the user's drag, the selection for sharing, the
 * "Lyrics from X" chip and the auto-scroll button. It knows the player only through [playhead], so it can be measured
 * frame by frame in tests ([follow], [rowsState] and [clock] can be handed in for that).
 *
 * Per frame while a song plays: the clock moves, the active line is looked up (only worked out again when a line
 * starts or ends), the row being sung redraws its sweep. Nothing recomposes until a line changes, and then only the
 * rows whose look changes (about ten); the glide moves rows in the placement only.
 */
@SuppressLint("StringFormatInvalid")
@Composable
internal fun LyricsListView(
    lines: List<LyricsEntry>,
    mergedLyricsList: List<LyricsListItem>,
    lyricsText: String?,
    playhead: LyricsPlayheadSource,
    sliderPositionProvider: () -> Long?,
    lyricsOffset: Int,
    style: LyricsListStyle,
    providerName: String?,
    onChipClick: () -> Unit,
    canSeek: Boolean,
    songDurationMs: () -> Long,
    onSeek: (Long) -> Unit,
    showTiming: Boolean,
    onTimingTap: (lineTimeMs: Long, positionMs: Long) -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier,
    frozen: Boolean = false,
    follow: LyricsFollow = remember { LyricsFollow() },
    rowsState: LyricsRowsState = remember { LyricsRowsState() },
    clock: LyricsClock = remember { LyricsClock() },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val motion = style.motion
    val isSynced = remember(lyricsText) { lyricsTextLooksSynced(lyricsText) }
    val hasWordTimings = remember(lines) { lines.any { it.words?.isNotEmpty() == true } }
    // line-synced lyrics scroll a quarter second before the line starts; word-synced ones when it starts
    val lookaheadMs = if (hasWordTimings) 0L else 250L

    // The position of the song (ms, without the lyrics offset), written once per frame by the clock and read only where
    // it is drawn (the sweep, the gap dots) or in derived states.
    val position = remember { mutableLongStateOf(Snapshot.withoutReadObservation { playhead.positionMs() }) }
    val positionProvider = remember(position) { { position.longValue } }
    val latestOffset by rememberUpdatedState(lyricsOffset)
    val latestFrozen by rememberUpdatedState(frozen)

    val activeLines = remember { mutableStateOf(emptySet<Int>()) }
    var scrollTargetIndex by remember { mutableIntStateOf(-1) }
    // the lyric line the view is anchored on, and the line times it belongs to (a new list of other lyrics must not use it)
    val anchorLine = remember { mutableIntStateOf(0) }
    val lineTimes = remember(lines) { lines.map { it.time } }
    val anchorFor = remember { mutableStateOf<List<Long>?>(null, referentialEqualityPolicy()) }
    var isSeeking by remember { mutableStateOf(false) }
    var isAutoScrollEnabled by rememberSaveable { mutableStateOf(true) }
    var lastPreviewTime by rememberSaveable { mutableLongStateOf(0L) }
    var touching by remember { mutableStateOf(false) }
    var userManualOffset by remember { mutableFloatStateOf(0f) }
    var isSelectionModeActive by rememberSaveable { mutableStateOf(false) }
    val selectedIndices = rememberSaveable(saver = listSaver<SnapshotStateList<Int>, Int>(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf<Int>() }
    // the line times the state above was set for: kept over a configuration change, so the reset below can tell new
    // lyrics from the same lyrics shown again
    var stateForTimes by rememberSaveable { mutableIntStateOf(0) }
    var showMaxSelectionToast by remember { mutableStateOf(false) }
    var flingJob by remember { mutableStateOf<Job?>(null) }

    BackHandler(enabled = isSelectionModeActive) {
        isSelectionModeActive = false
        selectedIndices.clear()
    }

    val maxSelectionLimit = 5
    LaunchedEffect(showMaxSelectionToast) {
        if (showMaxSelectionToast) {
            Toast.makeText(context, context.getString(R.string.max_selection_limit, maxSelectionLimit), Toast.LENGTH_SHORT).show()
            showMaxSelectionToast = false
        }
    }

    // The line to show for lyrics that just came in, worked out from the position when they come: before, the first
    // frames showed the top of the new lyrics and then the view slid down to the line being sung.
    val initialLine =
        remember(lines, isSynced) {
            if (isSynced) startedLineIndex(lines, Snapshot.withoutReadObservation { position.longValue } + lyricsOffset + lookaheadMs) else 0
        }

    // New lyrics (other line times): start again from the line being sung. The same lyrics made again (translation,
    // romanisation, the gap setting) keep their place, their selection and the user's scroll.
    LaunchedEffect(lineTimes) {
        // a fling still running would keep moving the new lyrics off their place
        flingJob?.cancel()
        userManualOffset = 0f
        scrollTargetIndex = if (isSynced) initialLine else -1
        anchorLine.intValue = initialLine
        anchorFor.value = lineTimes
        val timesKey = lineTimes.hashCode()
        if (stateForTimes != timesKey) {
            stateForTimes = timesKey
            isAutoScrollEnabled = true
            lastPreviewTime = 0L
            isSelectionModeActive = false
            selectedIndices.clear()
        }
    }

    val anchorListIndex =
        remember(mergedLyricsList, lineTimes, initialLine) {
            derivedStateOf {
                val line = if (anchorFor.value == lineTimes) anchorLine.intValue else initialLine
                listIndexOfLine(mergedLyricsList, line)
            }
        }

    // the user stopped browsing by hand: follow the song again, gliding from where the content is now
    val latestAnchor by rememberUpdatedState(anchorListIndex)
    val resumeFollow: () -> Unit = remember {
        {
            flingJob?.cancel()
            if (userManualOffset != 0f) {
                follow.shift(userManualOffset, latestAnchor.value)
                userManualOffset = 0f
            }
            isAutoScrollEnabled = true
            lastPreviewTime = 0L
        }
    }
    val latestLines by rememberUpdatedState(lines)
    val latestTimes by rememberUpdatedState(lineTimes)
    val resync: () -> Unit = remember {
        {
            var target = scrollTargetIndex
            if (target == -1) target = startedLineIndex(latestLines, position.longValue + latestOffset)
            scrollTargetIndex = target
            anchorLine.intValue = target
            anchorFor.value = latestTimes
            resumeFollow()
        }
    }

    // The frame loop: the clock, the active line and the line the view scrolls to. The rules of the scroll target are the
    // ones of 4.3.4; a seek (the slider, a tap on a line, a jump of the player) may move it backwards.
    LaunchedEffect(lines, isSynced, playhead) {
        val list = lines
        val times = lineTimes
        if (!isSynced || list.isEmpty()) {
            activeLines.value = emptySet()
            return@LaunchedEffect
        }
        val nowCache = ActiveLinesCache(list) { findActiveLineIndices(list, it) }
        val aheadCache = if (lookaheadMs == 0L) nowCache else ActiveLinesCache(list) { findActiveLineIndices(list, it) }
        var lastNow: Set<Int>? = null
        var lastAhead: Set<Int>? = null
        var previousScrollActive: Set<Int> = emptySet()
        var lastMainMaxSeen = -1
        var lastOffset = latestOffset

        runLyricsPlayhead(playhead, clock, sliderPositionProvider) { pos, slider ->
            // other lyrics are on their way (song change): these stay as they are instead of running to the top
            if (latestFrozen) return@runLyricsPlayhead
            position.longValue = pos
            isSeeking = slider
            // a new lyrics offset ("Waktu lirik") moves the lyric time like a seek: the view may go back to the line sung
            val offset = latestOffset
            val offsetChanged = offset != lastOffset
            lastOffset = offset
            val seeking = slider || clock.jumped || offsetChanged
            val effective = pos + offset
            val initialActive = nowCache.at(effective)
            val scrollActiveRaw = aheadCache.at(effective + lookaheadMs)
            // nothing started or ended since the last frame: the answer is the same (the old loop rebuilt it 30 times a second)
            if (!seeking && initialActive === lastNow && scrollActiveRaw === lastAhead) return@runLyricsPlayhead
            lastNow = initialActive
            lastAhead = scrollActiveRaw

            val scrollActive = scrollActiveRaw.toMutableSet()
            for (i in scrollActiveRaw) {
                if (list.getOrNull(i)?.isBackground == true) {
                    for (j in i - 1 downTo 0) {
                        if (list.getOrNull(j)?.isBackground == false) {
                            scrollActive.add(j)
                            break
                        }
                    }
                }
            }
            val newActive = initialActive.toMutableSet()
            for (i in initialActive) {
                if (list.getOrNull(i)?.isBackground == true) {
                    for (j in i - 1 downTo 0) {
                        if (list.getOrNull(j)?.isBackground == false) {
                            newActive.add(j)
                            break
                        }
                    }
                }
            }

            val scrollMax = scrollActive.filter { list.getOrNull(it)?.isBackground == false }.maxOrNull() ?: (scrollActive.maxOrNull() ?: -1)
            val isCurrentTargetStillActive = scrollTargetIndex in scrollActive
            val anyStillActive = scrollActive.isNotEmpty()
            val shouldScroll =
                when {
                    seeking -> true
                    // If current target just ended and there are other active lines, scroll to the new max
                    !isCurrentTargetStillActive && anyStillActive && scrollMax > scrollTargetIndex -> true
                    // If current target just ended and no other active lines
                    !isCurrentTargetStillActive && !anyStillActive && previousScrollActive.isNotEmpty() -> true
                    // If we don't have a target yet and lines became active
                    scrollTargetIndex == -1 && anyStillActive -> true
                    // New line started while nothing was active before
                    previousScrollActive.isEmpty() && anyStillActive && scrollMax > scrollTargetIndex -> true
                    else -> false
                }
            if (shouldScroll) {
                val targetToScroll =
                    when {
                        // a seek into a pause between lines: the last line that has started (before: stayed where it was)
                        seeking -> if (scrollMax != -1) scrollMax else startedLineIndex(list, effective + lookaheadMs)
                        !isCurrentTargetStillActive && anyStillActive -> scrollMax
                        !isCurrentTargetStillActive && !anyStillActive ->
                            (lastMainMaxSeen + 1 until list.size).firstOrNull { list.getOrNull(it)?.isBackground == false } ?: scrollTargetIndex
                        else -> scrollMax
                    }
                if (targetToScroll != -1 && (seeking || targetToScroll > scrollTargetIndex)) scrollTargetIndex = targetToScroll
            }
            if (seeking) lastMainMaxSeen = scrollMax
            if (scrollMax > lastMainMaxSeen && scrollMax != -1) lastMainMaxSeen = scrollMax
            previousScrollActive = scrollActive
            activeLines.value = newActive
            // the anchor follows in the same frame (before: one frame later, through an effect)
            if (isAutoScrollEnabled && scrollTargetIndex != -1) {
                anchorLine.intValue = scrollTargetIndex
                anchorFor.value = times
            }
        }
    }

    // After a manual browse the view follows the song again by itself (the sync button still does it at once).
    LaunchedEffect(isAutoScrollEnabled, lastPreviewTime, touching, isSelectionModeActive, isSynced) {
        if (!isAutoScrollEnabled && isSynced && !touching && !isSelectionModeActive && lastPreviewTime != 0L) {
            delay(LYRICS_PREVIEW_TIME)
            resync()
        }
    }

    val animateLatest by rememberUpdatedState(motion.animate)
    LaunchedEffect(follow) { follow.runGlide { animateLatest } }

    // New lyrics fade in (220 ms) instead of popping in; the same lyrics made again do not fade.
    val fade = remember { Animatable(0f) }
    var fadedIn by remember { mutableStateOf<String?>(null) }
    val hasRows = mergedLyricsList.isNotEmpty()
    LaunchedEffect(lyricsText, hasRows) {
        if (lyricsText != null && hasRows && fadedIn != lyricsText) {
            fade.snapTo(0f)
            fadedIn = lyricsText
            if (motion.animate) fade.animateTo(1f, tween(LYRICS_FADE_IN_MS)) else fade.snapTo(1f)
        } else if (lyricsText != null && hasRows && fade.value < 1f) {
            if (motion.animate) fade.animateTo(1f, tween(LYRICS_FADE_IN_MS)) else fade.snapTo(1f)
        }
    }
    val latestText by rememberUpdatedState(lyricsText)
    val listAlpha: () -> Float = remember { { if (fadedIn == latestText) fade.value else 0f } }

    val keys = remember(mergedLyricsList) { LongArray(mergedLyricsList.size) { lyricsRowKey(mergedLyricsList[it]) } }
    val noGapBefore: (Int) -> Boolean =
        remember(mergedLyricsList) {
            { i ->
                val item = mergedLyricsList.getOrNull(i)
                (item as? LyricsListItem.Line)?.entry?.isBackground == true || item is LyricsListItem.Indicator
            }
        }
    // lyrics without timing: how far from the middle of the view each row is (stepped, so a row only recomposes on a step)
    val edgeSteps: Array<MutableIntState>? = remember(mergedLyricsList, isSynced) { if (isSynced) null else Array(mergedLyricsList.size) { mutableIntStateOf(0) } }
    val onPlaced: LyricsRowPlaced? =
        remember(edgeSteps) {
            edgeSteps?.let { steps ->
                LyricsRowPlaced { i, y, height, area ->
                    val step = LyricsMotion.edgeStep(y + height / 2f, area.toFloat())
                    steps.getOrNull(i)?.let { if (it.intValue != step) it.intValue = step }
                }
            }
        }

    val velocityTracker = remember { VelocityTracker() }
    val decayAnimSpec = remember { exponentialDecay<Float>(frictionMultiplier = 1.8f) }

    // one click handler for every row, so a row does not recompose because a new lambda was made
    val clickState =
        rememberUpdatedState { index: Int, timeMs: Long ->
            if (showTiming && isSynced && !isSelectionModeActive) {
                onTimingTap(timeMs, position.longValue)
                resync()
            } else if (isSelectionModeActive) {
                if (selectedIndices.contains(index)) {
                    selectedIndices.remove(index)
                    if (selectedIndices.isEmpty()) isSelectionModeActive = false
                } else if (selectedIndices.size < maxSelectionLimit) {
                    selectedIndices.add(index)
                } else {
                    showMaxSelectionToast = true
                }
            } else if (canSeek) {
                if (timeMs < songDurationMs() + 30000L) {
                    onSeek((timeMs - lyricsOffset).coerceAtLeast(0))
                } else {
                    scrollTargetIndex = index
                }
                anchorLine.intValue = index
                anchorFor.value = lineTimes
                resumeFollow()
            }
        }
    val onRowClick: (Int, Long) -> Unit = remember { { i: Int, t: Long -> clickState.value(i, t) } }
    val onRowLongClick: (Int) -> Unit =
        remember {
            { index: Int ->
                if (!isSelectionModeActive) {
                    isSelectionModeActive = true
                    selectedIndices.add(index)
                }
            }
        }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .lyricsEdgeFade(listAlpha)
                .clipToBounds()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            touching = true
                            // a tap is a tap (a line, the chip): only a real drag takes the view off the song
                            var overSlop = 0f
                            val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                                change.consume()
                                overSlop = over
                            }
                            if (drag == null) {
                                touching = false
                                continue
                            }
                            flingJob?.cancel()
                            // a glide still on finishes under the finger (stopping it would make the rows behind jump)
                            isAutoScrollEnabled = false
                            lastPreviewTime = System.currentTimeMillis()
                            velocityTracker.resetTracking()
                            velocityTracker.addPosition(down.uptimeMillis, down.position)
                            userManualOffset = (userManualOffset + overSlop).coerceIn(rowsState.dragMin, rowsState.dragMax)
                            velocityTracker.addPosition(drag.uptimeMillis, drag.position)
                            verticalDrag(drag.id) { change ->
                                userManualOffset = (userManualOffset + change.positionChange().y).coerceIn(rowsState.dragMin, rowsState.dragMax)
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                            }
                            touching = false
                            lastPreviewTime = System.currentTimeMillis()
                            val velocity = velocityTracker.calculateVelocity().y.takeIf { it.isFinite() } ?: 0f
                            flingJob = scope.launch {
                                AnimationState(initialValue = userManualOffset, initialVelocity = velocity).animateDecay(decayAnimSpec) {
                                    val clamped = value.coerceIn(rowsState.dragMin, rowsState.dragMax)
                                    userManualOffset = clamped
                                    if (value != clamped) cancelAnimation()
                                }
                                lastPreviewTime = System.currentTimeMillis()
                            }
                        }
                    }
                }
        ) {
            val lyricsOffsetVal = lyricsOffset.toLong()
            LyricsRowsLayout(
                keys = keys,
                anchorIndex = { anchorListIndex.value },
                noGapBefore = noGapBefore,
                follow = follow,
                manualOffset = { userManualOffset },
                glide = { isAutoScrollEnabled && motion.animate },
                state = rowsState,
                onPlaced = onPlaced,
                modifier = Modifier.fillMaxSize(),
                chip = {
                    if (providerName != null && !isSelectionModeActive) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                            contentAlignment = when (style.textPosition) {
                                LyricsPosition.LEFT -> Alignment.CenterStart
                                LyricsPosition.CENTER -> Alignment.Center
                                else -> Alignment.CenterEnd
                            },
                        ) {
                            LyricsSourceChip(text = stringResource(R.string.lyrics_from_provider, providerName), onClick = onChipClick)
                        }
                    }
                },
                rows = {
                    mergedLyricsList.forEachIndexed { listIndex, listItem ->
                        key(keys[listIndex]) {
                            LyricsListRow(
                                listIndex = listIndex,
                                listItem = listItem,
                                lines = lines,
                                anchor = anchorListIndex,
                                activeLines = activeLines,
                                selectedIndices = selectedIndices,
                                isSelectionModeActive = isSelectionModeActive,
                                isAutoScrollEnabled = isAutoScrollEnabled,
                                isSynced = isSynced,
                                positionProvider = positionProvider,
                                lyricsOffset = lyricsOffsetVal,
                                edgeStep = edgeSteps?.getOrNull(listIndex),
                                style = style,
                                onClick = onRowClick,
                                onLongClick = onRowLongClick,
                            )
                        }
                    }
                },
            )
        }

        if (!showTiming) LyricsActionOverlay(
            modifier = Modifier.align(Alignment.BottomCenter),
            isAutoScrollEnabled = isAutoScrollEnabled, isSynced = isSynced,
            isSelectionModeActive = isSelectionModeActive, anySelected = selectedIndices.isNotEmpty(),
            onSyncClick = resync,
            onCancelSelection = { isSelectionModeActive = false; selectedIndices.clear() },
            onShareSelection = {
                val text = selectedIndices.sorted().mapNotNull { lines.getOrNull(it)?.text }.joinToString("\n")
                if (text.isNotBlank()) onShare(text)
                isSelectionModeActive = false; selectedIndices.clear()
            }
        )
    }
}

/**
 * One row of the list. What changes when the song moves on (its distance to the anchor, whether it is sung, whether a
 * background line shows) is read here from the shared states through derived states, so only the rows whose look
 * really changes recompose, never the list.
 */
@Composable
private fun LyricsListRow(
    listIndex: Int,
    listItem: LyricsListItem,
    lines: List<LyricsEntry>,
    anchor: State<Int>,
    activeLines: State<Set<Int>>,
    selectedIndices: SnapshotStateList<Int>,
    isSelectionModeActive: Boolean,
    isAutoScrollEnabled: Boolean,
    isSynced: Boolean,
    positionProvider: () -> Long,
    lyricsOffset: Long,
    edgeStep: MutableIntState?,
    style: LyricsListStyle,
    onClick: (Int, Long) -> Unit,
    onLongClick: (Int) -> Unit,
) {
    val distance by remember(listIndex, anchor) { derivedStateOf { min(abs(listIndex - anchor.value), LYRICS_DISTANCE_CAP) } }
    when (listItem) {
        is LyricsListItem.Indicator -> {
            val visible by remember(listItem, isAutoScrollEnabled, lyricsOffset) {
                derivedStateOf {
                    val pos = positionProvider() + lyricsOffset
                    isAutoScrollEnabled && pos >= listItem.gapStartMs && pos <= listItem.gapEndMs - 650L
                }
            }
            val gapPosition = remember(positionProvider, lyricsOffset) { { positionProvider() + lyricsOffset } }
            IntervalIndicator(
                listItem.gapStartMs,
                listItem.gapEndMs - 650L,
                gapPosition,
                visible,
                style.ink,
                Modifier.fillMaxWidth(),
                horizontal = LyricsMotion.horizontalFor(listItem.nextAgent, style.respectAgentPositioning, style.textPosition),
                animate = style.motion.animate,
            )
        }
        is LyricsListItem.Line -> {
            val index = listItem.index
            val item = listItem.entry
            val isActiveLine by remember(index, activeLines) { derivedStateOf { activeLines.value.contains(index) } }
            val paired = remember(item, index, lines) {
                if (item.isBackground) (index - 1 downTo 0).firstOrNull { lines.getOrNull(it)?.isBackground == false } ?: -1 else -1
            }
            val bgVisible by remember(item, index, lines, lyricsOffset, paired) {
                derivedStateOf {
                    if (!item.isBackground) return@derivedStateOf false
                    val active = activeLines.value
                    if (active.contains(paired) || active.contains(index)) return@derivedStateOf true
                    // between its main line's start and its own: read every frame, but only for background lines
                    if (paired == -1) return@derivedStateOf false
                    val pos = positionProvider() + lyricsOffset
                    pos >= lines[paired].time && pos <= item.time
                }
            }
            val edge: () -> Int = if (edgeStep == null) NoEdgeStep else remember(edgeStep) { { edgeStep.intValue } }
            LyricsLine(
                index = index, item = item, nextTimeMs = lines.getOrNull(index + 1)?.time, isSynced = isSynced,
                isActiveLine = isActiveLine,
                bgVisible = bgVisible, isSelected = selectedIndices.contains(index),
                isSelectionModeActive = isSelectionModeActive, currentPositionProvider = positionProvider,
                lyricsOffset = lyricsOffset,
                distance = distance, onScreen = true, edgeStep = edge, motion = style.motion,
                lyricsTextSize = style.textSizeSp, lyricsLineSpacing = style.lineSpacing,
                expressiveAccent = style.ink, lyricsTextPosition = style.textPosition,
                respectAgentPositioning = style.respectAgentPositioning, isAutoScrollEnabled = isAutoScrollEnabled,
                romanizeAsMain = style.romanizeAsMain,
                enabledLanguages = style.enabledLanguages, romanizeLyrics = style.romanizeLyrics,
                onSizeChanged = NoSizeReport,
                onClick = { onClick(index, item.time) },
                onLongClick = { onLongClick(index) },
            )
        }
    }
}
