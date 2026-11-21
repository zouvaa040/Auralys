package com.metrolist.music.ui.component

import com.metrolist.music.constants.DynamicIslandStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.random.Random

/**
 * The overlay window must follow the visible island: compact = pill + a small frame, open/morph = card + glow frame.
 * Android 12+ drops touches that pass through another app's window (frame based, not pixel based), so any transparent
 * margin of this window is a dead area for the app below (report: WhatsApp header buttons next to the island).
 */
class IslandWindowGeometryTest {
    private fun spec(
        screenW: Int = 1080,
        screenH: Int = 2400,
        density: Float = 2.75f,
        scale: Float = 1f,
        pillW: Int = 190,
        pillH: Int = 38,
        expandedW: Int = 360,
        expandedH: Float = 170f,
        x: Float = 0f,
        y: Int = 6,
        xOffset: Int = 0,
        frame: Float = IslandWindowGeometry.COMPACT_SLOP_DP,
    ) = IslandScreenSpec(
        screenWidth = screenW,
        screenHeight = screenH,
        statusBar = (32 * density).toInt(),
        density = density,
        scale = scale,
        pillWidthDp = pillW,
        pillHeightDp = pillH,
        expandedWidthDp = expandedW,
        expandedHeightDp = expandedH,
        xFraction = x,
        yDp = y,
        xOffsetDp = xOffset,
        compactFrameDp = frame,
    )

    private fun randomSpecs(n: Int = 3000): List<IslandScreenSpec> {
        val r = Random(434)
        val screens = listOf(Triple(720, 1600, 2f), Triple(1080, 2400, 2.75f), Triple(1440, 3200, 3.5f), Triple(1600, 2560, 2f), Triple(2400, 1080, 2.75f))
        return List(n) {
            val (w, h, d) = screens[r.nextInt(screens.size)]
            spec(
                screenW = w,
                screenH = h,
                density = d,
                scale = listOf(0.85f, 1f, 1.15f)[r.nextInt(3)],
                pillW = r.nextInt(80, 321),
                pillH = r.nextInt(24, 65),
                expandedW = r.nextInt(240, 421),
                expandedH = r.nextInt(72, 240).toFloat(),
                x = r.nextFloat() * 2f - 1f,
                y = r.nextInt(-60, 701),
                xOffset = r.nextInt(-80, 81),
                frame = if (r.nextBoolean()) IslandWindowGeometry.COMPACT_SLOP_DP else DynamicIslandDimens.GLOW_PAD,
            )
        }
    }

    @Test
    fun `compact window is the pill plus a small frame, not a screen-wide strip`() {
        val s = spec()
        val g = IslandWindowGeometry.compute(s)
        val frame = ceil(IslandWindowGeometry.COMPACT_SLOP_DP * s.density).toInt()
        assertEquals(g.pill.inflate(frame), g.compactWindow)
        assertEquals(ceil(190 * 2.75f).toInt() + frame * 2, g.compactWindow.width)
        // the old window was the card wide (360 dp + glow) = 1067 of 1080 px; now about half of that stays free on each side
        assertTrue("compact window ${g.compactWindow.width} px", g.compactWindow.width < 560)
        val midY = g.pill.y + g.pill.height / 2
        // WhatsApp's back arrow (left) and menu (right) at the island's height reach the app again
        assertFalse(g.compactWindow.contains(60, midY))
        assertFalse(g.compactWindow.contains(1020, midY))
        // just below the pill (the toolbar under the island) too
        assertFalse(g.compactWindow.contains(g.pill.x + 10, g.pill.bottom + frame + 4))
        assertTrue(g.compactWindow.contains(g.pill.x + g.pill.width / 2, midY))
    }

    @Test
    fun `every style, the Liquid glass too, keeps only the slop around the compact pill (finding 29)`() {
        DynamicIslandStyle.entries.forEach {
            assertEquals("$it", IslandWindowGeometry.COMPACT_SLOP_DP, IslandWindowGeometry.compactFrameDp(DynamicIslandConfig(style = it)), 0f)
        }
        val liquid = DynamicIslandConfig(style = DynamicIslandStyle.LIQUID)
        val g = IslandWindowGeometry.compute(spec(frame = IslandWindowGeometry.compactFrameDp(liquid), scale = 1.15f))
        val pad = ceil(IslandWindowGeometry.COMPACT_SLOP_DP * 1.15f * 2.75f).toInt()
        assertEquals(g.pill.inflate(pad), g.compactWindow)
        // the old 14 dp frame of the Liquid style blocked a band beside and under the pill: those points reach the app again
        val glow = ceil(DynamicIslandDimens.GLOW_PAD * 1.15f * 2.75f).toInt()
        val midY = g.pill.y + g.pill.height / 2
        assertFalse(g.compactWindow.contains(g.pill.x - glow / 2, midY))
        assertFalse(g.compactWindow.contains(g.pill.right + glow / 2, midY))
        assertFalse(g.compactWindow.contains(g.pill.x + g.pill.width / 2, g.pill.bottom + glow / 2))
        // and the compact contact shadow ends inside the slop, so the window edge never cuts it
        assertTrue(DynamicIslandDimens.compactShadowReachDp() <= IslandWindowGeometry.COMPACT_SLOP_DP)
        assertTrue(DynamicIslandDimens.COMPACT_SHADOW_SPREADS.last() + DynamicIslandDimens.COMPACT_SHADOW_STROKE / 2f - DynamicIslandDimens.COMPACT_SHADOW_DROP <= IslandWindowGeometry.COMPACT_SLOP_DP)
    }

