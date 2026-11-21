/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The flame of the listening streak: ten looks from a spark to a rainbow blaze ([StreakTiers]) and the code that draws
 * them ([FlameArt]). One drawing routine serves the card, the Home banner, the gallery and the share poster, so the
 * flame looks the same everywhere. [FlameArt.draw] takes a loop position (0..1) for the flicker and the rising embers;
 * every motion repeats exactly once per loop, so a looping animation has no jump.
 */

package com.metrolist.music.utils

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import androidx.annotation.StringRes
import com.metrolist.music.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * One look of the flame. Each layer lists its colours from the tip down to the base. [accent] and [tip] are the colours
 * the rest of the UI (progress bars, text, poster glow) borrows.
 */
class StreakTier(
    val index: Int,
    val minDays: Int,
    @StringRes val nameRes: Int,
    val outer: IntArray,
    val mid: IntArray,
    val core: IntArray,
    val glow: Int,
    /** Extra small flames licking up beside the main one (0..4). */
    val tongues: Int,
    /** Sparks floating up from the flame. */
    val embers: Int,
    /** A ring with orbiting sparks around the flame, for the three hottest looks. */
    val halo: Boolean,
) {
    val accent: Int get() = mid[0]
    val tip: Int get() = core[0]
}

object StreakTiers {
    private fun c(argb: Long): Int = argb.toInt()

    val all: List<StreakTier> =
        listOf(
            // 0: nothing burning, shown grey
            StreakTier(0, 0, R.string.auralys_flame_0, intArrayOf(c(0xFFFF7A22), c(0xFFFF9A3D)), intArrayOf(c(0xFFFF9A3D), c(0xFFFFB347)), intArrayOf(c(0xFFFFC95E), c(0xFFFFF0B0)), c(0xFFFF9A3D), 0, 0, false),
            StreakTier(1, 1, R.string.auralys_flame_1, intArrayOf(c(0xFFFF7A22), c(0xFFFF9A3D)), intArrayOf(c(0xFFFF9A3D), c(0xFFFFB347)), intArrayOf(c(0xFFFFC95E), c(0xFFFFF0B0)), c(0xFFFF9A3D), 0, 2, false),
            StreakTier(2, 3, R.string.auralys_flame_2, intArrayOf(c(0xFFFF5A1F), c(0xFFFF8A2B)), intArrayOf(c(0xFFFF8A2B), c(0xFFFFB347)), intArrayOf(c(0xFFFFC95E), c(0xFFFFF0B0)), c(0xFFFF7A22), 1, 4, false),
            StreakTier(3, 7, R.string.auralys_flame_3, intArrayOf(c(0xFFF0361C), c(0xFFFF6A22)), intArrayOf(c(0xFFFF6A22), c(0xFFFFA030)), intArrayOf(c(0xFFFFC857), c(0xFFFFF0B0)), c(0xFFFF5A1F), 2, 6, false),
            StreakTier(4, 14, R.string.auralys_flame_4, intArrayOf(c(0xFFD81E3A), c(0xFFFF4D2C)), intArrayOf(c(0xFFFF4D2C), c(0xFFFF9A2E)), intArrayOf(c(0xFFFFC04D), c(0xFFFFEDB0)), c(0xFFFF3D3D), 3, 9, false),
            StreakTier(5, 30, R.string.auralys_flame_5, intArrayOf(c(0xFFB5156B), c(0xFFFF3D6E)), intArrayOf(c(0xFFFF3D6E), c(0xFFFF9A4D)), intArrayOf(c(0xFFFFB86B), c(0xFFFFEBC8)), c(0xFFFF2E7E), 4, 12, false),
            StreakTier(6, 50, R.string.auralys_flame_6, intArrayOf(c(0xFF1747E6), c(0xFF2F8CFF)), intArrayOf(c(0xFF2F8CFF), c(0xFF5CD6FF)), intArrayOf(c(0xFFB8F1FF), c(0xFFFFFFFF)), c(0xFF2F8CFF), 4, 15, false),
            StreakTier(7, 100, R.string.auralys_flame_7, intArrayOf(c(0xFF7B36FF), c(0xFFA66BFF)), intArrayOf(c(0xFFA66BFF), c(0xFFD9AEFF)), intArrayOf(c(0xFFF0DCFF), c(0xFFFFFFFF)), c(0xFF8A3DFF), 4, 20, true),
            StreakTier(8, 365, R.string.auralys_flame_8, intArrayOf(c(0xFFEE9A08), c(0xFFFFC21A)), intArrayOf(c(0xFFFFC21A), c(0xFFFFE680)), intArrayOf(c(0xFFFFF1B0), c(0xFFFFFFFF)), c(0xFFFFB300), 4, 24, true),
            StreakTier(9, 1000, R.string.auralys_flame_9, intArrayOf(c(0xFFFF3DAE), c(0xFF7C4DFF), c(0xFF00B8FF)), intArrayOf(c(0xFF7C4DFF), c(0xFF00D4FF), c(0xFF5CFFB0)), intArrayOf(c(0xFFFFFFFF), c(0xFFE8FFF6)), c(0xFF4DA6FF), 4, 28, true),
        )

