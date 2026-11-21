package com.metrolist.music.poster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** The pure maths of the poster (ported from Windows 1.9.3): card projection, hit tests, layouts, sticker boxes and handles, touch rules. */
class PosterGeometryTest {
    private fun near(a: Float, b: Float, eps: Float = 0.05f) = assertTrue("$a vs $b", abs(a - b) <= eps)

    @Test
    fun flatCard_mapsItsPlaneOneToOne() {
        val p = CardPose(400f, 900f, 1f, 0f, 0f, 0f)
        val m = PosterGeometry.cardMatrix(p)
        val (x0, y0) = PosterGeometry.map(m, 0f, 0f)
        near(x0, 400f)
        near(y0, 900f)
        val (x1, y1) = PosterGeometry.map(m, 220f, 330f)
        near(x1, 620f)
        near(y1, 1230f)
    }

    @Test
    fun scaleAndRotation_moveTheCorners() {
        val p = CardPose(540f, 960f, 0.5f, 90f, 0f, 0f)
        val c = PosterGeometry.corners(p)
        // turned a quarter clockwise: the top left corner (-110, -165) lands at (+165, -110) from the centre
        near(c[0], 540f + 165f, 0.1f)
        near(c[1], 960f - 110f, 0.1f)
    }

    @Test
    fun invert_undoesTheProjection() {
        val p = CardPose(300f, 700f, 0.86f, -5f, 12f, 26f)
        val m = PosterGeometry.cardMatrix(p, 900f)
        val inv = PosterGeometry.invert(m)!!
        for ((u, v) in listOf(0f to 0f, 200f to -300f, -150f to 250f)) {
            val (x, y) = PosterGeometry.map(m, u, v)
            val (uu, vv) = PosterGeometry.map(inv, x, y)
            near(uu, u, 0.05f)
            near(vv, v, 0.05f)
        }
    }

    @Test
    fun anExtremePose_neverPutsACornerBehindTheCamera() {
        // review 2026-11-12 item 32: perspective 1 (camera 600), scale 1.8, tilt 60 / 45 used to turn a corner inside out (w < 0)
        val camera = PosterGeometry.cameraFor(1f)
        for (tiltY in listOf(-60f, 60f)) for (tiltX in listOf(-45f, 45f)) for (depth in listOf(0f, 30f)) {
            val m = PosterGeometry.cardMatrix(CardPose(540f, 960f, 1.8f, 0f, tiltX, tiltY), camera, depth)
            for (u in listOf(-260f, 260f)) for (v in listOf(-370f, 370f)) {
                val w = m[6] * u + m[7] * v + m[8]
                assertTrue("tilt $tiltY/$tiltX corner $u,$v: w $w", w > 0.25f)
            }
        }
        // an ordinary pose keeps its camera: the same matrix as with the camera passed in
        val p = CardPose(300f, 700f, 1f, -5f, 12f, 26f)
        val m = PosterGeometry.cardMatrix(p, 900f)
        val w = m[6] * 220f + m[7] * 330f + m[8]
        val th = Math.toRadians(26.0)
        val ph = Math.toRadians(12.0)
        val want = (Math.sin(th) * Math.cos(ph) * 220.0 - Math.sin(ph) * 330.0 + 900.0) / 900.0
        near(w, want.toFloat(), 1e-4f)
    }

    @Test
    fun tiltedCard_isForeshortenedOnTheFarSide() {
        // tiltY > 0 turns the right edge away from the viewer: it gets shorter than the left edge
        val c = PosterGeometry.corners(CardPose(540f, 960f, 1f, 0f, 0f, 30f))
        val left = c[7] - c[1]
        val right = c[5] - c[3]
        assertTrue("left $left right $right", right < left)
    }

    @Test
    fun hit_followsTheTiltAndTheRoundedCorners() {
        val p = CardPose(540f, 960f, 1f, 0f, 0f, 0f)
        assertTrue(PosterGeometry.hit(p, 540f, 960f))
        assertTrue(PosterGeometry.hit(p, 540f + 200f, 960f))
        assertFalse(PosterGeometry.hit(p, 540f + 230f, 960f))
        // the very corner is outside the rounded outline, a little inside it is in
        assertFalse(PosterGeometry.hit(p, 540f + 219f, 960f + 329f))
        assertTrue(PosterGeometry.hit(p, 540f + 180f, 960f + 300f))
        // the slop of a finger widens it
        assertTrue(PosterGeometry.hit(p, 540f + 230f, 960f, slop = 16f))
    }

    @Test
    fun hitCard_picksTheFrontMost() {
        val poses = listOf(CardPose(500f, 900f), CardPose(560f, 960f))
        assertEquals(1, PosterGeometry.hitCard(poses, 540f, 930f))
        assertEquals(0, PosterGeometry.hitCard(poses, 300f, 600f))
        assertEquals(-1, PosterGeometry.hitCard(poses, 30f, 30f))
    }

    @Test
    fun layouts_haveOnePosePerCard_andStayNearThePoster() {
        for (l in PosterLayout.entries) {
            for (n in 1..POSTER_MAX_SONGS) {
                val poses = PosterLayouts.poses(l, n)
                assertEquals("$l $n", n, poses.size)
                for (p in poses) {
                    assertTrue("$l $n x ${p.x}", p.x in -60f..(POSTER_W + 60f))
                    assertTrue("$l $n y ${p.y}", p.y in 0f..POSTER_H.toFloat())
                    assertTrue(p.scale in 0.3f..1.2f)
                }
            }
        }
    }

