/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Drop-in for Material 3's DropdownMenu. The glass colour scheme makes every surfaceContainer* colour a translucent
 * white, which is right for panes drawn over the page but wrong for a popup: the menu opens in its own window, so the
 * list underneath shows straight through it and the two texts overlap. In the glass style this paints the menu on an
 * opaque base (the page surface) with the usual glass fill, rim and sheen on top, so it still reads as glass.
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.metrolist.music.ui.theme.LocalUiStyle
import com.metrolist.music.ui.theme.UiStyle

@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    // Horizon: one menu pane in both styles (G3: opaque surface-1, 16 dp radius, E3 shadow)
    val shape = glassShape(16.dp, 16.dp)
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.glassOverlay(shape, 0.dp),
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        shape = shape,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 16.dp,
        content = content,
    )
}
