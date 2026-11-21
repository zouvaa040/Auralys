/**
 * Auralys - lyrics on the lock screen
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.lockscreen

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.C
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.metrolist.music.R
import com.metrolist.music.constants.AppFontKey
import com.metrolist.music.constants.AppFontWeightKey
import com.metrolist.music.constants.EnableHighRefreshRateKey
import com.metrolist.music.constants.GlassLensKey
import com.metrolist.music.constants.LyricsBlurKey
import com.metrolist.music.constants.LyricsLineSpacingKey
import com.metrolist.music.constants.LyricsTextPositionKey
import com.metrolist.music.constants.LyricsTextSizeKey
import com.metrolist.music.constants.RespectAgentPositioningKey
import com.metrolist.music.constants.ShowIntervalIndicatorKey
import com.metrolist.music.extensions.currentMetadata
import com.metrolist.music.extensions.togglePlayPause
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsUtils
import com.metrolist.music.playback.LockScreenLyricsHost
import com.metrolist.music.ui.component.ActiveLinesCache
import com.metrolist.music.ui.component.DEFAULT_LYRICS_POSITION
import com.metrolist.music.ui.component.GlassLensGuard
import com.metrolist.music.ui.component.GlassLensPolicy
import com.metrolist.music.ui.component.GlassLensRecipe
import com.metrolist.music.ui.component.GlassLensSystem
import com.metrolist.music.ui.component.IntervalIndicator
import com.metrolist.music.ui.component.LYRICS_DISTANCE_CAP
import com.metrolist.music.ui.component.LensMode
import com.metrolist.music.ui.component.LyricsClock
import com.metrolist.music.ui.component.LyricsFollow
import com.metrolist.music.ui.component.LyricsLine
import com.metrolist.music.ui.component.LyricsListItem
import com.metrolist.music.ui.component.LyricsMotion
import com.metrolist.music.ui.component.LyricsMotionEnv
import com.metrolist.music.ui.component.LyricsRowsLayout
import com.metrolist.music.ui.component.LyricsRowsState
import com.metrolist.music.ui.component.PlayerPlayheadSource
import com.metrolist.music.ui.component.SystemIcons
import com.metrolist.music.ui.component.applyGlassRecipe
import com.metrolist.music.ui.component.backdrop.Backdrop
import com.metrolist.music.ui.component.backdrop.backdrops.layerBackdrop
import com.metrolist.music.ui.component.backdrop.backdrops.rememberLayerBackdrop
import com.metrolist.music.ui.component.backdrop.drawBackdrop
import com.metrolist.music.ui.component.glassFrost
import com.metrolist.music.ui.component.listIndexOfLine
import com.metrolist.music.ui.component.lyricsEdgeFade
import com.metrolist.music.ui.component.lyricsRowKey
import com.metrolist.music.ui.component.painterResource
import com.metrolist.music.ui.component.rememberLyricsMotionEnv
import com.metrolist.music.ui.component.runLyricsPlayhead
import com.metrolist.music.ui.component.startedLineIndex
import com.metrolist.music.ui.screens.ambient.DefaultAmbientColors
import com.metrolist.music.ui.screens.ambient.extractAmbientColors
import com.metrolist.music.ui.screens.settings.LyricsPosition
import com.metrolist.music.ui.theme.AppFont
import com.metrolist.music.ui.theme.MetrolistTheme
import com.metrolist.music.ui.theme.family
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shown over the lock screen (started by LockScreenLyricsController when the screen goes off during playback). It closes
 * itself when the phone is unlocked, when the screen comes on without a lock, and at the next screen-off if nothing plays.
 * Swiping up asks the system to unlock: PIN, fingerprint and face stay the system's.
 */
class LockScreenLyricsActivity : ComponentActivity() {
    private val keyguard: KeyguardManager by lazy { getSystemService(KEYGUARD_SERVICE) as KeyguardManager }

    private val screenReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT -> finish()
                    Intent.ACTION_SCREEN_ON -> if (!keyguard.isKeyguardLocked) finish()
                    Intent.ACTION_SCREEN_OFF -> if (LockScreenLyricsHost.player.value?.playWhenReady != true) finish()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        preferFastestRefreshRate()
        LockScreenLyricsHost.showing.value = true
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onBackPressedDispatcher.addCallback(this) { finish() }
        setContent {
            val family = remember { SystemIcons.family(this@LockScreenLyricsActivity) }
            // the app's own font and weight, so the lyrics look exactly like the app's lyrics view
            val appFont by rememberEnumPreference(AppFontKey, AppFont.STYLE)
            val appFontShift by rememberPreference(AppFontWeightKey, 0)
            MetrolistTheme(darkTheme = true, appFont = appFont, fontWeightShift = appFontShift, iconFamily = family) {
                LockScreenLyricsScreen(onClose = { finish() }, onUnlock = { unlock() })
            }
        }
    }

    /** The lock screen often runs at 60 Hz to save power: ask for the panel's fastest mode, unless the user turned that off. */
    private fun preferFastestRefreshRate() {
        runCatching {
            if (dataStore[EnableHighRefreshRateKey] == false) return
            @Suppress("DEPRECATION")
            val display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay) ?: return
            val current = display.mode
            val fastest =
                display.supportedModes
                    .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                    .maxByOrNull { it.refreshRate } ?: return
            window.attributes = window.attributes.apply { preferredDisplayModeId = fastest.modeId }
        }
    }

    private fun unlock() {
        if (!keyguard.isKeyguardLocked) {
            finish()
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = finish()

                override fun onDismissError() = finish()
            },
        )
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        LockScreenLyricsHost.showing.value = false
        super.onDestroy()
    }
}

