/**
 * Auralys - responsive widget layout
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import kotlin.math.max
import kotlin.math.min

/** A rectangle inside the widget, in dp, measured from the widget's top-left corner. */
data class WRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h

    fun overlaps(other: WRect): Boolean =
        x < other.right - 0.01f && other.x < right - 0.01f && y < other.bottom - 0.01f && other.y < bottom - 0.01f
}

/** The arrangement a widget size ends up with. */
enum class WidgetForm {
    /** Tiny (about 1x1): the cover with a play button on top of it. */
    MICRO,

    /** Wide and short: cover, texts and buttons side by side in one row. */
    ROW,

    /** Wide with some height (about 4x2): the cover on the left; texts, buttons and progress in a column beside it. */
    BESIDE,

    /** Squarish: a small cover next to the texts, then the buttons, then the progress bar. */
    STACK,

    /** Tall (or a big square): a big cover on top of the texts, buttons and progress bar. */
    HERO,
}

/** What the widget may show; hidden things take no room and the rest is re-spaced. */
data class WidgetLayoutInput(
    val showCover: Boolean = true,
    val showProgress: Boolean = true,
    val showTime: Boolean = true,
    val showLike: Boolean = true,
    val showSkip: Boolean = true,
    /** 1 = normal; the text sizes are multiplied by it. */
    val textScale: Float = 1f,
    /** Listening streak in days; 0 = no badge. */
    val streakDays: Int = 0,
)

/** Where everything goes for one widget size. A null rectangle means "hidden". */
data class WidgetLayoutSpec(
    val form: WidgetForm,
    val widthDp: Float,
    val heightDp: Float,
    val cover: WRect?,
    val title: WRect?,
    val artist: WRect?,
    val like: WRect?,
    val prev: WRect?,
    val play: WRect?,
    val next: WRect?,
    val progress: WRect?,
    val timeNow: WRect?,
    val timeTotal: WRect?,
    val titleSize: Float,
    val artistSize: Float,
    val timeSize: Float,
    /** Edge of the icons drawn inside the touch boxes. */
    val likeIcon: Float,
    val skipIcon: Float,
    val playIcon: Float,
    /** Diameter of the round play background. */
    val playDisc: Float,
    /** The small streak badge (flame + days), at the end of the title or artist line; null = not shown. */
    val streak: WRect? = null,
    /** Edge of the flame icon and size of the day count in the badge. */
    val streakIcon: Float = 0f,
    val streakTextSize: Float = 0f,
) {
    /** Every visible rectangle, for checks. */
    val all: List<WRect>
        get() = listOfNotNull(cover, title, artist, like, prev, play, next, progress, timeNow, timeTotal, streak)
}

/**
 * Works out the layout of the widget for any size. It is pure arithmetic on purpose: the same numbers drive the
 * RemoteViews and the unit tests, which walk over many sizes (1:1, 4:1, 2:4 ...) and check that every element stays
 * inside the widget, nothing overlaps and the buttons stay big enough to hit.
 */
object AuralysWidgetLayout {
    const val MIN_TOUCH = 32f

    /** A column of buttons never gets narrower than this; below it the less important buttons are dropped. */
    const val MIN_BUTTON_WIDTH = 28f
    private const val GAP = 8f
    private const val TIME_WIDTH = 32f
    private const val BAR_HEIGHT = 4f

    /** Width needed for the two time labels plus a bar worth drawing. */
    private const val MIN_TIMED_BAR_WIDTH = 2 * (TIME_WIDTH + 6f) + 24f

    fun compute(widthDp: Float, heightDp: Float, input: WidgetLayoutInput = WidgetLayoutInput()): WidgetLayoutSpec {
        val w = max(widthDp, 24f)
        val h = max(heightDp, 24f)
        val pad = (min(w, h) * 0.09f).coerceIn(5f, 14f)
        val cw = w - 2 * pad
        val ch = h - 2 * pad
        val ratio = cw / ch
        val spec = when {
            ratio < 1.65f && ch < 78f -> micro(w, h, cw, ch, input)
            cw < 56f -> micro(w, h, cw, ch, input)
            ratio >= 1.65f -> row(w, h, pad, cw, ch, input)
            else -> column(w, h, pad, cw, ch, input)
        }
        return withStreak(spec, input, pad)
    }

    // --------------------------------------------------------------------------------------- streak

    /** A title never gets narrower than this to make room for the streak badge. */
    const val MIN_TITLE_WITH_STREAK = 44f
    private const val STREAK_GAP = 4f

