/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.ui.theme.isGlassStyle

/**
 * One group of rows of a menu. Kaca: an iOS "inset grouped" block (rounded, translucent fill, inset hairlines, the title on
 * the left and the icon on the right). Datar: rows 56 dp high under an inset hairline, the icon on the left.
 * [staggerBase] is the position of the group's first row among the sheet's parts: with iOS animations on, rows rise in one
 * after the other (Modifier.iosAppear); the default keeps every call site as it was.
 */
@Composable
fun Material3MenuGroup(
    items: List<Material3MenuItemData>,
    staggerBase: Int = 0,
) {
    if (isGlassStyle) {
        IosMenuGroup(items = items, staggerBase = staggerBase)
    } else {
        EditorialMenuGroup(items = items, staggerBase = staggerBase)
    }
}

/** The row's own background when the caller gave one (a danger row, a highlighted row). */
private fun Material3MenuItemData.rowBackground(): Modifier =
    cardColors?.containerColor?.takeIf { it != Color.Unspecified }?.let { Modifier.background(it) } ?: Modifier

@Composable
private fun IosMenuGroup(
    items: List<Material3MenuItemData>,
    staggerBase: Int,
) {
    // 4 dp on the sides: the action tiles above are inset the same, so the edges line up
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .padding(top = MenuLook.GroupGap),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(MenuLook.GroupRadiusGlass))
                    .background(glassFill()),
        ) {
            items.forEachIndexed { index, item ->
                if (index > 0) MenuHairline(inset = 20.dp)
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(item.rowBackground())
                            .iosAppear(staggerBase + index),
                ) {
                    IosMenuRow(item = item)
                }
            }
        }
    }
}

@Composable
private fun IosMenuRow(item: Material3MenuItemData) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .menuPressable(enabled = item.onClick != null, onClick = { item.onClick?.invoke() })
                .heightIn(min = MenuLook.RowMinGlass)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
                item.title()
            }
            item.description?.let { desc ->
                Spacer(modifier = Modifier.height(2.dp))
                ProvideTextStyle(
                    MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        color = scheme.onSurfaceVariant,
                    ),
                ) {
                    desc()
                }
            }
        }
        item.trailingContent?.let { trailing ->
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
        // iOS puts the glyph of a menu row at the END of the row
        item.icon?.let { icon ->
            Spacer(modifier = Modifier.width(12.dp))
            Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides scheme.onSurface.copy(alpha = 0.92f)) {
                    icon()
                }
            }
        }
    }
}

@Composable
private fun EditorialMenuGroup(
    items: List<Material3MenuItemData>,
    staggerBase: Int,
) {
    // Horizon: groups are separated by an inset hairline above them, not boxed (no card, no shadow per group)
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        EditorialDivider(modifier = Modifier.padding(horizontal = 16.dp))
        items.forEachIndexed { index, item ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(item.rowBackground())
                        .iosAppear(staggerBase + index),
            ) {
                EditorialMenuRow(item = item)
            }
        }
    }
}

@Composable
private fun EditorialMenuRow(item: Material3MenuItemData) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .menuPressable(enabled = item.onClick != null, onClick = { item.onClick?.invoke() })
                .heightIn(min = MenuLook.RowMinEditorial)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.icon?.let { icon ->
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                icon()
            }
            Spacer(modifier = Modifier.width(16.dp))
        }

        Column(
            modifier = Modifier.weight(1f),
        ) {
            ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
                item.title()
            }

            item.description?.let { desc ->
                Spacer(modifier = Modifier.height(2.dp))
                ProvideTextStyle(
                    MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    desc()
                }
            }
        }
        item.trailingContent?.let { trailing ->
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}

data class Material3MenuItemData(
    val icon: (@Composable () -> Unit)? = null,
    val title: @Composable () -> Unit,
    val description: (@Composable () -> Unit)? = null,
    val onClick: (() -> Unit)? = null,
    val cardColors: CardColors? = null,
    val trailingContent: (@Composable () -> Unit)? = null,
)