/** iOS-style glass of the Now Playing card: a lens at rest (Android 13+), heavy frost without one (Android 12). */
private val PlatterLens = GlassLensRecipe(
    blurDp = 12f, saturation = 1.7f, lensHeightDp = 22f, lensAmountDp = 28f,
    depth = true, dispersion = 0.6f, veilScale = 1f,
)
private val PlatterFrost = GlassLensRecipe(
    blurDp = 26f, saturation = 1.6f, lensHeightDp = 0f, lensAmountDp = 0f,
    depth = false, dispersion = 0f, veilScale = 1f,
)
private val PlatterShape = RoundedCornerShape(34.dp)

/** The card samples the background at half size, like the navigation bar: the blur hides it, the cost drops to a quarter. */
private const val PlatterGlassScale = 0.5f

private val SidePadding = 22.dp

@Composable
private fun LockScreenLyricsScreen(
    onClose: () -> Unit,
    onUnlock: () -> Unit,
) {
    val context = LocalContext.current
    val player by LockScreenLyricsHost.player.collectAsState()
    val p = player
    if (p == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    var meta by remember(p) { mutableStateOf(p.currentMetadata) }
    var playing by remember(p) { mutableStateOf(p.playWhenReady) }
    var canNext by remember(p) { mutableStateOf(p.hasNextMediaItem()) }
    var durationMs by remember(p) { mutableLongStateOf(p.duration) }
    DisposableEffect(p) {
        val listener =
            object : Player.Listener {
                override fun onEvents(
                    player: Player,
                    events: Player.Events,
                ) {
                    meta = player.currentMetadata
                    playing = player.playWhenReady
                    canNext = player.hasNextMediaItem()
                    durationMs = player.duration
                    if (player.mediaItemCount == 0) onClose()
                }
            }
        p.addListener(listener)
        onDispose { p.removeListener(listener) }
    }

    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    // The playhead of the app's lyrics view: a clock on the frame time steered by the player (smooth even when a reading
    // is late), running only while something plays and the page is on screen. Only draw code and the lyric tracker read it.
    val playhead = remember(p) { PlayerPlayheadSource { p } }
    DisposableEffect(playhead) {
        playhead.attach(p)
        onDispose { playhead.detach() }
    }
    val clock = remember(p) { LyricsClock() }
    val position = remember(p) { mutableLongStateOf(p.currentPosition) }
    val jumps = remember(p) { mutableIntStateOf(0) }
    LaunchedEffect(playhead, started) {
        if (!started) return@LaunchedEffect
        runLyricsPlayhead(playhead, clock, { null }) { pos, _ ->
            if (clock.jumped) jumps.intValue++
            position.longValue = pos
        }
    }

    val lines by LockScreenLyricsHost.lines.collectAsState()
    val offsetMs by LockScreenLyricsHost.lyricOffsetMs.collectAsState()
    val seekToLine: (Long) -> Unit = remember(p) { { ms -> runCatching { p.seekTo(ms.coerceAtLeast(0L)) } } }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(started) {
        while (started) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L + 50L)
        }
    }
    val timeText = remember(now) { DateFormat.getTimeFormat(context).format(Date(now)) }
    val dateText = remember(now) { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(now)) }

    val inter = remember { AppFont.IOS.family(context.assets) ?: FontFamily.Default }
    val thumbnailUrl = meta?.thumbnailUrl
    var palette by remember { mutableStateOf(DefaultAmbientColors) }
    var softCover by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(thumbnailUrl) {
        if (thumbnailUrl == null) return@LaunchedEffect
        extractAmbientColors(context, thumbnailUrl)?.let { palette = it }
        loadSoftCover(context, thumbnailUrl)?.let { softCover = it }
    }

    val lensMode = rememberLockGlassMode(started)
    val backdrop = rememberLayerBackdrop()

    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val dragY = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 140.dp.toPx() }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onDragEnd = {
                            val velocity = tracker.calculateVelocity().y
                            if (dragY.value < -threshold || (velocity < -1_600f && dragY.value < -threshold / 4f)) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onUnlock()
                            }
                            scope.launch { dragY.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 260f)) }
                        },
                        onDragCancel = { scope.launch { dragY.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 260f)) } },
                    ) { change, dy ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                        // free going up, a rubber band going down
                        val next = dragY.value + if (dragY.value + dy > 0f) dy * 0.2f else dy
                        scope.launch { dragY.snapTo(next.coerceIn(-size.height.toFloat(), 40f)) }
                    }
                },
    ) {
        LockBackground(
            softCover = softCover,
            palette = palette,
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(if (lensMode != LensMode.FLAT) Modifier.layerBackdrop(backdrop) else Modifier),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val progress = (-dragY.value / (threshold * 2.2f)).coerceIn(0f, 1f)
                        translationY = dragY.value
                        alpha = 1f - progress * 0.85f
                        val s = 1f - progress * 0.05f
                        scaleX = s
                        scaleY = s
                    }.safeDrawingPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            Icon(
                painter = painterResource(R.drawable.lock),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = dateText.replaceFirstChar { it.titlecase(Locale.getDefault()) },
                fontFamily = inter,
                fontWeight = FontWeight.SemiBold,
                fontSize = 19.sp,
                color = Color.White.copy(alpha = 0.92f),
            )
            Text(
                text = timeText,
                fontFamily = inter,
                fontWeight = FontWeight.Bold,
                fontSize = 96.sp,
                lineHeight = 100.sp,
                letterSpacing = (-3).sp,
                color = Color.White.copy(alpha = 0.96f),
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.22f), Offset(0f, 6f), 28f)),
            )

            AnimatedContent(
                targetState = lines.isNotEmpty(),
                transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(300)) },
                label = "lockBody",
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) { synced ->
                if (synced) {
                    // the app's lyrics view, row for row (its rows bring their own 20 dp side padding)
                    LockLyrics(
                        lines = lines,
                        position = position,
                        jumps = jumps,
                        offsetMs = offsetMs,
                        onSeek = seekToLine,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BigCover(thumbnailUrl)
                    }
                }
            }

            NowPlayingPlatter(
                backdrop = backdrop,
                lensMode = lensMode,
                title = meta?.title.orEmpty(),
                artists = meta?.artists?.joinToString { it.name }.orEmpty(),
                thumbnailUrl = thumbnailUrl,
                playing = playing,
                canNext = canNext,
                position = position,
                durationMs = durationMs.takeIf { it != C.TIME_UNSET && it > 0 } ?: ((meta?.duration ?: 0) * 1000L),
                family = inter,
                onSeek = { ms -> runCatching { p.seekTo(ms) } },
                onPrevious = { p.lockPrevious() },
                onPlayPause = { runCatching { p.togglePlayPause() } },
                onNext = { p.lockNext() },
                modifier = Modifier.padding(horizontal = SidePadding),
            )
            Spacer(Modifier.height(18.dp))
            SwipeHint(family = inter, onUnlock = onUnlock)
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** LENS on Android 13+ (unless the user turned the lens off, battery saver, low RAM or animations off), BLUR on 12, frost below. */
@Composable
private fun rememberLockGlassMode(visible: Boolean): LensMode {
    val context = LocalContext.current
    val userSwitch by rememberPreference(GlassLensKey, true)
    val lowRam = remember(context) { GlassLensSystem.isLowRam(context) }
    remember(context) { GlassLensSystem.start(context) }
    val failed = GlassLensGuard.failed
    val powerSave = GlassLensSystem.powerSave
    val animScale = GlassLensSystem.animScale
    return remember(userSwitch, lowRam, powerSave, animScale, visible, failed) {
        GlassLensPolicy.decide(
            sdk = Build.VERSION.SDK_INT,
            kaca = true,
            backdropAvailable = Build.VERSION.SDK_INT >= GlassLensPolicy.SDK_BLUR,
            userSwitch = userSwitch,
            lowRam = lowRam,
            powerSave = powerSave,
            animScale = animScale,
            appVisible = visible,
            shaderFailed = failed,
        )
    }
}