    /**
     * Adds the streak badge (flame + day count) where it fits without moving anything else: at the end of the title
     * line (the title gets shorter), else at the end of the artist line, else (tiny widget) in the free corner beside
     * the cover. When none of those has room the badge is left out.
     */
    private fun withStreak(spec: WidgetLayoutSpec, input: WidgetLayoutInput, pad: Float): WidgetLayoutSpec {
        if (input.streakDays <= 0) return spec
        val badge = StreakBadge(input.streakDays)

        spec.title?.let { title ->
            val b = badge.sizedFor(title.h, spec.artistSize.coerceIn(9f, 12.5f))
            if (title.w - b.w - STREAK_GAP >= MIN_TITLE_WITH_STREAK) {
                return spec.copy(
                    title = title.copy(w = title.w - b.w - STREAK_GAP),
                    streak = WRect(title.right - b.w, title.y + (title.h - b.h) / 2f, b.w, b.h),
                    streakIcon = b.icon,
                    streakTextSize = b.text,
                )
            }
        }
        spec.artist?.let { artist ->
            val b = badge.sizedFor(artist.h, spec.artistSize.coerceIn(8f, 12f))
            if (artist.w - b.w - STREAK_GAP >= MIN_TITLE_WITH_STREAK) {
                return spec.copy(
                    artist = artist.copy(w = artist.w - b.w - STREAK_GAP),
                    streak = WRect(artist.right - b.w, artist.y + (artist.h - b.h) / 2f, b.w, b.h),
                    streakIcon = b.icon,
                    streakTextSize = b.text,
                )
            }
        }
        // a narrow text column (2x2 widget): a line of its own under the texts, the text group moved up by half of it
        val lastLine = spec.artist ?: spec.title
        if (lastLine != null && spec.title != null) {
            val b = badge.sizedFor(min(spec.title.h, 18f), spec.artistSize.coerceIn(9f, 12.5f))
            val lift = (b.h + 2f) / 2f
            val others = spec.all.filter { it != spec.title && it != spec.artist }
            fun fits(r: WRect) =
                r.x >= pad - 0.01f && r.y >= pad - 0.01f && r.right <= spec.widthDp - pad + 0.01f &&
                    r.bottom <= spec.heightDp - pad + 0.01f && others.none { it.overlaps(r) }
            for (up in listOf(lift, 0f)) {
                val title = spec.title.copy(y = spec.title.y - up)
                val artist = spec.artist?.copy(y = spec.artist.y - up)
                val rect = WRect(title.x, (artist ?: title).bottom + 2f, b.w, b.h)
                if (b.w <= title.w && fits(title) && (artist == null || fits(artist)) && fits(rect)) {
                    return spec.copy(title = title, artist = artist, streak = rect, streakIcon = b.icon, streakTextSize = b.text)
                }
            }
        }
        if (spec.form == WidgetForm.MICRO) {
            // the corner beside a centred cover / play button, when the tiny widget is wider than tall
            val used = spec.cover ?: spec.play ?: return spec
            val b = badge.sizedFor(16f, 10f)
            val rect = WRect(used.right + STREAK_GAP, max(used.y, pad), b.w, b.h)
            if (rect.right <= spec.widthDp - pad + 0.01f && rect.bottom <= spec.heightDp - pad + 0.01f) {
                return spec.copy(streak = rect, streakIcon = b.icon, streakTextSize = b.text)
            }
        }
        return spec
    }

    /** Size of the badge for a day count: flame icon, a 2 dp gap, the digits. */
    private class StreakBadge(days: Int) {
        private val digits = days.toString().length

        class Size(val w: Float, val h: Float, val icon: Float, val text: Float)

        /** The badge for a line [lineH] high with the digits at most [textSize]. */
        fun sizedFor(lineH: Float, textSize: Float): Size {
            val text = min(textSize, lineH / 1.25f).coerceAtLeast(8f)
            val h = min(lineH, max(text * 1.25f, 12f))
            return Size(w = h + 2f + digits * text * 0.62f + 2f, h = h, icon = h, text = text)
        }
    }

    // ---------------------------------------------------------------------------------------- micro

