/**
 * Auralys - Dynamic Island: colour picker and the "place on screen" editor
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.metrolist.music.R
import com.metrolist.music.constants.DynamicIslandMaxY
import com.metrolist.music.constants.DynamicIslandMinY
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.DynamicIslandConfig
import com.metrolist.music.ui.component.DynamicIslandDimens
import com.metrolist.music.ui.component.DynamicIslandPill
import com.metrolist.music.ui.component.NoOpDynamicIslandActions
import com.metrolist.music.ui.component.Slider
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private val IslandSwatches =
    listOf(
        0xFFFFFFFF, 0xFF111114, 0xFF8E8E93, 0xFFFF453A, 0xFFFF9F0A, 0xFFFFD60A,
        0xFF30D158, 0xFF22E6FF, 0xFF0A84FF, 0xFF5E5CE6, 0xFFBF5AF2, 0xFFFF375F,
    ).map { it.toInt() }

/** A small colour chooser: swatches plus hue / saturation / brightness. [onSelect] gets 0 for "automatic". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IslandColorDialog(
    title: String,
    initial: Int,
    allowAuto: Boolean,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = if (initial == 0) IslandSwatches[8] else initial
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(start, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var brightness by remember { mutableFloatStateOf(hsv[2]) }
    val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))

    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(title) },
        buttons = {
            if (allowAuto) {
                TextButton(onClick = { onSelect(0) }) { Text(stringResource(R.string.dynamic_island_color_use_auto)) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            TextButton(onClick = { onSelect(color) }) { Text(stringResource(android.R.string.ok)) }
        },
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IslandSwatches.forEach { swatch ->
                Spacer(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(swatch))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable {
                            val next = FloatArray(3)
                            android.graphics.Color.colorToHSV(swatch, next)
                            hue = next[0]
                            saturation = next[1]
                            brightness = next[2]
                        },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(color))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        )
        Text(stringResource(R.string.dynamic_island_hue), style = MaterialTheme.typography.labelLarge)
        Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
        Text(stringResource(R.string.dynamic_island_saturation), style = MaterialTheme.typography.labelLarge)
        Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..1f)
        Text(stringResource(R.string.dynamic_island_brightness), style = MaterialTheme.typography.labelLarge)
        Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 0f..1f)
    }
}

/**
 * "Place on screen": a mock of the phone with the real island on it. Touching or dragging anywhere puts the island
 * under the finger; near the centre it snaps to the middle. Saves the same horizontal fraction and vertical offset the
 * overlay reads, so what is placed here is exactly where the island appears.
 */
@Composable
internal fun IslandPlaceDialog(
    config: DynamicIslandConfig,
    sampleTitle: String,
    sampleArtist: String,
    statusBarDp: Float,
    initialX: Float,
    initialY: Int,
    onSave: (x: Float, y: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val screen = LocalWindowInfo.current.containerDpSize
    val screenW = screen.width.value.coerceAtLeast(1f)
    val screenH = screen.height.value.coerceAtLeast(1f)
    var x by remember { mutableFloatStateOf(initialX.coerceIn(-1f, 1f)) }
    var y by remember { mutableFloatStateOf(initialY.toFloat().coerceIn(DynamicIslandMinY.toFloat(), DynamicIslandMaxY.toFloat())) }
    val sampleLyric = stringResource(R.string.dynamic_island_sample_lyric)
    val pillState = remember(sampleTitle, sampleArtist, sampleLyric) { samplePreviewState(sampleTitle, sampleArtist, expanded = false, lyric = sampleLyric) }
    val appAccent = MaterialTheme.colorScheme.primary
    val appDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    SideEffect {
        pillState.config = config
        pillState.appAccent = appAccent
        pillState.dark = appDark
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.dynamic_island_place), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.dynamic_island_place_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                BoxWithConstraints(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val fit = min(maxWidth.value / screenW, maxHeight.value / screenH)
                    val mockW = (screenW * fit).dp
                    val mockH = (screenH * fit).dp
                    val ratio = fit
                    val pillScale = config.size.scale * ratio
                    val compactW = DynamicIslandDimens.compactWidth(config).dp * pillScale
                    val compactH = config.pillHeight.dp * pillScale
                    val free = (mockW - compactW).coerceAtLeast(0.dp)
                    val left = free / 2 * (1f + x)
                    val top = if (config.attached) 0.dp else ((statusBarDp + y).dp * ratio).coerceAtLeast(0.dp)
                    SideEffect {
                        pillState.scale = pillScale
                        pillState.expandedWidth = config.expandedWidth * pillScale
                        pillState.compactShiftX = 0f
                        pillState.compactShiftY = 0f
                    }

                    fun moveCentreTo(px: Float, py: Float) {
                        val cx = with(density) { px.toDp() }
                        val cy = with(density) { py.toDp() }
                        val newLeft = cx - compactW / 2
                        val half = free / 2
                        x =
                            if (half.value > 0f) (newLeft / half - 1f).coerceIn(-1f, 1f).let { if (abs(it) < 0.05f) 0f else it } else 0f
                        val newTop = cy - compactH / 2
                        y = (newTop.value / ratio - statusBarDp).roundToInt().coerceIn(DynamicIslandMinY, DynamicIslandMaxY).toFloat()
                    }

                    Spacer(
                        Modifier
                            .size(mockW, mockH)
                            .clip(RoundedCornerShape(28.dp))
                            .background(Brush.verticalGradient(listOf(Color(0xFF3A4458), Color(0xFF1B1F29), Color(0xFF101217))))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp)),
                    )
                    // centre guide while the island sits exactly in the middle
                    if (x == 0f) {
                        Spacer(
                            Modifier
                                .size(1.dp, mockH)
                                .background(Color.White.copy(alpha = 0.18f)),
                        )
                    }
                    DynamicIslandPill(
                        state = pillState,
                        actions = NoOpDynamicIslandActions,
                        animateAppearance = false,
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .offset(
                                    x = (maxWidth - mockW) / 2 + left,
                                    y = (maxHeight - mockH) / 2 + top,
                                ),
                    )
                    // the touch layer sits on top of the island, otherwise the island would swallow the drag
                    Spacer(
                        Modifier
                            .align(Alignment.Center)
                            .size(mockW, mockH)
                            .pointerInput(compactW, compactH, free, ratio) {
                                detectTapGestures { moveCentreTo(it.x, it.y) }
                            }.pointerInput(compactW, compactH, free, ratio) {
                                detectDragGestures(
                                    onDragStart = { moveCentreTo(it.x, it.y) },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        moveCentreTo(change.position.x, change.position.y)
                                    },
                                )
                            },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                    TextButton(onClick = { onSave(x, y.roundToInt()) }) { Text(stringResource(android.R.string.ok)) }
                }
            }
        }
    }
}
