/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The colours the share cards (lyrics, song, recap, streak) fall back to when a cover gives no colour: the neutral slate
 * and ink of the app. A card takes its colour from the cover; with none it stays grey, never a brand or warm tone.
 */

package com.metrolist.music.utils

object ShareCardColors {
    /** Glow and gradient start when there is no cover colour: a cool mid grey. */
    const val ACCENT = 0xFF8C93A3.toInt()

    /** Gradient end and dark tone when there is no cover colour (the dark surface of the app). */
    const val DARK = 0xFF15171C.toInt()

    /** The base every card is painted over before the cover (the dark page of the app). */
    const val BASE = 0xFF0B0C0F.toInt()

    /** Text on a dark card (the dark-theme ink). */
    const val INK = 0xFFF2F4F7.toInt()
}
