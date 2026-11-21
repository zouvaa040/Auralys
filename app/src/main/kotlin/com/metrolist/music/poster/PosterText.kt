/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** How a text on the poster looks (round 4.3.6). */
enum class PosterTextStyle {
    /** Plain, clean, a soft shadow. */
    BERSIH,

    /** Heavy capitals with wide spacing: a headline. */
    TEBAL,

    /** An elegant serif italic. */
    SERIF,

    /** Capitals on a rounded plate of the chosen colour (a marker). */
    SOROT,

    /** Only the outline of the letters. */
    GARIS,
}

/**
 * One text of the poster: [x], [y] = its centre in poster pixels, [scale] 1 = [PosterTexts.BASE_SIZE] pixels of letter height, [rotation] in degrees
 * clockwise, [color] as ARGB (the letters; for SOROT the plate). [id] is unique within a poster. A line break in [text] starts a new line (three at most).
 */
data class PosterText(
    val id: Int,
    val text: String,
    val style: PosterTextStyle = PosterTextStyle.BERSIH,
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val color: Int = PosterTexts.WHITE,
)

/** The rules and the picture of the texts. Pure Android graphics (no Compose), so the editor's touch maths and the renderer share one measure. */
object PosterTexts {
    const val MAX_COUNT = 6
    const val MAX_LINES = 3
    const val MAX_CHARS = 80
    const val BASE_SIZE = 104f
    const val MIN_SCALE = 0.3f
    const val MAX_SCALE = 4f
    const val WHITE: Int = -1
    const val INK: Int = 0xFF101216.toInt()

    /** The lines of a text as they are shown (trimmed, empty lines dropped, three at most). */
    fun lines(text: String): List<String> = text.replace("\r", "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_LINES)

    /** What the editor accepts for a text: at most [MAX_CHARS] letters and [MAX_LINES] lines; null when nothing is left. */
    fun clean(text: String): String? = lines(text).joinToString("\n").take(MAX_CHARS).takeIf { it.isNotBlank() }

    private fun caps(style: PosterTextStyle) = style == PosterTextStyle.TEBAL || style == PosterTextStyle.SOROT

    fun shown(t: PosterText): List<String> = lines(t.text).map { if (caps(t.style)) it.uppercase(Locale.getDefault()) else it }

    private fun faceOf(style: PosterTextStyle): Typeface =
        when (style) {
            PosterTextStyle.BERSIH -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            PosterTextStyle.TEBAL -> Typeface.create("sans-serif-black", Typeface.NORMAL)
            PosterTextStyle.SERIF -> Typeface.create("serif", Typeface.BOLD_ITALIC)
            PosterTextStyle.SOROT -> Typeface.create("sans-serif", Typeface.BOLD)
            PosterTextStyle.GARIS -> Typeface.create("sans-serif-black", Typeface.NORMAL)
        }

    /** The paint of a style at scale 1 (a new one on every call: paints are not thread-safe and the editor and the renderer run on different threads). */
    private fun paint(style: PosterTextStyle): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = faceOf(style)
            textSize = BASE_SIZE * if (style == PosterTextStyle.SERIF) 1.08f else 1f
            textAlign = Paint.Align.LEFT
            letterSpacing = if (style == PosterTextStyle.TEBAL) 0.07f else if (style == PosterTextStyle.SOROT) 0.04f else 0f
        }

    /** Padding around the letters (poster pixels at scale 1): the plate of SOROT is wider. */
    private fun padX(style: PosterTextStyle) = if (style == PosterTextStyle.SOROT) 34f else 16f

    private fun padY(style: PosterTextStyle) = if (style == PosterTextStyle.SOROT) 18f else 10f

    private data class Key(val lines: List<String>, val style: PosterTextStyle)

