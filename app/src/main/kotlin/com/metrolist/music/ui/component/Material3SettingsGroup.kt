/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.ui.theme.HorizonRadius
import com.metrolist.music.ui.theme.HorizonSpace

/**
 * Settings group (Horizon): an optional plain group title above a flat surface-1 block (16 dp radius, no border, no
 * shadow). Rows are at least 56 dp, a bare 22 dp ink icon on the left (no disc, no tile, no accent), the title in
 * semi-bold, a description only when the item has one, the value or control on the right, and an inset hairline between
 * rows. The same in both styles.
 */
@Composable
fun Material3SettingsGroup(
    title: String? = null,
    items: List<Material3SettingsItem>,
    useLowContrast: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = HorizonSpace.S16, end = HorizonSpace.S16, top = HorizonSpace.S8, bottom = HorizonSpace.S8),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassCard(glassShape(HorizonRadius.Lg, HorizonRadius.Lg)),
        ) {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    EditorialDivider(modifier = Modifier.padding(horizontal = HorizonSpace.S16))
                }
                Material3SettingsItemRow(item = item)
            }
        }
    }
}

@Composable
private fun Material3SettingsItemRow(item: Material3SettingsItem) {
    val scheme = MaterialTheme.colorScheme
    val textColor = if (item.enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HorizonSpace.SettingsRowHeight)
            .clickable(
                enabled = item.enabled && item.onClick != null,
                onClick = { item.onClick?.invoke() },
            )
            .padding(horizontal = HorizonSpace.S16, vertical = HorizonSpace.S8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.leadingContent != null) {
            item.leadingContent.invoke()
            Spacer(modifier = Modifier.width(HorizonSpace.S16))
        } else if (item.icon != null) {
            if (item.showBadge) {
                BadgedBox(badge = { Badge(containerColor = scheme.primary) }) {
                    Icon(painter = item.icon, contentDescription = null, tint = textColor, modifier = Modifier.size(22.dp))
                }
            } else {
                Icon(painter = item.icon, contentDescription = null, tint = textColor, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(HorizonSpace.S16))
        }

        Column(modifier = Modifier.weight(1f)) {
            ProvideTextStyle(MaterialTheme.typography.titleMedium.copy(color = textColor)) { item.title() }

            item.description?.let { desc ->
                Spacer(modifier = Modifier.height(2.dp))
                ProvideTextStyle(
                    MaterialTheme.typography.bodyMedium.copy(
                        color = if (item.enabled) scheme.onSurfaceVariant else scheme.onSurface.copy(alpha = 0.38f),
                    ),
                ) { desc() }
            }
        }

        item.trailingContent?.let { trailing ->
            Spacer(modifier = Modifier.width(HorizonSpace.S8))
            ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurfaceVariant)) {
                trailing()
            }
        }
        if (item.trailingContent == null && item.onClick != null && item.enabled) {
            Spacer(modifier = Modifier.width(HorizonSpace.S8))
            Icon(
                painter = painterResource(R.drawable.navigate_next),
                contentDescription = null,
                tint = scheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Data class for Material 3 settings item
 */
data class Material3SettingsItem(
    val icon: Painter? = null,
    val leadingContent: (@Composable () -> Unit)? = null,
    val title: @Composable () -> Unit,
    val description: (@Composable () -> Unit)? = null,
    val trailingContent: (@Composable () -> Unit)? = null,
    val showBadge: Boolean = false,
    val isHighlighted: Boolean = false,
    val enabled: Boolean = true,
    val onClick: (() -> Unit)? = null
)
