/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ====================================================================================================================
// "Kilau" (round 4.3.5, K: the owner's "kenapa efek kilauan nya jadi ilang"): glints as a cross-screen star filter on the lens draws them.
//  * WHERE: only where light really catches something: the glass edge of a card where it faces the key light (a rounded corner turns its
//    normal through 90 degrees, so one point of it mirrors the light: the hero of each card; the lit straight edges a smaller one), and the
//    real specular highlights of the photo (small, very bright, isolated spots: metal, a clasp, hair in the back light, a bokeh light).
//    Every visible card gets one at moderate amounts, so a poster without a photo sparkles too. Never on the face or head, never over a
//    card's text or controls, never in the mark's band, never scattered over empty space, no flare from nowhere. Same poster = same glints.
//  * HOW: one star filter on the lens: every glint of a picture has its spikes at the same angle; two long main lines (four spikes) and two
//    thinner, shorter secondary lines between them; a small white core with a soft bloom; each spike a Gaussian line that tapers and fades
//    smoothly; a breath of the light's colour towards the tips. Computed per pixel once per size (a sprite), laid on as ADDED light.
//    Drawn into the scene before the finish (the grade, the vignette and the halation treat it as the photo's own light) and BEHIND the
//    person (a glint on a card the person stands in front of is hidden by the person as the card is); a highlight on the person is drawn
//    over the person.
// The amount sets how many (about 1 + 13 x "Kilau": 0.5 = 8, 1.0 = 14, as many as there are good places), how large (a few large heroes,
// more small ones, the size following how bright the spot is) and how bright.

internal enum class GlintSource { PHOTO, CARD }

/**
 * A glint in poster pixels. [strength] 0..1: for a highlight of the photo how strong a specular it is (the lens streak takes only a strong
 * one), for a card's edge how squarely it faces the key light. Once placed: [length] = its main spikes (poster pixels), [intensity] 0..1,
 * [hero] = one of the few large ones, [front] = on the person (drawn over the cut-out; every other glint is drawn behind the person).
 */
internal class Glint(
    val x: Float,
    val y: Float,
    val strength: Float,
    val source: GlintSource = GlintSource.PHOTO,
    val card: Int = -1,
    val length: Float = 0f,
    val intensity: Float = 0f,
    val hero: Boolean = false,
    val front: Boolean = false,
) {
    /** The ranking among all glints of a picture: a real specular of the photo is worth a little more than its raw strength says. */
    val rank: Float get() = if (source == GlintSource.PHOTO) 0.25f + 0.75f * sqrt(strength.coerceIn(0f, 1f)) else strength

    fun placed(length: Float, intensity: Float, hero: Boolean): Glint = Glint(x, y, strength, source, card, length, intensity, hero, front)

    fun at(x: Float, y: Float, front: Boolean): Glint = Glint(x, y, strength, source, card, length, intensity, hero, front)
}

/** A specular spot of the photo, in the photo's own pixels (found once per photo, whatever its place on the poster). */
internal class PhotoSpot(val x: Float, val y: Float, val strength: Float)

internal object PosterGlints {
    /** The star filter's angle (degrees): the main lines at this and 90 degrees more, the secondary ones half way between. */
    const val STAR_ANGLE = 20f

    /** The secondary lines are this much of the main ones' length. */
    const val SECONDARY = 0.42f

    /** Two glints are at least this far apart (poster pixels) at a moderate "Kilau"; more "Kilau" packs them a little closer ([spacingFor]). */
    const val SPACING = 90f

    /** Two of the large glints are at least this far apart. */
    const val HERO_SPACING = 300f

    /** Below this the mark's band starts (no glint there, no spike into it). */
    const val MARK_BAND = 1660f

    /** The shortest glint worth drawing (main spike, poster pixels). */
    const val MIN_LENGTH = 12f

    // the photo's specular spots: at least this bright in all three channels (a near-white, nearly clipped point: a reflection of a light,
    // not a pale patch such as faded denim or a white shirt in the shade), this far above the ring around it
    const val SPOT_MIN_VALUE = 236
    const val SPOT_MIN_CONTRAST = 60

    /** A spot on the person must be this strong (a real metal or jewelry reflection); it stays a small glint, never a large one. */
    const val PERSON_MIN_STRENGTH = 0.5f

    /** At most this many of the 16 ring samples may be bright: a line (a hair, an edge) passes through two of them, a lit area through many. */
    const val SPOT_RING_BRIGHT = 2

