/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp

/**
 * The two looks of the Horizon design, stored under the same names as before. EDITORIAL is shown as "Datar": the same
 * tokens, no blur and no veils (floating parts are opaque). GLASS is shown as "Kaca": neutral liquid glass on bars and
 * controls. GLASS is the default for people who never chose.
 */
enum class UiStyle {
    EDITORIAL,
    GLASS,
    ;

    companion object {
        val DEFAULT = GLASS
    }
}

/** Provided by MetrolistTheme. */
val LocalUiStyle = staticCompositionLocalOf { UiStyle.DEFAULT }

/** True while the Liquid Glass style is active. */
val isGlassStyle: Boolean
    @Composable
    @ReadOnlyComposable
    get() = LocalUiStyle.current == UiStyle.GLASS

/** Picks a value depending on the active style. */
@Composable
@ReadOnlyComposable
fun <T> styled(glass: T, editorial: T): T = if (LocalUiStyle.current == UiStyle.GLASS) glass else editorial

/**
 * Corner radius helper: the Liquid Glass look uses the big [glass] radius, the Editorial look the
 * smaller [editorial] one (defaults to 12dp, the old paper-card radius).
 */
@Composable
@ReadOnlyComposable
fun styledRadius(glass: Dp, editorial: Dp): Dp = if (LocalUiStyle.current == UiStyle.GLASS) glass else editorial

/** Rounded shape helper built on [styledRadius], snapped to the Horizon radius scale (4, 8, 12, 16, 24, 28; fully round passes through). */
@Composable
@ReadOnlyComposable
fun styledShape(glass: Dp, editorial: Dp): RoundedCornerShape = RoundedCornerShape(HorizonRadius.snap(styledRadius(glass, editorial)))