    private fun micro(w: Float, h: Float, cw: Float, ch: Float, input: WidgetLayoutInput): WidgetLayoutSpec {
        val side = max(min(cw, ch), 16f)
        val left = (w - side) / 2f
        val top = (h - side) / 2f
        val cover = if (input.showCover) WRect(left, top, side, side) else null
        val disc = if (input.showCover) (side * 0.5f).coerceAtLeast(min(side, 26f)) else side * 0.8f
        val box = max(disc, min(side, MIN_TOUCH))
        val play = WRect((w - box) / 2f, (h - box) / 2f, box, box)
        return spec(WidgetForm.MICRO, w, h, cover = cover, play = play, playDisc = disc, titleSize = 12f)
    }

    // ------------------------------------------------------------------------------------------ row

    private fun row(w: Float, h: Float, pad: Float, cw: Float, ch: Float, input: WidgetLayoutInput): WidgetLayoutSpec {
        val wantProgress = input.showProgress && ch >= 78f
        val barRow = if (wantProgress) barRowHeight(input) else 0f
        val bandH = ch - (if (barRow > 0f) barRow + 6f else 0f)

        val cover = if (input.showCover) min(min(bandH, cw * 0.30f), 112f) else 0f
        val coverSpace = if (cover > 0f) cover + GAP + 2f else 0f
        val box = (bandH * 0.62f).coerceIn(MIN_TOUCH, 46f).coerceAtMost(max(bandH, MIN_TOUCH))
        fun flatTextWidth(like: Boolean, skip: Boolean) =
            cw - coverSpace - box * (1 + (if (like) 1 else 0) + (if (skip) 2 else 0)) - GAP

        var like = input.showLike
        var skip = input.showSkip
        val flat = when {
            flatTextWidth(like, skip) >= 72f -> true
            flatTextWidth(false, skip) >= 72f -> true.also { like = false }
            else -> false
        }
        if (!flat && ch >= 64f) return beside(w, h, pad, cw, ch, input, barRow)

        // too flat for two levels and too narrow for everything: keep only the buttons that fit beside the texts
        if (!flat) {
            like = false
            if (flatTextWidth(false, true) < 72f) skip = false
        }
        val titleSize = (bandH * 0.20f).coerceIn(11f, 19f) * input.textScale
        val artistSize = (titleSize - 2.5f).coerceAtLeast(9f)
        val controlsWidth = box * (1 + (if (like) 1 else 0) + (if (skip) 2 else 0))
        val controlsLeft = pad + cw - controlsWidth
        val controlsTop = pad + (bandH - box) / 2f
        var x = controlsLeft
        val likeRect = if (like) WRect(x, controlsTop, box, box).also { x += box } else null
        val prevRect = if (skip) WRect(x, controlsTop, box, box).also { x += box } else null
        val playRect = WRect(x, controlsTop, box, box).also { x += box }
        val nextRect = if (skip) WRect(x, controlsTop, box, box) else null

        val coverRect = if (cover > 0f) WRect(pad, pad + (bandH - cover) / 2f, cover, cover) else null
        val textLeft = pad + coverSpace
        val textW = max(controlsLeft - GAP - textLeft, 0f)
        val (titleRect, artistRect) = textBlock(textLeft, pad, textW, bandH, titleSize, artistSize)
        val bar = if (barRow > 0f) progressRects(pad, pad + bandH + 6f, cw, barRow, input) else Triple(null, null, null)
        return spec(
            WidgetForm.ROW, w, h, coverRect, titleRect, artistRect, likeRect, prevRect, playRect, nextRect,
            bar.first, bar.second, bar.third, titleSize, artistSize, box, input,
        )
    }

    /** Wide with some height: the cover on the left, a column with texts and buttons on the right, progress below. */
    private fun beside(
        w: Float,
        h: Float,
        pad: Float,
        cw: Float,
        ch: Float,
        input: WidgetLayoutInput,
        barRow: Float,
    ): WidgetLayoutSpec {
        val colH = ch - (if (barRow > 0f) barRow + 6f else 0f)
        val cover = if (input.showCover) min(min(colH, cw * 0.34f), 112f) else 0f
        val colLeft = pad + (if (cover > 0f) cover + GAP + 2f else 0f)
        val colW = pad + cw - colLeft

        var like = input.showLike
        var skip = input.showSkip
        fun shares() = (if (like) 1f else 0f) + (if (skip) 2f else 0f) + 1.3f
        if (colW / shares() < MIN_BUTTON_WIDTH) like = false
        if (colW / shares() < MIN_BUTTON_WIDTH) skip = false

        val ctrlH = (colH * 0.42f).coerceIn(30f, 40f).coerceAtMost(colH)
        val ctrlTop = pad + colH - ctrlH
        val (titleSize, artistSize, showArtist) = fitTexts(colH - ctrlH - 4f, (colH * 0.17f).coerceIn(11f, 18f) * input.textScale)
        val textArea = colH - ctrlH - 4f
        val (titleRect, artistRect) = textBlock(colLeft, pad, colW, textArea, titleSize, artistSize, showArtist)

        val (likeRect, prevRect, playRect, nextRect) = spread(colLeft, colW, ctrlTop, ctrlH, like, skip)
        val coverRect = if (cover > 0f) WRect(pad, pad + (colH - cover) / 2f, cover, cover) else null
        val bar = if (barRow > 0f) progressRects(pad, pad + colH + 6f, cw, barRow, input) else Triple(null, null, null)
        return spec(
            WidgetForm.BESIDE, w, h, coverRect, titleRect, artistRect, likeRect, prevRect, playRect, nextRect,
            bar.first, bar.second, bar.third, titleSize, artistSize, min(ctrlH, colW / shares() * 1.3f), input,
        )
    }

