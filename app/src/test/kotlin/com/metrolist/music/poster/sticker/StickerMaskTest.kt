package com.metrolist.music.poster.sticker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Round 4.3.5, the sticker editor's pixel rules (pure JVM): the way "Hapus latar" picks, the colour fill from the border (edge-connected,
 * soft edge, gradients followed, holes kept, specks dropped), the brush strokes, the undo history and the finished sticker.
 */
class StickerMaskTest {
    private fun rgb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** Deterministic small noise (a JPEG's grain on a flat background). */
    private fun noise(i: Int, amp: Int): Int {
        var h = i * 374761393
        h = (h xor (h ushr 13)) * 1274126177
        return ((h ushr 24) and 255) * (2 * amp + 1) / 256 - amp
    }

    private fun a(mask: ByteArray, w: Int, x: Int, y: Int) = mask[y * w + x].toInt() and 255

    /** A logo: a red disc (anti-aliased) with a white hole in the middle, on a slightly noisy white background, plus one dark speck far away. */
    private fun logo(w: Int = 200, h: Int = 150): IntArray {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val d = hypot(x + 0.5f - 100f, y + 0.5f - 75f)
            val cover = (40.5f - d).coerceIn(0f, 1f)
            val hole = d < 10f
            val n = noise(i, 4)
            val bgv = (250 + n).coerceIn(0, 255)
            px[i] =
                if (hole) {
                    rgb(bgv, bgv, bgv)
                } else {
                    val r = (220 * cover + bgv * (1 - cover)).toInt()
                    val g = (30 * cover + bgv * (1 - cover)).toInt()
                    val b = (40 * cover + bgv * (1 - cover)).toInt()
                    rgb(r, g, b)
                }
        }
        // a 2 x 2 dark speck in the background (dust): not a sticker part
        for (y in 20..21) for (x in 20..21) px[y * w + x] = rgb(60, 60, 60)
        return px
    }

    @Test
    fun method_flatBorderIsColour_photoIsPerson_transparentIsKept() {
        assertEquals(StickerMethod.COLOR, StickerMask.chooseMethod(logo(), 200, 150))
        // a photo: every border pixel a different colour
        val photo = IntArray(120 * 90) { rgb((it * 37) and 255, (it * 91) and 255, (it * 53) and 255) }
        assertEquals(StickerMethod.PERSON, StickerMask.chooseMethod(photo, 120, 90))
        // a PNG already cut: see-through around
        val png = logo().map { p -> if ((p and 0xFFFFFF) == rgb(250, 250, 250) and 0xFFFFFF || ((p shr 16) and 255) > 240) p and 0x00FFFFFF else p }.toIntArray()
        assertTrue(StickerMask.hasTransparency(png, 200, 150))
        assertEquals(StickerMethod.KEEP, StickerMask.chooseMethod(png, 200, 150))
        assertFalse(StickerMask.hasTransparency(logo(), 200, 150))
    }

    @Test
    fun borderPalette_findsTheBackground() {
        val share = FloatArray(1)
        val pal = StickerMask.borderPalette(logo(), 200, 150, share)
        assertTrue(pal.isNotEmpty())
        val c = pal[0]
        assertTrue("background ${Integer.toHexString(c)}", ((c shr 16) and 255) > 240 && (c and 255) > 240)
        assertTrue(share[0] > 0.95f)
    }

    @Test
    fun colourCut_takesTheBorderConnectedBackground_keepsTheObjectAndItsHole() {
        val w = 200
        val h = 150
        val m = StickerMask.colorCut(logo(), w, h, 0.35f, 0f)
        // the corners and the background around: gone
        for ((x, y) in listOf(0 to 0, 199 to 149, 5 to 75, 150 to 10)) assertEquals("($x, $y)", 0, a(m, w, x, y))
        // the disc: kept
        for ((x, y) in listOf(100 to 45, 130 to 75, 75 to 90)) assertEquals("($x, $y)", 255, a(m, w, x, y))
        // the white hole inside the disc is NOT connected to the border: it stays (an edge fill never eats an enclosed area)
        assertEquals(255, a(m, w, 100, 75))
        // the dust speck in the background: dropped
        assertEquals(0, a(m, w, 20, 20))
        // the anti-aliased rim: some pixels in between (a soft edge, no jagged cut)
        var soft = 0
        for (y in 0 until h) for (x in 0 until w) if (a(m, w, x, y) in 1..254) soft++
        assertTrue("soft edge pixels $soft", soft > 20)
    }

