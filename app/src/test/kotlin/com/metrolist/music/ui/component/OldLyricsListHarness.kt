/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * MEASUREMENT ONLY: the row list of ExperimentalLyrics as it was in 4.3.4 (backup ExperimentalLyrics.kt.orig, lines
 * 352-945), copied with its logic unchanged so the smoothness test can measure "before" and "after" with the same frames.
 * Changed only at its inputs: the player is a fake, the clock is the test clock, there is no chip, no selection, no
 * touch, no translation header. Counters are added at the row's measure (layout {}), placement (offset {}) and at every
 * new target of a row's scroll animation.
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsUtils.findActiveLineIndices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

internal class OldProbe {
    var rowMeasures = 0
    var rowPlacements = 0
    var animationTargets = 0
    var position = 0L
    val rowY = HashMap<Int, Float>()
}

private val OLD_FALLBACK_HEIGHT = 68.dp
private val OLD_GAP = LyricsMotion.ROW_GAP_DP.dp
private const val OLD_STAGGER_PER_DISTANCE = 20
private const val OLD_STAGGER_MAX_MS = 200
private val OldNoEdgeStep: () -> Int = { 0 }

@Composable
internal fun OldLyricsListHarness(
    lines: List<LyricsEntry>,
    mergedLyricsList: List<LyricsListItem>,
    isSynced: Boolean,
    playerPosition: () -> Long,
    playerIsPlaying: () -> Boolean,
    wallClockMs: () -> Long,
    sliderPositionProvider: () -> Long?,
    style: LyricsListStyle,
    probe: OldProbe,
) {
    val density = LocalDensity.current
    val motionEnv = style.motion
    val lyrics = "harness"
    val hasWordTimings = remember(lines) { lines.any { it.words?.isNotEmpty() == true } }
    var activeLineIndices by remember { mutableStateOf(emptySet<Int>()) }
    var scrollTargetIndex by remember { mutableIntStateOf(-1) }
    var previousScrollActiveIndices by remember { mutableStateOf(emptySet<Int>()) }
    val currentPositionHolder = remember { mutableLongStateOf(0L) }
    var currentPositionState by currentPositionHolder
    val positionProvider = remember { { currentPositionHolder.longValue } }
    var deferredCurrentLineIndex by remember { mutableIntStateOf(0) }
    var isSeeking by remember { mutableStateOf(false) }
    val isAutoScrollEnabled by remember { mutableStateOf(true) }
    var lastMainMaxSeen by remember(lyrics, lines) { mutableIntStateOf(-1) }

    LaunchedEffect(lyrics, lines) {
        if (lines.isEmpty()) {
            activeLineIndices = emptySet()
            return@LaunchedEffect
        }
        var lastPlayerPos = playerPosition()
        var lastUpdateTime = wallClockMs()
        var lastComputeTime = 0L
        while (isActive) {
            withFrameNanos { _ -> }
            val now = wallClockMs()
            val sliderPosition = sliderPositionProvider()
            isSeeking = sliderPosition != null
            val position = if (isSeeking) {
                sliderPosition!!
            } else {
                val playerPos = playerPosition()
                if (playerPos != lastPlayerPos) {
                    lastPlayerPos = playerPos
                    lastUpdateTime = now
                }
                val elapsed = now - lastUpdateTime
                lastPlayerPos + (if (playerIsPlaying()) elapsed else 0)
            }
            currentPositionState = position
            probe.position = position
            if (!isSeeking && now - lastComputeTime < 33L) continue
            lastComputeTime = now
            val effectivePosition = position
            val initialActiveIndices = findActiveLineIndices(lines, effectivePosition)
            val scrollActiveIndicesRaw = findActiveLineIndices(lines, effectivePosition + (if (hasWordTimings) 0L else 250L))
            val scrollActiveIndices = scrollActiveIndicesRaw.toMutableSet()
            for (i in scrollActiveIndicesRaw) {
                if (lines.getOrNull(i)?.isBackground == true) {
                    for (j in i - 1 downTo 0) {
                        if (lines.getOrNull(j)?.isBackground == false) {
                            scrollActiveIndices.add(j)
                            break
                        }
                    }
                }
            }
            val newActiveIndices = initialActiveIndices.toMutableSet()
            for (i in initialActiveIndices) {
                if (lines.getOrNull(i)?.isBackground == true) {
                    for (j in i - 1 downTo 0) {
                        if (lines.getOrNull(j)?.isBackground == false) {
                            newActiveIndices.add(j)
                            break
                        }
                    }
                }
            }
            val scrollMax = scrollActiveIndices.filter { lines.getOrNull(it)?.isBackground == false }.maxOrNull() ?: (scrollActiveIndices.maxOrNull() ?: -1)
            val isCurrentTargetStillActive = scrollTargetIndex in scrollActiveIndices
            val anyStillActive = scrollActiveIndices.isNotEmpty()
            val shouldScroll = when {
                isSeeking -> true
                !isCurrentTargetStillActive && anyStillActive && scrollMax > scrollTargetIndex -> true
                !isCurrentTargetStillActive && !anyStillActive && previousScrollActiveIndices.isNotEmpty() -> true
                scrollTargetIndex == -1 && anyStillActive -> true
                previousScrollActiveIndices.isEmpty() && anyStillActive && scrollMax > scrollTargetIndex -> true
                else -> false
            }
            if (shouldScroll) {
                val targetToScroll = when {
                    isSeeking -> scrollMax
                    !isCurrentTargetStillActive && anyStillActive -> scrollMax
                    !isCurrentTargetStillActive && !anyStillActive ->
                        (lastMainMaxSeen + 1 until lines.size).firstOrNull { lines.getOrNull(it)?.isBackground == false } ?: scrollTargetIndex
                    else -> scrollMax
                }
                if (targetToScroll != -1 && (isSeeking || targetToScroll > scrollTargetIndex)) scrollTargetIndex = targetToScroll
            }
            if (scrollMax > lastMainMaxSeen && scrollMax != -1) lastMainMaxSeen = scrollMax
            previousScrollActiveIndices = scrollActiveIndices
            activeLineIndices = newActiveIndices
        }
    }

    LaunchedEffect(scrollTargetIndex, isAutoScrollEnabled) {
        if (scrollTargetIndex != -1 && isAutoScrollEnabled) deferredCurrentLineIndex = scrollTargetIndex
    }

    val userManualOffset by remember { mutableFloatStateOf(0f) }
    val itemHeights = remember(lyrics) { mutableStateMapOf<LyricsListItem, Int>() }
    val heightOf: (Int) -> Int? = { i -> mergedLyricsList.getOrNull(i)?.let { itemHeights[it] } }
    var isInitialLayout by remember(lyrics, mergedLyricsList) { mutableStateOf(true) }
    val activeListIndex by remember(mergedLyricsList, deferredCurrentLineIndex) {
        derivedStateOf {
            mergedLyricsList.indexOfFirst {
                (it is LyricsListItem.Line && it.index == deferredCurrentLineIndex) ||
                    (it is LyricsListItem.Indicator && it.afterLineIndex == deferredCurrentLineIndex)
            }.coerceAtLeast(0)
        }
    }

    BoxWithConstraints(contentAlignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize().padding(bottom = 12.dp)) {
        val maxHeightPx = constraints.maxHeight.toFloat()
        val anchorY = LyricsMotion.anchorY(maxHeightPx)
        val lineHeightPx = with(density) { OLD_FALLBACK_HEIGHT.toPx() }
        val indicatorHeightPx = with(density) { 72.dp.toPx() }
        val placement = remember(itemHeights.toMap(), activeListIndex, mergedLyricsList) {
            LyricsRowPlacement.compute(
                count = mergedLyricsList.size,
                anchor = activeListIndex,
                measured = heightOf,
                guess = { if (mergedLyricsList[it] is LyricsListItem.Indicator) indicatorHeightPx else lineHeightPx },
                gapBefore = { i ->
                    val item = mergedLyricsList[i]
                    val noGap = (item as? LyricsListItem.Line)?.entry?.isBackground == true || item is LyricsListItem.Indicator
                    if (noGap) 0f else with(density) { OLD_GAP.toPx() }
                },
            )
        }
        val positions = placement.positions

        LaunchedEffect(true, lyrics, mergedLyricsList) {
            if (mergedLyricsList.isNotEmpty()) {
                isInitialLayout = true
                snapshotFlow {
                    val h = itemHeights.toMap()
                    val windowStart = (activeListIndex - 8).coerceAtLeast(0)
                    val windowEnd = (activeListIndex + 12).coerceAtMost(mergedLyricsList.size - 1)
                    (windowStart..windowEnd).all { i -> mergedLyricsList.getOrNull(i)?.let { h.containsKey(it) } == true }
                }.first { it }
                isInitialLayout = false
            }
        }
        LaunchedEffect(activeListIndex) {
            if (mergedLyricsList.isNotEmpty()) {
                val h = itemHeights.toMap()
                val windowStart = (activeListIndex - 3).coerceAtLeast(0)
                val windowEnd = (activeListIndex + 3).coerceAtMost(mergedLyricsList.size - 1)
                val needsMeasurement = (windowStart..windowEnd).any { i -> mergedLyricsList.getOrNull(i)?.let { h.containsKey(it) } != true }
                if (needsMeasurement) {
                    isInitialLayout = true
                    snapshotFlow {
                        val hh = itemHeights.toMap()
                        (windowStart..windowEnd).all { i -> mergedLyricsList.getOrNull(i)?.let { hh.containsKey(it) } == true }
                    }.first { it }
                    isInitialLayout = false
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().lyricsEdgeFade().clipToBounds()) {
            mergedLyricsList.forEachIndexed { listIndex, listItem ->
                key(listItem) {
                    val distance = abs(listIndex - activeListIndex)
                    val targetOffset = anchorY + positions.getOrDefault(listIndex, (listIndex - activeListIndex) * lineHeightPx)
                    val exact = placement.isExact(listIndex)
                    val wasExact = remember { booleanArrayOf(exact) }
                    val lastTarget = remember { floatArrayOf(Float.NaN) }
                    val animatedOffset by animateFloatAsState(
                        targetValue = targetOffset,
                        animationSpec = if (isInitialLayout || !isAutoScrollEnabled || !motionEnv.animate || !exact || !wasExact[0]) snap()
                        else tween(750, (distance * OLD_STAGGER_PER_DISTANCE).coerceAtMost(OLD_STAGGER_MAX_MS), FastOutSlowInEasing),
                        label = "old_$listIndex",
                    )
                    SideEffect {
                        wasExact[0] = exact
                        if (lastTarget[0] != targetOffset) {
                            if (!lastTarget[0].isNaN()) probe.animationTargets++
                            lastTarget[0] = targetOffset
                        }
                    }
                    val exactState = rememberUpdatedState(exact)
                    val onScreen by remember(listIndex, maxHeightPx) {
                        derivedStateOf {
                            LyricsMotion.onScreen(animatedOffset + userManualOffset, itemHeights[listItem]?.toFloat() ?: lineHeightPx, maxHeightPx, margin = lineHeightPx)
                        }
                    }
                    Box(
                        modifier = Modifier.fillMaxWidth().layout { m, c ->
                            probe.rowMeasures++
                            val p = m.measure(c.copy(maxHeight = Constraints.Infinity))
                            layout(p.width, 0) { p.place(0, 0) }
                        }.offset {
                            probe.rowPlacements++
                            val y = animatedOffset + userManualOffset
                            probe.rowY[listIndex] = y
                            IntOffset(0, y.roundToInt())
                        }.graphicsLayer { alpha = if (exactState.value) 1f else 0f }
                    ) {
                        when (listItem) {
                            is LyricsListItem.Indicator -> {
                                val visible by remember(listItem, isAutoScrollEnabled) {
                                    derivedStateOf {
                                        val pos = currentPositionHolder.longValue
                                        isAutoScrollEnabled && pos >= listItem.gapStartMs && pos <= listItem.gapEndMs - 650L
                                    }
                                }
                                IntervalIndicator(
                                    listItem.gapStartMs, listItem.gapEndMs - 650L, { currentPositionHolder.longValue }, visible, style.ink,
                                    Modifier.fillMaxWidth().onSizeChanged { itemHeights[listItem] = it.height },
                                    horizontal = LyricsMotion.horizontalFor(listItem.nextAgent, style.respectAgentPositioning, style.textPosition),
                                    animate = motionEnv.animate,
                                )
                            }
                            is LyricsListItem.Line -> {
                                val index = listItem.index
                                val item = listItem.entry
                                val isActiveLine = activeLineIndices.contains(index)
                                val pairedMainLineIndex = if (item.isBackground) (index - 1 downTo 0).firstOrNull { lines.getOrNull(it)?.isBackground == false } ?: -1 else -1
                                val isInGapWithMain by remember(item, pairedMainLineIndex, lines) {
                                    derivedStateOf {
                                        if (item.isBackground && pairedMainLineIndex != -1) {
                                            val pos = currentPositionHolder.longValue
                                            pos >= lines[pairedMainLineIndex].time && pos <= item.time
                                        } else false
                                    }
                                }
                                val bgVisible = item.isBackground && (activeLineIndices.contains(pairedMainLineIndex) || activeLineIndices.contains(index) || isInGapWithMain)
                                LyricsLine(
                                    index = index, item = item, nextTimeMs = lines.getOrNull(index + 1)?.time, isSynced = isSynced,
                                    isActiveLine = isActiveLine, bgVisible = bgVisible, isSelected = false,
                                    isSelectionModeActive = false, currentPositionProvider = positionProvider, lyricsOffset = 0L,
                                    distance = distance, onScreen = onScreen, edgeStep = OldNoEdgeStep, motion = motionEnv,
                                    lyricsTextSize = style.textSizeSp, lyricsLineSpacing = style.lineSpacing,
                                    expressiveAccent = style.ink, lyricsTextPosition = style.textPosition,
                                    respectAgentPositioning = style.respectAgentPositioning, isAutoScrollEnabled = isAutoScrollEnabled,
                                    romanizeAsMain = false, enabledLanguages = emptyList(), romanizeLyrics = false,
                                    onSizeChanged = { itemHeights[listItem] = it }, onClick = {}, onLongClick = {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
