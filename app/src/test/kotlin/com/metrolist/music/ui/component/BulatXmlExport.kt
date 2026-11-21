/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import java.util.Locale

/**
 * ImageVector -> Android VectorDrawable XML (white, tinted by the system). Only what the Bulat family uses: flat
 * lists of paths with solid fill and / or stroke. Used by [BulatXmlTwinsTest] to write the `bu_*.xml` twins and to
 * prove that the checked-in files still equal the vectors.
 */
object BulatXmlExport {
    private fun f(v: Float): String {
        val s = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
        return if (s.isEmpty() || s == "-0") "0" else s
    }

    private fun d(n: PathNode): String =
        when (n) {
            is PathNode.Close -> "Z"
            is PathNode.RelativeMoveTo -> "m ${f(n.dx)} ${f(n.dy)}"
            is PathNode.MoveTo -> "M ${f(n.x)} ${f(n.y)}"
            is PathNode.RelativeLineTo -> "l ${f(n.dx)} ${f(n.dy)}"
            is PathNode.LineTo -> "L ${f(n.x)} ${f(n.y)}"
            is PathNode.RelativeHorizontalTo -> "h ${f(n.dx)}"
            is PathNode.HorizontalTo -> "H ${f(n.x)}"
            is PathNode.RelativeVerticalTo -> "v ${f(n.dy)}"
            is PathNode.VerticalTo -> "V ${f(n.y)}"
            is PathNode.RelativeCurveTo ->
                "c ${f(n.dx1)} ${f(n.dy1)} ${f(n.dx2)} ${f(n.dy2)} ${f(n.dx3)} ${f(n.dy3)}"
            is PathNode.CurveTo ->
                "C ${f(n.x1)} ${f(n.y1)} ${f(n.x2)} ${f(n.y2)} ${f(n.x3)} ${f(n.y3)}"
            is PathNode.RelativeReflectiveCurveTo -> "s ${f(n.dx1)} ${f(n.dy1)} ${f(n.dx2)} ${f(n.dy2)}"
            is PathNode.ReflectiveCurveTo -> "S ${f(n.x1)} ${f(n.y1)} ${f(n.x2)} ${f(n.y2)}"
            is PathNode.RelativeQuadTo -> "q ${f(n.dx1)} ${f(n.dy1)} ${f(n.dx2)} ${f(n.dy2)}"
            is PathNode.QuadTo -> "Q ${f(n.x1)} ${f(n.y1)} ${f(n.x2)} ${f(n.y2)}"
            is PathNode.RelativeReflectiveQuadTo -> "t ${f(n.dx)} ${f(n.dy)}"
            is PathNode.ReflectiveQuadTo -> "T ${f(n.x)} ${f(n.y)}"
            is PathNode.RelativeArcTo ->
                "a ${f(n.horizontalEllipseRadius)} ${f(n.verticalEllipseRadius)} ${f(n.theta)} " +
                    "${if (n.isMoreThanHalf) 1 else 0} ${if (n.isPositiveArc) 1 else 0} ${f(n.arcStartDx)} ${f(n.arcStartDy)}"
            is PathNode.ArcTo ->
                "A ${f(n.horizontalEllipseRadius)} ${f(n.verticalEllipseRadius)} ${f(n.theta)} " +
                    "${if (n.isMoreThanHalf) 1 else 0} ${if (n.isPositiveArc) 1 else 0} ${f(n.arcStartX)} ${f(n.arcStartY)}"
        }

    private fun paths(node: VectorNode, out: MutableList<VectorPath>) {
        when (node) {
            is VectorPath -> out += node
            is VectorGroup -> node.forEach { paths(it, out) }
        }
    }

    fun toXml(v: ImageVector): String {
        val list = mutableListOf<VectorPath>()
        paths(v.root, list)
        require(list.isNotEmpty()) { "${v.name} has no path" }
        val sb = StringBuilder()
        sb.append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
        sb.append("    android:width=\"${f(v.defaultWidth.value)}dp\"\n")
        sb.append("    android:height=\"${f(v.defaultHeight.value)}dp\"\n")
        sb.append("    android:viewportWidth=\"${f(v.viewportWidth)}\"\n")
        if (v.autoMirror) sb.append("    android:autoMirrored=\"true\"\n")
        sb.append("    android:viewportHeight=\"${f(v.viewportHeight)}\">\n")
        for (p in list) {
            sb.append("    <path\n")
            sb.append("        android:pathData=\"${p.pathData.joinToString(" ") { d(it) }}\"\n")
            sb.append("        android:fillColor=\"${if (p.fill != null) "#FFFFFFFF" else "#00000000"}\"")
            if (p.pathFillType == PathFillType.EvenOdd) sb.append("\n        android:fillType=\"evenOdd\"")
            if (p.stroke != null) {
                sb.append("\n        android:strokeColor=\"#FFFFFFFF\"")
                sb.append("\n        android:strokeWidth=\"${f(p.strokeLineWidth)}\"")
                sb.append("\n        android:strokeLineCap=\"${if (p.strokeLineCap == StrokeCap.Round) "round" else if (p.strokeLineCap == StrokeCap.Square) "square" else "butt"}\"")
                sb.append("\n        android:strokeLineJoin=\"${if (p.strokeLineJoin == StrokeJoin.Round) "round" else if (p.strokeLineJoin == StrokeJoin.Bevel) "bevel" else "miter"}\"")
            }
            sb.append(" />\n")
        }
        sb.append("</vector>\n")
        return sb.toString()
    }
}
