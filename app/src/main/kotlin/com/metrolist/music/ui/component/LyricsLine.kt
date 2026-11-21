/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.WordTimestamp
import com.metrolist.music.ui.screens.settings.LyricsPosition
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.PI

private data class HyphenGroupWord(
    val pos: Int,
    val size: Int,
    val isLast: Boolean,
    val groupStartMs: Long,
    val groupEndMs: Long
)

private fun String.containsRtl(): Boolean {
    for (c in this) {
        val directionality = Character.getDirectionality(c).toInt()
        if (directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT.toInt() ||
            directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC.toInt()
        ) {
            return true
        }
    }
    return false
}

// Indic scripts (Devanagari through Sinhala) join glyphs across grapheme clusters, e.g. the
// Bengali/Devanagari headstroke, so drawing clusters separately leaves seams and overlaps.
private fun String.containsJoinedIndic(): Boolean = any { it in 'ऀ'..'෿' }

/**
 * Splits a string into Unicode grapheme clusters using BreakIterator.
 * This correctly handles Devanagari, Bengali, Arabic, Hangul, emoji, etc.
 * where a single visible glyph is composed of multiple code points (e.g. base
 * consonant + matra + anusvara = one cluster, not three separate chars).
 */
private fun String.toGraphemeClusters(): List<String> {
    if (isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(this)
    var start = it.first()
    var end = it.next()
    while (end != java.text.BreakIterator.DONE) {
        result.add(substring(start, end))
        start = end
        end = it.next()
    }
    return result
}

/**
 * One lyric line of the experimental view, drawn the way the Windows app draws it: the line being sung is full ink with
 * a sweep that follows the singing, the others step back by their distance from it (smaller, fainter, a little blurred
 * where the phone can afford it). It knows nothing about the player: the position comes through [currentPositionProvider]
 * and is only read while drawing.
 *
 * @param distance rows between this row and the anchor row (the row the view scrolls to)
 * @param nextTimeMs start of the next line, which ends the sweep of a line that has no word timing
 * @param onScreen whether the row is inside the lyric area (blur is only spent on rows you can see)
 * @param edgeStep for lyrics without timing: 0 in the middle of the view, 1 and 2 towards the edges
 * @param motion what the phone can afford (blur, animations)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LyricsLine(
    index: Int,
    item: LyricsEntry,
    nextTimeMs: Long?,
    isSynced: Boolean,
    isActiveLine: Boolean,
    bgVisible: Boolean,
    isSelected: Boolean,
    isSelectionModeActive: Boolean,
    currentPositionProvider: () -> Long,
    lyricsOffset: Long,
    distance: Int,
    onScreen: Boolean,
    edgeStep: () -> Int,
    motion: LyricsMotionEnv,
    lyricsTextSize: Float,
    lyricsLineSpacing: Float,
    expressiveAccent: Color,
    lyricsTextPosition: LyricsPosition,
    respectAgentPositioning: Boolean,
    isAutoScrollEnabled: Boolean,
    romanizeAsMain: Boolean,
    enabledLanguages: List<String>,
    romanizeLyrics: Boolean,
    onSizeChanged: (Int) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = item.isBackground
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // Depth: the target look of this row. The values glide to it in the layer phase; nothing here changes the measured size.
    val relaxed = !isAutoScrollEnabled || isSelectionModeActive
    val plain = !isSynced
    val step = if (plain) edgeStep() else 0
    val scaleTarget = if (plain || bg) 1f else LyricsMotion.lineScale(isActiveLine)
    val alphaTarget = when {
        bg -> 1f
        plain -> LyricsMotion.edgeAlpha(step)
        relaxed -> if (isActiveLine) 1f else LyricsMotion.RELAXED_ALPHA
        else -> LyricsMotion.lineAlpha(distance, isActiveLine)
    }
    val blurTarget = when {
        bg -> 0f
        plain -> if (motion.blurAllowed && onScreen) LyricsMotion.edgeBlurDp(step) else 0f
        else -> LyricsMotion.targetBlurDp(motion.blurAllowed, distance, isActiveLine, onScreen, relaxed)
    }
    val depth = rememberLyricDepth(scaleTarget, alphaTarget, blurTarget, pressed, motion.animate, motion.blurAllowed)
    val lineAlpha: () -> Float = remember(depth) { { depth.alpha.value } }

    val agentAlignment = when {
        respectAgentPositioning && item.agent == "v1" -> Alignment.Start
        respectAgentPositioning && item.agent == "v2" -> Alignment.End
        respectAgentPositioning && item.agent == "v1000" -> Alignment.CenterHorizontally
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> Alignment.Start
            LyricsPosition.CENTER -> Alignment.CenterHorizontally
            LyricsPosition.RIGHT -> Alignment.End
        }
    }

    // Left means the start of the line in the reading direction: a right-to-left line (Arabic, Hebrew) starts at the right edge.
    val rtlLine = remember(item.text) { item.text.containsRtl() }
    val agentTextAlign = when {
        respectAgentPositioning && item.agent == "v1" -> TextAlign.Left
        respectAgentPositioning && item.agent == "v2" -> TextAlign.Right
        respectAgentPositioning && item.agent == "v1000" -> TextAlign.Center
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> if (rtlLine) TextAlign.Right else TextAlign.Left
            LyricsPosition.CENTER -> TextAlign.Center
            LyricsPosition.RIGHT -> TextAlign.Right
        }
    }
    val originX = when (agentTextAlign) {
        TextAlign.Right -> 1f
        TextAlign.Center -> 0.5f
        else -> 0f
    }

    val sidePadding = when (lyricsTextPosition) { LyricsPosition.LEFT, LyricsPosition.RIGHT -> 20.dp; LyricsPosition.CENTER -> 24.dp }
    val itemModifier = modifier
        .fillMaxWidth()
        .onSizeChanged { onSizeChanged(it.height) }
        .lyricDepth(depth, originX)
        .clip(RoundedCornerShape(8.dp))
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick
        )
        .background(if (isSelected && isSelectionModeActive) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
        .padding(
            start = sidePadding,
            end = sidePadding,
            top = if (bg) 0.dp else LyricsMotion.ROW_PAD_DP.dp,
            bottom = if (bg) 2.dp else LyricsMotion.ROW_PAD_DP.dp
        )

    Box(modifier = itemModifier, contentAlignment = when {
        respectAgentPositioning && item.agent == "v1" -> Alignment.CenterStart
        respectAgentPositioning && item.agent == "v2" -> Alignment.CenterEnd
        respectAgentPositioning && item.agent == "v1000" -> Alignment.Center
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> Alignment.CenterStart
            LyricsPosition.RIGHT -> Alignment.CenterEnd
            LyricsPosition.CENTER -> Alignment.Center
        }
    }) {
        @Composable
        fun LyricContent() {
            // Lyrics with no timing cannot follow the song, so instead of showing every line at once they ease in one after the other.
            val enter = remember(isSynced) { Animatable(if (isSynced) 1f else 0f) }
            if (!isSynced) {
                LaunchedEffect(Unit) {
                    delay(LyricsMotion.enterDelayMs(index))
                    if (motion.animate) enter.animateTo(1f, tween(450)) else enter.snapTo(1f)
                }
            }
            Column(
                modifier =
                    if (isSynced) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.fillMaxWidth().graphicsLayer {
                            alpha = enter.value
                            translationY = (1f - enter.value) * 18.dp.toPx()
                        }
                    },
                horizontalAlignment = agentAlignment,
            ) {
                // Ink is the page's ink; the dimming of far lines is the opacity of the layer around the row.
                val ink = if (bg) expressiveAccent.copy(alpha = expressiveAccent.alpha * 0.7f) else expressiveAccent
                val unsungAlpha = if (bg) 0.28f else 0.4f

                val romanizedTextState by item.romanizedTextFlow.collectAsStateWithLifecycle()
                val isRomanizedAvailable = romanizedTextState != null
                val mainTextRaw = if (romanizeAsMain && isRomanizedAvailable) romanizedTextState else item.text
                val subTextRaw = if (romanizeAsMain && isRomanizedAvailable) item.text else romanizedTextState
                val mainText = if (bg) mainTextRaw?.removePrefix("(")?.removeSuffix(")") else mainTextRaw
                val subText = if (bg) subTextRaw?.removePrefix("(")?.removeSuffix(")") else subTextRaw

                val fontFamily = MaterialTheme.typography.headlineSmall.fontFamily
                val sizeSp = if (bg) lyricsTextSize * 0.7f else lyricsTextSize
                val lyricStyle = remember(sizeSp, lyricsLineSpacing, bg, agentTextAlign, fontFamily) {
                    TextStyle(
                        fontSize = sizeSp.sp,
                        fontWeight = if (bg) FontWeight.SemiBold else FontWeight.Bold,
                        fontStyle = if (bg) FontStyle.Italic else FontStyle.Normal,
                        lineHeight = (sizeSp * lyricsLineSpacing).sp,
                        letterSpacing = LyricsMotion.LETTER_SPACING_SP.sp,
                        textAlign = agentTextAlign,
                        fontFamily = fontFamily,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both
                        )
                    )
                }

                val timedWords = item.words?.takeIf { it.isNotEmpty() }
                // Right-to-left and joined scripts, and lines with a held syllable, keep the word by word drawing of the
                // word machinery (it handles their shaping and their growing letters); every other line gets the sweep.
                val wordLevel = remember(mainText, timedWords) {
                    mainText != null && (
                        mainText.containsRtl() || mainText.containsJoinedIndic() ||
                            (timedWords != null && LyricsMotion.hasHyphenGroup(timedWords.map { it.text }, timedWords.map { it.hasTrailingSpace }))
                        )
                }
                val timing = remember(timedWords, item.time, nextTimeMs, mainText, bg) {
                    if (timedWords != null) {
                        val texts = timedWords.mapIndexed { i, w ->
                            var t = w.text
                            if (bg) {
                                if (i == 0) t = t.removePrefix("(")
                                if (i == timedWords.lastIndex) t = t.removeSuffix(")")
                            }
                            t
                        }
                        val starts = LongArray(timedWords.size) { (timedWords[it].startTime * 1000).toLong() }
                        val ends = LongArray(timedWords.size) { (timedWords[it].endTime * 1000).toLong() }
                        SweepTiming(starts.first(), ends.last(), texts, starts, ends)
                    } else {
                        SweepTiming(item.time, LyricsMotion.lineSweepEndMs(item.time, nextTimeMs, mainText?.length ?: 0))
                    }
                }
                val wordLevelWords = remember(mainText, timedWords, timing, wordLevel) {
                    if (!wordLevel || mainText == null) {
                        null
                    } else {
                        timedWords ?: LyricsMotion.spreadWords(mainText, timing.startMs, timing.endMs).map {
                            WordTimestamp(it.text, it.startMs / 1000.0, it.endMs / 1000.0, it.trailingSpace)
                        }
                    }
                }
                val position: () -> Long = remember(currentPositionProvider, lyricsOffset) { { currentPositionProvider() + lyricsOffset } }

                if (isSynced && wordLevelWords != null && mainText != null && (isActiveLine || distance <= 3)) {
                    WordLevelLyrics(
                        mainText = mainText,
                        words = wordLevelWords,
                        isActiveLine = isActiveLine,
                        positionProvider = position,
                        lyricStyle = lyricStyle,
                        ink = ink,
                        isBackground = bg,
                        unsungAlpha = unsungAlpha,
                        lineAlpha = lineAlpha,
                        alignment = agentTextAlign
                    )
                } else {
                    LyricSweepText(
                        text = mainText ?: "",
                        style = lyricStyle,
                        ink = ink,
                        sweeping = isSynced && isActiveLine && wordLevelWords == null && mainText != null,
                        positionProvider = position,
                        timing = timing,
                        lineAlpha = lineAlpha,
                        unsungAlpha = unsungAlpha,
                    )
                }

                if (romanizeLyrics && enabledLanguages.isNotEmpty()) {
                    subText?.let {
                        Text(
                            text = it,
                            fontSize = 18.sp,
                            color = ink.copy(alpha = ink.alpha * 0.6f),
                            textAlign = agentTextAlign,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                val transText by item.translatedTextFlow.collectAsStateWithLifecycle()
                transText?.let {
                    Text(
                        text = it,
                        fontSize = 16.sp,
                        color = ink.copy(alpha = ink.alpha * 0.5f),
                        textAlign = agentTextAlign,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        if (bg) {
            AnimatedVisibility(
                visible = bgVisible,
                enter = fadeIn(tween(durationMillis = 250, delayMillis = 100)),
                exit = fadeOut(tween(250))
            ) {
                LyricContent()
            }
        } else {
            LyricContent()
        }
    }
}

@Composable
private fun WordLevelLyrics(
    mainText: String,
    words: List<WordTimestamp>,
    isActiveLine: Boolean,
    positionProvider: () -> Long,
    lyricStyle: TextStyle,
    ink: Color,
    isBackground: Boolean,
    unsungAlpha: Float,
    lineAlpha: () -> Float,
    alignment: TextAlign
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val glowPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
        }
    }
    
    val (effectiveWords, effectiveToOriginalIdx) = remember(words, isBackground) {
        words.flatMapIndexed { originalIdx, word ->
            val shouldSplit = word.text.contains('-') && word.text.length > 1 &&
                (!word.hasTrailingSpace || words.size == 1)
            if (shouldSplit) {
                val segments = mutableListOf<String>()
                var start = 0
                for (i in 0 until word.text.length) {
                    if (word.text[i] == '-') {
                        segments.add(word.text.substring(start, i + 1))
                        start = i + 1
                    }
                }
                if (start < word.text.length) {
                    segments.add(word.text.substring(start))
                }

                if (segments.size > 1) {
                    val totalDuration = word.endTime - word.startTime
                    val segmentDuration = totalDuration / segments.size
                    segments.mapIndexed { index, segmentText ->
                        WordTimestamp(
                            text = segmentText,
                            startTime = word.startTime + index * segmentDuration,
                            endTime = word.startTime + (index + 1) * segmentDuration,
                            hasTrailingSpace = if (index == segments.size - 1) word.hasTrailingSpace else false
                        ) to originalIdx
                    }
                } else listOf(word to originalIdx)
            } else listOf(word to originalIdx)
        }.let { data -> data.map { it.first } to data.map { it.second } }
    }

    // Break mainText into grapheme clusters so that multi-codepoint glyphs
    // (Devanagari/Bengali matras, Arabic ligatures, emoji, etc.) are treated
    // as single units throughout the animation pipeline.
    val graphemeClusters = remember(mainText) { mainText.toGraphemeClusters() }
    val clusterCount = graphemeClusters.size
    // For each cluster index, the String offset (Char index) of its first character in mainText.
    // Required because TextLayoutResult.getBoundingBox/getLineForOffset take
    // String offsets (UTF-16/Char indices), not cluster indices.
    val clusterCharOffsets = remember(mainText) {
        IntArray(clusterCount).also { offsets ->
            var charOffset = 0
            graphemeClusters.forEachIndexed { i, cluster ->
                offsets[i] = charOffset
                charOffset += cluster.length
            }
        }
    }

    // wordIdxMap / charInWordMap / wordLenMap are now sized and indexed by
    // CLUSTER INDEX (not codepoint index) so that each visual glyph unit is
    // mapped to exactly one word slot.
    val charToWordData = remember(mainText, effectiveWords, isBackground, graphemeClusters, clusterCharOffsets) {
        val wordIdxMap = IntArray(clusterCount) { -1 }
        val charInWordMap = IntArray(clusterCount)
        val wordLenMap = IntArray(clusterCount) { 1 }
        var currentPos = 0
        var clCursor = 0
        effectiveWords.forEachIndexed { wordIdx, word ->
            val rawWordText = word.text.let {
                if (isBackground) {
                    var t = it
                    if (wordIdx == 0) t = t.removePrefix("(")
                    if (wordIdx == effectiveWords.size - 1) t = t.removeSuffix(")")
                    t
                } else it
            }
            val indexInMain = mainText.indexOf(rawWordText, currentPos)
            if (indexInMain != -1) {
                val wordEndInMain = indexInMain + rawWordText.length
                // Advance clCursor to the first cluster at or after indexInMain
                while (clCursor < clusterCount && clusterCharOffsets[clCursor] < indexInMain) {
                    clCursor++
                }
                val firstClIdx = clCursor
                // Collect all clusters in the word range [indexInMain, wordEndInMain)
                val wordClusterIndices = mutableListOf<Int>()
                while (clCursor < clusterCount && clusterCharOffsets[clCursor] < wordEndInMain) {
                    wordClusterIndices.add(clCursor)
                    clCursor++
                }
                val wordClusterLen = wordClusterIndices.size
                wordClusterIndices.forEachIndexed { posInWord, clIdx ->
                    wordIdxMap[clIdx] = wordIdx
                    charInWordMap[clIdx] = posInWord
                    wordLenMap[clIdx] = wordClusterLen
                }
                // Check the cluster at clCursor for a trailing space
                if (clCursor < clusterCount && clusterCharOffsets[clCursor] == wordEndInMain && 
                    wordEndInMain < mainText.length && mainText[wordEndInMain] == ' ') {
                    val spaceClIdx = clCursor
                    wordIdxMap[spaceClIdx] = wordIdx
                    charInWordMap[spaceClIdx] = wordClusterLen
                    wordLenMap[spaceClIdx] = wordClusterLen + 1
                    clCursor++
                }
                currentPos = wordEndInMain
            }
        }
        Triple(wordIdxMap, charInWordMap, wordLenMap)
    }

    val hyphenGroupData = remember(effectiveWords) {
        val map = mutableMapOf<Int, HyphenGroupWord>()
        var currentGroup = mutableListOf<Int>()
        effectiveWords.forEachIndexed { wordIdx, word ->
            currentGroup.add(wordIdx)
            if (!word.text.endsWith("-")) {
                if (currentGroup.size > 1) {
                    val groupSize = currentGroup.size
                    val groupStartMs = (effectiveWords[currentGroup.first()].startTime * 1000).toLong()
                    val groupEndMs = (word.endTime * 1000).toLong()
                    currentGroup.forEachIndexed { pos, idx ->
                        map[idx] = HyphenGroupWord(pos, groupSize, pos == groupSize - 1, groupStartMs, groupEndMs)
                    }
                }
                currentGroup = mutableListOf()
            }
        }
        map
    }

    // Per word: when it is sung, in ms (read every frame, so worked out once).
    val wordStartMs = remember(effectiveWords) { LongArray(effectiveWords.size) { (effectiveWords[it].startTime * 1000).toLong() } }
    val wordEndMs = remember(effectiveWords) { LongArray(effectiveWords.size) { (effectiveWords[it].endTime * 1000).toLong() } }
    val originalStartMs = remember(words) { LongArray(words.size) { (words[it].startTime * 1000).toLong() } }
    // Scratch arrays the draw pass fills on every frame (before, every frame made lists, Triples and boxes per letter).
    val sungFactors = remember(effectiveWords) { FloatArray(effectiveWords.size) }
    val wordSung = remember(effectiveWords) { BooleanArray(effectiveWords.size) }
    val wordWobbles = remember(words) { FloatArray(words.size) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxWidthPx = constraints.maxWidth
        val layoutResult = remember(mainText, maxWidthPx, lyricStyle) {
            textMeasurer.measure(
                text = mainText,
                style = lyricStyle,
                constraints = Constraints(minWidth = maxWidthPx, maxWidth = maxWidthPx),
                softWrap = true
            )
        }

        // Each layout corresponds to one grapheme cluster (the visual unit),
        // not one codepoint. Fixes Devanagari/Bengali matra fragmentation.
        val letterLayouts = remember(mainText, lyricStyle) {
            graphemeClusters.map { cluster -> textMeasurer.measure(cluster, lyricStyle) }
        }

        // The box and the visual line of every cluster, from the layout (asked once, not twice per letter per frame).
        val clusterBoxes = remember(layoutResult) { Array(clusterCount) { layoutResult.getBoundingBox(clusterCharOffsets[it]) } }
        val clusterLines = remember(layoutResult) { IntArray(clusterCount) { layoutResult.getLineForOffset(clusterCharOffsets[it]) } }
        val linePushes = remember(layoutResult) { FloatArray(layoutResult.lineCount) }
        val lineTotals = remember(layoutResult) { FloatArray(layoutResult.lineCount) }

        // RTL and joined Indic text is drawn as one shaped line with per-word clips instead of per cluster.
        val drawWholeLine = remember(mainText) { mainText.containsRtl() || mainText.containsJoinedIndic() }
        // for that drawing: the box of every word (left, top, right, bottom), NaN when the word is not in the text
        val wordBoxes = remember(layoutResult, charToWordData) {
            val boxes = FloatArray(effectiveWords.size * 4) { Float.NaN }
            val wordIdxMap = charToWordData.first
            for (i in 0 until clusterCount) {
                val w = wordIdxMap[i]
                if (w < 0) continue
                val b = clusterBoxes[i]
                val k = w * 4
                boxes[k] = if (boxes[k].isNaN()) b.left else minOf(boxes[k], b.left)
                boxes[k + 1] = if (boxes[k + 1].isNaN()) b.top else minOf(boxes[k + 1], b.top)
                boxes[k + 2] = if (boxes[k + 2].isNaN()) b.right else maxOf(boxes[k + 2], b.right)
                boxes[k + 3] = if (boxes[k + 3].isNaN()) b.bottom else maxOf(boxes[k + 3], b.bottom)
            }
            boxes
        }

        Canvas(modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { layoutResult.size.height.toDp() })
        ) {
            if (mainText.isEmpty()) return@Canvas
            if (!isActiveLine) {
                drawText(layoutResult, color = ink)
            } else {
                // The position is read here, in the draw phase, so only the active line redraws while a song plays.
                val smoothPosition = positionProvider()
                // The unsung part stays at 40 percent of full ink however faint the line still is (it is fading in).
                val focusedAlpha = (unsungAlpha / lineAlpha().coerceAtLeast(0.05f)).coerceAtMost(ink.alpha)
                for (w in effectiveWords.indices) {
                    val wStartMs = wordStartMs[w]
                    val wEndMs = wordEndMs[w]
                    val isWordSung = smoothPosition > wEndMs
                    val isWordActive = smoothPosition in wStartMs..wEndMs
                    wordSung[w] = isWordSung
                    sungFactors[w] = if (isWordSung) 1f
                                    else if (isWordActive) ((smoothPosition - wStartMs).toFloat() / (wEndMs - wStartMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                                    else 0f
                }
                if (drawWholeLine) {
                    drawText(layoutResult, color = ink.copy(alpha = focusedAlpha))

                    for (wIdx in effectiveWords.indices) {
                        val k = wIdx * 4
                        if (wordBoxes[k].isNaN()) continue
                        val sungFactor = sungFactors[wIdx]
                        val isWordSung = wordSung[wIdx]
                        val isWordActive = smoothPosition in wordStartMs[wIdx]..wordEndMs[wIdx]
                        if (isWordSung) {
                            clipRect(left = wordBoxes[k], top = wordBoxes[k + 1], right = wordBoxes[k + 2], bottom = wordBoxes[k + 3]) {
                                drawText(layoutResult, color = ink)
                            }
                        } else if (isWordActive && sungFactor > 0f) {
                            clipRect(left = wordBoxes[k], top = wordBoxes[k + 1], right = wordBoxes[k + 2], bottom = wordBoxes[k + 3]) {
                                drawText(layoutResult, color = ink.copy(alpha = focusedAlpha + (ink.alpha - focusedAlpha) * sungFactor))
                            }
                        }
                    }
                    return@Canvas
                }

                val (wordIdxMap, charInWordMap, wordLenMap) = charToWordData

                for (wordIdx in words.indices) {
                    val timeSinceStart = (smoothPosition - originalStartMs[wordIdx]).toFloat()
                    wordWobbles[wordIdx] = if (timeSinceStart in 0f..750f) {
                        if (timeSinceStart < 125f) timeSinceStart / 125f
                        else (1f - (timeSinceStart - 125f) / 625f).coerceAtLeast(0f)
                    } else 0f
                }

                linePushes.fill(0f)
                lineTotals.fill(0f)

                // Pre-calculate total pushes per line to handle alignment correctly.
                // Iterate over cluster indices so each visual glyph unit is one slot.
                for (i in 0 until clusterCount) {
                    val lineIdx = clusterLines[i]
                    val wordIdx = wordIdxMap[i]
                    val originalWordIdx = if (wordIdx != -1) effectiveToOriginalIdx[wordIdx] else -1

                    val sungFactor = if (wordIdx != -1) sungFactors[wordIdx] else 0f
                    val isWordSung = wordIdx != -1 && wordSung[wordIdx]
                    val wobble = if (originalWordIdx != -1) wordWobbles[originalWordIdx] else 0f

                    var crescendoDeltaX = 0f
                    val groupWord = if (wordIdx != -1) hyphenGroupData[wordIdx] else null
                    if (groupWord != null) {
                        val p = sungFactor
                        val timeSinceEnd = (smoothPosition - groupWord.groupEndMs).toFloat()
                        val exitDuration = 600f
                        val pOut = (timeSinceEnd / exitDuration).coerceIn(0f, 1f)
                        val peakScale = 0.06f
                        val decay = 2.5f
                        val freq = 10.0f
                        val baseScalePerSegment = 0.012f
                        if (pOut > 0f) {
                            val baseAtEnd = groupWord.pos * baseScalePerSegment
                            val totalAtEnd = baseAtEnd + peakScale
                            crescendoDeltaX = totalAtEnd * exp(-decay * pOut) * cos(freq * pOut * PI.toFloat()) * (1f - pOut)
                        } else if (groupWord.isLast) {
                            val base = groupWord.pos * baseScalePerSegment
                            val springPart = peakScale * (1f - exp(-decay * p) * cos(freq * p * PI.toFloat()) * (1f - p))
                            crescendoDeltaX = base + springPart
                        } else {
                            val boost = if (p > 0f) 0.02f * (1f - p) else 0f
                            crescendoDeltaX = (groupWord.pos * baseScalePerSegment) + boost
                        }
                    }

                    val charLp = if (wordIdx != -1) {
                        val wordItem = effectiveWords[wordIdx]
                        val sMs = wordItem.startTime * 1000
                        val dur = (wordItem.endTime * 1000 - wordItem.startTime * 1000).coerceAtLeast(100.0)
                        val wProg = (smoothPosition.toDouble() - sMs) / dur
                        val cInW = charInWordMap[i].toDouble()
                        val wLen = wordLenMap[i].toDouble()
                        ((wProg - cInW / wLen) * wLen).coerceIn(0.0, 1.0).toFloat()
                    } else 0f

                    val nudgeScale = if (wordIdx != -1 && !isWordSung && sungFactor > 0f) {
                        0.038f * sin(charLp * PI.toFloat()) * exp(-3f * charLp)
                    } else 0f

                    val charScaleX = 1f + (wobble * 0.025f) + crescendoDeltaX + (nudgeScale * 0.3f)
                    lineTotals[lineIdx] += clusterBoxes[i].width * (charScaleX - 1f)
                }

                // Main drawing loop: iterate over cluster indices so each visual
                // glyph (including multi-codepoint Devanagari clusters) is one unit.
                for (i in 0 until clusterCount) {
                    val lineIdx = clusterLines[i]
                    val charBounds = clusterBoxes[i]
                    val wordIdx = wordIdxMap[i]
                    val originalWordIdx = if (wordIdx != -1) effectiveToOriginalIdx[wordIdx] else -1

                    val alignShift = when(alignment) {
                        TextAlign.Center -> -lineTotals[lineIdx] / 2f
                        TextAlign.Right -> -lineTotals[lineIdx]
                        else -> 0f
                    }

                    val sungFactor = if (wordIdx != -1) sungFactors[wordIdx] else 0f
                    val isWordSung = wordIdx != -1 && wordSung[wordIdx]
                    val wobble = if (originalWordIdx != -1) wordWobbles[originalWordIdx] else 0f
                    val wobbleX = wobble * 0.025f
                    val wobbleY = wobble * 0.015f

                    val charLp = if (wordIdx != -1) {
                        val wordItem = effectiveWords[wordIdx]
                        val sMs = wordItem.startTime * 1000
                        val dur = (wordItem.endTime * 1000 - wordItem.startTime * 1000).coerceAtLeast(100.0)
                        val wProg = (smoothPosition.toDouble() - sMs) / dur
                        val cInW = charInWordMap[i].toDouble()
                        val wLen = wordLenMap[i].toDouble()
                        ((wProg - cInW / wLen) * wLen).coerceIn(0.0, 1.0).toFloat()
                    } else 0f

                    var crescendoDeltaX = 0f
                    var crescendoDeltaY = 0f
                    val groupWord = if (wordIdx != -1) hyphenGroupData[wordIdx] else null
                    if (groupWord != null) {
                        val p = sungFactor
                        val timeSinceEnd = (smoothPosition - groupWord.groupEndMs).toFloat()
                        val exitDuration = 600f
                        val pOut = (timeSinceEnd / exitDuration).coerceIn(0f, 1f)
                        val peakScale = 0.06f
                        val decay = 3.5f
                        val freq = 5.0f
                        val baseScalePerSegment = 0.012f
                        if (pOut > 0f) {
                            val baseAtEnd = groupWord.pos * baseScalePerSegment
                            val totalAtEnd = baseAtEnd + peakScale
                            val springOut = totalAtEnd * exp(-decay * pOut) * cos(freq * pOut * PI.toFloat()) * (1f - pOut)
                            crescendoDeltaX = springOut
                            crescendoDeltaY = springOut
                        } else if (groupWord.isLast) {
                            val base = groupWord.pos * baseScalePerSegment
                            val springPart = peakScale * (1f - exp(-decay * p) * cos(freq * p * PI.toFloat()) * (1f - p))
                            crescendoDeltaX = base + springPart
                            crescendoDeltaY = base + springPart
                        } else {
                            val boost = if (p > 0f) 0.02f * (1f - p) else 0f
                            val base = (groupWord.pos * baseScalePerSegment) + boost
                            crescendoDeltaX = base
                            crescendoDeltaY = base
                        }
                    }

                    val nudgeStrength = 0.038f
                    val nudgeScale = if (wordIdx != -1 && !isWordSung && sungFactor > 0f) {
                        nudgeStrength * sin(charLp * PI.toFloat()) * exp(-3f * charLp)
                    } else 0f

                    val charScaleX = 1f + wobbleX + crescendoDeltaX + nudgeScale * 0.3f
                    val charScaleY = 1f + wobbleY + crescendoDeltaY + nudgeScale

                    withTransform({
                        var waveOffset = 0f
                        if (groupWord != null) {
                            val timeInGroup = (smoothPosition - groupWord.groupStartMs).toFloat()
                            val timeToGroupEnd = (groupWord.groupEndMs - smoothPosition).toFloat()
                            val waveFade = (timeInGroup / 200f).coerceIn(0f, 1f) * (timeToGroupEnd / 200f).coerceIn(0f, 1f)
                            if (waveFade > 0.01f) {
                                val waveSpeed = 0.006f
                                val waveHeight = 3.24f
                                val phaseOffset = i * 0.4f
                                // song time, not the wall clock: it moves with the frame clock of the lyrics
                                waveOffset = sin(smoothPosition * waveSpeed + phaseOffset) * waveHeight * waveFade
                            }
                        }

                        translate(left = alignShift + linePushes[lineIdx] + charBounds.left, top = charBounds.top + waveOffset)
                        if (wordIdx != -1) {
                            scale(
                                charScaleX,
                                charScaleY,
                                pivot = Offset(charBounds.width / 2f, charBounds.height)
                            )
                        }
                    }) {
                        // Editorial look: no glow, only flat ink coloring.
                        val baseAlpha = if (isWordSung || charLp > 0.99f) ink.alpha else (focusedAlpha + (ink.alpha - focusedAlpha) * sungFactor)
                        drawText(letterLayouts[i], color = ink.copy(alpha = if (wordIdx == -1) focusedAlpha else baseAlpha))
                        if (!isWordSung && charLp > 0f && charLp < 1f) {
                            val fXL = charBounds.width * charLp
                            val eW = (charBounds.width * 0.45f).coerceAtLeast(1f)
                            val sWL = (fXL - eW).coerceAtLeast(0f)
                            if (sWL > 0f) {
                                clipRect(left = 0f, top = 0f, right = sWL, bottom = charBounds.height) { drawText(letterLayouts[i], color = ink) }
                            }
                            for (j in 0 until 12) {
                                val start = sWL + (j * eW / 12f)
                                val end = (sWL + ((j + 1) * eW / 12f) + 0.5f).coerceAtMost(fXL)
                                if (end > start) {
                                    clipRect(left = start, top = 0f, right = end, bottom = charBounds.height) { drawText(letterLayouts[i], color = ink.copy(alpha = ink.alpha * (1f - (j + 0.5f) / 12f))) }
                                }
                            }
                        }
                    }
                    linePushes[lineIdx] += charBounds.width * (charScaleX - 1f)
                }
            }
        }
    }
}