    /** Where along a lit straight edge (from its brighter corner) a small glint may sit, and how much of the edge's light it gets. */
    private val EDGE_POINTS = listOf(0.3f to 0.45f, 0.55f to 0.3f, 0.8f to 0.2f)

    private val ringCos = FloatArray(16) { cos(it * Math.PI / 8).toFloat() }
    private val ringSin = FloatArray(16) { sin(it * Math.PI / 8).toFloat() }

    // ---------------------------------------------------------------- the photo's highlights

    /**
     * The real specular highlights of a photo: the brightest near-white value (min of r, g, b) of blocks of about 4 poster pixels, kept where a
     * block is the brightest of its 5 x 5 neighbourhood, at least [SPOT_MIN_VALUE] (nearly clipped), small (at most a 3 x 3 plateau), at least
     * [SPOT_MIN_CONTRAST] above the mean of a ring about 20 poster pixels out with at most [SPOT_RING_BRIGHT] bright samples on it (a spot or
     * a thin line, never a lit area). Strongest first, at most [max]. Photo pixels; heavy (the whole photo): once per photo.
     */
    fun photoSpots(img: PosterImage, max: Int = 64): List<PhotoSpot> {
        val w = img.w
        val h = img.h
        val block = max(1, (4f / PosterPhotoGeometry.scale(w, h, 1f)).roundToInt())
        val gw = (w + block - 1) / block
        val gh = (h + block - 1) / block
        val v = IntArray(gw * gh)
        val px = img.argb
        for (y in 0 until h) {
            val row = y * w
            val g = (y / block) * gw
            for (x in 0 until w) {
                val p = px[row + x]
                val m = min((p shr 16) and 255, min((p shr 8) and 255, p and 255))
                val k = g + x / block
                if (m > v[k]) v[k] = m
            }
        }
        val ring = 5
        val out = ArrayList<PhotoSpot>()
        for (gy in ring until gh - ring) {
            val row = gy * gw
            for (gx in ring until gw - ring) {
                val c = v[row + gx]
                if (c < SPOT_MIN_VALUE) continue
                // the brightest of its 5 x 5 neighbourhood (ties: the first one wins), with a small core
                var peak = true
                var core = 0
                var sx = 0f
                var sy = 0f
                loop@ for (dy in -2..2) for (dx in -2..2) {
                    if (dx == 0 && dy == 0) continue
                    val n = v[(gy + dy) * gw + gx + dx]
                    if (n > c || (n == c && (dy < 0 || (dy == 0 && dx < 0)))) {
                        peak = false
                        break@loop
                    }
                    if (abs(dx) <= 1 && abs(dy) <= 1 && n >= c - 25) {
                        core++
                        sx += dx
                        sy += dy
                    }
                }
                if (!peak || core > 8) continue
                var sum = 0
                var bright = 0
                for (k in 0 until 16) {
                    val n = v[(gy + (ringSin[k] * ring).roundToInt()) * gw + gx + (ringCos[k] * ring).roundToInt()]
                    sum += n
                    if (n >= c - 40) bright++
                }
                val mean = sum / 16
                if (c - mean < SPOT_MIN_CONTRAST || bright > SPOT_RING_BRIGHT) continue
                val strength = ((c - mean) / 255f) * ((c - 200) / 55f) * (1f + min(core, 5) * 0.12f) * (1f - 0.2f * bright)
                // the centre of the spot (its plateau)
                val cx = gx + 0.5f + sx / (core + 1)
                val cy = gy + 0.5f + sy / (core + 1)
                out.add(PhotoSpot(cx * block, cy * block, strength.coerceIn(0f, 1f)))
            }
        }
        out.sortWith(compareByDescending<PhotoSpot> { it.strength }.thenBy { it.y }.thenBy { it.x })
        return if (out.size > max) out.subList(0, max).toList() else out
    }

    // ---------------------------------------------------------------- the cards' glass edges

