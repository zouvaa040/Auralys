package com.metrolist.music.ui.screens.settings

import com.metrolist.music.constants.DynamicIslandMaxY
import com.metrolist.music.constants.DynamicIslandMinY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The island's height slider has its 0 in the middle: minus to the left, plus to the right. */
class IslandHeightSliderTest {
    @Test
    fun `zero is in the middle and the ends are the limits`() {
        assertEquals(0f, IslandHeightSlider.toSlider(0f), 0f)
        assertEquals(-1f, IslandHeightSlider.toSlider(DynamicIslandMinY.toFloat()), 0f)
        assertEquals(1f, IslandHeightSlider.toSlider(DynamicIslandMaxY.toFloat()), 0f)
        assertEquals(0, IslandHeightSlider.fromSlider(0f))
        assertEquals(DynamicIslandMinY, IslandHeightSlider.fromSlider(-1f))
        assertEquals(DynamicIslandMaxY, IslandHeightSlider.fromSlider(1f))
    }

    @Test
    fun `minus values are left of the middle and plus values right of it`() {
        assertTrue(IslandHeightSlider.toSlider(-22f) < 0f)
        assertTrue(IslandHeightSlider.toSlider(12f) > 0f)
        assertEquals(-22, IslandHeightSlider.fromSlider(IslandHeightSlider.toSlider(-22f)))
        assertEquals(12, IslandHeightSlider.fromSlider(IslandHeightSlider.toSlider(12f)))
        assertEquals(350, IslandHeightSlider.fromSlider(IslandHeightSlider.toSlider(350f)))
    }

    @Test
    fun `close to the middle it snaps to zero and the slider never leaves its range`() {
        assertEquals(0, IslandHeightSlider.fromSlider(0.01f))
        assertEquals(0, IslandHeightSlider.fromSlider(-0.01f))
        assertEquals(DynamicIslandMaxY, IslandHeightSlider.fromSlider(5f))
        assertEquals(DynamicIslandMinY, IslandHeightSlider.fromSlider(-5f))
        var last = Int.MIN_VALUE
        for (i in -100..100) {
            val y = IslandHeightSlider.fromSlider(i / 100f)
            assertTrue("rises with the slider", y >= last)
            last = y
        }
    }
}