    fun forDays(days: Int): StreakTier = all.lastOrNull { it.minDays <= days } ?: all.first()
}

object FlameArt {
    private val GREY_OUTER = intArrayOf(0xFF4E4E55.toInt(), 0xFF666670.toInt())
    private val GREY_MID = intArrayOf(0xFF7C7C86.toInt(), 0xFF92929C.toInt())
    private val GREY_CORE = intArrayOf(0xFFB6B6BF.toInt(), 0xFFD6D6DD.toInt())

    /** The small flames beside the main one: horizontal offset, size, lean of the tip, narrowness. */
    private val TONGUES =
        arrayOf(
            floatArrayOf(-0.50f, 0.80f, -0.70f, 0.62f),
            floatArrayOf(0.52f, 0.72f, 0.70f, 0.62f),
            floatArrayOf(-0.24f, 0.95f, -0.30f, 0.55f),
            floatArrayOf(0.26f, 0.89f, 0.30f, 0.55f),
        )

    private const val TWO_PI = (2 * PI).toFloat()

    /** Width of the flame relative to its height. */
    private const val ASPECT = 0.86f

    /**
     * Draws the flame of [tier] into the box (0,0)..([width],[height]) of [canvas]; move the canvas to place it. When
     * [lit] is false the flame is grey, still and without glow. [phase] is the position in the animation loop, 0..1.
     */
    fun draw(canvas: Canvas, width: Float, height: Float, tier: StreakTier, lit: Boolean, phase: Float = 0.25f) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        val outer = if (lit) tier.outer else GREY_OUTER
        val mid = if (lit) tier.mid else GREY_MID
        val core = if (lit) tier.core else GREY_CORE

        val flameH = min(height * 0.78f, width * 1.1f)
        val flameW = flameH * ASPECT
        val cx = width / 2f
        val bottom = height * 0.94f
        val top = bottom - flameH

        val sway = if (lit) sin(phase * TWO_PI) * 0.6f + sin(phase * 2 * TWO_PI + 1.3f) * 0.4f else sin(phase * TWO_PI) * 0.12f
        val flicker = if (lit) sin(phase * 3 * TWO_PI) else 0f

