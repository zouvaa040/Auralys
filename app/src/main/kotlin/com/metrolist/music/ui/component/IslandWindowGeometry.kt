/**
 * Auralys - Dynamic Island overlay window geometry
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Everything the island's window placement depends on. Screen values in px, island values in unscaled dp (the size
 * multiplier [scale] is applied here), the same inputs the overlay controller reads from the display and the options.
 */
data class IslandScreenSpec(
    val screenWidth: Int,
    val screenHeight: Int,
    val statusBar: Int,
    val density: Float,
    val scale: Float,
    val pillWidthDp: Int,
    val pillHeightDp: Int,
    val expandedWidthDp: Int,
    val expandedHeightDp: Float,
    /** -1 (left) .. 1 (right). */
    val xFraction: Float,
    /** Distance below the status bar, dp (may be negative: into the status bar). */
    val yDp: Int,
    val xOffsetDp: Int,
    /** Free frame kept around the compact pill, unscaled dp ([IslandWindowGeometry.COMPACT_SLOP_DP] for every style). */
    val compactFrameDp: Float,
    /**
     * Attached: the island is glued to the top edge of the screen (the pill and the card start at y = 0, [yDp] is not used)
     * and the compact pill's top corners flare outward by [compactFlareDp] (unscaled dp), the notch of the Windows island.
     * The open card has no flare. The flare lies beside the pill, so the windows get that much room at the sides.
     */
    val attached: Boolean = false,
    val compactFlareDp: Float = 0f,
)

/** A rectangle in screen pixels. */
data class IslandRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    fun contains(px: Int, py: Int): Boolean = px >= x && py >= y && px < right && py < bottom

    fun inflate(by: Int): IslandRect = IslandRect(x - by, y - by, width + by * 2, height + by * 2)

    fun union(other: IslandRect): IslandRect {
        val l = minOf(x, other.x)
        val t = minOf(y, other.y)
        return IslandRect(l, t, maxOf(right, other.right) - l, maxOf(bottom, other.bottom) - t)
    }
}

/**
 * Where the island is on screen, and the two overlay windows it uses.
 *
 * The window follows the visible shape. Since Android 12 the system drops a touch that would pass through another app's
 * window unless that window is (nearly) transparent as a whole (`LayoutParams.alpha` <= 0.8); the test uses the window's
 * frame, never its pixels, and a touchable window takes every touch inside its frame anyway. So any transparent margin
 * of the island window is a dead area for the app below: the old window, as wide as the expanded card while compact,
 * blocked the whole strip next to the pill (WhatsApp's header buttons beside the island could not be pressed). Now:
 * - compact: [compactWindow] = the pill plus [IslandScreenSpec.compactFrameDp] (2 dp for every style: the Liquid glass
 *   draws a tight contact shadow inside those 2 dp while compact; its old 14 dp soft-shadow frame blocked the app around
 *   the pill, review 2026-11-12 finding 29);
 * - open card and the morph in either direction: [expandedWindow] = card and pill plus the glow frame.
 */
data class IslandGeometry(
    /** The compact pill on screen. */
    val pill: IslandRect,
    /** The expanded card on screen. */
    val card: IslandRect,
    val compactWindow: IslandRect,
    val expandedWindow: IslandRect,
    /** Width of the expanded card in dp, size multiplier applied. */
    val cardWidthDp: Float,
) {
    /** Pill top-left minus card top-left: where the morph starts (compact) and ends (open). Same in both windows. */
    val shiftX: Int get() = pill.x - card.x
    val shiftY: Int get() = pill.y - card.y

    /** Top-left of the expanded card inside [window], in px. The pill is drawn at this point plus [shiftX]/[shiftY]. */
    fun originX(window: IslandRect): Int = card.x - window.x

    fun originY(window: IslandRect): Int = card.y - window.y

    /** The pill's top-left inside [window]. */
    fun pillInX(window: IslandRect): Int = originX(window) + shiftX

    fun pillInY(window: IslandRect): Int = originY(window) + shiftY

    /**
     * The window layout that matches a window measured at [width] x [height] (the size the system really gave the
     * window in this layout pass): the in-window offsets must always belong to the window that is on screen, or the
     * pill jumps for a frame while the window is being resized. Null when it matches neither (then keep the last one).
     */
    fun windowFor(
        width: Int,
        height: Int,
        expandedPreferred: Boolean,
    ): IslandRect? {
        val c = width == compactWindow.width && height == compactWindow.height
        val e = width == expandedWindow.width && height == expandedWindow.height
        return when {
            c && e -> if (expandedPreferred) expandedWindow else compactWindow
            e -> expandedWindow
            c -> compactWindow
            else -> null
        }
    }
}