    @Test
    fun sliders_mapToTheirRanges() {
        near(PosterGeometry.cameraFor(0f), 3200f, 0.5f)
        near(PosterGeometry.cameraFor(1f), 600f, 0.5f)
        near(PosterGeometry.cornerFor(0.5f), 48f)
        near(PosterGeometry.thicknessFor(1f), 30f)
    }

    @Test
    fun cardNumbers_areStableAndReadable() {
        assertEquals("3:07", PosterGeometry.clock(187))
        assertEquals("0:00", PosterGeometry.clock(-5))
        for (id in listOf("a", "song-1", "xyzXYZ123")) {
            val p = PosterGeometry.progressOf(id)
            assertTrue(p in 0.2f..0.7f)
            assertEquals(p, PosterGeometry.progressOf(id))
        }
        assertEquals(213, PosterGeometry.shownDuration(PosterSong("x", "t", "a", null, 213)))
        val made = PosterGeometry.shownDuration(PosterSong("x", "t", "a", null, 0))
        assertTrue(made in 160..250)
    }

    @Test
    fun stickerBox_handle_andHit() {
        val s = PosterSticker(1, StickerKind.KILAU, 500f, 600f, scale = 1f)
        val (hx, hy) = PosterGeometry.stickerHandle(s)
        near(hx, 620f)
        near(hy, 720f)
        val flipped = s.copy(flip = true)
        val (fx, fy) = PosterGeometry.stickerHandle(flipped)
        near(fx, 620f)
        near(fy, 720f)
        assertTrue(PosterGeometry.hitSticker(s, 500f, 600f))
        assertFalse(PosterGeometry.hitSticker(s, 630f, 600f))
        // a wide custom picture is wider than tall
        val wide = PosterGeometry.stickerBounds(s.copy(kind = StickerKind.CUSTOM), aspect = 2f)
        near(wide[2] - wide[0], 240f)
        near(wide[7] - wide[1], 120f)
    }

    @Test
    fun draggingTheHandle_ontoItself_changesNothing() {
        for (a in listOf(1f, 2.5f, 0.4f)) {
            val s = PosterSticker(1, StickerKind.CUSTOM, 400f, 800f, scale = 1.3f, rotation = 25f)
            val (hx, hy) = PosterGeometry.stickerHandle(s, a)
            val d = PosterEdit.draggedHandle(s, hx, hy, a)
            near(d.scale, 1.3f, 0.01f)
            near(d.rotation, 25f, 0.1f)
        }
    }

    @Test
    fun pinch_sizesAndTurnsWithinLimits() {
        val p = CardPose(500f, 900f, 1f, 10f)
        val q = PosterEdit.pinchedCard(p, 20f, -10f, 1.5f, 5f)
        near(q.x, 520f)
        near(q.y, 890f)
        near(q.scale, 1.5f)
        near(q.rotation, 15f)
        assertEquals(PosterEdit.MAX_CARD_SCALE, PosterEdit.pinchedCard(p, 0f, 0f, 9f, 0f).scale)
        assertEquals(45f, PosterEdit.pinchedCard(p, 0f, 0f, 1f, 80f).rotation)
        val s = PosterEdit.pinchedSticker(PosterSticker(1, StickerKind.BURUNG, 10f, 10f), -50f, -50f, 100f, 200f)
        assertEquals(0f, s.x)
        assertEquals(PosterStickers.MAX_SCALE, s.scale)
        near(s.rotation, -160f)
    }

    @Test
    fun photoPinch_keepsThePointUnderTheFingers() {
        val w = 1500
        val h = 2000
        val (z, px, py) = PosterEdit.pinchedPhoto(w, h, 1f, 0f, 0f, 300f, 500f, 2f, 0f, 0f)
        assertEquals(2f, z)
        // the photo point under (300, 500) before the zoom is still there after it
        val before = PosterPhotoGeometry.toPhoto(300f, 500f, w, h, 1f, 0f, 0f)
        val after = PosterPhotoGeometry.toPhoto(300f, 500f, w, h, z, px, py)
        near(before.first, after.first, 0.5f)
        near(before.second, after.second, 0.5f)
        // never past the limits, never an empty edge at zoom >= 1
        val (z2, px2, _) = PosterEdit.pinchedPhoto(w, h, 1f, 0f, 0f, 540f, 960f, 1f, 5000f, 0f)
        assertEquals(1f, z2)
        val (mx, _) = PosterEdit.panLimit(w, h, 1f)
        near(px2, mx)
    }

    @Test
    fun fitZoom_showsTheWholePhoto() {
        // a landscape phone photo (4:3 and 16:9) fits whole; a panorama stops at the smallest zoom
        for ((w, h) in listOf(1600 to 1200, 1920 to 1080, 900 to 1600)) {
            val z = PosterEdit.fitZoom(w, h)
            val f = PosterPhotoGeometry.scale(w, h, z)
            assertTrue("$w x $h", w * f <= POSTER_W + 0.5f)
            assertTrue("$w x $h", h * f <= POSTER_H + 0.5f)
        }
        assertEquals(PosterEdit.MIN_PHOTO_ZOOM, PosterEdit.fitZoom(4000, 1000))
    }

    @Test
    fun normalizeDegrees_wraps() {
        near(PosterEdit.normalizeDegrees(190f), -170f)
        near(PosterEdit.normalizeDegrees(-190f), 170f)
        near(PosterEdit.normalizeDegrees(720f), 0f)
        assertTrue(hypot(1f, 1f) > 1f)
    }
}