    // --------------------------------------------------------------------------------------- column

    private fun column(w: Float, h: Float, pad: Float, cw: Float, ch: Float, input: WidgetLayoutInput): WidgetLayoutSpec {
        val titleSize = (min(cw, ch) * 0.115f).coerceIn(11f, 19f) * input.textScale
        val artistSize = (titleSize - 2.5f).coerceAtLeast(9f)
        val textH = titleSize * 1.3f + artistSize * 1.3f + 2f
        val wantProgress = input.showProgress && ch >= 100f
        val barRow = if (wantProgress) barRowHeight(input) else 0f
        val ctrlH = (ch * 0.22f).coerceIn(MIN_TOUCH, 48f)

        // the bottom of the widget is built upwards: progress bar, buttons, texts, then whatever is left is the cover
        val barTop = pad + ch - barRow
        val ctrlBottom = if (barRow > 0f) barTop - 4f else pad + ch
        val ctrlTop = ctrlBottom - ctrlH
        val textTop = ctrlTop - GAP * 0.75f - textH
        val coverAvail = textTop - GAP * 0.75f - pad

        val hero = input.showCover && coverAvail >= 64f && coverAvail >= cw * 0.55f
        val coverRect: WRect?
        val titleRect: WRect?
        val artistRect: WRect?
        if (hero) {
            val side = min(cw, coverAvail)
            coverRect = WRect(pad, pad, side, side)
            val (t, a) = textBlock(pad, textTop, cw, textH, titleSize, artistSize)
            titleRect = t
            artistRect = a
        } else {
            // compact: a small cover with the texts beside it, centred in the room above the buttons
            val topH = ctrlTop - GAP * 0.75f - pad
            val cs = if (input.showCover) min(min(cw * 0.42f, topH), 76f).coerceAtLeast(24f) else 0f
            coverRect = if (cs > 0f && cs <= topH + 0.01f) WRect(pad, pad + (topH - cs) / 2f, cs, cs) else null
            val left = pad + (if (coverRect != null) cs + GAP + 2f else 0f)
            val (t, a) = textBlock(left, pad, pad + cw - left, topH, titleSize, artistSize)
            titleRect = t
            artistRect = a
        }

        // buttons: spread over the full width; a narrow widget drops the like button and then previous/next so the
        // ones that stay are still easy to hit
        var like = input.showLike
        var skip = input.showSkip
        fun shares() = (if (like) 1f else 0f) + (if (skip) 2f else 0f) + 1.3f
        if (cw / shares() < MIN_BUTTON_WIDTH) like = false
        if (cw / shares() < MIN_BUTTON_WIDTH) skip = false
        val (likeRect, prevRect, playRect, nextRect) = spread(pad, cw, ctrlTop, ctrlH, like, skip)

        val bar = if (barRow > 0f) progressRects(pad, barTop, cw, barRow, input) else Triple(null, null, null)
        return spec(
            if (hero) WidgetForm.HERO else WidgetForm.STACK, w, h, coverRect, titleRect, artistRect,
            likeRect, prevRect, playRect, nextRect, bar.first, bar.second, bar.third, titleSize, artistSize,
            min(ctrlH, cw / shares() * 1.3f), input,
        )
    }

    // ----------------------------------------------------------------------------------------- bits

    private data class Buttons(val like: WRect?, val prev: WRect?, val play: WRect, val next: WRect?)