    /**
     * Where the glass edges of the cards catch the key light (coming from [shadowAngle] + 180 degrees, the same light as the lit hairline of
     * PosterCardPaint): on each rounded corner the point whose normal looks most into the light (a curved polished edge mirrors a light in one
     * point), and on each straight edge facing the light a point a third of the way from its brighter corner. [strength] = how squarely it
     * faces the light (to the 5th power: the corner facing the light leads, its neighbours follow, the far side has none), times the "Tepi
     * kartu" [rim] and "Pantulan" [gloss], the card's size, and less on a card blurred by the depth of field ([soft] 0..1 per card).
     * Poster pixels, every card, nothing hidden yet (see [cardVisible]).
     */
    fun cardCandidates(poses: List<CardPose>, camera: Float, radius: Float, shadowAngle: Float, gloss: Float, rim: Float, soft: (Int) -> Float = { 0f }): List<Glint> {
        val a = Math.toRadians(shadowAngle.toDouble())
        val lx0 = (-cos(a)).toFloat()
        val ly0 = (-sin(a)).toFloat()
        val inset = 2.5f
        val steps = 8
        val o = PosterGeometry.rrectOutline(PosterCards.BASE_W / 2f - inset, PosterCards.BASE_H / 2f - inset, max(4f, radius - inset), steps)
        val per = steps + 1
        val out = ArrayList<Glint>()
        for ((i, p) in poses.withIndex()) {
            // the light in the card's own frame (as the card's hairline is lit)
            val r = Math.toRadians(-p.rotation.toDouble())
            val c = cos(r).toFloat()
            val s = sin(r).toFloat()
            val lx = lx0 * c - ly0 * s
            val ly = lx0 * s + ly0 * c
            val k = (0.6f + 0.4f * rim.coerceIn(0f, 1f)) * (0.8f + 0.2f * gloss.coerceIn(0f, 1f)) * (p.scale / 0.86f).coerceIn(0.75f, 1.15f) * (1f - 0.6f * soft(i).coerceIn(0f, 1f))
            val m = PosterGeometry.cardMatrix(p, camera)
            val faceOf = FloatArray(4)
            for (arc in 0 until 4) {
                var best = arc * per
                var bf = -2f
                for (j in arc * per until arc * per + per) {
                    val f = o.nx[j] * lx + o.ny[j] * ly
                    if (f > bf + 1e-6f) {
                        bf = f
                        best = j
                    }
                }
                faceOf[arc] = bf
                if (bf > 0.15f) {
                    val (x, y) = PosterGeometry.map(m, o.u[best], o.v[best])
                    out.add(Glint(x, y, (bf.pow(5) * k).coerceIn(0f, 1f), GlintSource.CARD, i))
                }
            }
            // the straight edges facing the light: edge e runs from the end of corner e to the start of corner e + 1, its normal is corner e's
            // last one; small glints along its lit hairline (a third of the way from its brighter corner first, then further along, weaker)
            for (e in 0 until 4) {
                val end = e * per + steps
                val f = o.nx[end] * lx + o.ny[end] * ly
                if (f < 0.5f) continue
                val next = ((e + 1) % 4) * per
                val fromEnd = faceOf[e] >= faceOf[(e + 1) % 4]
                val u0 = if (fromEnd) o.u[end] else o.u[next]
                val v0 = if (fromEnd) o.v[end] else o.v[next]
                val u1 = if (fromEnd) o.u[next] else o.u[end]
                val v1 = if (fromEnd) o.v[next] else o.v[end]
                for ((t, w) in EDGE_POINTS) {
                    val (x, y) = PosterGeometry.map(m, u0 + (u1 - u0) * t, v0 + (v1 - v0) * t)
                    out.add(Glint(x, y, (f.pow(5) * w * k).coerceIn(0f, 1f), GlintSource.CARD, i))
                }
            }
        }
        return out
    }

    /** False when a card drawn later (in front) covers the point of card [card]'s edge, or the point is outside the picture or in the mark's band. */
    fun cardVisible(g: Glint, poses: List<CardPose>, camera: Float, ratio: PosterRatio = PosterRatio.DEFAULT): Boolean {
        if (g.x < 4f || g.x > ratio.w - 4f || g.y < 4f || g.y > ratio.markBand) return false
        for (j in g.card + 1 until poses.size) if (PosterGeometry.hit(poses[j], g.x, g.y, camera, slop = 3f)) return false
        return true
    }

    /** True when a poster point is on the text or the controls of a card (the device line down to the volume row), seen through [inv] (the card's inverse matrix). */
    fun onCardText(inv: FloatArray, x: Float, y: Float): Boolean {
        val w = inv[6] * x + inv[7] * y + inv[8]
        if (abs(w) < 1e-9f) return false
        val u = (inv[0] * x + inv[1] * y + inv[2]) / w
        val v = (inv[3] * x + inv[4] * y + inv[5]) / w
        return abs(u) <= CardLook.COVER / 2f + 12f && v >= CardLook.DEVICE_TOP - 12f && v <= CardLook.VOLUME_Y + 18f
    }