    @Test
    fun `the pill is at the same screen spot in the compact and in the open window (no jump when the window changes)`() {
        randomSpecs().forEach { s ->
            val g = IslandWindowGeometry.compute(s)
            val inCompactX = g.compactWindow.x + g.pillInX(g.compactWindow)
            val inCompactY = g.compactWindow.y + g.pillInY(g.compactWindow)
            val inOpenX = g.expandedWindow.x + g.pillInX(g.expandedWindow)
            val inOpenY = g.expandedWindow.y + g.pillInY(g.expandedWindow)
            assertEquals("$s", g.pill.x, inCompactX)
            assertEquals("$s", g.pill.y, inCompactY)
            assertEquals("$s", g.pill.x, inOpenX)
            assertEquals("$s", g.pill.y, inOpenY)
            // the card ends where it is on screen inside the open window
            assertEquals("$s", g.card.x, g.expandedWindow.x + g.originX(g.expandedWindow))
            assertEquals("$s", g.card.y, g.expandedWindow.y + g.originY(g.expandedWindow))
        }
    }

    @Test
    fun `each window holds everything drawn in its state`() {
        randomSpecs().forEach { s ->
            val g = IslandWindowGeometry.compute(s)
            val glow = ceil(DynamicIslandDimens.GLOW_PAD * s.scale * s.density).toInt()
            val frame = ceil(s.compactFrameDp * s.scale * s.density).toInt()
            // compact: the pill and its frame, nothing more
            assertEquals("$s", g.pill.inflate(frame), g.compactWindow)
            // open + morph: card, pill (a pill may be wider than a narrow card) and the glow ring around both
            val open = g.expandedWindow
            listOf(g.card.inflate(glow), g.pill.inflate(glow)).forEach { r ->
                assertTrue("$s: $r not in $open", r.x >= open.x && r.y >= open.y && r.right <= open.right && r.bottom <= open.bottom)
            }
            assertEquals("$s", g.card.union(g.pill).inflate(glow), open)
            // the pill and the card stay on the screen
            assertTrue("$s pill ${g.pill}", g.pill.x >= 0 && g.pill.right <= maxOf(s.screenWidth, g.pill.width))
            assertTrue("$s pill ${g.pill}", g.pill.y >= 0 && g.pill.bottom <= maxOf(s.screenHeight, g.pill.height))
        }
    }

    @Test
    fun `touch area per state - compact, opening, open, closing, hidden`() {
        val g = IslandWindowGeometry.compute(spec())
        val beside = Pair(40, g.pill.y + g.pill.height / 2)
        // compact: only the pill (+ slop)
        assertFalse(g.compactWindow.contains(beside.first, beside.second))
        // opening / open / closing: the card area (the card is drawn there) and its glow frame, nothing below the card
        assertTrue(g.expandedWindow.contains(g.card.x + 5, g.card.bottom - 5))
        val glow = ceil(DynamicIslandDimens.GLOW_PAD * 2.75f).toInt()
        assertEquals(g.card.bottom + glow, g.expandedWindow.bottom)
        assertFalse(g.expandedWindow.contains(g.card.x + 5, g.card.bottom + glow + 2))
        // the open window is gone again once the card has closed: compact is far smaller
        assertTrue(g.compactWindow.width * g.compactWindow.height * 4 < g.expandedWindow.width * g.expandedWindow.height)
        // hidden: the controller removes the window (and marks it not touchable while it fades), nothing to compute
    }

    @Test
    fun `the overlay root picks the offsets of the window it is really laid out at`() {
        val g = IslandWindowGeometry.compute(spec())
        val c = g.compactWindow
        val e = g.expandedWindow
        assertEquals(c, g.windowFor(c.width, c.height, expandedPreferred = true))
        assertEquals(e, g.windowFor(e.width, e.height, expandedPreferred = false))
        assertNull(g.windowFor(c.width + 1, c.height, expandedPreferred = false))
        // after a drag only x/y change: same size, same in-window offsets
        val moved = IslandWindowGeometry.compute(spec(x = 0.7f, y = 200))
        assertEquals(g.pillInX(g.compactWindow), moved.pillInX(moved.compactWindow))
        assertEquals(g.pillInY(g.compactWindow), moved.pillInY(moved.compactWindow))
    }

    @Test
    fun `pill wider than a narrow card still morphs inside the open window`() {
        val g = IslandWindowGeometry.compute(spec(pillW = 320, expandedW = 240, x = -1f))
        assertTrue(g.pill.width > g.card.width)
        assertTrue(g.expandedWindow.x <= g.pill.x && g.expandedWindow.right >= g.pill.right)
        assertTrue(g.expandedWindow.x <= g.card.x && g.expandedWindow.right >= g.card.right)
    }

    @Test
    fun `position maths matches the old controller (saved positions land where they were)`() {
        // old formulas (DynamicIslandController before 4.3.4)
        randomSpecs(500).forEach { s ->
            val g = IslandWindowGeometry.compute(s)
            val pillW = ceil(s.pillWidthDp * s.scale * s.density).toInt()
            val pillH = ceil(s.pillHeightDp * s.scale * s.density).toInt()
            val free = (s.screenWidth - pillW).coerceAtLeast(0)
            val left = (((free / 2f) * (1f + s.xFraction)).let { Math.round(it) } + Math.round(s.xOffsetDp * s.density)).coerceIn(0, free)
            val top = (s.statusBar + Math.round(s.yDp * s.density)).coerceIn(0, maxOf(0, s.screenHeight - pillH))
            assertEquals("$s", left, g.pill.x)
            assertEquals("$s", top, g.pill.y)
        }
    }
}
