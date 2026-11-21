/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The ready-made arrangements for every poster shape (round 4.3.6). The 9:16 poster keeps its four hand-tuned arrangements ([PosterLayoutMath]);
 * every other shape, and the two new arrangements (KOLASE, BESAR) on every shape, come from here: the same ideas, worked out from the room the
 * cards may take on THAT shape (never stretched), with a card size that fits it. Pure maths, no Android.
 */
internal object PosterLayoutGen {
    private const val BW = PosterCards.BASE_W
    private const val BH = PosterCards.BASE_H

    /** The room the cards may take: left, top, right, bottom in poster pixels (the Auralys mark's band is left free). */
    class Room(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
        val w: Float get() = x1 - x0
        val h: Float get() = y1 - y0
        val cx: Float get() = (x0 + x1) / 2f
        val cy: Float get() = (y0 + y1) / 2f
    }

    fun room(r: PosterRatio): Room {
        val m = 56f
        return Room(m, m, r.w - m, r.markBand - 14f)
    }

    /** A shape that is clearly wider than tall (16:9, 4:3, 3:2): the cards stand beside the subject, not above and below it. */
    private val PosterRatio.wide: Boolean get() = aspect > 1.2f

    fun poses(layout: PosterLayout, count: Int, r: PosterRatio): List<CardPose> {
        val n = count.coerceIn(1, POSTER_MAX_SONGS)
        val raw =
            when (layout) {
                PosterLayout.ORBIT -> orbit(n, r)
                PosterLayout.TUMPUK -> tumpuk(n, r)
                PosterLayout.DERET -> deret(n, r)
                PosterLayout.KOLOM -> kolom(n, r)
                PosterLayout.KOLASE -> kolase(n, r)
                PosterLayout.BESAR -> besar(n, r)
            }
        return keepInside(raw, r)
    }

    // ------------------------------------------------------------------------------------------------ the arrangements

    /** The cards at the two sides, the middle free for the subject (the old ORBIT: two on top, two below, a fifth in the middle). */
    private fun orbit(n: Int, r: PosterRatio): List<CardPose> {
        val room = room(r)
        val wide = r.wide
        val rows2 = n >= 3
        val sCap = floatArrayOf(0.98f, 0.88f, 0.86f, 0.86f, 0.76f)[n - 1]
        val hFit = (if (rows2) (if (wide) 0.53f else 0.46f) else 0.9f) * room.h / BH
        val wFit = (if (wide) 0.30f else 0.47f) * room.w / BW
        val s = min(sCap, min(hFit, wFit))
        val xl = if (wide) 0.21f else 0.255f
        val xr = if (wide) 0.79f else 0.75f
        val ch = BH * s
        fun y(frac: Float): Float = (room.y0 + frac * room.h).coerceIn(room.y0 + ch / 2f, room.y1 - ch / 2f)
        fun at(fx: Float, fy: Float, rot: Float, tiltY: Float) = CardPose(r.w * fx, y(fy), s, rot, 0f, tiltY)
        return when (n) {
            1 -> listOf(at(if (wide) 0.27f else 0.305f, 0.42f, -5f, 24f))
            2 -> listOf(at(xl, 0.32f, -5f, 26f), at(xr, 0.27f, 5f, -26f))
            3 -> listOf(at(xl, 0.30f, -5f, 26f), at(xr, 0.25f, 5f, -26f), at(xl - 0.02f, 0.81f, 4f, 28f))
            4 -> listOf(at(xl, 0.30f, -5f, 26f), at(xr, 0.25f, 5f, -26f), at(xl - 0.02f, 0.81f, 4f, 28f), at(xr + 0.04f, 0.77f, -4f, -28f))
            else -> listOf(at(xl - 0.02f, 0.26f, -6f, 28f), at(xr + 0.02f, 0.21f, 6f, -28f), at(xl - 0.03f, 0.845f, 4f, 30f), at(xr + 0.05f, 0.80f, -4f, -30f), at(0.5f, 0.47f, 0f, 0f))
        }
    }