// -------------------------------------------------------------------- the lyrics: the app's own view

/** The rows of the app's lyrics view for [lines]: every line with text, and the dots of every gap over 4 s (LyricsViewModel). */
private fun lockRows(
    lines: List<LyricsEntry>,
    withGaps: Boolean,
): List<LyricsListItem> {
    val result = ArrayList<LyricsListItem>(lines.size + 4)
    lines.forEachIndexed { i, entry ->
        if (entry.text.isNotBlank()) result += LyricsListItem.Line(i, entry)
        if (withGaps && i < lines.size - 1) {
            val nextStart = lines[i + 1].time
            val currentEnd =
                when {
                    !entry.words.isNullOrEmpty() -> (entry.words.last().endTime * 1000).toLong()
                    entry.text.isBlank() -> entry.time
                    else -> null
                }
            if (currentEnd != null && currentEnd < nextStart && nextStart - currentEnd > 4000L) {
                result += LyricsListItem.Indicator(i, nextStart - currentEnd, currentEnd, nextStart, lines[i + 1].agent)
            }
        }
    }
    return result
}

/**
 * Which lines are sung and which line the view rests on: the rules of the app's lyrics view (LyricsListView), without the
 * user's hand scrolling. Work happens only when a line starts or ends, or after a seek.
 */
