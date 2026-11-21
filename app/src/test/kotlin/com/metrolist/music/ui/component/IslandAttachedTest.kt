package com.metrolist.music.ui.component

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.metrolist.music.constants.DynamicIslandCompactContent
import com.metrolist.music.constants.DynamicIslandLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/** "Gabung": the island glued to the top edge with flared corners, like the notch of the Windows island. */
class IslandAttachedTest {
    private fun spec(
        attached: Boolean,
        y: Int = 40,
        x: Float = 0f,
        density: Float = 2.75f,
        scale: Float = 1f,
        pillH: Int = 34,
    ) = IslandScreenSpec(
        screenWidth = 1080,
        screenHeight = 2400,
        statusBar = (32 * density).toInt(),
        density = density,
        scale = scale,
        pillWidthDp = 240,
        pillHeightDp = pillH,
        expandedWidthDp = 360,
        expandedHeightDp = 150f,
        xFraction = x,
        yDp = y,
        xOffsetDp = 0,
        compactFrameDp = IslandWindowGeometry.COMPACT_SLOP_DP,
        attached = attached,
        compactFlareDp = DynamicIslandDimens.COMPACT_FLARE,
    )

    @Test
    fun `an attached island sits at the very top whatever the saved height is`() {
        for (y in listOf(-30, 0, 6, 40, 300)) {
            val g = IslandWindowGeometry.compute(spec(attached = true, y = y))
            assertEquals(0, g.pill.y)
            assertEquals(0, g.card.y)
        }
        val floating = IslandWindowGeometry.compute(spec(attached = false, y = 40))
        assertTrue("a floating island keeps its height", floating.pill.y > 0)
    }

    @Test
    fun `the windows have room for the flares at the sides and not above the edge`() {
        val density = 2.75f
        val g = IslandWindowGeometry.compute(spec(attached = true, density = density))
        val frame = ceil(IslandWindowGeometry.COMPACT_SLOP_DP * density).toInt()
        val flare = ceil(DynamicIslandDimens.COMPACT_FLARE * density).toInt()
        val side = maxOf(frame, flare)
        assertEquals(g.pill.x - side, g.compactWindow.x)
        assertEquals(g.pill.width + 2 * side, g.compactWindow.width)
        assertEquals("nothing is drawn above the screen edge", 0, g.compactWindow.y)
        assertEquals(g.pill.height + frame, g.compactWindow.height)
        val glow = ceil(DynamicIslandDimens.GLOW_PAD * density).toInt()
        val room = maxOf(glow, flare)
        assertTrue(g.expandedWindow.x <= g.card.x - room)
        assertTrue(g.expandedWindow.right >= g.card.right + room)
        assertTrue(g.expandedWindow.contains(g.pill.x, g.pill.y) && g.expandedWindow.contains(g.card.x, g.card.y))
    }

    @Test
    fun `a floating island keeps the old windows exactly`() {
        val s = spec(attached = false)
        val g = IslandWindowGeometry.compute(s.copy(compactFlareDp = 0f))
        val frame = ceil(s.compactFrameDp * s.density).toInt()
        assertEquals(g.pill.inflate(frame), g.compactWindow)
    }

    @Test
    fun `the open card keeps extra room under the screen edge and a pill with a lyric line is wide enough`() {
        val card = DynamicIslandConfig()
        val glued = card.copy(attached = true)
        assertEquals(DynamicIslandDimens.expandedHeight(card) + DynamicIslandDimens.ATTACHED_TOP_PAD, DynamicIslandDimens.expandedHeight(glued), 0.01f)
        val row = card.copy(layout = DynamicIslandLayout.COMPACT_ROW)
        assertEquals(DynamicIslandDimens.expandedHeight(row) + DynamicIslandDimens.ATTACHED_TOP_PAD, DynamicIslandDimens.expandedHeight(row.copy(attached = true)), 0.01f)
        assertEquals(0f, DynamicIslandDimens.compactFlare(card), 0f)
        assertEquals(DynamicIslandDimens.COMPACT_FLARE, DynamicIslandDimens.compactFlare(glued.copy(pillHeight = 38)), 0f)
        assertTrue("a thin pill gets a smaller flare", DynamicIslandDimens.compactFlare(glued.copy(pillHeight = 20)) <= 10f)

        val narrow = card.copy(pillWidth = 150)
        assertEquals(150, DynamicIslandDimens.compactWidth(narrow))
        assertEquals(DynamicIslandDimens.LYRIC_PILL_MIN_WIDTH, DynamicIslandDimens.compactWidth(narrow.copy(compactContent = DynamicIslandCompactContent.COVER_LYRICS)))
        assertEquals(DynamicIslandDimens.LYRIC_PILL_MIN_WIDTH, DynamicIslandDimens.compactWidth(narrow.copy(compactContent = DynamicIslandCompactContent.LYRICS_ONLY)))
        assertEquals(150, DynamicIslandDimens.compactWidth(narrow.copy(compactContent = DynamicIslandCompactContent.LYRICS_ONLY, showLyrics = false)))
        assertEquals(300, DynamicIslandDimens.compactWidth(narrow.copy(pillWidth = 300, compactContent = DynamicIslandCompactContent.COVER_LYRICS)))
    }

    @Test
    fun `the glue survives preferences and the Windows preset sets the look`() {
        val glued = DynamicIslandConfig(attached = true, compactContent = DynamicIslandCompactContent.LYRICS_ONLY)
        val prefs = mutablePreferencesOf().apply { putDynamicIslandConfig(glued) }
        assertEquals(glued, prefs.toDynamicIslandConfig())
        assertFalse(DynamicIslandConfig().attached)

    }
}