    /** A fan of cards, each one turned a little more than the one before. The fan is as large as the shape lets it be. */
    private fun tumpuk(n: Int, r: PosterRatio): List<CardPose> {
        val room = room(r)
        val wide = r.wide
        val step = if (wide) 18f else 15f
        val radius = (if (wide) 0.62f else 0.302f) * r.h
        val sTable = floatArrayOf(0.9f, 0.85f, 0.8f, 0.74f, 0.68f)[n - 1] * (if (wide) 1.25f else 1f)
        val maxH = (if (wide) 0.92f else 0.5f) * room.h
        fun gen(s: Float): List<CardPose> =
            List(n) { i ->
                val a = (i - (n - 1) / 2f) * step
                val rad = Math.toRadians(a.toDouble())
                CardPose((radius * sin(rad)).toFloat(), -(radius * cos(rad)).toFloat(), s, a, 0f, -a / 3f)
            }
        var lo = 0.3f
        var hi = sTable
        if (!fits(gen(hi), room.w, maxH)) {
            repeat(14) {
                val mid = (lo + hi) / 2f
                if (fits(gen(mid), room.w, maxH)) lo = mid else hi = mid
            }
        } else {
            lo = hi
        }
        val poses = gen(lo)
        val b = bounds(poses)
        val dx = room.cx - (b[0] + b[2]) / 2f
        val dy = if (wide) room.cy - (b[1] + b[3]) / 2f else room.y0 + 0.07f * room.h - b[1]
        return poses.map { it.copy(x = it.x + dx, y = it.y + dy) }
    }

    /** One row of cards, a gentle arc: the middle one faces front, the outer ones turn in. */
    private fun deret(n: Int, r: PosterRatio): List<CardPose> {
        val room = room(r)
        val wide = r.wide
        val table = floatArrayOf(0.95f, 0.9f, 0.78f, 0.6f, 0.5f)[n - 1]
        val avail = if (wide) r.w - 160f else r.w - 80f
        val pitch0 = if (n == 1) 0f else avail / n
        val s =
            if (wide) {
                min(1.0f, min(0.9f * room.h / BH, if (n == 1) 1f else 1.2f * pitch0 / BW))
            } else {
                min(table, 0.62f * room.h / BH)
            }
        val pitch = if (n == 1) 0f else if (wide) min(pitch0, 1.12f * BW * s + 40f) else pitch0
        val ch = BH * s
        val yBase = if (wide) room.cy else room.y0 + 0.06f * room.h + ch / 2f + 22f
        return List(n) { i ->
            val d = if (n == 1) 0f else (i - (n - 1) / 2f) / ((n - 1) / 2f)
            CardPose(r.w / 2f + (i - (n - 1) / 2f) * pitch, yBase - 22f + 22f * d * d, s, 3f * d, 0f, -15f * d)
        }
    }

    /**
     * A column at the right. When the shape is too short for all the cards one under the other (without covering each other more than a
     * third), they stand in two columns side by side, the second one half a card lower, like bricks.
     */
    private fun kolom(n: Int, r: PosterRatio): List<CardPose> {
        val room = room(r)
        val wide = r.wide
        val table = if (wide) floatArrayOf(0.95f, 0.85f, 0.75f, 0.7f, 0.65f) else floatArrayOf(0.8f, 0.66f, 0.58f, 0.5f, 0.47f)
        var s = table[n - 1]
        val yMid = if (wide) room.cy else room.cy + 0.06f * room.h
        // one column: the step between two cards as long as the shape allows, at least 0.78 of a card's height
        fun fitOne(): Pair<Float, Float>? {
            var ov = 1.12f
            var sz = s
            while (ov >= 0.78f) {
                if (BH * sz * (1f + (n - 1) * ov) <= room.h) return sz to ov
                ov -= 0.04f
            }
            return null
        }
        if (n == 1) {
            s = min(s, 0.95f * room.h / BH)
            return listOf(CardPose(r.w * (if (wide) 0.78f else 0.72f), yMid, s, 3f, 0f, -18f))
        }
        val one = fitOne()
        if (one != null) {
            val (sz, ov) = one
            val step = ov * BH * sz
            val x = r.w * (if (wide) 0.78f else if (n <= 2) 0.72f else 0.77f)
            return List(n) { i -> CardPose(x, yMid + (i - (n - 1) / 2f) * step, sz, 3f, 0f, -18f) }
        }
        // two columns: the first takes the larger half; each column's cascade fits the room's height
        val a = (n + 1) / 2
        val b = n - a
        var sz = minOf(s, 0.7f)
        var ov = 0.8f
        while (BH * sz * (1f + (a - 1) * ov + 0.5f) > room.h && ov > 0.5f) ov -= 0.04f
        while (BH * sz * (1f + (a - 1) * ov + 0.5f) > room.h && sz > 0.3f) sz -= 0.01f
        val step = ov * BH * sz
        val gapX = BW * sz + 36f
        val xB = r.w * (if (wide) 0.84f else 0.80f)
        val xA = xB - gapX
        val topA = yMid - (BH * sz * (1f + (a - 1) * ov + 0.5f)) / 2f + BH * sz / 2f
        val out = ArrayList<CardPose>(n)
        for (i in 0 until a) out.add(CardPose(xA, topA + i * step, sz, 3f, 0f, -18f))
        for (i in 0 until b) out.add(CardPose(xB, topA + (0.5f + i) * step, sz, 3f, 0f, -18f))
        return out
    }