private class LockLyricsTracker(
    private val lines: List<LyricsEntry>,
    private val lookaheadMs: Long,
    initialLine: Int,
) {
    val active = mutableStateOf(emptySet<Int>())
    val anchorLine = mutableIntStateOf(initialLine)
    private val nowCache = ActiveLinesCache(lines) { LyricsUtils.findActiveLineIndices(lines, it) }
    private val aheadCache = if (lookaheadMs == 0L) nowCache else ActiveLinesCache(lines) { LyricsUtils.findActiveLineIndices(lines, it) }
    private var lastNow: Set<Int>? = null
    private var lastAhead: Set<Int>? = null
    private var previousScrollActive: Set<Int> = emptySet()
    private var lastMainMaxSeen = -1
    private var scrollTarget = initialLine
    private var lastOffset = Long.MIN_VALUE
    private var lastJumps = 0

    private fun withMainLines(set: Set<Int>): Set<Int> {
        if (set.none { lines.getOrNull(it)?.isBackground == true }) return set
        val out = set.toMutableSet()
        for (i in set) {
            if (lines.getOrNull(i)?.isBackground == true) {
                for (j in i - 1 downTo 0) {
                    if (lines.getOrNull(j)?.isBackground == false) {
                        out += j
                        break
                    }
                }
            }
        }
        return out
    }

    fun update(
        positionMs: Long,
        offsetMs: Long,
        jumps: Int,
    ) {
        val seeking = jumps != lastJumps || (lastOffset != Long.MIN_VALUE && offsetMs != lastOffset)
        lastJumps = jumps
        lastOffset = offsetMs
        val effective = positionMs + offsetMs
        val initialActive = nowCache.at(effective)
        val scrollActiveRaw = aheadCache.at(effective + lookaheadMs)
        if (!seeking && initialActive === lastNow && scrollActiveRaw === lastAhead) return
        lastNow = initialActive
        lastAhead = scrollActiveRaw

        val scrollActive = withMainLines(scrollActiveRaw)
        val scrollMax = scrollActive.filter { lines.getOrNull(it)?.isBackground == false }.maxOrNull() ?: (scrollActive.maxOrNull() ?: -1)
        val targetStillActive = scrollTarget in scrollActive
        val anyActive = scrollActive.isNotEmpty()
        val shouldScroll =
            when {
                seeking -> true
                !targetStillActive && anyActive && scrollMax > scrollTarget -> true
                !targetStillActive && !anyActive && previousScrollActive.isNotEmpty() -> true
                scrollTarget == -1 && anyActive -> true
                previousScrollActive.isEmpty() && anyActive && scrollMax > scrollTarget -> true
                else -> false
            }
        if (shouldScroll) {
            val next =
                when {
                    seeking -> if (scrollMax != -1) scrollMax else startedLineIndex(lines, effective + lookaheadMs)
                    !targetStillActive && anyActive -> scrollMax
                    !targetStillActive && !anyActive ->
                        (lastMainMaxSeen + 1 until lines.size).firstOrNull { lines.getOrNull(it)?.isBackground == false } ?: scrollTarget
                    else -> scrollMax
                }
            if (next != -1 && (seeking || next > scrollTarget)) scrollTarget = next
        }
        if (seeking) lastMainMaxSeen = scrollMax
        if (scrollMax > lastMainMaxSeen && scrollMax != -1) lastMainMaxSeen = scrollMax
        previousScrollActive = scrollActive
        active.value = withMainLines(initialActive)
        if (scrollTarget != -1) anchorLine.intValue = scrollTarget
    }
}

/** The look of the rows: the user's lyric settings, as the app's lyrics view reads them. */
private class LockLyricsStyle(
    val sizeSp: Float,
    val lineSpacing: Float,
    val position: LyricsPosition,
    val respectAgent: Boolean,
    val motion: LyricsMotionEnv,
)

private val NoEdgeStep: () -> Int = { 0 }
private val NoSizeReport: (Int) -> Unit = {}
private val NoLongClick: () -> Unit = {}

