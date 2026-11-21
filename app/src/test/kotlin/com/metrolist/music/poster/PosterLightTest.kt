package com.metrolist.music.poster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 2026-11-12, finding 13: no light on the face. The person on a coarse grid and the head found in it (round 4.3.5: the glints and the
 * lens streak stay off the head box; see PosterFinishTest).
 */
class PosterLightTest {
    @Test
    fun grid_findsTheHead_ofTheSyntheticPerson() {
        val photo = PosterSamples.syntheticPhoto()
        // 540 x 960 covers the poster at scale 2: the head's centre is at (540, 768), about 240 px wide
        val g = SubjectGrid.build(photo, 1f, 0f, 0f)
        assertNotNull(g)
        val head = g!!.head!!
        println("synthetic head box ${head.toList()}")
        assertTrue(g.inHead(540f, 768f))
        assertTrue("head top ${head[1]}", head[1] <= 630f)
        assertTrue("head bottom ${head[3]} stops at the shoulders", head[3] < 1150f)
        assertTrue(head[0] < 430f && head[2] > 650f)
        // the grid knows the person (grown by a cell) and the free wall
        assertTrue(g.at(540f, 1500f) > 200)
        assertEquals(0, g.at(80f, 300f))
    }

    @Test
    fun grid_ofAnEmptyMask_isNull() {
        val p = PosterPhoto(PosterImage(100, 100, IntArray(100 * 100)), ByteArray(100 * 100))
        assertNull(SubjectGrid.build(p, 1f, 0f, 0f))
    }

    @Test
    fun headBox_isNotStretchedByShouldersThatWidenSmoothly() {
        // a 12-cell head over shoulders that widen one cell per row (no jump): the head still ends near 1.4 x 12 rows
        val gw = 68
        val gh = 120
        val raw = ByteArray(gw * gh)
        for (y in 20 until 40) for (x in 28..39) raw[y * gw + x] = 255.toByte()
        for (y in 40 until 110) {
            val half = 6 + (y - 40)
            for (x in (34 - half).coerceAtLeast(0)..(33 + half).coerceAtMost(gw - 1)) raw[y * gw + x] = 255.toByte()
        }
        val box = SubjectGrid.headBox(raw, gw, gh)!!
        val rows = box[3] / SubjectGrid.CELL - 3 - 20 + 1
        assertTrue("head rows $rows", rows in 14f..18f)
    }

    @Test
    fun headBox_ofAFullLengthFigure_endsAtTheShoulders() {
        // grid rows: a head 6 cells wide for 8 rows, a 3-cell neck, then 20-cell shoulders
        val gw = 68
        val gh = 120
        val raw = ByteArray(gw * gh)
        fun fill(y: Int, x0: Int, x1: Int) {
            for (x in x0..x1) raw[y * gw + x] = 255.toByte()
        }
        for (y in 10 until 18) fill(y, 31, 36)
        for (y in 18 until 20) fill(y, 33, 34)
        for (y in 20 until 60) fill(y, 24, 43)
        val box = SubjectGrid.headBox(raw, gw, gh)!!
        val cell = SubjectGrid.CELL
        assertEquals(((10 - 2) * cell).toFloat(), box[1], 0f)
        // 6 wide: the depth reaches the width 6 rows down, so the head is 1.4 x 6 -> 9 rows: rows 10..18 (the neck's first row), not the shoulders
        assertEquals("the head ends above the shoulders", ((18 + 1 + 2) * cell).toFloat(), box[3], 0f)
        assertEquals(((31 - 2) * cell).toFloat(), box[0], 0f)
        assertEquals(((36 + 1 + 2) * cell).toFloat(), box[2], 0f)
    }
}
