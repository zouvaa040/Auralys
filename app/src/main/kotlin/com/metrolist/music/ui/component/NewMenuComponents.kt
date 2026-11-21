/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.ui.theme.isGlassStyle

/**
 * One quick action of the menu. Kaca: an iOS tile (20 dp corners, translucent group fill, 80 dp high, the icon over its
 * label). Datar: a tile with a hairline outline and no fill (16 dp corners, 84 dp high). A caller's own [backgroundColor]
 * wins in both. The click is still run from composition (the lambda is @Composable), as before.
 */
@Composable
private fun NewActionButton(
    icon: @Composable () -> Unit,
    text: String,
    onClick: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = Color.Unspecified,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val glass = isGlassStyle
    val shape = RoundedCornerShape(if (glass) MenuLook.TileRadiusGlass else MenuLook.TileRadiusEditorial)
    val custom = backgroundColor != Color.Unspecified
    val fill =
        when {
            custom -> backgroundColor
            glass -> glassFill()
            else -> Color.Transparent
        }

    val animatedBackground by animateColorAsState(
        targetValue = if (enabled) fill else fill.copy(alpha = fill.alpha * 0.5f),
        animationSpec = tween(200),
        label = "background",
    )

    val animatedContent by animateColorAsState(
        targetValue = if (enabled) contentColor else contentColor.copy(alpha = 0.5f),
        animationSpec = tween(200),
        label = "content",
    )

    var performAction by remember { mutableStateOf(false) }

    if (performAction) {
        onClick()
        LaunchedEffect(Unit) {
            performAction = false
        }
    }

    Box(
        modifier =
            modifier
                .pressScale(0.95f)
                .clip(shape)
                .background(animatedBackground)
                .then(
                    if (!glass && !custom) {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                    } else {
                        Modifier
                    },
                ).menuPressable(enabled = enabled) { performAction = true },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (glass) MenuLook.TileMinGlass else MenuLook.TileMinEditorial)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalContentColor provides animatedContent) {
                    icon()
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = animatedContent,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The quick actions in equal tiles, [columns] to a row. With iOS animations on the tiles rise in one after the other from
 * [staggerBase] (Modifier.iosAppear); the default keeps every call site as it was.
 */
@Composable
fun NewActionGrid(
    actions: List<NewAction>,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    staggerBase: Int = 0,
) {
    val rows = actions.chunked(columns)
    val gap = if (isGlassStyle) 10.dp else 12.dp

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        rows.forEachIndexed { rowIndex, row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                row.forEachIndexed { index, action ->
                    NewActionButton(
                        icon = action.icon,
                        text = action.text,
                        onClick = action.onClick,
                        modifier = Modifier.weight(1f).iosAppear(staggerBase + rowIndex * columns + index),
                        enabled = action.enabled,
                        backgroundColor = action.backgroundColor,
                        contentColor =
                            if (action.contentColor !=
                                Color.Unspecified
                            ) {
                                action.contentColor
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                }

                repeat(columns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

data class NewAction(
    val icon: @Composable () -> Unit,
    val text: String,
    val onClick: @Composable () -> Unit,
    val enabled: Boolean = true,
    val backgroundColor: Color = Color.Unspecified,
    val contentColor: Color = Color.Unspecified,
)
