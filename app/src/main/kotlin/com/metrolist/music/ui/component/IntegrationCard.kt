/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component


import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

/** Integration list: same look as [Material3SettingsGroup]. */
@Composable
fun IntegrationCard(
    title: String? = null,
    items: List<IntegrationCardItem>
) {
    Material3SettingsGroup(
        title = title,
        items = items.map {
            Material3SettingsItem(
                icon = it.icon,
                title = it.title,
                description = it.description,
                trailingContent = it.trailingContent,
                showBadge = it.showBadge,
                isHighlighted = it.isHighlighted,
                onClick = it.onClick,
            )
        },
    )
}

/**
 * Data class for Material 3 settings item
 */
data class IntegrationCardItem(
    val icon: Painter? = null,
    val title: @Composable () -> Unit,
    val description: (@Composable () -> Unit)? = null,
    val trailingContent: (@Composable () -> Unit)? = null,
    val showBadge: Boolean = false,
    val isHighlighted: Boolean = false,
    val onClick: (() -> Unit)? = null
)