/**
 * The lyrics exactly as the app's lyrics view draws them (LyricsLine, the gap dots, the glide of LyricsRowsLayout, the
 * faded edges and the depth of the lines), following the song by itself: no hand scrolling here, a vertical swipe
 * unlocks the phone. A tap on a line jumps there, like in the app.
 */
@Composable
private fun LockLyrics(
    lines: List<LyricsEntry>,
    position: MutableLongState,
    jumps: State<Int>,
    offsetMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val userTextSize by rememberPreference(LyricsTextSizeKey, 24f)
    val userLineSpacing by rememberPreference(LyricsLineSpacingKey, 1.2f)
    val userBlur by rememberPreference(LyricsBlurKey, true)
    val textPosition by rememberEnumPreference(LyricsTextPositionKey, DEFAULT_LYRICS_POSITION)
    val respectAgent by rememberPreference(RespectAgentPositioningKey, true)
    val showGaps by rememberPreference(ShowIntervalIndicatorKey, true)
    val motion = rememberLyricsMotionEnv(userBlur)
    val style =
        remember(userTextSize, userLineSpacing, textPosition, respectAgent, motion) {
            LockLyricsStyle(
                sizeSp = LyricsMotion.TEXT_SP * LyricsMotion.sizeScale(userTextSize),
                lineSpacing = LyricsMotion.LINE_HEIGHT_RATIO * LyricsMotion.spacingScale(userLineSpacing),
                position = textPosition,
                respectAgent = respectAgent,
                motion = motion,
            )
        }

    val rows = remember(lines, showGaps) { lockRows(lines, showGaps) }
    val keys = remember(rows) { LongArray(rows.size) { lyricsRowKey(rows[it]) } }
    val noGapBefore: (Int) -> Boolean =
        remember(rows) {
            { i ->
                val item = rows.getOrNull(i)
                (item as? LyricsListItem.Line)?.entry?.isBackground == true || item is LyricsListItem.Indicator
            }
        }

    val lookaheadMs = remember(lines) { if (lines.any { it.words?.isNotEmpty() == true }) 0L else 250L }
    val tracker =
        remember(lines) {
            LockLyricsTracker(lines, lookaheadMs, startedLineIndex(lines, position.longValue + offsetMs + lookaheadMs))
        }
    val latestOffset by rememberUpdatedState(offsetMs)
    LaunchedEffect(tracker) {
        snapshotFlow { position.longValue to jumps.value }.collect { (pos, jumpCount) -> tracker.update(pos, latestOffset, jumpCount) }
    }
    val anchorListIndex = remember(rows, tracker) { derivedStateOf { listIndexOfLine(rows, tracker.anchorLine.intValue) } }

    val follow = remember { LyricsFollow() }
    val rowsState = remember { LyricsRowsState() }
    val animate by rememberUpdatedState(motion.animate)
    LaunchedEffect(follow) { follow.runGlide { animate } }
    val positionProvider = remember(position) { { position.longValue } }

    Box(
        modifier =
            modifier
                .lyricsEdgeFade()
                .clipToBounds(),
    ) {
        LyricsRowsLayout(
            keys = keys,
            anchorIndex = { anchorListIndex.value },
            noGapBefore = noGapBefore,
            follow = follow,
            manualOffset = { 0f },
            glide = { animate },
            state = rowsState,
            modifier = Modifier.fillMaxSize(),
            chip = {},
            rows = {
                rows.forEachIndexed { listIndex, item ->
                    key(keys[listIndex]) {
                        LockLyricsRow(
                            listIndex = listIndex,
                            item = item,
                            lines = lines,
                            anchor = anchorListIndex,
                            activeLines = tracker.active,
                            positionProvider = positionProvider,
                            offsetMs = offsetMs,
                            style = style,
                            onSeek = onSeek,
                        )
                    }
                }
            },
        )
    }
}