    // ---------------------------------------------------------------- choosing and sizing

    /** How many glints [amount] ("Kilau", 0..1) asks for, at most: 0.45 = 7, 0.5 = 8, 0.7 = 10, 1.0 = 14. */
    fun countFor(amount: Float): Int = if (amount <= 0.01f) 0 else (1f + 13f * amount.coerceIn(0f, 1f)).roundToInt()

    /** How many large ones: 0.45 = 2, 1.0 = 3. */
    fun heroesFor(amount: Float): Int = if (amount <= 0.01f) 0 else 1 + (2f * amount.coerceIn(0f, 1f)).roundToInt()

    /** How far apart two glints are at least: 0.45 = 90, 1.0 = 68 (more glints fit along the same lit edges). */
    fun spacingFor(amount: Float): Float = SPACING * (1f - 0.45f * (amount.coerceIn(0.45f, 1f) - 0.45f))

    /** The main spike of the largest glint (poster pixels): 0.45 = 124, 1.0 = 190. */
    fun maxLength(amount: Float): Float = 70f + 120f * amount.coerceIn(0f, 1f)

    /**
     * The glints of a picture from its [candidates] (cards' edges and the photo's highlights, already visible): from moderate amounts on the
     * best place of every card first (so every card catches the light), then the rest by rank, all [spacingFor] apart, at most [countFor]; the
     * best few that have room for it, [HERO_SPACING] apart and not on the person, are the large heroes, the others small, the size following
     * the rank (one on the person at most 0.3 of the largest). Each one shrinks until no spike reaches a [blocked] point (the head, a card's
     * text, the mark's band); one that cannot be drawn [MIN_LENGTH] long is left out. Deterministic: the same candidates give the same glints.
     */
    fun place(candidates: List<Glint>, amount: Float, blocked: (Float, Float) -> Boolean): List<Glint> {
        val n = countFor(amount)
        if (n == 0 || candidates.isEmpty()) return emptyList()
        val a = amount.coerceIn(0f, 1f)
        val sorted = candidates.sortedWith(compareByDescending<Glint> { it.rank }.thenBy { it.y }.thenBy { it.x })
        val chosen = ArrayList<Glint>()
        fun free(g: Glint, d: Float, among: List<Glint>) = among.none { hypot(it.x - g.x, it.y - g.y) < d }
        val spacing = spacingFor(a)
        fun ok(g: Glint) = g.rank > 0.01f && free(g, spacing, chosen) && !crosses(g.x, g.y, MIN_LENGTH, blocked)
        if (a >= 0.25f) {
            val done = HashSet<Int>()
            for (g in sorted) {
                if (chosen.size >= n) break
                if (g.source != GlintSource.CARD || g.card in done) continue
                done.add(g.card)
                if (ok(g)) chosen.add(g)
            }
        }
        for (g in sorted) {
            if (chosen.size >= n) break
            if (g !in chosen && ok(g)) chosen.add(g)
        }
        if (chosen.isEmpty()) return emptyList()
        chosen.sortWith(compareByDescending<Glint> { it.rank }.thenBy { it.y }.thenBy { it.x })
        val top = chosen[0].rank.coerceAtLeast(1e-3f)
        val heroes = ArrayList<Glint>()
        val hn = heroesFor(a)
        val lmax = maxLength(a)
        val glow = 0.78f + 0.22f * a
        val out = ArrayList<Glint>()
        for (g in chosen) {
            val rr = (g.rank / top).coerceIn(0f, 1f)
            // a large one only where it has room (a hero squeezed by a card's text would just be a small glint); never on the person
            var hero = false
            var len = 0f
            if (heroes.size < hn && !g.front && g.rank >= 0.35f * top && free(g, HERO_SPACING, heroes)) {
                val want = lmax * (0.72f + 0.28f * rr)
                val l = fit(g.x, g.y, want, blocked)
                if (l >= 0.8f * want) {
                    hero = true
                    len = l
                    heroes.add(g)
                }
            }
            if (!hero) {
                var want = lmax * (0.15f + 0.3f * rr.pow(1.3f))
                if (g.front) want = min(want, 0.3f * lmax)
                len = fit(g.x, g.y, max(want, MIN_LENGTH), blocked)
            }
            if (len < MIN_LENGTH) continue
            out.add(g.placed(len, (if (hero) 1f else 0.5f + 0.4f * rr) * glow, hero))
        }
        return out
    }

