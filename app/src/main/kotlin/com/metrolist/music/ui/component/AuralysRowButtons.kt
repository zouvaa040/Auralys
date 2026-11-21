/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import com.metrolist.music.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The two buttons at the end of a song row in a playlist: "more" (three bare dots, lying down) and the drag grip (two
 * columns of dots). They replace the stock vertical dots and hamburger lines, drawn here so they cost no icon inflation
 * and keep one look in both UI styles. No pill, no fill: they are ink-grey glyphs like every other trailing icon.
 */
@Composable
fun RowMoreButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dot = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(44.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.more_options),
                    onClick = onClick,
                ),
    ) {
        Canvas(Modifier.size(width = 20.dp, height = 4.dp)) {
            val r = 2.dp.toPx()
            val step = (size.width - 2 * r) / 2f
            for (i in 0..2) drawCircle(dot, r, Offset(r + step * i, size.height / 2f))
        }
    }
}

/** The drag grip: pass `Modifier.draggableHandle()` as [modifier] inside a reorderable item. */
@Composable
fun RowDragGrip(modifier: Modifier = Modifier) {
    val dot = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(40.dp)) {
        Canvas(Modifier.size(width = 12.dp, height = 18.dp)) {
            val r = 1.6.dp.toPx()
            val dx = size.width - 2 * r
            val dy = (size.height - 2 * r) / 2f
            for (col in 0..1) for (row in 0..2) {
                drawCircle(dot, r, Offset(r + dx * col, r + dy * row))
            }
        }
    }
}
