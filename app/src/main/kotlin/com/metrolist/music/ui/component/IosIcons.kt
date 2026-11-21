/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The icon families of the app and the one place that picks the drawable or vector for an icon.
 *  - LINE  ("Garis"): the normal drawables (Lucide-style 1.75 strokes).
 *  - IOS   ("iOS"): the `cx_` twins listed in [IosIconTable] (generated): Ionicons (MIT licence) outlines and a few
 *    hand-drawn glyphs in the same pen.
 *  - ROUND ("Bulat"): Material Icons Rounded plus custom 2.2 px strokes, see [BulatIconTable]. Default.
 * A drawable that is missing from a table would silently keep its LINE look in that family: IconCoverageTest fails
 * for every icon the code draws that has no twin in both tables, so that cannot happen unnoticed.
 * Same name and signature as androidx.compose.ui.res.painterResource, so call sites only swap the import.
 * The family no longer depends on the UI style: it is its own setting (Appearance > Icon style).
 */

package com.metrolist.music.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter

/** Stored by name in the preference `iconFamily`. A missing or unknown value reads as [DEFAULT]. */
enum class IconFamily {
    LINE,
    IOS,
    ROUND,
    ;

    companion object {
        val DEFAULT = ROUND
    }
}

/** Provided by MetrolistTheme. Without a provider (previews, the crash screen) the default family is used. */
val LocalIconFamily = staticCompositionLocalOf { IconFamily.DEFAULT }

/** White, like the strokes of the XML drawables; used when nothing tints the icon. */
private val WhiteSrcIn = ColorFilter.tint(Color.White, BlendMode.SrcIn)

/**
 * Paints [inner] white unless the caller gives a colour filter (`Icon(tint = ...)` does). Replaces the tint-and-content
 * `rememberVectorPainter` overload, which builds a sub-composition for every icon on screen: this one wraps the
 * cheap `ImageVector` painter (no composition, shared by image id) and only chooses the default filter.
 */
private class WhiteDefaultPainter(
    private val inner: Painter,
) : Painter() {
    private var alphaValue = 1f
    private var filter: ColorFilter? = null

    override val intrinsicSize: Size get() = inner.intrinsicSize

    override fun applyAlpha(alpha: Float): Boolean {
        alphaValue = alpha
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        filter = colorFilter
        return true
    }

    override fun DrawScope.onDraw() {
        with(inner) { draw(size, alphaValue, filter ?: WhiteSrcIn) }
    }
}

@Composable
private fun whitePainter(image: ImageVector): Painter {
    val inner = rememberVectorPainter(image)
    return remember(inner) { WhiteDefaultPainter(inner) }
}

/** The twin XML drawable, drawn white unless the caller tints (the twins carry no colour of their own). */
@Composable
private fun whitePainter(@DrawableRes twin: Int): Painter {
    val inner = androidx.compose.ui.res.painterResource(twin)
    return remember(inner) { WhiteDefaultPainter(inner) }
}

@Composable
fun painterResource(@DrawableRes id: Int): Painter {
    when (LocalIconFamily.current) {
        IconFamily.ROUND -> BulatIconTable.vectorFor(id)?.let { return whitePainter(it) }
        IconFamily.IOS -> iosTwinFor(id)?.let { return whitePainter(it) }
        IconFamily.LINE -> Unit
    }
    return androidx.compose.ui.res.painterResource(id)
}