    /** The longest main spike up to [want] (shrinking by steps) whose glint reaches no [blocked] point, or 0. */
    fun fit(x: Float, y: Float, want: Float, blocked: (Float, Float) -> Boolean): Float {
        var len = want
        while (len >= MIN_LENGTH) {
            if (!crosses(x, y, len, blocked)) return len
            len *= 0.84f
        }
        return if (!crosses(x, y, MIN_LENGTH, blocked)) MIN_LENGTH else 0f
    }

    private val dirs: Array<FloatArray> by lazy {
        Array(8) { k ->
            val t = Math.toRadians((STAR_ANGLE + k * 45f).toDouble())
            floatArrayOf(cos(t).toFloat(), sin(t).toFloat(), if (k % 2 == 0) 1f else SECONDARY)
        }
    }

    /** True when a glint at ([x], [y]) with main spikes [len] long touches a [blocked] point: the core, the bloom, every spike to its tip and a little beyond. */
    fun crosses(x: Float, y: Float, len: Float, blocked: (Float, Float) -> Boolean): Boolean {
        if (blocked(x, y)) return true
        for (d in dirs) {
            val reach = len * d[2] + 6f
            var t = 6f
            while (t <= reach + 0.1f) {
                if (blocked(x + d[0] * t, y + d[1] * t) || blocked(x - d[0] * t, y - d[1] * t)) return true
                t += 8f
            }
            if (blocked(x + d[0] * reach, y + d[1] * reach) || blocked(x - d[0] * reach, y - d[1] * reach)) return true
        }
        return false
    }

    /** The colour of the light in the glints for a grade (towards the spikes' tips; the core is always white). */
    fun tint(g: PosterGrade): Color =
        when (g) {
            PosterGrade.DINGIN -> Color(0xFFCFE4FF)
            PosterGrade.NEON -> Color(0xFFE6D2FF)
            PosterGrade.ASLI -> Color(0xFFFFF3E2)
            else -> Color(0xFFFFE2BC)
        }

    // ---------------------------------------------------------------- the lens streak (P2, unchanged)

    /**
     * The anamorphic streak through a strong highlight: a very thin horizontal line of light with a fainter wider band, fading out [left]
     * poster pixels to the left and [right] to the right (each half fades on its own, so a side shortened before the person ends softly).
     */
    fun DrawScope.streak(cx: Float, cy: Float, left: Float, right: Float, intensity: Float, tint: Color) {
        val i = intensity.coerceIn(0f, 1f)
        if (i <= 0.01f) return
        val c = Offset(cx, cy)
        fun half(len: Float, x0: Float, x1: Float) {
            if (len <= 4f) return
            clipRect(left = x0, top = 0f, right = x1, bottom = 100000f) {
                fun band(sy: Float, a: Float, color: Color) {
                    withTransform({ scale(1f, sy, c) }) {
                        drawCircle(
                            Brush.radialGradient(0f to color.copy(alpha = a), 0.3f to color.copy(alpha = a * 0.45f), 1f to color.copy(alpha = 0f), center = c, radius = len),
                            radius = len,
                            center = c,
                            blendMode = BlendMode.Plus,
                        )
                    }
                }
                band(min(1f, 0.022f * 600f / len), 0.10f * i, tint)
                band(min(1f, 0.0055f * 600f / len), 0.42f * i, tint)
                band(min(1f, 0.0022f * 600f / len), 0.55f * i, Color.White)
            }
        }
        half(left, cx - left - 1f, cx)
        half(right, cx, cx + right + 1f)
    }

    /** The strongest highlight of the PHOTO that may carry the streak (a real light, not a card's edge or a faint sparkle), or null. */
    fun streakSource(glints: List<Glint>): Glint? = glints.filter { it.source == GlintSource.PHOTO }.maxByOrNull { it.strength }?.takeIf { it.strength >= 0.45f }

