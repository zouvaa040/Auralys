package com.metrolist.music.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the 16:9 picture sits in the cover area: centred, in the full-bleed player between the header and the pill. */
class VideoPictureLayoutTest {
    @Test
    fun `on a card the picture is centred in the box`() {
        assertEquals(350, pictureTop(boxH = 1000, h = 300, topSafe = null, bottomReserve = 0))
    }

    @Test
    fun `in the full-bleed player the picture is centred between the header and the pill`() {
        // box 1500 px, header ends at 300, the pill room is 150: the room is 300..1350, 1050 tall, the picture 600 tall
        val top = pictureTop(boxH = 1500, h = 600, topSafe = 300, bottomReserve = 150)
        assertEquals(300 + (1050 - 600) / 2, top)
        // the gap above the picture equals the gap below it: no empty block under the picture
        val gapAbove = top - 300
        val gapBelow = (1500 - 150) - (top + 600)
        assertEquals(gapAbove, gapBelow)
    }

    @Test
    fun `a picture taller than the room sits at the header edge, never off the box`() {
        val top = pictureTop(boxH = 800, h = 700, topSafe = 300, bottomReserve = 150)
        assertTrue(top in 0..(800 - 700))
    }

    @Test
    fun `a box without room for the header and the pill still gives a valid top`() {
        val top = pictureTop(boxH = 400, h = 300, topSafe = 380, bottomReserve = 200)
        assertTrue(top in 0..100)
    }

    @Test
    fun `no negative top`() {
        assertEquals(0, pictureTop(boxH = 100, h = 300, topSafe = null, bottomReserve = 0))
        assertEquals(0, pictureTop(boxH = 100, h = 300, topSafe = 50, bottomReserve = 10))
    }
}
