/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.poster


import kotlin.math.sqrt

/**
 * The shape of the poster (round 4.3.6). The poster's own coordinate space IS its export size: a card, a sticker or the photo is placed in
 * poster pixels of [w] x [h], and the picture is painted at any device size by scaling that space. The short side is always 1080 px and the
 * long side 1920 for the two widest/tallest shapes, so the cards, the glints and the mark keep one size on every shape. 9:16 is the default
 * and the old poster: every number of it is unchanged.
 */
enum class PosterRatio(val w: Int, val h: Int, val label: String) {
    /** 9:16, TikTok, Reels, Stories. */
    R9_16(1080, 1920, "9:16"),

    /** 16:9, YouTube cover and thumbnail. */
    R16_9(1920, 1080, "16:9"),

    /** 4:5, Instagram feed. */
    R4_5(1080, 1350, "4:5"),

    /** 1:1, square. */
    R1_1(1080, 1080, "1:1"),

    /** 3:4, portrait photo. */
    R3_4(1080, 1440, "3:4"),

    /** 4:3, a screen or a slide. */
    R4_3(1440, 1080, "4:3"),

    /** 2:3, a print or Pinterest. */
    R2_3(1080, 1620, "2:3"),

    /** 3:2, a camera photo. */
    R3_2(1620, 1080, "3:2"),
    ;

    val aspect: Float get() = w.toFloat() / h

    val landscape: Boolean get() = w > h

    /** A shape as tall as 9:16 or 2:3 (the layouts of the old poster fit it). */
    val tall: Boolean get() = aspect < 0.7f

    /** Where the Auralys mark sits: the middle of its row, [markCy] poster pixels from the top. */
    val markCy: Float get() = h - 92f

    /** Below this line (poster pixels from the top) is the mark's band: no glint, no card is laid out there. 1660 on the 9:16 poster. */
    val markBand: Float get() = if (h >= 1920) h - 260f else h - 190f

    /** One number for "how big is this poster compared to the 9:16 one" (its mean side): gels and light spots scale with it. */
    val unit: Float get() = sqrt(w.toFloat() * h / (1080f * 1920f))

    companion object {
        val DEFAULT = R9_16

        fun parse(name: String?): PosterRatio = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
