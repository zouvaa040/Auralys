/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The pure geometry of the picture (no Android, no Compose), ported 1:1 from the Windows app: where a card's corners land once it is turned and tilted, the
 * numbers a card shows, the stickers' boxes. The renderer, the gestures and the tests all read it, so they cannot disagree.
 *
 * A card lives on its own plane: the origin is the card's centre, u goes right and v goes down, in card pixels at scale 1. The pose turns that plane in 3D, looks at
 * it through a camera `camera` poster pixels away, turns the result by `rotation` and puts its centre at (x, y): one projective 3 x 3 map.
 * Signs: tiltY > 0 turns the right edge away, tiltX > 0 turns the top edge away, rotation > 0 turns clockwise.
 */
object PosterGeometry {
    const val CAMERA = 1100f
    const val CARD_RADIUS = 48f

    /** The nearest a corner of a card may come to the camera, as a share of the camera's distance (see [cardMatrix]). */
    const val MIN_DEPTH_SHARE = 0.3

    /** Row-major 3 x 3 matrix mapping (u, v, 1) of the card's plane at [depth] (card pixels behind the face) to poster pixels (divide by w). */
    fun cardMatrix(p: CardPose, camera: Float = CAMERA, depth: Float = 0f): FloatArray {
        val a = p.scale.toDouble()
        val th = Math.toRadians(p.tiltY.toDouble())
        val ph = Math.toRadians(p.tiltX.toDouble())
        val rho = Math.toRadians(p.rotation.toDouble())
        val t = depth.toDouble()
        val ct = cos(th)
        val st = sin(th)
        val cp = cos(ph)
        val sp = sin(ph)
        // a big, strongly tilted card at the closest camera (scale 1.8, tilt 60 / 45, "Perspektif" 1) put a corner BEHIND the camera and the card
        // turned inside out (review 2026-11-12, item 32): for such a pose the camera steps back until every corner keeps MIN_DEPTH_SHARE of the
        // camera's distance. Ordinary poses are far from that and keep the camera as it is.
        val reach = a * (abs(st * cp) * (PosterCards.BASE_W / 2f + 40f) + abs(sp) * (PosterCards.BASE_H / 2f + 40f))
        val d = max(camera.toDouble(), reach / (1.0 - MIN_DEPTH_SHARE))
        val r0 = doubleArrayOf(a * d * ct, 0.0, -a * d * t * st)
        val r1 = doubleArrayOf(a * d * st * sp, a * d * cp, a * d * t * ct * sp)
        val r2 = doubleArrayOf(a * st * cp, -a * sp, d + a * t * ct * cp)
        val cr = cos(rho)
        val sr = sin(rho)
        val out = FloatArray(9)
        for (k in 0 until 3) {
            out[k] = ((cr * r0[k] - sr * r1[k] + p.x * r2[k]) / r2[2]).toFloat()
            out[3 + k] = ((sr * r0[k] + cr * r1[k] + p.y * r2[k]) / r2[2]).toFloat()
            out[6 + k] = (r2[k] / r2[2]).toFloat()
        }
        return out
    }

    /** "Perspektif" slider (0 flat .. 1 close camera): 3200 .. 600 poster pixels. */
    fun cameraFor(perspective: Float): Float = 600f + 2600f * Math.pow((1f - perspective.coerceIn(0f, 1f)).toDouble(), 2.1).toFloat()

    /** "Sudut" slider: 18 .. 78 card pixels. */
    fun cornerFor(corner: Float): Float = 18f + 60f * corner.coerceIn(0f, 1f)

    /** "Ketebalan" slider: 0 .. 30 card pixels. */
    fun thicknessFor(thickness: Float): Float = 30f * thickness.coerceIn(0f, 1f)

    class Outline(val u: FloatArray, val v: FloatArray, val nx: FloatArray, val ny: FloatArray) {
        val size: Int get() = u.size
    }