        if (lit) {
            val pulse = 0.8f + 0.2f * sin(phase * TWO_PI)
            val glowRadius = flameH * 1.05f
            paint.shader =
                RadialGradient(
                    cx,
                    bottom - flameH * 0.45f,
                    glowRadius,
                    withAlpha(tier.glow, (150 * pulse).toInt()),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
            canvas.drawCircle(cx, bottom - flameH * 0.45f, glowRadius, paint)
            // a pool of light under the flame, so it stands on something
            paint.shader =
                RadialGradient(
                    cx,
                    bottom,
                    flameW * 0.75f,
                    withAlpha(tier.mid[1], (120 * pulse).toInt()),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
            canvas.save()
            canvas.scale(1f, 0.22f, cx, bottom)
            canvas.drawCircle(cx, bottom, flameW * 0.75f, paint)
            canvas.restore()
            paint.shader = null
        }

        if (lit && tier.halo) {
            val radius = min(flameH * 0.62f, width * 0.47f)
            drawHalo(canvas, paint, tier, cx, bottom - flameH * 0.5f, radius, phase)
        }

        // small flames behind the main one, each swaying a little differently
        for (i in 0 until if (lit) tier.tongues else 0) {
            val spec = TONGUES[i]
            val tongueSway = sin((phase + i * 0.23f) * TWO_PI) * 0.8f
            val path = flamePath(cx, bottom, flameW, flameH, spec[1], spec[0], spec[2], tongueSway, sin((phase + i * 0.31f) * 3 * TWO_PI), spec[3])
            fill(canvas, paint, path, top + flameH * (1f - spec[1]), bottom, outer, alpha = 215)
        }

        // main flame: outer body, warm middle, bright core
        fill(canvas, paint, flamePath(cx, bottom, flameW, flameH, 1f, 0f, 0f, sway, flicker), top, bottom, outer)
        fill(canvas, paint, flamePath(cx, bottom, flameW, flameH, 0.68f, 0f, 0f, sway * 0.8f, flicker), bottom - flameH * 0.68f, bottom, mid)
        fill(canvas, paint, flamePath(cx, bottom, flameW, flameH, 0.38f, 0f, 0f, sway * 0.6f, flicker), bottom - flameH * 0.38f, bottom, core)

        if (lit) drawEmbers(canvas, paint, tier, cx, bottom, flameW, flameH, phase)
    }

    private fun drawHalo(canvas: Canvas, paint: Paint, tier: StreakTier, cx: Float, cy: Float, radius: Float, phase: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.035f
        paint.color = Color.WHITE
        paint.shader =
            SweepGradient(
                cx,
                cy,
                intArrayOf(Color.TRANSPARENT, withAlpha(tier.mid[0], 200), withAlpha(tier.tip, 235), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 0.75f, 1f),
            )
        canvas.save()
        canvas.rotate(phase * 360f, cx, cy)
        canvas.drawCircle(cx, cy, radius, paint)
        canvas.restore()
        paint.shader = null
        paint.style = Paint.Style.FILL
        for (k in 0 until 3) {
            val angle = phase * TWO_PI + k * TWO_PI / 3f
            paint.color = withAlpha(tier.tip, 235)
            canvas.drawCircle(cx + cos(angle) * radius, cy + sin(angle) * radius, radius * 0.05f, paint)
        }
    }

    private fun drawEmbers(canvas: Canvas, paint: Paint, tier: StreakTier, cx: Float, bottom: Float, flameW: Float, flameH: Float, phase: Float) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        for (k in 0 until tier.embers) {
            val speed = 1 + k % 2
            val life = (phase * speed + frac(k * 0.37f)) % 1f
            val spread = (frac(k * 0.618034f) - 0.5f) * 1.5f
            val x = cx + (spread + sin((life + k) * TWO_PI) * 0.08f) * flameW / 2f
            val y = bottom - (0.18f + life * 1.15f) * flameH
            val alpha = (sin(life * PI.toFloat()) * 230f).toInt().coerceIn(0, 255)
            val radius = flameH * (0.014f + 0.016f * ((k * 7) % 3) / 2f) * (1f - life * 0.55f)
            paint.color = withAlpha(if (k % 3 == 0) tier.core[0] else tier.mid[1], alpha)
            if (y > 0f) canvas.drawCircle(x, y, radius, paint)
        }
    }

    private fun fill(canvas: Canvas, paint: Paint, path: Path, topY: Float, bottomY: Float, colors: IntArray, alpha: Int = 255) {
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.alpha = alpha
        paint.shader = LinearGradient(0f, topY, 0f, bottomY, colors, null, Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.alpha = 255
    }

    /**
     * The flame outline in a box of ([flameW] x [flameH]) standing on ([cx], [bottom]). [scale] shrinks it towards its
     * base ([narrow] only its width), [dx] moves it sideways (in half widths), [lean] bends the tip, [sway] and [flicker] are the animation.
     */
    private fun flamePath(cx: Float, bottom: Float, flameW: Float, flameH: Float, scale: Float, dx: Float, lean: Float, sway: Float, flicker: Float, narrow: Float = 1f): Path {
        val tipX = sway * 0.22f + lean
        val tipV = 1f + flicker * 0.04f
        // unit points: x in -1..1 across the width, v 0 (base) .. 1 (tip)
        val u =
            floatArrayOf(
                0f, 0f,
                -0.62f, 0f, -0.98f, 0.22f, -0.92f, 0.50f,
                -0.88f, 0.70f, -0.50f + sway * 0.1f, 0.74f, tipX - 0.10f, tipV,
                tipX + 0.02f, 0.74f, 0.06f + sway * 0.2f, 0.62f, 0.38f, 0.58f + flicker * 0.02f,
                0.66f, 0.60f, 0.96f, 0.40f, 0.82f, 0.20f,
                0.70f, 0.04f, 0.40f, 0f, 0f, 0f,
            )
        fun px(i: Int) = cx + (u[i] * scale * narrow + dx) * flameW / 2f
        fun py(i: Int) = bottom - u[i] * scale * flameH
        val path = Path()
        path.moveTo(px(0), py(1))
        var i = 2
        while (i < u.size) {
            path.cubicTo(px(i), py(i + 1), px(i + 2), py(i + 3), px(i + 4), py(i + 5))
            i += 6
        }
        path.close()
        return path
    }

    private fun frac(value: Float): Float = value - kotlin.math.floor(value)

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