    /** A flat grid of the cards, every row centred, the outer cards turned in a little (cover-flow). */
    private fun kolase(n: Int, r: PosterRatio): List<CardPose> {
        val room = room(r)
        val g = grid(n, room, if (r.wide) 1.0f else 0.95f, 30f, room.cx, room.cy)
        return g.centers.mapIndexed { i, c ->
            val d = ((c.first - room.cx) / (room.w / 2f)).coerceIn(-1f, 1f)
            CardPose(c.first, c.second, g.s, if (i % 2 == 0) -1.5f else 1.5f, 0f, -14f * d)
        }
    }

    /** The first card large, the others small beside or below it. */
    private fun besar(n: Int, r: PosterRatio): List<CardPose> {
        val room = room(r)
        if (n == 1) {
            val s = min(1.2f, min(0.92f * room.h / BH, 0.84f * room.w / BW))
            return listOf(CardPose(room.cx, room.cy, s, -3f, 0f, 8f))
        }
        val rest = n - 1
        if (r.wide) {
            val heroS = min(1.2f, 0.92f * room.h / BH)
            val heroW = BW * heroS
            val heroX = room.x0 + 24f + heroW / 2f + 0.04f * room.w
            val sub = Room(heroX + heroW / 2f + 36f, room.y0, room.x1, room.y1)
            val g = grid(rest, sub, 0.7f * heroS, 26f, sub.cx, sub.cy)
            val hero = CardPose(heroX, room.cy, heroS, -3f, 0f, 14f)
            return listOf(hero) + g.centers.map { CardPose(it.first, it.second, g.s, 2f, 0f, -12f) }
        }
        val heroS = min(1.2f, min(0.56f * room.h / BH, 0.82f * room.w / BW))
        val heroH = BH * heroS
        val hero = CardPose(room.cx, room.y0 + heroH / 2f + 6f, heroS, -2f, 0f, 8f)
        val subTop = room.y0 + heroH + 46f
        val sub = Room(room.x0, subTop, room.x1, room.y1)
        val g = grid(rest, sub, 0.62f * heroS, 24f, sub.cx, sub.cy)
        return listOf(hero) + g.centers.mapIndexed { i, c ->
            val d = ((c.first - room.cx) / (room.w / 2f)).coerceIn(-1f, 1f)
            CardPose(c.first, c.second, g.s, if (i % 2 == 0) 2f else -2f, 0f, -12f * d)
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    class Grid(val cols: Int, val rows: Int, val s: Float, val centers: List<Pair<Float, Float>>)

    /**
     * [n] cards in the grid (columns x rows) that lets them be the largest inside [room] (at most [sMax]), the last row centred, the whole block centred
     * on ([cx], [cy]) and [gap] poster pixels between two cards.
     */
    fun grid(n: Int, room: Room, sMax: Float, gap: Float, cx: Float, cy: Float): Grid {
        var bestCols = 1
        var bestScore = -1f
        var bestS = 0.3f
        for (cols in 1..n) {
            val rows = (n + cols - 1) / cols
            val cw = (room.w - gap * (cols - 1)) / cols
            val chh = (room.h - gap * (rows - 1)) / rows
            val s = min(sMax, min(cw / BW, chh / BH))
            // the largest cards win, a squarer block wins a near tie (a row of four little cards loses to two rows of two)
            val score = s - 0.03f * abs(cols - rows)
            if (score > bestScore + 0.001f) {
                bestScore = score
                bestS = s
                bestCols = cols
            }
        }
        val s = bestS.coerceAtLeast(0.3f)
        val cols = bestCols
        val rows = (n + cols - 1) / cols
        val pitchX = BW * s + gap
        val pitchY = BH * s + gap
        val out = ArrayList<Pair<Float, Float>>(n)
        for (row in 0 until rows) {
            val inRow = if (row < rows - 1) cols else n - cols * (rows - 1)
            for (k in 0 until inRow) {
                out.add((cx + (k - (inRow - 1) / 2f) * pitchX) to (cy + (row - (rows - 1) / 2f) * pitchY))
            }
        }
        return Grid(cols, rows, s, out)
    }

    /** Smallest box (x0, y0, x1, y1) around the corners of every card. */
    private fun bounds(poses: List<CardPose>): FloatArray {
        val all = ArrayList<Float>(poses.size * 8)
        for (p in poses) for (v in PosterGeometry.corners(p)) all.add(v)
        return PosterGeometry.bounds(all.toFloatArray())
    }

    private fun fits(poses: List<CardPose>, maxW: Float, maxH: Float): Boolean {
        val b = bounds(poses)
        return b[2] - b[0] <= maxW && b[3] - b[1] <= maxH
    }

    /** A last safety net: a card whose corners leave the picture (or reach the mark's band) is moved back in, never resized. */
    private fun keepInside(poses: List<CardPose>, r: PosterRatio): List<CardPose> =
        poses.map { p ->
            val b = PosterGeometry.bounds(PosterGeometry.corners(p))
            var dx = 0f
            var dy = 0f
            val bottom = r.markBand
            if (b[2] - b[0] <= r.w - 8f) {
                if (b[0] < 4f) dx = 4f - b[0] else if (b[2] > r.w - 4f) dx = r.w - 4f - b[2]
            }
            if (b[3] - b[1] <= bottom - 8f) {
                if (b[1] < 4f) dy = 4f - b[1] else if (b[3] > bottom) dy = bottom - b[3]
            }
            if (dx == 0f && dy == 0f) p else p.copy(x = p.x + dx, y = p.y + dy)
        }
}

/** Moving a poster from one shape to another: where everything goes. Positions follow their share of the picture, sizes follow the short side. */
internal object PosterRemap {
    fun sx(from: PosterRatio, to: PosterRatio): Float = to.w.toFloat() / from.w

    fun sy(from: PosterRatio, to: PosterRatio): Float = to.h.toFloat() / from.h

    /** True when [poses] are exactly the ready-made arrangement of [layout] on the [from] shape (nothing was moved by hand). */
    fun untouched(poses: List<CardPose>, layout: PosterLayout, count: Int, from: PosterRatio): Boolean {
        val want = PosterLayoutMath.poses(layout, count, from).take(count)
        if (want.size != poses.size) return false
        for (i in want.indices) {
            val a = want[i]
            val b = poses[i]
            if (abs(a.x - b.x) > 0.5f || abs(a.y - b.y) > 0.5f || abs(a.scale - b.scale) > 0.002f || abs(a.rotation - b.rotation) > 0.05f) return false
        }
        return true
    }

    /** A hand-placed card on the new shape: the same share of the picture, the size of the same share of its area, and never out of the picture. */
    fun card(p: CardPose, from: PosterRatio, to: PosterRatio): CardPose {
        val k = sqrt(sx(from, to) * sy(from, to)).coerceIn(0.5f, 1.6f)
        val room = PosterLayoutGen.room(to)
        val maxS = min(0.95f * room.h / PosterCards.BASE_H, 0.9f * room.w / PosterCards.BASE_W)
        val s = (p.scale * k).coerceIn(0.2f, max(0.2f, maxS))
        return p.copy(
            x = (p.x * sx(from, to)).coerceIn(PosterCards.BASE_W * s / 3f, to.w - PosterCards.BASE_W * s / 3f),
            y = (p.y * sy(from, to)).coerceIn(PosterCards.BASE_H * s / 3f, to.markBand - PosterCards.BASE_H * s / 3f),
            scale = s,
        )
    }

    fun point(x: Float, y: Float, from: PosterRatio, to: PosterRatio): Pair<Float, Float> = (x * sx(from, to)).coerceIn(0f, to.w.toFloat()) to (y * sy(from, to)).coerceIn(0f, to.h.toFloat())
}