    /** The rounded outline of a card, clockwise from the top right corner, with the outward unit normals. */
    fun rrectOutline(hw: Float, hh: Float, r: Float, arcSteps: Int): Outline {
        val n = 4 * (arcSteps + 1)
        val us = FloatArray(n)
        val vs = FloatArray(n)
        val nxs = FloatArray(n)
        val nys = FloatArray(n)
        var i = 0
        fun arc(cx: Float, cy: Float, from: Double) {
            for (k in 0..arcSteps) {
                val ang = Math.toRadians(from + 90.0 * k / arcSteps)
                val c = cos(ang).toFloat()
                val s = sin(ang).toFloat()
                us[i] = cx + r * c
                vs[i] = cy + r * s
                nxs[i] = c
                nys[i] = s
                i++
            }
        }
        arc(hw - r, -hh + r, -90.0)
        arc(hw - r, hh - r, 0.0)
        arc(-hw + r, hh - r, 90.0)
        arc(-hw + r, -hh + r, 180.0)
        return Outline(us, vs, nxs, nys)
    }

    /** The outline of a card on the poster (x0, y0, x1, y1, ...): its rounded rectangle at [depth] seen through the pose and the camera. */
    fun outline(p: CardPose, camera: Float, depth: Float, radius: Float, arcSteps: Int = 8): FloatArray {
        val o = rrectOutline(PosterCards.BASE_W / 2f, PosterCards.BASE_H / 2f, radius, arcSteps)
        val m = cardMatrix(p, camera, depth)
        val out = FloatArray(o.size * 2)
        for (i in 0 until o.size) {
            val u = o.u[i]
            val v = o.v[i]
            val w = m[6] * u + m[7] * v + m[8]
            out[i * 2] = (m[0] * u + m[1] * v + m[2]) / w
            out[i * 2 + 1] = (m[3] * u + m[4] * v + m[5]) / w
        }
        return out
    }

    fun map(m: FloatArray, u: Float, v: Float): Pair<Float, Float> {
        val w = m[6] * u + m[7] * v + m[8]
        return ((m[0] * u + m[1] * v + m[2]) / w) to ((m[3] * u + m[4] * v + m[5]) / w)
    }