    /**
     * How far the streak from [g] may reach to the left and to the right, at most [want] each way: it stops short of the person and the head
     * (finding 13: no light across the face; a streak drawn over a body reads as pasted), 32 poster pixels before them.
     */
    fun streakReach(g: Glint, grid: SubjectGrid?, want: Float, width: Float = POSTER_W.toFloat()): Pair<Float, Float> {
        if (grid == null) return want to want
        fun reach(dir: Float): Float {
            var d = 0f
            while (d < want) {
                val x = g.x + dir * d
                if (x < 0f || x > width) return want
                for (dy in floatArrayOf(-10f, 0f, 10f)) if (grid.at(x, g.y + dy) > 32 || grid.inHead(x, g.y + dy)) return max(0f, d - 32f)
                d += 8f
            }
            return want
        }
        return reach(-1f) to reach(1f)
    }
}

/**
 * The star filter's light around one point (a pure function, so the tests can look at it): [len] = the main spikes in pixels, the picture
 * (2R + 1) pixels square with the point in the middle of the centre pixel. Non-premultiplied ARGB (alpha = the brightest channel), the colour
 * white in the core turning to [tint] towards the tips. [bold] = wider spikes and core (the "Kilau" sticker, a deliberate decoration).
 * [shadow] = the sticker's soft dark under-light (black, alpha only): wide soft lines under the spikes, only a faint veil at the core.
 */
internal object GlintPsf {
    fun radius(len: Float): Int = ceil(len + 4f).toInt()

    fun pixels(len: Float, tint: Color, shadow: Boolean, bold: Boolean = false): IntArray {
        val r = radius(len)
        val n = 2 * r + 1
        val out = IntArray(n * n)
        val lines = Array(4) { k ->
            val t = Math.toRadians((PosterGlints.STAR_ANGLE + k * 45f).toDouble())
            floatArrayOf(cos(t).toFloat(), sin(t).toFloat())
        }
        val wide = if (bold) 1.45f else 1f
        val w0 = if (shadow) 2.2f + 0.02f * len else (0.55f + 0.0055f * len) * wide
        val sc = if (shadow) 3f + 0.04f * len else (0.7f + 0.012f * len) * (if (bold) 1.25f else 1f)
        val bloom = if (shadow) 4f + 0.09f * len else 1.2f + 0.05f * len
        val halo = 0.2f * len
        val coreAmp = if (shadow) 0.6f else 1.6f
        val bloomAmp = if (shadow) 0.3f else 0.45f
        val tr = tint.red
        val tg = tint.green
        val tb = tint.blue
        for (j in 0..r) {
            val dy = (j - r).toFloat()
            // the picture is symmetric through its centre: the lower half is the upper one turned
            val iMax = if (j == r) r else n - 1
            for (i in 0..iMax) {
                val dx = (i - r).toFloat()
                val d2 = dx * dx + dy * dy
                val rad = sqrt(d2)
                var e = 0f
                if (d2 < 25f * sc * sc) e += coreAmp * exp(-d2 / (2f * sc * sc))
                if (rad < 8f * bloom) e += bloomAmp * exp(-rad / bloom)
                if (!shadow && rad < 3f * halo) e += 0.09f * exp(-d2 / (halo * halo))
                for (k in 0 until 4) {
                    val main = k % 2 == 0
                    val l = if (main) len else len * PosterGlints.SECONDARY
                    val ux = lines[k][0]
                    val uy = lines[k][1]
                    val t = abs(dx * ux + dy * uy)
                    if (t >= l) continue
                    val d = abs(dy * ux - dx * uy)
                    val x = t / l
                    val w = (if (main) w0 else w0 * 0.75f) * (1f - 0.5f * x)
                    // a soft glow around each main line (the filter's scatter): it keeps the spikes readable when the poster is seen small
                    val wg = 3f * w + 1.2f
                    if (d > 4.5f * wg) continue
                    val f = (1f - x).pow(1.6f) / (1f + 3f * x)
                    val we = w * w + 0.09f
                    e += (if (main) 1.25f else 0.6f) * f * exp(-d * d / (2f * we))
                    if (main) e += 0.22f * f * exp(-d * d / (2f * wg * wg))
                }
                if (e < 0.002f) continue
                val p =
                    if (shadow) {
                        val al = (min(1f, e * 0.9f) * 255f).roundToInt()
                        al shl 24
                    } else {
                        // white near the core, the light's tint towards the tips
                        val m = 0.2f + 0.8f * smooth(rad / len, 0.04f, 0.5f)
                        val cr = min(1f, e * (1f + (tr - 1f) * m))
                        val cg = min(1f, e * (1f + (tg - 1f) * m))
                        val cb = min(1f, e * (1f + (tb - 1f) * m))
                        val al = max(cr, max(cg, cb))
                        val ai = (al * 255f).roundToInt()
                        if (ai == 0) 0 else (ai shl 24) or ((cr / al * 255f).roundToInt() shl 16) or ((cg / al * 255f).roundToInt() shl 8) or (cb / al * 255f).roundToInt()
                    }
                out[j * n + i] = p
                out[(n - 1 - j) * n + (n - 1 - i)] = p
            }
        }
        return out
    }