/** One row of [LockLyrics]: only the rows whose distance or state really changes recompose (derived states), like the app's view. */
@Composable
private fun LockLyricsRow(
    listIndex: Int,
    item: LyricsListItem,
    lines: List<LyricsEntry>,
    anchor: State<Int>,
    activeLines: State<Set<Int>>,
    positionProvider: () -> Long,
    offsetMs: Long,
    style: LockLyricsStyle,
    onSeek: (Long) -> Unit,
) {
    val distance by remember(listIndex, anchor) { derivedStateOf { min(abs(listIndex - anchor.value), LYRICS_DISTANCE_CAP) } }
    when (item) {
        is LyricsListItem.Indicator -> {
            val visible by remember(item, offsetMs) {
                derivedStateOf {
                    val pos = positionProvider() + offsetMs
                    pos >= item.gapStartMs && pos <= item.gapEndMs - 650L
                }
            }
            val gapPosition = remember(positionProvider, offsetMs) { { positionProvider() + offsetMs } }
            IntervalIndicator(
                item.gapStartMs,
                item.gapEndMs - 650L,
                gapPosition,
                visible,
                Color.White,
                Modifier.fillMaxWidth(),
                horizontal = LyricsMotion.horizontalFor(item.nextAgent, style.respectAgent, style.position),
                animate = style.motion.animate,
            )
        }
        is LyricsListItem.Line -> {
            val index = item.index
            val entry = item.entry
            val isActiveLine by remember(index, activeLines) { derivedStateOf { activeLines.value.contains(index) } }
            val paired =
                remember(entry, index, lines) {
                    if (entry.isBackground) (index - 1 downTo 0).firstOrNull { lines.getOrNull(it)?.isBackground == false } ?: -1 else -1
                }
            val bgVisible by remember(entry, index, lines, offsetMs, paired) {
                derivedStateOf {
                    if (!entry.isBackground) return@derivedStateOf false
                    val active = activeLines.value
                    if (active.contains(paired) || active.contains(index)) return@derivedStateOf true
                    if (paired == -1) return@derivedStateOf false
                    val pos = positionProvider() + offsetMs
                    pos >= lines[paired].time && pos <= entry.time
                }
            }
            val onClick = remember(entry, offsetMs, onSeek) { { onSeek(entry.time - offsetMs) } }
            LyricsLine(
                index = index,
                item = entry,
                nextTimeMs = lines.getOrNull(index + 1)?.time,
                isSynced = true,
                isActiveLine = isActiveLine,
                bgVisible = bgVisible,
                isSelected = false,
                isSelectionModeActive = false,
                currentPositionProvider = positionProvider,
                lyricsOffset = offsetMs,
                distance = distance,
                onScreen = true,
                edgeStep = NoEdgeStep,
                motion = style.motion,
                lyricsTextSize = style.sizeSp,
                lyricsLineSpacing = style.lineSpacing,
                expressiveAccent = Color.White,
                lyricsTextPosition = style.position,
                respectAgentPositioning = style.respectAgent,
                isAutoScrollEnabled = true,
                romanizeAsMain = false,
                enabledLanguages = emptyList(),
                romanizeLyrics = false,
                onSizeChanged = NoSizeReport,
                onClick = onClick,
                onLongClick = NoLongClick,
            )
        }
    }
}

// -------------------------------------------------------------------- the background

/**
 * The cover blurred ONCE on the CPU at 64 px, then blown up and drifting in two layers like Apple Music's animated
 * background. Moving two small bitmaps costs nothing per frame (a live blur of the whole screen would run every frame),
 * and it looks the same on every Android version.
 */