    fun invert(m: FloatArray): FloatArray? {
        val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[2].toDouble()
        val d = m[3].toDouble(); val e = m[4].toDouble(); val f = m[5].toDouble()
        val g = m[6].toDouble(); val h = m[7].toDouble(); val i = m[8].toDouble()
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(det) < 1e-12) return null
        val inv = 1.0 / det
        return floatArrayOf(
            ((e * i - f * h) * inv).toFloat(), ((c * h - b * i) * inv).toFloat(), ((b * f - c * e) * inv).toFloat(),
            ((f * g - d * i) * inv).toFloat(), ((a * i - c * g) * inv).toFloat(), ((c * d - a * f) * inv).toFloat(),
            ((d * h - e * g) * inv).toFloat(), ((b * g - a * h) * inv).toFloat(), ((a * e - b * d) * inv).toFloat(),
        )
    }

    /** The four corners of a card on the poster, clockwise from the top left: x0, y0, x1, y1, ... */
    fun corners(p: CardPose, camera: Float = CAMERA): FloatArray {
        val m = cardMatrix(p, camera)
        val hw = PosterCards.BASE_W / 2f
        val hh = PosterCards.BASE_H / 2f
        val out = FloatArray(8)
        val pts = arrayOf(-hw to -hh, hw to -hh, hw to hh, -hw to hh)
        for ((k, pt) in pts.withIndex()) {
            val (x, y) = map(m, pt.first, pt.second)
            out[k * 2] = x
            out[k * 2 + 1] = y
        }
        return out
    }

    /** True when the poster point is on the card (rounded corners), seen through its tilt and the camera. */
    fun hit(p: CardPose, x: Float, y: Float, camera: Float = CAMERA, slop: Float = 0f): Boolean {
        val inv = invert(cardMatrix(p, camera)) ?: return false
        val w = inv[6] * x + inv[7] * y + inv[8]
        if (abs(w) < 1e-9f) return false
        val u = (inv[0] * x + inv[1] * y + inv[2]) / w
        val v = (inv[3] * x + inv[4] * y + inv[5]) / w
        val hw = PosterCards.BASE_W / 2f + slop
        val hh = PosterCards.BASE_H / 2f + slop
        if (abs(u) > hw || abs(v) > hh) return false
        val cx = abs(u) - (hw - CARD_RADIUS)
        val cy = abs(v) - (hh - CARD_RADIUS)
        return !(cx > 0f && cy > 0f && cx * cx + cy * cy > CARD_RADIUS * CARD_RADIUS)
    }

    /** The front-most card under the point, or -1 (the last card is drawn last, so it is on top). */
    fun hitCard(poses: List<CardPose>, x: Float, y: Float, camera: Float = CAMERA, slop: Float = 0f): Int {
        for (i in poses.indices.reversed()) if (hit(poses[i], x, y, camera, slop)) return i
        return -1
    }

    // ------------------------------------------------------------------------------------------------ stickers

    /** Width over height of a sticker's picture: the user's picture for a custom one, 1 for the drawn ones (and for a missing picture). */
    fun stickerAspect(s: PosterSticker, aspects: Map<String, Float>?): Float {
        if (s.kind != StickerKind.CUSTOM) return 1f
        return s.imageKey?.let { aspects?.get(it) } ?: 1f
    }

    private fun stickerHalf(s: PosterSticker, aspect: Float): Pair<Float, Float> {
        val h = PosterStickers.BASE * s.scale / 2f
        val a = aspect.coerceIn(0.02f, 50f)
        return if (a >= 1f) h to h / a else h * a to h
    }

    /** The four corners of the sticker's box on the poster (clockwise from its own top left), rotated and mirrored as it is. */
    fun stickerBounds(s: PosterSticker, aspect: Float = 1f): FloatArray {
        val (hw, hh) = stickerHalf(s, aspect)
        val rad = Math.toRadians(s.rotation.toDouble())
        val c = cos(rad).toFloat()
        val sn = sin(rad).toFloat()
        val fl = if (s.flip) -1f else 1f
        val out = FloatArray(8)
        val corners = arrayOf(-hw to -hh, hw to -hh, hw to hh, -hw to hh)
        for ((i, p) in corners.withIndex()) {
            val u = p.first * fl
            val v = p.second
            out[i * 2] = s.x + u * c - v * sn
            out[i * 2 + 1] = s.y + u * sn + v * c
        }
        return out
    }

    /** The corner handle (size and turn): the bottom right corner of the box as the eye sees it. */
    fun stickerHandle(s: PosterSticker, aspect: Float = 1f): Pair<Float, Float> {
        val k = stickerBounds(s, aspect)
        val i = if (s.flip) 3 else 2
        return k[i * 2] to k[i * 2 + 1]
    }

    fun hitSticker(s: PosterSticker, x: Float, y: Float, aspect: Float = 1f, slop: Float = 0f): Boolean {
        val (hw, hh) = stickerHalf(s, aspect)
        val rad = Math.toRadians(s.rotation.toDouble())
        val dx = x - s.x
        val dy = y - s.y
        val u = dx * cos(rad).toFloat() + dy * sin(rad).toFloat()
        val v = -dx * sin(rad).toFloat() + dy * cos(rad).toFloat()
        return abs(u) <= hw + slop && abs(v) <= hh + slop
    }

    /** The front-most sticker under the point, or -1. */
    fun hitSticker(list: List<PosterSticker>, x: Float, y: Float, aspects: Map<String, Float>? = null, slop: Float = 0f): Int {
        for (i in list.indices.reversed()) if (hitSticker(list[i], x, y, stickerAspect(list[i], aspects), slop)) return i
        return -1
    }

    // ------------------------------------------------------------------------------------------------ what a card shows

    /** "3:07" for 187 seconds. */
    fun clock(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        return (s / 60).toString() + ":" + (s % 60).toString().padStart(2, '0')
    }

    /** Where a card's progress bar stands: fixed per id, 0.2 to 0.7. */
    fun progressOf(id: String): Float {
        var h = 17
        for (c in id) h = h * 31 + c.code
        return 0.2f + ((h and 0x7fffffff) % 51) / 100f
    }

    /** The song's own length, or a made-up but fixed one between 2:40 and 4:10. */
    fun shownDuration(song: PosterSong): Int {
        if (song.durationSec > 0) return song.durationSec
        var h = 7
        for (c in song.id) h = h * 37 + c.code
        return 160 + (h and 0x7fffffff) % 91
    }

    /** Smallest axis-aligned box (x0, y0, x1, y1) of a point list x0, y0, x1, y1, ... */
    fun bounds(pts: FloatArray): FloatArray {
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < pts.size) {
            x0 = minOf(x0, pts[i]); x1 = max(x1, pts[i])
            y0 = minOf(y0, pts[i + 1]); y1 = max(y1, pts[i + 1])
            i += 2
        }
        return floatArrayOf(x0, y0, x1, y1)
    }
}

