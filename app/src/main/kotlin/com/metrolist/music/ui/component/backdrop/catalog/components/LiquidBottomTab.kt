/*
 * Vendored from Kyant0/AndroidLiquidGlass (kmp branch)
 * https://github.com/Kyant0/AndroidLiquidGlass — Copyright 2025 Kyant0, Apache License 2.0
 * app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTab.kt
 *
 * Not published as a library — ported straight from the catalog demo app that
 * showcases Kyant0/backdrop, source-only. Package renamed, imports repointed at
 * this app's vendored com.metrolist.music.ui.component.backdrop, and
 * com.kyant.shapes.Capsule swapped for RoundedCornerShape(percent = 50) (the
 * lens effect only supports CornerBasedShape here — see GlassEffect.kt).
 *
 * Used directly by LiquidBottomTabs.kt — see that file for where it's wired in.
 */
package com.metrolist.music.ui.component.backdrop.catalog.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal val LocalLiquidBottomTabScale =
    staticCompositionLocalOf { { 1f } }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowScope.LiquidBottomTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    Column(
        modifier
            .clip(RoundedCornerShape(percent = 50))
            .combinedClickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onLongClick = onLongClick,
                onClick = onClick
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val scale = scale()
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}
