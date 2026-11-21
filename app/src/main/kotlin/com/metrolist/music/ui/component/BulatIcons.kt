/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Custom icons of the "Bulat" family: thick (2.2) strokes with round caps and joins on a 24 x 24 grid.
 * The shapes are adapted from BitChord's `BitChordIcons` (GPL-3.0 as well, same idea: one stroke weight, round
 * ends). Changes: only the shapes Auralys needs, filled/outline pairs for tabs, the names. Everything that has no
 * shape here comes from Material Icons Rounded (Apache-2.0), see [BulatIconTable].
 */

package com.metrolist.music.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object BulatIcons {
    private const val STROKE = 2.2f

    // The colour is irrelevant: the wrapper tints every vector (see IosIcons.kt).
    private val ink = SolidColor(Color.Black)

    private fun icon(
        name: String,
        block: ImageVector.Builder.() -> Unit,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply(block)
            .build()

    private fun ImageVector.Builder.line(
        width: Float = STROKE,
        filled: Boolean = false,
        block: PathBuilder.() -> Unit,
    ) {
        path(
            fill = if (filled) ink else null,
            stroke = ink,
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    /** A full circle written as two arcs (centre cx, cy and radius r). */
    private fun PathBuilder.circle(
        cx: Float,
        cy: Float,
        r: Float,
    ) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, 2 * r, 0f)
        arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, -2 * r, 0f)
    }

    /** A rounded rectangle (left, top, width, height, corner radius). */
    private fun PathBuilder.roundedRect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        r: Float,
    ) {
        moveTo(x + r, y)
        lineTo(x + w - r, y)
        arcToRelative(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, r, r)
        lineTo(x + w, y + h - r)
        arcToRelative(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, -r, r)
        lineTo(x + r, y + h)
        arcToRelative(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, -r, -r)
        lineTo(x, y + r)
        arcToRelative(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, r, -r)
        close()
    }

    val Play: ImageVector by lazy {
        icon("Bulat.Play") {
            line(filled = true) {
                moveTo(6.8f, 4.8f)
                lineTo(19.2f, 12f)
                lineTo(6.8f, 19.2f)
                close()
            }
        }
    }

    val Plus: ImageVector by lazy {
        icon("Bulat.Plus") {
            line {
                moveTo(12f, 5f)
                lineTo(12f, 19f)
                moveTo(5f, 12f)
                lineTo(19f, 12f)
            }
        }
    }

    val Check: ImageVector by lazy {
        icon("Bulat.Check") {
            line {
                moveTo(5f, 12.8f)
                lineTo(9.6f, 17.4f)
                lineTo(19f, 6.9f)
            }
        }
    }

    val ChevronRight: ImageVector by lazy {
        icon("Bulat.ChevronRight") {
            line {
                moveTo(9.5f, 6.2f)
                lineTo(15.3f, 12f)
                lineTo(9.5f, 17.8f)
            }
        }
    }

    val Download: ImageVector by lazy {
        icon("Bulat.Download") {
            line {
                moveTo(12f, 4.5f)
                lineTo(12f, 15.5f)
                moveTo(7.5f, 11f)
                lineTo(12f, 15.5f)
                lineTo(16.5f, 11f)
                moveTo(4.5f, 18f)
                lineTo(19.5f, 18f)
            }
        }
    }

    val Search: ImageVector by lazy {
        icon("Bulat.Search") {
            line {
                circle(11f, 11f, 6.4f)
                moveTo(15.9f, 15.9f)
                lineTo(20.4f, 20.4f)
            }
        }
    }

    val Explore: ImageVector by lazy {
        icon("Bulat.Explore") {
            line {
                circle(12f, 12f, 8.6f)
                moveTo(15.4f, 8.6f)
                lineTo(13.6f, 13.6f)
                lineTo(8.6f, 15.4f)
                lineTo(10.4f, 10.4f)
                close()
            }
        }
    }

    // BitChord draws one house for both tab states; Auralys keeps an outline and a filled house.
    private fun house(
        name: String,
        filled: Boolean,
    ): ImageVector =
        icon(name) {
            line(width = if (filled) 1.7f else STROKE, filled = filled) {
                moveTo(12f, 4.05f)
                lineTo(19.75f, 11.35f)
                lineTo(19.75f, 19.65f)
                lineTo(14.95f, 19.65f)
                lineTo(14.95f, 15.6f)
                lineTo(9.05f, 15.6f)
                lineTo(9.05f, 19.65f)
                lineTo(4.25f, 19.65f)
                lineTo(4.25f, 11.35f)
                close()
            }
        }

    val Home: ImageVector by lazy { house("Bulat.Home", filled = false) }
    val HomeFilled: ImageVector by lazy { house("Bulat.HomeFilled", filled = true) }

    // Four spines (three upright, one leaning). The filled tab state is the same shape with a heavier pen.
    private fun library(
        name: String,
        width: Float,
    ): ImageVector =
        icon(name) {
            line(width = width) {
                moveTo(4.6f, 4.8f)
                lineTo(4.6f, 19.2f)
                moveTo(9.2f, 4.8f)
                lineTo(9.2f, 19.2f)
                moveTo(13.8f, 4.8f)
                lineTo(13.8f, 19.2f)
                moveTo(17.2f, 5.6f)
                lineTo(20.6f, 18.9f)
            }
        }

    val Library: ImageVector by lazy { library("Bulat.Library", STROKE) }
    val LibraryFilled: ImageVector by lazy { library("Bulat.LibraryFilled", 3.2f) }

    private fun heart(
        name: String,
        filled: Boolean,
    ): ImageVector =
        icon(name) {
            line(filled = filled) {
                moveTo(12f, 20f)
                curveTo(12f, 20f, 3.2f, 14.6f, 3.2f, 8.9f)
                arcToRelative(4.5f, 4.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 8.8f, -1.5f)
                arcToRelative(4.5f, 4.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 8.8f, 1.5f)
                curveTo(20.8f, 14.6f, 12f, 20f, 12f, 20f)
                close()
            }
        }

    val Heart: ImageVector by lazy { heart("Bulat.Heart", filled = false) }
    val HeartFilled: ImageVector by lazy { heart("Bulat.HeartFilled", filled = true) }

    val Queue: ImageVector by lazy {
        icon("Bulat.Queue") {
            line(width = 2f) {
                for (y in listOf(6f, 12f, 18f)) {
                    moveTo(8f, y)
                    lineTo(21f, y)
                }
            }
            path(fill = ink) {
                for (y in listOf(6f, 12f, 18f)) {
                    moveTo(4.35f, y)
                    arcToRelative(1.25f, 1.25f, 0f, true, true, -2.5f, 0f)
                    arcToRelative(1.25f, 1.25f, 0f, true, true, 2.5f, 0f)
                    close()
                }
            }
        }
    }

    val Lyrics: ImageVector by lazy {
        icon("Bulat.Lyrics") {
            line {
                moveTo(6.2f, 4.6f)
                lineTo(17.8f, 4.6f)
                arcToRelative(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2.8f, 2.8f)
                lineTo(20.6f, 13.6f)
                arcToRelative(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2.8f, 2.8f)
                lineTo(10.6f, 16.4f)
                lineTo(6.8f, 19.6f)
                lineTo(6.8f, 16.4f)
                lineTo(6.2f, 16.4f)
                arcToRelative(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2.8f, -2.8f)
                lineTo(3.4f, 7.4f)
                arcToRelative(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2.8f, -2.8f)
                close()
                moveTo(7.6f, 9f)
                lineTo(16.4f, 9f)
                moveTo(7.6f, 12.1f)
                lineTo(13.2f, 12.1f)
            }
        }
    }

    val MusicNote: ImageVector by lazy {
        icon("Bulat.MusicNote") {
            path(fill = ink) {
                moveTo(4.2f, 17.7f)
                arcToRelative(2.9f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 5.8f, 0f)
                arcToRelative(2.9f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, -5.8f, 0f)
                close()
                moveTo(14.2f, 15.9f)
                arcToRelative(2.9f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 5.8f, 0f)
                arcToRelative(2.9f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, -5.8f, 0f)
                close()
            }
            line {
                moveTo(10f, 17.7f)
                lineTo(10f, 6.7f)
                moveTo(20f, 15.9f)
                lineTo(20f, 4.9f)
                moveTo(10f, 6.7f)
                lineTo(20f, 4.9f)
            }
        }
    }

    // Four rounded squares.
    val GridView: ImageVector by lazy {
        icon("Bulat.GridView") {
            line {
                for ((x, y) in listOf(4.5f to 4.5f, 13.5f to 4.5f, 4.5f to 13.5f, 13.5f to 13.5f)) {
                    moveTo(x + 2f, y)
                    lineTo(x + 4f, y)
                    arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 2f)
                    lineTo(x + 6f, y + 4f)
                    arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 2f)
                    lineTo(x + 2f, y + 6f)
                    arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
                    lineTo(x, y + 2f)
                    arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
                    close()
                }
            }
        }
    }

    // Three rows with a bullet each.
    val ListView: ImageVector by lazy {
        icon("Bulat.ListView") {
            line {
                for (y in listOf(6.5f, 12f, 17.5f)) {
                    moveTo(4.5f, y)
                    lineTo(5.5f, y)
                    moveTo(9.5f, y)
                    lineTo(19.5f, y)
                }
            }
        }
    }

    // A page with a cut corner and an arrow going up: exporting a playlist (the share nodes are "Bagikan").
    val Export: ImageVector by lazy {
        icon("Bulat.Export") {
            line {
                moveTo(13f, 3f)
                lineTo(7f, 3f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, -2f, 2f)
                lineTo(5f, 19f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 2f, 2f)
                lineTo(17f, 21f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 2f, -2f)
                lineTo(19f, 9f)
                close()
                moveTo(13f, 3f)
                lineTo(13f, 7f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 2f, 2f)
                lineTo(19f, 9f)
                moveTo(12f, 17.4f)
                lineTo(12f, 12f)
                moveTo(9.6f, 14.2f)
                lineTo(12f, 11.8f)
                lineTo(14.4f, 14.2f)
            }
        }
    }

    // A phone with the island pill at the top.
    val DynamicIsland: ImageVector by lazy {
        icon("Bulat.DynamicIsland") {
            line {
                roundedRect(5f, 2.8f, 14f, 18.4f, 3f)
                moveTo(10.3f, 6.6f)
                lineTo(13.7f, 6.6f)
            }
        }
    }

    // A screen with its bottom bar.
    val NavBar: ImageVector by lazy {
        icon("Bulat.NavBar") {
            line {
                roundedRect(4f, 4f, 16f, 16f, 3f)
                moveTo(4f, 15f)
                lineTo(20f, 15f)
            }
        }
    }

    // A speaker and two pause bars: "pause when the sound is muted".
    val VolumeOffPause: ImageVector by lazy {
        icon("Bulat.VolumeOffPause") {
            line {
                moveTo(3.6f, 9.6f)
                lineTo(7f, 9.6f)
                lineTo(11.2f, 6f)
                lineTo(11.2f, 18f)
                lineTo(7f, 14.4f)
                lineTo(3.6f, 14.4f)
                close()
                moveTo(15.4f, 9f)
                lineTo(15.4f, 15f)
                moveTo(19.4f, 9f)
                lineTo(19.4f, 15f)
            }
        }
    }

    // The outline twins of Play / Pause / Skip: the Dynamic Island draws these when its "outline" switch is on.
    val PlayOutline: ImageVector by lazy {
        icon("Bulat.PlayOutline") {
            line {
                moveTo(6.8f, 4.8f)
                lineTo(19.2f, 12f)
                lineTo(6.8f, 19.2f)
                close()
            }
        }
    }

    val PauseOutline: ImageVector by lazy {
        icon("Bulat.PauseOutline") {
            line {
                roundedRect(5.8f, 4.6f, 4.6f, 14.8f, 1.4f)
                roundedRect(13.6f, 4.6f, 4.6f, 14.8f, 1.4f)
            }
        }
    }

    val SkipNextOutline: ImageVector by lazy {
        icon("Bulat.SkipNextOutline") {
            line {
                moveTo(6.4f, 6.6f)
                lineTo(14.6f, 12f)
                lineTo(6.4f, 17.4f)
                close()
                moveTo(18f, 6.6f)
                lineTo(18f, 17.4f)
            }
        }
    }

    val SkipPreviousOutline: ImageVector by lazy {
        icon("Bulat.SkipPreviousOutline") {
            line {
                moveTo(17.6f, 6.6f)
                lineTo(9.4f, 12f)
                lineTo(17.6f, 17.4f)
                close()
                moveTo(6f, 6.6f)
                lineTo(6f, 17.4f)
            }
        }
    }

    // Two people as an outline: the pair of the filled Material group (selected tab states, rows).
    val GroupOutline: ImageVector by lazy {
        icon("Bulat.GroupOutline") {
            line {
                circle(9.2f, 8.2f, 3.2f)
                moveTo(3.4f, 19.4f)
                curveTo(3.4f, 16.2f, 6f, 14.4f, 9.2f, 14.4f)
                curveTo(12.4f, 14.4f, 15f, 16.2f, 15f, 19.4f)
                moveTo(14.8f, 5.2f)
                arcToRelative(3.2f, 3.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, 6f)
                moveTo(17.4f, 14.8f)
                curveTo(19.5f, 15.3f, 20.8f, 17f, 20.8f, 19.4f)
            }
        }
    }
}
