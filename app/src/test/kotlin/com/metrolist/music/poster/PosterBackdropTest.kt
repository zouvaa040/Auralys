package com.metrolist.music.poster

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Lead review 2026-11-13: the no-photo backdrop went a muddy brown with several different covers. Its tone now comes from the one hue the
 * covers share most (pixels weighted by colourfulness, the first card wins a tie); a warm hue becomes a deep oxblood, colourless covers the
 * neutral charcoal-blue. Pure JVM.
 */
class PosterBackdropTest {
    private fun argb(c: Long) = c.toInt()

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 255) * (1 - t) + ((b shr s) and 255) * t).toInt()
        return (255 shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** A 48 x 48 cover: a vertical gradient through [stops] and, optionally, a disc of [accent] in the middle (as album art often is). */
    private fun cover(vararg stops: Long, accent: Long? = null): IntArray {
        val n = 48
        val px = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val t = y / (n - 1f) * (stops.size - 1)
            val i = t.toInt().coerceAtMost(stops.size - 2)
            var c = mix(argb(stops[i]), argb(stops[i + 1]), t - i)
            if (accent != null && hypot(x - 24f, y - 24f) < 12f) c = argb(accent)
            px[y * n + x] = c
        }
        return px
    }

    /** The five covers of the gallery's sample poster: orange-pink-purple, teal, night with an orange horizon, slate, pink-purple. */
    private val mixed =
        listOf(
            cover(0xFFFFB347, 0xFFD94A6A, 0xFF6A2C70, accent = 0xFFFFE9C7),
            cover(0xFF22C7B8, 0xFF0F5C8C, 0xFF071E45, accent = 0xFFFFFFFF),
            cover(0xFF0B1230, 0xFF30204F, 0xFFE0713F),
            cover(0xFF8FA6BF, 0xFF3B4C66, accent = 0xFF1B2433),
            cover(0xFFFF9BC6, 0xFF6E4BD8, accent = 0xFFFFE3F1),
        )

    private fun weights(n: Int) = List(n) { if (it == 0) 1.15f else 1f }

    private fun tones(covers: List<IntArray>) = PosterFx.backdropTones(PosterFx.dominantHue(covers, weights(covers.size)))

    /** True for a low-saturation orange-brown (the "mud" the lead saw). */
    private fun muddy(c: Color): Boolean {
        val k = PosterFx.hsl(c)
        return k.h in 20f..45f && k.s < 0.35f
    }

    @Test
    fun fiveMixedCovers_doNotMakeAMuddyBrown() {
        val d = PosterFx.dominantHue(mixed, weights(5))
        println("mixed covers: dominant $d")
        val (dark, lit) = tones(mixed)
        println("mixed covers: dark ${PosterFx.hsl(dark).let { "${it.h} ${it.s} ${it.l}" }} lit ${PosterFx.hsl(lit).let { "${it.h} ${it.s} ${it.l}" }}")
        assertTrue("dark is mud", !muddy(dark))
        assertTrue("lit is mud", !muddy(lit))
        // and it stays deep and quiet
        assertTrue(PosterFx.hsl(lit).l in 0.2f..0.3f)
        assertTrue(PosterFx.hsl(dark).l < 0.1f)
    }

    @Test
    fun allGreyCovers_giveTheNeutralCharcoalBlue() {
        val greys = listOf(cover(0xFF101010, 0xFF808080), cover(0xFFFFFFFF, 0xFFC8C8C8, accent = 0xFF000000), cover(0xFF3A3A3C, 0xFF2A2A2C), cover(0xFF9A9A9A, 0xFF5A5A5A))
        assertNull(PosterFx.dominantHue(greys, weights(4)))
        val (dark, lit) = tones(greys)
        for (c in listOf(dark, lit)) {
            val k = PosterFx.hsl(c)
            assertTrue("hue ${k.h}", abs(k.h - PosterFx.NEUTRAL_HUE) < 10f)
            assertTrue("sat ${k.s}", k.s <= 0.2f)
        }
        // no covers at all (not loaded yet): the same neutral
        assertNull(PosterFx.dominantHue(emptyList(), emptyList()))
    }

    @Test
    fun allBlueCovers_giveABlueBackdrop() {
        val blues = listOf(cover(0xFF0B3D91, 0xFF1E88E5), cover(0xFF4FC3F7, 0xFF01579B, accent = 0xFFFFFFFF), cover(0xFF283593, 0xFF5C6BC0), cover(0xFF1565C0, 0xFF0D47A1))
        val d = PosterFx.dominantHue(blues, weights(4))!!
        assertTrue("hue ${d.hue}", d.hue in 195f..240f)
        val k = PosterFx.hsl(tones(blues).second)
        assertTrue("hue ${k.h}", k.h in 195f..240f)
    }

    @Test
    fun aWarmDominantHue_becomesADeepOxblood_notMud() {
        val orange = listOf(cover(0xFFFF8A2B, 0xFFE0713F, 0xFFB5481C))
        val d = PosterFx.dominantHue(orange, weights(1))!!
        assertTrue("dominant ${d.hue}", d.hue in 12f..40f)
        val (dark, lit) = tones(orange)
        for (c in listOf(dark, lit)) {
            val k = PosterFx.hsl(c)
            assertTrue("hue ${k.h}", k.h < 20f || k.h > 340f)
            assertTrue("sat ${k.s}", k.s >= 0.3f)
        }
    }

    @Test
    fun mixedGreenAndRed_takesOneHue_neverTheirMuddyMean() {
        val gr = listOf(cover(0xFF1B5E20, 0xFF43A047), cover(0xFFC62828, 0xFFE53935), cover(0xFF2E7D32, 0xFF66BB6A), cover(0xFFB71C1C, 0xFFEF5350))
        val d = PosterFx.dominantHue(gr, weights(4))!!
        // green (about 120) or red (about 0): one of the covers' own hues, never the olive/brown in between
        assertTrue("hue ${d.hue}", d.hue in 95f..145f || d.hue < 15f || d.hue > 345f)
        assertTrue(!muddy(tones(gr).second))
    }

    @Test
    fun aTie_goesToTheFirstCard_andTheSameCoversGiveTheSameHue() {
        // two covers exactly as colourful (the same chroma), red and blue
        val red = cover(0xFFD32F2F, 0xFFD32F2F)
        val blue = cover(0xFF2F2FD3, 0xFF2F2FD3)
        val a = PosterFx.dominantHue(listOf(red, blue), weights(2))!!
        val b = PosterFx.dominantHue(listOf(blue, red), weights(2))!!
        assertTrue("red first: ${a.hue}", a.hue < 15f || a.hue > 345f)
        assertTrue("blue first: ${b.hue}", b.hue in 230f..250f)
        assertEquals(a, PosterFx.dominantHue(listOf(red, blue), weights(2)))
    }
}
