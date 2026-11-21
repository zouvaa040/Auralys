package com.metrolist.music.poster

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Round 4.3.5 ("no AI look"), pure JVM: the grades are real tone curves (monotonic, smooth, lifted blacks, a soft shoulder, the split toning
 * in the right direction), the light field (vignette, key light) and its dither, the luminance grain, and the glints ("Kilau", round K: the
 * photo's real isolated specular highlights, never a lit area; the cards' lit edges; how many, how large, never into a blocked place; the
 * star filter's picture).
 */
class PosterFinishTest {
    private fun grey(v: Int) = (255 shl 24) or (v shl 16) or (v shl 8) or v

    // ---------------------------------------------------------------- grades

    @Test
    fun original_hasNoCurves() {
        assertNull(PosterTone.spec(PosterGrade.ASLI))
        assertNull(PosterTone.params(PosterGrade.ASLI))
    }

    @Test
    fun everyGrade_isMonotonicSmooth_andKeepsMidGrey() {
        for (g in PosterGrade.entries) {
            val t = PosterTone.spec(g) ?: continue
            for ((name, lut) in listOf("r" to t.r, "g" to t.g, "b" to t.b)) {
                for (v in 1..255) {
                    val step = lut[v] - lut[v - 1]
                    assertTrue("$g $name not monotonic at $v", step >= 0)
                    // never a jump: at most about 2.5 output steps per input step (x 16 units)
                    assertTrue("$g $name jumps $step at $v", step <= 40)
                }
                assertTrue("$g $name white clips: ${lut[255]}", lut[255] <= 4080)
            }
            // a mid grey stays about mid grey (a grade shapes the tones, it does not wash the picture lighter or darker)
            val mid = (t.r[128] + t.g[128] + t.b[128]) / 3f / 16f
            assertTrue("$g moves mid grey to $mid", abs(mid - 128f) <= 10f)
            assertTrue("$g saturation ${t.sat}", t.sat in 200..300)
        }
    }

    @Test
    fun film_hasLiftedBlacks_aSoftShoulder_andCoolShadowsWarmHighlights() {
        val t = PosterTone.spec(PosterGrade.FILM)!!
        // black is a dark grey, not black; white stops under white
        for (lut in listOf(t.r, t.g, t.b)) {
            assertTrue("lifted black ${lut[0] / 16}", lut[0] / 16 >= 12)
            assertTrue("soft shoulder ${lut[255] / 16}", lut[255] / 16 <= 250)
        }
        // shadows lean blue-green, highlights lean warm
        assertTrue(t.b[10] > t.r[10])
        assertTrue(t.r[235] > t.b[235])
        // and it is soft: less contrast than the warm grade around the middle
        val warm = PosterTone.spec(PosterGrade.HANGAT)!!
        assertTrue(t.g[160] - t.g[96] < warm.g[160] - warm.g[96])
    }

    @Test
    fun warmAndCoolGrades_tintTheHighlightsTheRightWay() {
        val warm = PosterTone.spec(PosterGrade.HANGAT)!!
        assertTrue(warm.r[220] > warm.b[220])
        val cool = PosterTone.spec(PosterGrade.DINGIN)!!
        assertTrue(cool.b[220] > cool.r[220])
        // the S: shadows go a little down and highlights a little up around the middle (contrast), never a colour wash at mid grey
        assertTrue(warm.g[64] / 16 < 64 + 4)
        assertTrue(warm.g[192] / 16 > 192 - 4)
    }

    @Test
    fun curve_endpointsAreToeAndWhite() {
        assertEquals(0.05f, PosterTone.curve(0f, 0.2f, 1f, 0.05f, 0.95f), 1e-5f)
        assertEquals(0.95f, PosterTone.curve(1f, 0.2f, 1f, 0.05f, 0.95f), 1e-5f)
        assertEquals(0.5f, PosterTone.curve(0.5f, 0.3f, 1f, 0f, 1f), 1e-5f)
    }

    // ---------------------------------------------------------------- the finish pass

    @Test
    fun finish_withoutGradeOrField_isExactlyTheInput() {
        val w = 37
        val h = 23
        val px = IntArray(w * h) { (255 shl 24) or ((it * 7) and 255 shl 16) or ((it * 13) and 255 shl 8) or ((it * 3) and 255) }
        val copy = px.copyOf()
        PosterFinish.apply(px, w, h, null, null)
        assertArrayEquals(copy, px)
    }

    @Test
    fun field_vignetteDarkensTheCorners_naturally() {
        val f = PosterField.build(108, 192, 115f, 0f, PosterGrade.ASLI)
        val centre = PosterField.at(0.5f, 0.5f, 192f / 108f, 0f, 0f, 0f, floatArrayOf(1f, 0f, 0f))
        val corner = PosterField.at(0f, 0f, 192f / 108f, 0f, 0f, 0f, floatArrayOf(1f, 0f, 0f))
        assertEquals(1f, centre[0], 1e-4f)
        // about -0.4 EV in the corner: visible, never a black ring
        assertTrue("corner ${corner[0]}", corner[0] in 0.7f..0.8f)
        assertTrue(f.r.all { it in 2000..4200 })
    }

    @Test
    fun field_keyLightBrightensTheLitSide_andWarmsIt() {
        // shadows fall to the lower left (115 degrees): the light comes from the upper right
        val aspect = 1920f / 1080f
        val a = Math.toRadians(115.0)
        val lx = (-kotlin.math.cos(a)).toFloat()
        val ly = (-kotlin.math.sin(a)).toFloat()
        val wm = floatArrayOf(1f, 0.35f, -0.7f)
        val lit = PosterField.at(0.85f, 0.15f, aspect, lx, ly, 1f, wm)
        val far = PosterField.at(0.15f, 0.85f, aspect, lx, ly, 1f, wm)
        assertTrue("lit ${lit.toList()} far ${far.toList()}", lit[1] > far[1] * 1.25f)
        // warmer on the lit side, cooler on the far side
        assertTrue(lit[0] / lit[2] > far[0] / far[2])
        // with no key light the two sides are alike (the vignette is symmetric)
        val l0 = PosterField.at(0.85f, 0.15f, aspect, lx, ly, 0f, wm)
        val f0 = PosterField.at(0.15f, 0.15f, aspect, lx, ly, 0f, wm)
        assertEquals(l0[1], f0[1], 1e-5f)
    }

    @Test
    fun finish_dithersWithoutBias_soSmoothTonesNeverBand() {
        val w = 216
        val h = 384
        val px = IntArray(w * h) { grey(128) }
        // a flat light of 0.7: 128 x 0.7 = 89.6 is between two levels
        val cell = 16
        val nx = w / cell + 2
        val ny = h / cell + 2
        val k = 2867
        val field = LightField(w, h, cell, nx, ny, IntArray(nx * ny) { k }, IntArray(nx * ny) { k }, IntArray(nx * ny) { k })
        PosterFinish.apply(px, w, h, null, field)
        var sum = 0L
        val levels = HashSet<Int>()
        for (p in px) {
            val g = (p shr 8) and 255
            sum += g
            levels.add(g)
        }
        val expected = 128f * k / 4096f
        // the mean is the exact fractional value (no rounding bias), spread over the two neighbouring levels: a smooth tone never bands
        assertTrue("mean ${sum.toFloat() / px.size} vs $expected", abs(sum.toFloat() / px.size - expected) < 0.05f)
        assertEquals(setOf(89, 90), levels)
    }

    @Test
    fun grain_isLuminanceOnly_meanPreserving_andSparesBlackAndWhite() {
        val w = 256
        val h = 256
        val px = IntArray(w * h) { if (it < w * 8) grey(0) else if (it < w * 16) grey(255) else grey(128) }
        PosterFinish.grain(px, w, h, 1f, 1f)
        var sum = 0L
        var n = 0
        var varied = 0
        for (i in w * 16 until w * h) {
            val p = px[i]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            assertTrue("grain made colour at $i", r == g && g == b)
            sum += g
            n++
            if (g != 128) varied++
        }
        assertTrue("mean ${sum.toFloat() / n}", abs(sum.toFloat() / n - 128f) < 0.5f)
        assertTrue(varied > n / 2)
        for (i in 0 until w * 8) assertEquals(grey(0), px[i])
        for (i in w * 8 until w * 16) assertEquals(grey(255), px[i])
        // amount 0 changes nothing
        val q = IntArray(64) { grey(100) }
        PosterFinish.grain(q, 8, 8, 0f, 1f)
        assertTrue(q.all { it == grey(100) })
    }

    // ---------------------------------------------------------------- the photo's light

    /** A person (an ellipse) whose shading falls off from [litLeft] side to the other; the clothes change colour by rows (the probe must not care). */
    private fun lit(litLeft: Boolean?, w: Int = 200, h: Int = 320): PosterPhoto {
        val px = IntArray(w * h)
        val a = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x - w / 2f) / (w * 0.35f)
            val dy = (y - h / 2f) / (h * 0.45f)
            if (dx * dx + dy * dy > 1f) {
                px[y * w + x] = grey(90)
                continue
            }
            a[y * w + x] = 255.toByte()
            val albedo = if ((y / 40) % 2 == 0) 1f else 0.55f
            val t = (x - w * 0.15f) / (w * 0.7f)
            val shade = when (litLeft) {
                true -> 1f - 0.5f * t
                false -> 0.5f + 0.5f * t
                null -> 0.8f
            }
            px[y * w + x] = grey((230 * albedo * shade).toInt().coerceIn(0, 255))
        }
        return PosterPhoto(PosterImage(w, h, px), a)
    }

    @Test
    fun lightProbe_findsTheLitSide_andTurnsTheShadowAway() {
        val left = PosterLightProbe.side(lit(true))!!
        val right = PosterLightProbe.side(lit(false))!!
        assertTrue("left $left", left > 0.1f)
        assertTrue("right $right", right < -0.1f)
        // lit from the left: the shadows fall to the lower right (under 90 degrees); from the right: to the lower left
        assertTrue(PosterLightProbe.shadowAngle(left) < 60f)
        assertTrue(PosterLightProbe.shadowAngle(right) > 120f)
        assertEquals(90f, PosterLightProbe.shadowAngle(0f), 0f)
        // frontal light or no person: nothing changes
        assertNull(PosterLightProbe.side(lit(null)))
        assertNull(PosterLightProbe.side(PosterPhoto(PosterImage(10, 10, IntArray(100)), ByteArray(100))))
    }

    // ---------------------------------------------------------------- glints ("Kilau", round 4.3.5 K)

    /** A dark photo of exactly the poster's size (1 photo pixel = 1 poster pixel) with white dots, a white square and a thin white line. */
    private fun photo(dots: List<Pair<Int, Int>>, square: Boolean = true, line: Boolean = true, dotValue: Int = 255): PosterImage {
        val w = POSTER_W
        val h = POSTER_H
        val px = IntArray(w * h) { grey(40) }
        fun set(x: Int, y: Int, v: Int) {
            if (x in 0 until w && y in 0 until h) px[y * w + x] = grey(v)
        }
        for ((cx, cy) in dots) for (dy in -1..1) for (dx in -1..1) set(cx + dx, cy + dy, dotValue)
        if (square) for (y in 700 until 780) for (x in 540 until 620) set(x, y, 255)
        if (line) for (x in 100 until 400) for (y in 1300..1301) set(x, y, 255)
        return PosterImage(w, h, px)
    }

    @Test
    fun photoSpots_onIsolatedSpecularSpots_neverOnALitArea() {
        val spots = PosterGlints.photoSpots(photo(listOf(200 to 300, 800 to 1100)))
        println("photo spots: ${spots.map { "(${it.x}, ${it.y}) ${it.strength}" }}")
        for ((x, y) in listOf(200f to 300f, 800f to 1100f)) assertTrue("no spot near $x,$y", spots.any { hypot(it.x - x, it.y - y) < 6f })
        // the lit square is an area, never a spot (not even its corners)
        assertTrue(spots.none { it.x in 520f..640f && it.y in 680f..800f })
        // a thin line may give at most its ends (a strand of hair in the back light), never its middle
        assertTrue(spots.none { it.y in 1290f..1310f && it.x in 130f..370f })
        assertTrue(spots.filter { hypot(it.x - 200f, it.y - 300f) < 6f || hypot(it.x - 800f, it.y - 1100f) < 6f }.all { it.strength in 0.2f..1f })
        // a grey spot is not a specular highlight
        assertTrue(PosterGlints.photoSpots(photo(listOf(200 to 300), square = false, line = false, dotValue = 190)).isEmpty())
        // the same photo gives the same spots
        val again = PosterGlints.photoSpots(photo(listOf(200 to 300, 800 to 1100)))
        assertEquals(spots.map { Triple(it.x, it.y, it.strength) }, again.map { Triple(it.x, it.y, it.strength) })
    }

    @Test
    fun cardEdges_theCornerFacingTheLightLeads_andALaterCardHidesIt() {
        // shadows fall to the lower left (115 degrees): the light comes from the upper right
        val flat = CardPose(540f, 900f)
        val c = PosterGlints.cardCandidates(listOf(flat), PosterGeometry.CAMERA, 48f, 115f, 0.5f, 0.6f)
        println("card candidates: ${c.map { "(${it.x.toInt()}, ${it.y.toInt()}) ${"%.2f".format(it.strength)}" }}")
        val best = c.maxByOrNull { it.strength }!!
        // a card facing the camera still catches the light on its corner that faces it (its lit hairline): the top right one
        assertTrue("best at ${best.x},${best.y}", best.x > 540f + 150f && best.y < 900f - 260f)
        assertTrue(best.strength > 0.5f)
        // it sits on the glass edge: on the card, not inside its face
        assertTrue(PosterGeometry.hit(flat, best.x, best.y, PosterGeometry.CAMERA, slop = 1f))
        assertTrue(!PosterGeometry.hit(flat.copy(scale = 1f - 28f / PosterCards.BASE_H), best.x, best.y, PosterGeometry.CAMERA))
        // nothing on the far (lower left) side
        assertTrue(c.none { it.x < 540f - 150f && it.y > 900f + 200f })
        // "Tepi kartu" lowers them, never removes them ("Kilau" is always visible when it is on)
        val dim = PosterGlints.cardCandidates(listOf(flat), PosterGeometry.CAMERA, 48f, 115f, 0.5f, 0f).maxOf { it.strength }
        assertTrue(dim > 0.2f && dim < best.strength)
        // a card drawn later over that corner hides it
        val cover = CardPose(best.x, best.y, scale = 0.6f)
        assertTrue(!PosterGlints.cardVisible(best, listOf(flat, cover), PosterGeometry.CAMERA))
        assertTrue(PosterGlints.cardVisible(best, listOf(flat), PosterGeometry.CAMERA))
        // a card turned a quarter has its own lit corner (the light is the poster's, not the card's)
        val turned = PosterGlints.cardCandidates(listOf(flat.copy(rotation = 90f)), PosterGeometry.CAMERA, 48f, 115f, 0.5f, 0.6f).maxByOrNull { it.strength }!!
        assertTrue("turned best at ${turned.x},${turned.y}", turned.x > 540f && turned.y < 900f)
    }

    /** Candidates on five "cards" (three each) and two photo highlights, spread over the poster. */
    private fun candidates(): List<Glint> {
        val out = ArrayList<Glint>()
        for (card in 0 until 5) for (k in 0 until 3) out.add(Glint(120f + card * 200f, 200f + k * 300f + card * 37f, 0.9f - k * 0.3f - card * 0.05f, GlintSource.CARD, card))
        out.add(Glint(300f, 1250f, 0.8f, GlintSource.PHOTO))
        out.add(Glint(800f, 1450f, 0.3f, GlintSource.PHOTO))
        return out
    }

    @Test
    fun place_countSizeAndEveryCard() {
        val none = { _: Float, _: Float -> false }
        val half = PosterGlints.place(candidates(), 0.5f, none)
        val full = PosterGlints.place(candidates(), 1f, none)
        val def = PosterGlints.place(candidates(), PosterFxDefaults.SPARKLE_BERSIH, none)
        println("place: 0.5 -> ${half.size}, 1.0 -> ${full.size}, default -> ${def.size}; lengths 1.0 ${full.map { it.length.toInt() }}")
        assertTrue("0.5 gives ${half.size}", half.size in 5..8)
        assertTrue("1.0 gives ${full.size}", full.size in 12..16)
        assertTrue(full.size > half.size)
        // every card catches the light at moderate amounts
        for (card in 0 until 5) assertTrue("card $card", def.any { it.card == card })
        // a few large heroes, more small ones; larger with more "Kilau"
        val heroes = full.filter { it.hero }
        assertTrue(heroes.size in 1..PosterGlints.heroesFor(1f))
        assertTrue(heroes.minOf { it.length } > full.filter { !it.hero }.maxOf { it.length })
        assertTrue(full.maxOf { it.length } > half.maxOf { it.length })
        // spaced
        for (a in full) for (b in full) if (a !== b) assertTrue(hypot(a.x - b.x, a.y - b.y) >= PosterGlints.spacingFor(1f))
        for (a in half) for (b in half) if (a !== b) assertTrue(hypot(a.x - b.x, a.y - b.y) >= PosterGlints.spacingFor(0.5f))
        assertTrue(PosterGlints.spacingFor(1f) < PosterGlints.spacingFor(0.5f))
        // off = none
        assertTrue(PosterGlints.place(candidates(), 0f, none).isEmpty())
    }

    @Test
    fun place_neverReachesABlockedPlace_andIsDeterministic() {
        // a "head" box and the mark's band
        val head = floatArrayOf(250f, 100f, 520f, 420f)
        val blocked = { x: Float, y: Float -> y > PosterGlints.MARK_BAND || (x >= head[0] && x <= head[2] && y >= head[1] && y <= head[3]) }
        val list = candidates() + Glint(380f, 250f, 1f, GlintSource.PHOTO) + Glint(600f, 300f, 0.95f, GlintSource.PHOTO) + Glint(540f, 1640f, 0.95f, GlintSource.PHOTO)
        val g = PosterGlints.place(list, 1f, blocked)
        assertTrue(g.none { blocked(it.x, it.y) })
        // no spike, no bloom of any glint touches a blocked place
        for (it in g) assertTrue("glint at ${it.x},${it.y} length ${it.length}", !PosterGlints.crosses(it.x, it.y, it.length, blocked))
        // the strong one next to the head was made shorter instead of dropped
        val near = g.firstOrNull { hypot(it.x - 600f, it.y - 300f) < 1f }
        assertTrue(near != null && near.length < PosterGlints.maxLength(1f))
        // the same candidates in any order give the same glints
        val again = PosterGlints.place(list.reversed(), 1f, blocked)
        assertEquals(g.map { listOf(it.x, it.y, it.length, it.intensity) }, again.map { listOf(it.x, it.y, it.length, it.intensity) })
    }

    @Test
    fun psf_isAStarFilter_withTaperingSpikes_andAWhiteCore() {
        val len = 100f
        val px = GlintPsf.pixels(len, PosterGlints.tint(PosterGrade.HANGAT), shadow = false)
        val r = GlintPsf.radius(len)
        val n = 2 * r + 1
        fun at(t: Float, deg: Float): Int {
            val a = Math.toRadians(deg.toDouble())
            val x = r + (t * kotlin.math.cos(a)).toFloat()
            val y = r + (t * kotlin.math.sin(a)).toFloat()
            return px[kotlin.math.round(y).toInt() * n + kotlin.math.round(x).toInt()]
        }
        fun alpha(p: Int) = (p ushr 24) and 255
        val main = PosterGlints.STAR_ANGLE
        // a white, solid core
        val core = px[r * n + r]
        assertEquals(255, alpha(core))
        assertTrue(((core shr 16) and 255) > 245 && (core and 255) > 245)
        // the main spike tapers and fades smoothly to its tip
        val a = listOf(10f, 25f, 45f, 70f, 92f).map { alpha(at(it, main)) }
        println("psf main spike alphas $a, secondary ${alpha(at(25f, main + 45f))}, between ${alpha(at(60f, main + 22.5f))}")
        for (i in 1 until a.size) assertTrue("not fading at $i: $a", a[i] < a[i - 1])
        assertTrue(a[1] > 60 && a.last() < 12)
        // the secondary spike is shorter and fainter, the dark between the spikes stays dark (no disc, no star shape)
        assertTrue(alpha(at(25f, main + 45f)) < a[1])
        assertTrue(alpha(at(60f, main + 45f)) < 6)
        assertTrue(alpha(at(60f, main + 22.5f)) < 4)
        // the same angle through the whole picture: every main spike at STAR_ANGLE + k x 90
        for (k in 0 until 4) assertTrue(alpha(at(45f, main + k * 90f)) > 25)
        // the tips take the light's colour (warm for HANGAT), the core stays white
        val tip = at(45f, main)
        assertTrue(((tip shr 16) and 255) > (tip and 255) + 20)
        // the sticker's under-light is black, alpha only
        val sh = GlintPsf.pixels(len, Color.White, shadow = true)
        assertEquals(0, sh[r * n + r] and 0xFFFFFF)
        assertTrue(alpha(sh[r * n + r]) > 200)
    }

    @Test
    fun streak_stopsShortOfThePersonAndTheHead_onEachSide() {
        // a person standing in x 480..720 from y 600 down, the head box 520..680 x 320..600 (grid cells of 16 poster px)
        val cell = SubjectGrid.CELL
        val gw = (POSTER_W + cell - 1) / cell
        val gh = (POSTER_H + cell - 1) / cell
        val cells = ByteArray(gw * gh)
        for (gy in 600 / cell until gh) for (gx in 480 / cell until 720 / cell) cells[gy * gw + gx] = 255.toByte()
        val grid = SubjectGrid(gw, gh, cells, floatArrayOf(520f, 320f, 680f, 600f))
        // a highlight left of the body: full reach to the left, stopped before the body on the right
        val g = Glint(200f, 900f, 0.9f)
        val (l, r) = PosterGlints.streakReach(g, grid, 400f)
        assertEquals(400f, l, 0f)
        assertTrue("right reach $r", r < 480f - 200f - 16f && r > 150f)
        // at the head's height: stopped before the head box
        val (_, rh) = PosterGlints.streakReach(Glint(200f, 400f, 0.9f), grid, 600f)
        assertTrue("right reach at the head $rh", 200f + rh < 520f)
        // nobody: the whole length both ways
        assertEquals(400f to 400f, PosterGlints.streakReach(g, null, 400f))
        assertNull(PosterGlints.streakSource(listOf(Glint(1f, 1f, 0.2f))))
        assertEquals(0.9f, PosterGlints.streakSource(listOf(Glint(1f, 1f, 0.2f), g))!!.strength, 0f)
    }
}