    private val sizes = object : LinkedHashMap<Key, FloatArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, FloatArray>?) = size > 48
    }

    /** Half the width and half the height of the text's box at scale 1 (padding included). */
    fun half1(t: PosterText): Pair<Float, Float> {
        val lines = shown(t)
        val key = Key(lines, t.style)
        synchronized(sizes) { sizes[key]?.let { return it[0] to it[1] } }
        val p = paint(t.style)
        var w = 0f
        for (l in lines) w = max(w, p.measureText(l))
        val m = p.fontMetrics
        val lh = (m.descent - m.ascent) * 1.04f
        val h = lh * max(1, lines.size)
        val out = floatArrayOf((w / 2f + padX(t.style)).coerceAtLeast(24f), (h / 2f + padY(t.style)).coerceAtLeast(24f))
        synchronized(sizes) { sizes[key] = out }
        return out[0] to out[1]
    }

    fun half(t: PosterText): Pair<Float, Float> {
        val (a, b) = half1(t)
        return a * t.scale to b * t.scale
    }

    /** The ink to lay on a colour: dark on a light colour, white on a dark one. */
    fun readableOn(color: Int): Int {
        val r = (color shr 16) and 255
        val g = (color shr 8) and 255
        val b = color and 255
        return if ((0.299f * r + 0.587f * g + 0.114f * b) / 255f > 0.62f) INK else WHITE
    }

    // ------------------------------------------------------------------------------------------------ geometry (the same shape as a sticker's)

    /** The four corners of the text's box on the poster (clockwise from its top left), turned as it is. */
    fun bounds(t: PosterText): FloatArray {
        val (hw, hh) = half(t)
        val rad = Math.toRadians(t.rotation.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val out = FloatArray(8)
        val corners = arrayOf(-hw to -hh, hw to -hh, hw to hh, -hw to hh)
        for ((i, p) in corners.withIndex()) {
            out[i * 2] = t.x + p.first * c - p.second * s
            out[i * 2 + 1] = t.y + p.first * s + p.second * c
        }
        return out
    }

    /** The corner handle (size and turn): the bottom right corner of the box. */
    fun handle(t: PosterText): Pair<Float, Float> {
        val k = bounds(t)
        return k[4] to k[5]
    }

    fun hit(t: PosterText, x: Float, y: Float, slop: Float = 0f): Boolean {
        val (hw, hh) = half(t)
        val rad = Math.toRadians(t.rotation.toDouble())
        val dx = x - t.x
        val dy = y - t.y
        val u = dx * cos(rad).toFloat() + dy * sin(rad).toFloat()
        val v = -dx * sin(rad).toFloat() + dy * cos(rad).toFloat()
        return kotlin.math.abs(u) <= hw + slop && kotlin.math.abs(v) <= hh + slop
    }

    /** The front-most text under the point, or -1. */
    fun hit(list: List<PosterText>, x: Float, y: Float, slop: Float = 0f): Int {
        for (i in list.indices.reversed()) if (hit(list[i], x, y, slop)) return i
        return -1
    }

    fun onHandle(t: PosterText, x: Float, y: Float): Boolean {
        val (hx, hy) = handle(t)
        return hypot(x - hx, y - hy) <= PosterEdit.HANDLE_RADIUS
    }

    /** The text with its corner handle dragged to ([x], [y]): the size follows the distance from the centre and the turn the direction. */
    fun draggedHandle(t: PosterText, x: Float, y: Float): PosterText {
        val (hw1, hh1) = half1(t)
        val d = hypot(x - t.x, y - t.y)
        val scale = (d / hypot(hw1, hh1)).coerceIn(MIN_SCALE, MAX_SCALE)
        val corner = Math.toDegrees(atan2(hh1.toDouble(), hw1.toDouble())).toFloat()
        val angle = Math.toDegrees(atan2((y - t.y).toDouble(), (x - t.x).toDouble())).toFloat() - corner
        return t.copy(scale = scale, rotation = PosterEdit.normalizeDegrees(angle))
    }

    fun moved(t: PosterText, dx: Float, dy: Float, ratio: PosterRatio = PosterRatio.DEFAULT): PosterText =
        t.copy(x = (t.x + dx).coerceIn(0f, ratio.w.toFloat()), y = (t.y + dy).coerceIn(0f, ratio.h.toFloat()))

    fun pinched(t: PosterText, dx: Float, dy: Float, zoom: Float, turnDeg: Float, ratio: PosterRatio = PosterRatio.DEFAULT): PosterText {
        val m = moved(t, dx, dy, ratio)
        return m.copy(scale = (m.scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE), rotation = PosterEdit.normalizeDegrees(m.rotation + turnDeg))
    }

    // ------------------------------------------------------------------------------------------------ the picture

    /** Draws [t] on [canvas] in poster pixels (the caller scales the canvas to the device). */
    fun draw(canvas: Canvas, t: PosterText) {
        val lines = shown(t)
        if (lines.isEmpty()) return
        val p = paint(t.style)
        val m = p.fontMetrics
        val lh = (m.descent - m.ascent) * 1.04f
        val n = lines.size
        var w = 0f
        for (l in lines) w = max(w, p.measureText(l))
        val (hw1, hh1) = half1(t)
        canvas.save()
        canvas.translate(t.x, t.y)
        canvas.rotate(t.rotation)
        canvas.scale(t.scale, t.scale)
        if (t.style == PosterTextStyle.SOROT) {
            val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.color or (0xFF shl 24) }
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
            val r = hh1.coerceAtMost(56f)
            canvas.drawRoundRect(RectF(-hw1, -hh1 + 6f, hw1, hh1 + 6f), r, r, shadow)
            canvas.drawRoundRect(RectF(-hw1, -hh1, hw1, hh1), r, r, plate)
        }
        for ((i, l) in lines.withIndex()) {
            val lw = p.measureText(l)
            val base = (i - (n - 1) / 2f) * lh - (m.ascent + m.descent) / 2f
            val x = -lw / 2f
            when (t.style) {
                PosterTextStyle.GARIS -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 5f
                    p.strokeJoin = Paint.Join.ROUND
                    p.color = t.color or (0xFF shl 24)
                    p.setShadowLayer(10f, 0f, 3f, 0x66000000)
                    canvas.drawText(l, x, base, p)
                }
                PosterTextStyle.SOROT -> {
                    p.style = Paint.Style.FILL
                    p.color = readableOn(t.color)
                    canvas.drawText(l, x, base, p)
                }
                else -> {
                    p.style = Paint.Style.FILL
                    p.setShadowLayer(14f, 0f, 4f, 0x73000000)
                    p.color = t.color or (0xFF shl 24)
                    canvas.drawText(l, x, base, p)
                }
            }
        }
        canvas.restore()
    }
}