private suspend fun loadSoftCover(
    context: Context,
    url: String,
): ImageBitmap? =
    withContext(Dispatchers.Default) {
        try {
            val source =
                context.imageLoader
                    .execute(ImageRequest.Builder(context).data(url).size(64).allowHardware(false).build())
                    .image
                    ?.toBitmap() ?: return@withContext null
            val longest = max(source.width, source.height).coerceAtLeast(1)
            val scale = 64f / longest
            val small =
                Bitmap.createScaledBitmap(
                    source,
                    (source.width * scale).toInt().coerceAtLeast(8),
                    (source.height * scale).toInt().coerceAtLeast(8),
                    true,
                )
            softBlur(small, radius = 5, passes = 3).asImageBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

/** Three box-blur passes (close to a Gaussian) over a tiny bitmap. */
private fun softBlur(
    source: Bitmap,
    radius: Int,
    passes: Int,
): Bitmap {
    val w = source.width
    val h = source.height
    val a = IntArray(w * h)
    val b = IntArray(w * h)
    source.copy(Bitmap.Config.ARGB_8888, false).getPixels(a, 0, w, 0, 0, w, h)
    repeat(passes) {
        boxPass(a, b, w, h, radius, horizontal = true)
        boxPass(b, a, w, h, radius, horizontal = false)
    }
    return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
}

private fun boxPass(
    src: IntArray,
    dst: IntArray,
    w: Int,
    h: Int,
    r: Int,
    horizontal: Boolean,
) {
    val outer = if (horizontal) h else w
    val inner = if (horizontal) w else h
    val div = 2 * r + 1
    for (o in 0 until outer) {
        val at = { i: Int ->
            val c = i.coerceIn(0, inner - 1)
            if (horizontal) src[o * w + c] else src[c * w + o]
        }
        var sr = 0
        var sg = 0
        var sb = 0
        for (k in -r..r) {
            val px = at(k)
            sr += (px shr 16) and 0xFF
            sg += (px shr 8) and 0xFF
            sb += px and 0xFF
        }
        for (i in 0 until inner) {
            dst[if (horizontal) o * w + i else i * w + o] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
            val add = at(i + r + 1)
            val remove = at(i - r)
            sr += ((add shr 16) and 0xFF) - ((remove shr 16) and 0xFF)
            sg += ((add shr 8) and 0xFF) - ((remove shr 8) and 0xFF)
            sb += (add and 0xFF) - (remove and 0xFF)
        }
    }
}

@Composable
private fun LockBackground(
    softCover: ImageBitmap?,
    palette: List<Color>,
    modifier: Modifier,
) {
    val colorA by animateColorAsState(palette[0], tween(1200), label = "lockA")
    val colorB by animateColorAsState(palette[1], tween(1200), label = "lockB")
    val colorC by animateColorAsState(palette[2], tween(1200), label = "lockC")
    val motion = rememberInfiniteTransition(label = "lockBackground")
    val spin by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(80_000, easing = LinearEasing)), label = "spin")
    val breathe by motion.animateFloat(1f, 1.14f, infiniteRepeatable(tween(12_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")

    Box(
        modifier =
            modifier.drawBehind {
                val w = size.width
                val h = size.height
                val r = max(w, h)
                drawRect(colorC)
                val c1 = Offset(w * 0.2f, h * 0.22f)
                drawCircle(Brush.radialGradient(listOf(colorA, Color.Transparent), c1, r * 0.85f), r * 0.85f, c1)
                val c2 = Offset(w * 0.85f, h * 0.78f)
                drawCircle(Brush.radialGradient(listOf(colorB, Color.Transparent), c2, r * 0.8f), r * 0.8f, c2)
            },
    ) {
        Crossfade(targetState = softCover, animationSpec = tween(900), label = "softCover") { cover ->
            if (cover == null) return@Crossfade
            Box(Modifier.fillMaxSize()) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.35f * breathe
                                scaleY = 1.35f * breathe
                            },
                )
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val s = 2.6f / breathe
                                scaleX = s
                                scaleY = s
                                rotationZ = spin
                                alpha = 0.55f
                            },
                )
            }
        }
        // keeps white text readable on any cover: darker at the top (clock) and the bottom (card)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.34f),
                        0.4f to Color.Black.copy(alpha = 0.16f),
                        0.7f to Color.Black.copy(alpha = 0.22f),
                        1f to Color.Black.copy(alpha = 0.5f),
                    ),
                ),
        )
    }
}

@Composable
private fun BigCover(thumbnailUrl: String?) {
    val context = LocalContext.current
    val request = remember(thumbnailUrl) { ImageRequest.Builder(context).data(thumbnailUrl).crossfade(500).build() }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
            Modifier
                .size(270.dp)
                .shadow(36.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black.copy(alpha = 0.55f), spotColor = Color.Black.copy(alpha = 0.55f))
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.08f)),
    )
}

// -------------------------------------------------------------------- the Now Playing card

/** The Now Playing card of the iOS lock screen, in the liquid glass of the app's navigation bar. */
@Composable
private fun NowPlayingPlatter(
    backdrop: Backdrop,
    lensMode: LensMode,
    title: String,
    artists: String,
    thumbnailUrl: String?,
    playing: Boolean,
    canNext: Boolean,
    position: MutableLongState,
    durationMs: Long,
    family: FontFamily,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass =
        when (lensMode) {
            LensMode.LENS, LensMode.BLUR -> {
                val recipe = if (lensMode == LensMode.LENS) PlatterLens else PlatterFrost
                val veil = if (lensMode == LensMode.LENS) 0.14f else 0.24f
                Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { PlatterShape },
                    effects = { applyGlassRecipe(recipe, PlatterGlassScale) },
                    onDrawSurface = {
                        drawRect(Color.Black.copy(alpha = veil))
                        drawRect(Color.White.copy(alpha = 0.04f))
                    },
                    backdropScale = PlatterGlassScale,
                )
            }
            LensMode.FLAT -> Modifier.glassFrost(PlatterShape)
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .then(glass)
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val context = LocalContext.current
            val request = remember(thumbnailUrl) { ImageRequest.Builder(context).data(thumbnailUrl).crossfade(400).build() }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.1f)),
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontFamily = family,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = artists,
                    fontFamily = family,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Scrubber(position = position, durationMs = durationMs, family = family, onSeek = onSeek)
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
        ) {
            GlyphButton(R.drawable.skip_previous, stringResource(R.string.ambient_mode_previous), 36.dp, onClick = onPrevious)
            AnimatedContent(
                targetState = playing,
                transitionSpec = { (scaleIn(spring(dampingRatio = 0.6f, stiffness = 500f), initialScale = 0.6f) + fadeIn(tween(150))) togetherWith fadeOut(tween(100)) },
                label = "playPause",
            ) { isPlaying ->
                GlyphButton(
                    if (isPlaying) R.drawable.pause else R.drawable.play,
                    stringResource(if (isPlaying) R.string.ambient_mode_pause else R.string.ambient_mode_play),
                    46.dp,
                    onClick = onPlayPause,
                )
            }
            GlyphButton(R.drawable.skip_next, stringResource(R.string.ambient_mode_next), 36.dp, enabled = canNext, onClick = onNext)
        }
    }
}