    private fun smooth(v: Float, a: Float, b: Float): Float {
        val t = ((v - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/**
 * The glints' pictures ([GlintPsf]) by size, made once and kept (sizes on a ladder 9 percent apart, so a poster needs only a few, and a
 * dragged card or a moved slider reuses them), at most about 6 MB. One per renderer (not thread-safe).
 */
internal class GlintSprites {
    private data class Key(val step: Int, val tint: Int, val shadow: Boolean, val bold: Boolean)

    private val cache = LinkedHashMap<Key, Bitmap>(16, 0.75f, true)
    private var pixels = 0L
    private val light = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val over = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** How many pictures were made (a test checks that a dragged card makes none). */
    var made = 0
        private set

    private fun length(step: Int): Float = BASE * STEP.pow(step)

    private fun stepFor(len: Float): Int = (ln(max(len, BASE) / BASE) / ln(STEP)).roundToInt().coerceIn(0, MAX_STEP)

    private fun sprite(step: Int, tint: Color, shadow: Boolean, bold: Boolean): Bitmap {
        val key = Key(step, if (shadow) 0 else tint.hashCode(), shadow, bold && !shadow)
        cache[key]?.takeIf { !it.isRecycled }?.let { return it }
        val len = length(step)
        val n = 2 * GlintPsf.radius(len) + 1
        val bmp = Bitmap.createBitmap(GlintPsf.pixels(len, tint, shadow, bold), n, n, Bitmap.Config.ARGB_8888)
        cache[key] = bmp
        pixels += n.toLong() * n
        made++
        val it = cache.entries.iterator()
        while (pixels > BUDGET && cache.size > 1 && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            pixels -= e.value.width.toLong() * e.value.height
            it.remove()
        }
        return bmp
    }

    /**
     * Lays one glint on [canvas] at ([cx], [cy]) in the canvas's units: main spikes [len] units long, [unit] device pixels per unit, [intensity]
     * 0..1, turned by [rotation] degrees. The light is ADDED; [shadow] > 0 first lays the soft dark under-light ("Kilau" sticker on a bright
     * ground) with that opacity. [plain] = laid with source-over instead of added (a dark ground in the editor's button, no blend mode needed).
     * [bold] = the sticker's wider spikes.
     */
    fun draw(canvas: android.graphics.Canvas, cx: Float, cy: Float, len: Float, unit: Float, intensity: Float, tint: Color, rotation: Float = 0f, shadow: Float = 0f, plain: Boolean = false, bold: Boolean = false) {
        val i = intensity.coerceIn(0f, 1f)
        if (i <= 0.004f || len * unit < 2f) return
        val dev = len * unit
        val step = stepFor(dev)
        canvas.save()
        if (rotation != 0f) canvas.rotate(rotation, cx, cy)
        if (shadow > 0.004f) {
            val sb = sprite(step, tint, true, false)
            put(canvas, sb, cx, cy, dev / length(step) / unit)
            over.alpha = (shadow.coerceIn(0f, 1f) * 255f).roundToInt()
            canvas.drawBitmap(sb, null, rect, over)
        }
        val sp = sprite(step, tint, false, bold)
        put(canvas, sp, cx, cy, dev / length(step) / unit)
        val p = if (plain) over else light
        p.alpha = (i * 255f).roundToInt()
        canvas.drawBitmap(sp, null, rect, p)
        canvas.restore()
    }

    private fun put(canvas: android.graphics.Canvas, b: Bitmap, cx: Float, cy: Float, k: Float) {
        val half = b.width / 2f * k
        rect.set(cx - half, cy - half, cx + half, cy + half)
    }

    fun release() {
        cache.values.forEach { it.recycle() }
        cache.clear()
        pixels = 0
    }

    private companion object {
        const val BASE = 6f
        const val STEP = 1.09f

        /** The largest picture made: about 205 px spikes; a larger glint (a big sticker) is this one scaled up. */
        const val MAX_STEP = 41
        const val BUDGET = 1_500_000L
    }
}