object IslandWindowGeometry {
    /** Frame kept around a compact pill that draws nothing outside itself (anti-aliasing and rounding only). */
    const val COMPACT_SLOP_DP = 2f

    fun compute(spec: IslandScreenSpec): IslandGeometry {
        val d = spec.density
        val s = spec.scale
        val sw = spec.screenWidth
        val sh = spec.screenHeight

        val pillW = ceil(spec.pillWidthDp * s * d).toInt()
        val pillH = ceil(spec.pillHeightDp * s * d).toInt()
        val free = (sw - pillW).coerceAtLeast(0)
        val pillLeft =
            (((free / 2f) * (1f + spec.xFraction)).roundToInt() + (spec.xOffsetDp * d).roundToInt())
                .coerceIn(0, free)
        // the pill may sit anywhere, over the status bar / camera area too, but never off screen; attached = at the very top
        val pillTop = if (spec.attached) 0 else (spec.statusBar + (spec.yDp * d).roundToInt()).coerceIn(0, maxOf(0, sh - pillH))
        val pill = IslandRect(pillLeft, pillTop, pillW, pillH)

        // the card grows from the pill toward the screen centre, clamped to the screen with a margin
        val margin = (DynamicIslandDimens.SCREEN_MARGIN * d).roundToInt()
        val maxWidthDp = (sw / d - DynamicIslandDimens.SCREEN_MARGIN * 2f).coerceAtLeast(spec.pillWidthDp * s)
        val cardWidthDp = (spec.expandedWidthDp * s).coerceAtMost(maxWidthDp)
        val cardW = ceil(cardWidthDp * d).toInt()
        val cardH = ceil(spec.expandedHeightDp * s * d).toInt()
        val centre = pillLeft + pillW / 2f
        val cardLeft = (centre - cardW / 2f).roundToInt().coerceIn(margin, maxOf(margin, sw - margin - cardW))
        val cardTop = pillTop.coerceAtMost(sh - margin - cardH).coerceAtLeast(0)
        val card = IslandRect(cardLeft, cardTop, cardW, cardH)

        val glow = ceil(DynamicIslandDimens.GLOW_PAD * s * d).toInt()
        val frame = ceil(spec.compactFrameDp.coerceAtLeast(0f) * s * d).toInt()
        if (spec.attached) {
            val flareC = ceil(spec.compactFlareDp.coerceAtLeast(0f) * s * d).toInt()
            // the flares widen the compact window at the sides only; nothing is drawn above the screen edge, so it starts there
            val side = maxOf(frame, flareC)
            return IslandGeometry(
                pill = pill,
                card = card,
                expandedWindow = card.union(pill).inflate(maxOf(glow, flareC)),
                compactWindow = IslandRect(pill.x - side, pill.y, pill.width + side * 2, pill.height + frame),
                cardWidthDp = cardWidthDp,
            )
        }
        return IslandGeometry(
            pill = pill,
            card = card,
            // the morph runs inside the open window, so it holds the pill as well (a pill wider than the card)
            expandedWindow = card.union(pill).inflate(glow),
            compactWindow = pill.inflate(frame),
            cardWidthDp = cardWidthDp,
        )
    }

    /**
     * Free frame around the compact pill for [config]: only the slop, for every style. The Liquid glass used to keep its
     * 14 dp soft-shadow frame here, a band around the pill where the app below could not be touched; its compact shadow
     * now fits the slop ([DynamicIslandDimens.compactShadowReachDp]).
     */
    @Suppress("UNUSED_PARAMETER")
    fun compactFrameDp(config: DynamicIslandConfig): Float = COMPACT_SLOP_DP
}