/**
 * The thin iOS progress line: thicker while it is held, drag or tap to seek. The line is drawn from the playhead in the
 * draw phase; the times under it recompose once a second.
 */
@Composable
private fun Scrubber(
    position: MutableLongState,
    durationMs: Long,
    family: FontFamily,
    onSeek: (Long) -> Unit,
) {
    val dragFraction = remember { mutableFloatStateOf(-1f) }
    val held = dragFraction.floatValue >= 0f
    val barHeight by animateDpAsState(if (held) 9.dp else 6.dp, spring(dampingRatio = 0.7f, stiffness = 500f), label = "barHeight")
    val shownSecond by remember(durationMs) {
        derivedStateOf {
            val f = dragFraction.floatValue
            if (f >= 0f) (f * durationMs).toLong() / 1000 else position.longValue / 1000
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        if (durationMs > 0) onSeek(((offset.x / size.width).coerceIn(0f, 1f) * durationMs).toLong())
                    }
                }.pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> dragFraction.floatValue = (offset.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            val f = dragFraction.floatValue
                            if (f >= 0f && durationMs > 0) onSeek((f * durationMs).toLong())
                            dragFraction.floatValue = -1f
                        },
                        onDragCancel = { dragFraction.floatValue = -1f },
                    ) { change, _ ->
                        change.consume()
                        dragFraction.floatValue = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }.drawBehind {
                    val h = barHeight.toPx()
                    val y = (size.height - h) / 2f
                    val radius = CornerRadius(h / 2f, h / 2f)
                    drawRoundRect(Color.White.copy(alpha = 0.22f), topLeft = Offset(0f, y), size = Size(size.width, h), cornerRadius = radius)
                    val drag = dragFraction.floatValue
                    val played = if (durationMs > 0) (position.longValue.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                    val f = if (drag >= 0f) drag else played
                    if (f > 0f) {
                        drawRoundRect(
                            Color.White.copy(alpha = if (drag >= 0f) 1f else 0.86f),
                            topLeft = Offset(0f, y),
                            size = Size(max(size.width * f, h), h),
                            cornerRadius = radius,
                        )
                    }
                },
    )
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val timeStyle =
            remember(family) {
                TextStyle(
                    fontFamily = family,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.58f),
                    fontFeatureSettings = "tnum",
                )
            }
        val shownMs = shownSecond * 1000
        Text(formatMs(shownMs), style = timeStyle)
        Text("-" + formatMs((durationMs - shownMs).coerceAtLeast(0L)), style = timeStyle)
    }
}

@Composable
private fun GlyphButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    size: Dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.82f else 1f, spring(dampingRatio = 0.55f, stiffness = 600f), label = "glyphScale")
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(size + 24.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = if (enabled) 1f else 0.35f
                }.clip(CircleShape)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(size),
        )
    }
}

/** "Swipe up to unlock" with the shimmer of the old iPhone slider, above the home bar. The shimmer is a draw-time mask. */
@Composable
private fun SwipeHint(
    family: FontFamily,
    onUnlock: () -> Unit,
) {
    val motion = rememberInfiniteTransition(label = "swipeHint")
    val sweep = motion.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(2_600, easing = LinearEasing)), label = "shimmer")
    val bob = motion.animateFloat(0f, -5f, infiniteRepeatable(tween(1_100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onUnlock),
    ) {
        Text(
            text = stringResource(R.string.lock_lyrics_swipe),
            fontFamily = family,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = Color.White,
            modifier =
                Modifier
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val w = size.width
                        val x = w * sweep.value
                        drawRect(
                            Brush.horizontalGradient(
                                0f to Color.White.copy(alpha = 0.45f),
                                0.5f to Color.White,
                                1f to Color.White.copy(alpha = 0.45f),
                                startX = x - w * 0.35f,
                                endX = x + w * 0.35f,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .graphicsLayer { translationY = bob.value.dp.toPx() }
                .width(136.dp)
                .height(5.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.9f)),
        )
    }
}

private fun formatMs(ms: Long): String {
    val total = ms / 1000
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}

private fun Player.lockPrevious() {
    runCatching {
        if (currentPosition > 3000 || !hasPreviousMediaItem()) seekTo(0) else seekToPreviousMediaItem()
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) prepare()
        playWhenReady = true
    }
}

private fun Player.lockNext() {
    runCatching {
        seekToNext()
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) prepare()
        playWhenReady = true
    }
}
