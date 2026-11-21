/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.equalizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.eq.data.GraphicEq
import com.metrolist.music.ui.component.Slider
import java.util.Locale
import kotlin.math.abs

/** The name of a built-in preset; [id] is [GraphicEq.Preset.id]. */
@Composable
internal fun presetName(id: String): String =
    stringResource(
        when (id) {
            "bass" -> R.string.eq_preset_bass
            "treble" -> R.string.eq_preset_treble
            "vocal" -> R.string.eq_preset_vocal
            "pop" -> R.string.eq_preset_pop
            "rock" -> R.string.eq_preset_rock
            "jazz" -> R.string.eq_preset_jazz
            "classic" -> R.string.eq_preset_classic
            "dance" -> R.string.eq_preset_dance
            "hiphop" -> R.string.eq_preset_hiphop
            "acoustic" -> R.string.eq_preset_acoustic
            "loudness" -> R.string.eq_preset_loudness
            else -> R.string.eq_preset_flat
        },
    )

/** +3, -2.5, 0: a gain as the sliders show it. */
internal fun dbText(db: Double): String =
    if (abs(db) < 0.05) "0" else String.format(Locale.US, "%+.1f", db).removeSuffix(".0")

/**
 * The easy view: three sliders, one for the bass, one for the vocals and one for the treble. Each moves a whole region of the
 * ten bands together; the ten sliders below show what that did.
 */
@Composable
internal fun EqToneSliders(
    gains: List<Double>,
    onGroup: (group: Int, level: Double) -> Unit,
) {
    val rows =
        listOf(
            GraphicEq.BASS to R.string.eq_tone_bass,
            GraphicEq.VOCAL to R.string.eq_tone_vocal,
            GraphicEq.TREBLE to R.string.eq_tone_treble,
        )
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        rows.forEach { (group, label) ->
            val level = GraphicEq.groupLevel(gains, group)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.width(68.dp),
                )
                Slider(
                    value = level.toFloat(),
                    onValueChange = { onGroup(group, it.toDouble()) },
                    valueRange = -GraphicEq.MAX_DB.toFloat()..GraphicEq.MAX_DB.toFloat(),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.eq_db_value, dbText(level)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(64.dp),
                )
            }
        }
    }
}

private val BAND_LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

/** The ten vertical sliders, one per octave from 31 Hz to 16 kHz. */
@Composable
internal fun EqBandSliders(
    gains: List<Double>,
    onBand: (index: Int, db: Double) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        gains.forEachIndexed { i, db ->
            BandSlider(
                db = db,
                label = BAND_LABELS[i],
                onChange = { onBand(i, it) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private val BandHeight = 168.dp
private val ThumbRadius = 11.dp
private val TrackWidth = 6.dp

@Composable
private fun BandSlider(
    db: Double,
    label: String,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val change by rememberUpdatedState(onChange)
    val haptics = LocalHapticFeedback.current
    val trackColor = scheme.surfaceContainerHighest
    val fillColor = scheme.primary
    val thumbFill = scheme.surface
    val zeroColor = scheme.outline
    val maxDb = GraphicEq.MAX_DB
    val description = stringResource(R.string.eq_band_description, label)
    val valueText = stringResource(R.string.eq_db_value, dbText(db))

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = dbText(db),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(BandHeight)
                .semantics {
                    contentDescription = description
                    stateDescription = valueText
                    progressBarRangeInfo = ProgressBarRangeInfo(db.toFloat(), -maxDb.toFloat()..maxDb.toFloat())
                    setProgress { v ->
                        change(GraphicEq.snap(v.toDouble()))
                        true
                    }
                }
                .pointerInput(Unit) {
                    val pad = ThumbRadius.toPx()

                    fun valueAt(y: Float): Double {
                        val span = (size.height - 2 * pad).coerceAtLeast(1f)
                        val t = ((y - pad) / span).coerceIn(0f, 1f)
                        return GraphicEq.snap(maxDb - t * 2 * maxDb)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        var last = valueAt(down.position.y)
                        change(last)
                        drag(down.id) { move ->
                            val v = valueAt(move.position.y)
                            if (v != last) {
                                // a small tick when the slider passes the middle (0 dB)
                                if (v == 0.0 && last != 0.0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                last = v
                                change(v)
                            }
                            move.consume()
                        }
                    }
                },
        ) {
            val pad = ThumbRadius.toPx()
            val cx = size.width / 2f
            val top = pad
            val bottom = size.height - pad

            fun yFor(v: Double): Float = top + (1f - ((v + maxDb) / (2 * maxDb)).toFloat()) * (bottom - top)
            val stroke = TrackWidth.toPx()
            drawLine(trackColor, Offset(cx, top), Offset(cx, bottom), strokeWidth = stroke, cap = StrokeCap.Round)
            val y0 = yFor(0.0)
            val yv = yFor(db)
            drawLine(zeroColor, Offset(cx - 9.dp.toPx(), y0), Offset(cx + 9.dp.toPx(), y0), strokeWidth = 1.dp.toPx())
            drawLine(fillColor, Offset(cx, y0), Offset(cx, yv), strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(fillColor, radius = ThumbRadius.toPx(), center = Offset(cx, yv))
            drawCircle(thumbFill, radius = ThumbRadius.toPx() - 3.dp.toPx(), center = Offset(cx, yv))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