    /** The buttons spread over [width] starting at [left]; the play button gets a little more room than the others. */
    private fun spread(left: Float, width: Float, top: Float, height: Float, like: Boolean, skip: Boolean): Buttons {
        val shares = buildList {
            if (like) add(1f)
            if (skip) add(1f)
            add(1.3f)
            if (skip) add(1f)
        }
        val unit = width / shares.sum()
        var x = left
        var index = 0
        fun next(): WRect {
            val bw = shares[index++] * unit
            return WRect(x, top, bw, height).also { x += bw }
        }
        val likeRect = if (like) next() else null
        val prevRect = if (skip) next() else null
        val playRect = next()
        val nextRect = if (skip) next() else null
        return Buttons(likeRect, prevRect, playRect, nextRect)
    }

    private fun barRowHeight(input: WidgetLayoutInput): Float = if (input.showTime) 14f else 8f

    /** The progress bar and, when [WidgetLayoutInput.showTime] and wide enough, the two time labels beside it. */
    private fun progressRects(left: Float, top: Float, width: Float, rowH: Float, input: WidgetLayoutInput):
        Triple<WRect?, WRect?, WRect?> {
        val barY = top + (rowH - BAR_HEIGHT) / 2f
        return if (input.showTime && width >= MIN_TIMED_BAR_WIDTH) {
            Triple(
                WRect(left + TIME_WIDTH + 6f, barY, width - 2 * (TIME_WIDTH + 6f), BAR_HEIGHT),
                WRect(left, top, TIME_WIDTH, rowH),
                WRect(left + width - TIME_WIDTH, top, TIME_WIDTH, rowH),
            )
        } else {
            Triple(WRect(left, barY, width, BAR_HEIGHT), null, null)
        }
    }

    /** Title size that fits [room] together with an artist line; when it would become too small the artist goes. */
    private fun fitTexts(room: Float, wanted: Float): Triple<Float, Float, Boolean> {
        val withArtist = (room - 2f + 2.5f * 1.3f) / 2.6f
        return when {
            withArtist >= 11f -> Triple(min(wanted, withArtist), (min(wanted, withArtist) - 2.5f).coerceAtLeast(9f), true)
            else -> Triple(min(wanted, (room / 1.3f)).coerceAtLeast(10f), 9f, false)
        }
    }

    /** The title and artist, stacked and centred vertically in the band [top] .. [top] + [height]. */
    private fun textBlock(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        titleSize: Float,
        artistSize: Float,
        showArtist: Boolean = true,
    ): Pair<WRect?, WRect?> {
        if (width < 24f) return null to null
        val titleH = titleSize * 1.3f
        val artistH = artistSize * 1.3f
        val both = titleH + artistH + 2f
        return if (showArtist && height >= both) {
            val y = top + (height - both) / 2f
            WRect(left, y, width, titleH) to WRect(left, y + titleH + 2f, width, artistH)
        } else if (height >= titleH) {
            WRect(left, top + (height - titleH) / 2f, width, titleH) to null
        } else {
            null to null
        }
    }

    private fun spec(
        form: WidgetForm,
        w: Float,
        h: Float,
        cover: WRect? = null,
        title: WRect? = null,
        artist: WRect? = null,
        like: WRect? = null,
        prev: WRect? = null,
        play: WRect? = null,
        next: WRect? = null,
        progress: WRect? = null,
        timeNow: WRect? = null,
        timeTotal: WRect? = null,
        titleSize: Float = 14f,
        artistSize: Float = 11f,
        box: Float = MIN_TOUCH,
        input: WidgetLayoutInput = WidgetLayoutInput(),
        playDisc: Float = 0f,
    ): WidgetLayoutSpec {
        val playBox = play?.let { min(it.w, it.h) } ?: box
        val disc = if (playDisc > 0f) playDisc else playBox * 0.78f
        return WidgetLayoutSpec(
            form = form,
            widthDp = w,
            heightDp = h,
            cover = cover,
            title = title,
            artist = artist,
            like = like,
            prev = prev,
            play = play,
            next = next,
            progress = progress,
            timeNow = timeNow,
            timeTotal = timeTotal,
            titleSize = titleSize,
            artistSize = artistSize,
            timeSize = 10f,
            likeIcon = (box * 0.5f).coerceIn(16f, 26f),
            skipIcon = (box * 0.56f).coerceIn(18f, 28f),
            playIcon = (disc * 0.58f).coerceIn(14f, 30f),
            playDisc = disc,
        )
    }
}