/** The ready-made arrangements per number of cards (1..5), poster pixels; ported from the Windows app. */
internal object PosterLayoutMath {
    fun poses(layout: PosterLayout, count: Int, ratio: PosterRatio = PosterRatio.DEFAULT): List<CardPose> {
        val n = count.coerceIn(1, POSTER_MAX_SONGS)
        // the 9:16 poster keeps its four hand-tuned arrangements to the pixel; every other shape (and the new arrangements) is worked out for the shape
        if (ratio != PosterRatio.R9_16 || layout == PosterLayout.KOLASE || layout == PosterLayout.BESAR) return PosterLayoutGen.poses(layout, n, ratio)
        return when (layout) {
            PosterLayout.ORBIT -> orbit(n)
            PosterLayout.TUMPUK -> tumpuk(n)
            PosterLayout.DERET -> deret(n)
            PosterLayout.KOLOM -> kolom(n)
            else -> PosterLayoutGen.poses(layout, n, ratio)
        }
    }

    private fun orbit(n: Int): List<CardPose> =
        when (n) {
            1 -> listOf(CardPose(330f, 720f, 0.98f, -5f, 0f, 24f))
            2 -> listOf(CardPose(275f, 560f, 0.88f, -5f, 0f, 26f), CardPose(805f, 480f, 0.88f, 5f, 0f, -26f))
            3 -> listOf(CardPose(275f, 540f, 0.86f, -5f, 0f, 26f), CardPose(810f, 455f, 0.86f, 5f, 0f, -26f), CardPose(250f, 1350f, 0.86f, 4f, 0f, 28f))
            4 ->
                listOf(
                    CardPose(275f, 540f, 0.86f, -5f, 0f, 26f),
                    CardPose(810f, 455f, 0.86f, 5f, 0f, -26f),
                    CardPose(250f, 1350f, 0.86f, 4f, 0f, 28f),
                    CardPose(850f, 1285f, 0.86f, -4f, 0f, -28f),
                )
            else ->
                listOf(
                    CardPose(235f, 470f, 0.74f, -6f, 0f, 28f),
                    CardPose(850f, 395f, 0.74f, 6f, 0f, -28f),
                    CardPose(225f, 1400f, 0.76f, 4f, 0f, 30f),
                    CardPose(860f, 1335f, 0.76f, -4f, 0f, -30f),
                    CardPose(540f, 800f, 0.76f, 0f, 0f, 0f),
                )
        }

    private fun tumpuk(n: Int): List<CardPose> {
        val s = floatArrayOf(0.9f, 0.85f, 0.8f, 0.74f, 0.68f)[n - 1]
        val step = 15f
        val cx = 540f
        val cy = 1060f
        val r = 580f
        return List(n) { i ->
            val a = (i - (n - 1) / 2f) * step
            val rad = Math.toRadians(a.toDouble())
            CardPose(cx + (r * sin(rad)).toFloat(), cy - (r * cos(rad)).toFloat(), s, a, 0f, -a / 3f)
        }
    }

    private fun deret(n: Int): List<CardPose> {
        val s = floatArrayOf(0.95f, 0.9f, 0.78f, 0.6f, 0.5f)[n - 1]
        val spacing = if (n == 1) 0f else (POSTER_W - 80f) / n
        return List(n) { i ->
            val d = if (n == 1) 0f else (i - (n - 1) / 2f) / ((n - 1) / 2f)
            val x = 540f + (i - (n - 1) / 2f) * spacing
            val y = 150f + PosterCards.BASE_H * s / 2f + 22f * d * d
            CardPose(x, y, s, 3f * d, 0f, -15f * d)
        }
    }

    private fun kolom(n: Int): List<CardPose> {
        val s = floatArrayOf(0.8f, 0.66f, 0.58f, 0.5f, 0.47f)[n - 1]
        val step = 1.12f * PosterCards.BASE_H * s
        val x = if (n <= 2) 780f else 835f
        return List(n) { i ->
            val y = 960f + (i - (n - 1) / 2f) * step
            CardPose(x, y, s, 3f, 0f, -18f)
        }
    }
}
