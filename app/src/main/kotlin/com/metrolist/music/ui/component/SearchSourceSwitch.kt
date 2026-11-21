/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import kotlin.math.sin
import kotlin.math.floor
import kotlin.math.PI
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.metrolist.music.ui.theme.isGlassStyle
import kotlin.math.roundToInt

/**
 * Two (or more) always-visible options in one capsule, e.g. "YouTube | Perangkat" under the search field (Horizon
 * segmented control, 36 dp). No accent colour: the track is surface-2 (Kaca: a G2 glass control), the selection is a
 * thumb that slides under the chosen label (spring): surface-1 with a soft shadow on light pages, white 14 percent with
 * a rim on dark ones. Labels are ink, semi-bold when selected, secondary otherwise. No backdrop blur: the switch lives
 * inside the page that the app backdrop records and sits still while the result list scrolls. Idea from BitChord's search
 * source switcher (GPLv3), own implementation.
 */
@Composable
fun SearchSourceSwitch(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = isGlassStyle
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val shape = RoundedCornerShape(50)
    val count = options.size.coerceAtLeast(1)
    val selected = selectedIndex.coerceIn(0, count - 1)
    val ios = LocalIosMotion.current
    // read in the offset lambda only, so the slide never recomposes anything. iOS animations: a softer, bouncier spring
    val position =
        animateFloatAsState(
            targetValue = selected.toFloat(),
            animationSpec =
                if (ios) {
                    spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)
                } else {
                    spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium)
                },
            label = "searchSourcePuck",
        )

    val track = if (glass) Modifier.glass(shape) else Modifier.clip(shape).background(scheme.surfaceContainerHigh)
    val puck =
        if (dark) {
            Modifier
                .clip(shape)
                .background(Color.White.copy(alpha = 0.14f))
                .border(0.8.dp, Color.White.copy(alpha = 0.12f), shape)
        } else {
            Modifier
                .shadow(3.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.14f))
                .clip(shape)
                .background(scheme.surfaceContainerLow)
        }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .height(36.dp)
                .then(track)
                .padding(3.dp),
    ) {
        val segmentWidth = maxWidth / count
        val segmentPx = constraints.maxWidth / count.toFloat()
        Box(
            modifier =
                Modifier
                    .offset { IntOffset((position.value * segmentPx).roundToInt(), 0) }
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .then(
                        // iOS animations: the thumb stretches a little while it travels, like a drop of liquid
                        if (ios) {
                            Modifier.graphicsLayer {
                                val f = position.value - floor(position.value)
                                scaleX = 1f + 0.10f * sin(PI.toFloat() * f)
                            }
                        } else {
                            Modifier
                        },
                    ).then(puck),
        )
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { index, label ->
                Segment(
                    label = label,
                    selected = index == selected,
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun RowScope.Segment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val content by animateColorAsState(
        if (selected) scheme.onSurface else scheme.onSurfaceVariant,
        label = "segmentContent",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .iosTap(0.95f)
                .clip(RoundedCornerShape(50))
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Text(
            text = label,
            color = content,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