    @Test
    fun colourCut_followsAGradientBackground() {
        val w = 160
        val h = 100
        // a backdrop that goes from light grey to a cooler darker grey across the picture, a dark square in the middle
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x in 60..99 && y in 30..69) {
                rgb(30, 35, 40)
            } else {
                val t = x / (w - 1f)
                rgb((235 - 60 * t).toInt(), (235 - 45 * t).toInt(), (238 - 25 * t).toInt())
            }
        }
        val m = StickerMask.colorCut(px, w, h, 0.1f, 0f)
        assertEquals(0, a(m, w, 2, 50))
        assertEquals("the far end of the gradient is reached step by step", 0, a(m, w, 157, 50))
        assertEquals(255, a(m, w, 80, 50))
    }

    @Test
    fun colourCut_keepsThePicturesOwnAlpha_andSoftenNeverGrows() {
        val w = 200
        val h = 150
        val px = logo(w, h)
        for (i in 0 until w) px[i] = px[i] and 0x00FFFFFF
        val m = StickerMask.colorCut(px, w, h, 0.35f, 0.6f)
        assertEquals(0, a(m, w, 50, 0))
        val hard = StickerMask.colorCut(px, w, h, 0.35f, 0f)
        for (i in m.indices) assertTrue("soften made pixel $i stronger", (m[i].toInt() and 255) <= (hard[i].toInt() and 255))
        assertTrue(m.indices.count { (m[it].toInt() and 255) != (hard[it].toInt() and 255) } > 50)
    }

    @Test
    fun colourCut_stopsWhenCancelled() {
        // cancelled at once: nothing (or almost nothing) is taken, and it returns
        val m = StickerMask.colorCut(logo(), 200, 150, 0.35f, 0f) { true }
        assertNotNull(m)
    }

    // ---------------------------------------------------------------- the brush

    @Test
    fun erase_hardBrush_clearsTheDisc_andNothingOutside() {
        val w = 64
        val h = 64
        val mask = ByteArray(w * h) { 255.toByte() }
        val orig = mask.copyOf()
        val s = StickerStroke(w, h, mask, orig, erase = true, radius = 10f, softness = 0f)
        s.to(32f, 32f)
        assertEquals(0, a(mask, w, 32, 32))
        assertEquals(0, a(mask, w, 38, 32))
        assertEquals(255, a(mask, w, 44, 32))
        assertEquals(255, a(mask, w, 0, 0))
        // its weight: a one-pixel smooth rim only
        assertEquals(1f, s.weight(8.9f), 0f)
        assertEquals(0f, s.weight(10f), 0f)
    }

    @Test
    fun softBrush_fallsOffSmoothly_andGoingOverAgainDoesNotStack() {
        val w = 80
        val h = 40
        val mask = ByteArray(w * h) { 255.toByte() }
        val s = StickerStroke(w, h, mask, mask.copyOf(), erase = true, radius = 12f, softness = 1f)
        s.to(40f, 20f)
        val mid = a(mask, w, 46, 20)
        assertTrue("half way $mid", mid in 40..215)
        // back and forth over the same spot in one stroke: no stronger than the strongest dab
        val once = mask.copyOf()
        s.to(41f, 20f)
        s.to(40f, 20f)
        s.to(41f, 20f)
        for (x in 30..50) assertTrue(a(mask, w, x, 20) >= (once[20 * w + x].toInt() and 255) - 64)
        // the touched box covers the dabs
        assertTrue(s.touched && s.x0 <= 28 && s.x1 >= 53)
    }

    @Test
    fun restore_bringsBackThePicturesOwnAlpha_notMore() {
        val w = 40
        val h = 40
        val orig = ByteArray(w * h) { i -> if (i % w < 20) 255.toByte() else 100.toByte() }
        val mask = ByteArray(w * h)
        val s = StickerStroke(w, h, mask, orig, erase = false, radius = 30f, softness = 0f)
        s.to(20f, 20f)
        assertEquals(255, a(mask, w, 10, 20))
        assertEquals(100, a(mask, w, 30, 20))
    }

    @Test
    fun stroke_followsTheFinger_andCancelPutsEverythingBack() {
        val w = 120
        val h = 40
        val mask = ByteArray(w * h) { 255.toByte() }
        val before = mask.copyOf()
        val s = StickerStroke(w, h, mask, before.copyOf(), erase = true, radius = 4f, softness = 0f)
        s.to(10f, 20f)
        s.to(110f, 20f)
        // a continuous line: every pixel on the way is erased (dabs a quarter radius apart)
        for (x in 10..110) assertEquals("x $x", 0, a(mask, w, x, 20))
        assertNotNull(s.cancel())
        assertArrayEquals(before, mask)
    }

    // ---------------------------------------------------------------- undo and redo

    @Test
    fun history_undoRedo_exactly_andANewStepDropsTheRedo() {
        val w = 50
        val h = 50
        val mask = ByteArray(w * h) { 255.toByte() }
        val orig = mask.copyOf()
        val hist = StickerHistory()
        val m0 = mask.copyOf()
        val s1 = StickerStroke(w, h, mask, orig, true, 6f, 0.3f)
        s1.to(10f, 10f)
        s1.to(30f, 30f)
        hist.push(s1.patch()!!)
        val m1 = mask.copyOf()
        val s2 = StickerStroke(w, h, mask, orig, true, 5f, 0f)
        s2.to(40f, 10f)
        hist.push(s2.patch()!!)
        val m2 = mask.copyOf()
        assertTrue(hist.canUndo)
        assertNotNull(hist.undo(mask, w))
        assertArrayEquals(m1, mask)
        hist.undo(mask, w)
        assertArrayEquals(m0, mask)
        assertNull(hist.undo(mask, w))
        hist.redo(mask, w)
        assertArrayEquals(m1, mask)
        assertTrue(hist.canRedo)
        // a new step after an undo: the redo is gone
        val s3 = StickerStroke(w, h, mask, orig, false, 5f, 0f)
        s3.to(20f, 20f)
        hist.push(s3.patch()!!)
        assertFalse(hist.canRedo)
        assertTrue(!m2.contentEquals(mask))
    }

    @Test
    fun history_isBounded_andAWholeMaskStepIsSmall() {
        val w = 1024
        val h = 1024
        val a = ByteArray(w * h)
        val b = ByteArray(w * h) { i -> if (hypot((i % w - 512).toFloat(), (i / w - 512).toFloat()) < 300f) 255.toByte() else 0 }
        val p = StickerPatch.of(a, b, w, 0, 0, w, h)
        assertTrue("a whole 1024 x 1024 step is ${p.bytes} bytes", p.bytes < 96 * 1024)
        val hist = StickerHistory(maxSteps = 5)
        repeat(9) { hist.push(StickerPatch.of(a, b, w, 0, 0, w, h)) }
        assertEquals(5, hist.size)
        val small = StickerHistory(maxSteps = 100, maxBytes = p.bytes * 3)
        repeat(10) { small.push(StickerPatch.of(a, b, w, 0, 0, w, h)) }
        assertTrue(small.totalBytes <= p.bytes * 3)
        // and a patch brings the bytes back exactly
        val m = a.copyOf()
        p.redo(m, w)
        assertArrayEquals(b, m)
        p.undo(m, w)
        assertArrayEquals(a, m)
    }

    // ---------------------------------------------------------------- the finished sticker

    @Test
    fun bounds_andCompose_cropAndCleanTheEdge() {
        val w = 40
        val h = 30
        val mask = ByteArray(w * h)
        for (y in 10..19) for (x in 12..27) mask[y * w + x] = 255.toByte()
        // a soft edge column with the old white background mixed in
        for (y in 10..19) mask[y * w + 28] = 128.toByte()
        val px = IntArray(w * h) { i -> if (i % w == 28) rgb(240, 128, 128) else rgb(255, 0, 0) }
        assertNull(StickerMask.bounds(ByteArray(w * h), w, h))
        val box = StickerMask.bounds(mask, w, h, margin = 2)!!
        assertArrayEquals(intArrayOf(10, 8, 31, 22), box)
        val out = StickerMask.compose(px, mask, w, h, box)
        val bw = box[2] - box[0]
        val solid = out[(15 - box[1]) * bw + (20 - box[0])]
        assertEquals(255, solid ushr 24)
        val edge = out[(15 - box[1]) * bw + (28 - box[0])]
        assertEquals(128, edge ushr 24)
        // the edge pixel's colour is pulled towards the solid red next to it (no pale fringe)
        assertTrue("edge green ${(edge shr 8) and 255}", ((edge shr 8) and 255) < 64)
        assertEquals(0, out[0])
    }

    @Test
    fun specks_belowTheSizeAreDropped_biggerPartsStay() {
        val w = 30
        val h = 30
        val a = ByteArray(w * h)
        for (y in 2..3) for (x in 2..3) a[y * w + x] = 255.toByte()
        for (y in 10..25) for (x in 10..25) a[y * w + x] = 255.toByte()
        StickerMask.dropSpecks(a, w, h, 24, IntArray(w * h), ByteArray(w * h))
        assertEquals(0, a[2 * w + 2].toInt() and 255)
        assertEquals(255, a[15 * w + 15].toInt() and 255)
    }
}
