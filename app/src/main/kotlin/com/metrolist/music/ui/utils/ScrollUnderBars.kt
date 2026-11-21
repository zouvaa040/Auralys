/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Pages scroll UNDER the floating nav pill and mini player (the idea follows BitChord, GPL-3.0: the page takes the
 * whole screen and the bars are siblings that float above it). The old code padded the bottom inset BEFORE
 * `verticalScroll`, which shrank and clipped the scroll viewport, so a hard edge cut the content above the bars.
 * Rule: put only the horizontal inset on the scroll container and add the top/bottom inset as content
 * (these spacers), so the viewport stays full height and the content still ends above the bars.
 */

package com.metrolist.music.ui.utils

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.metrolist.music.LocalPlayerAwareWindowInsets

/** Last child of a scroll container: room for the nav pill and the mini player. */
@Composable
fun BarsBottomSpacer() {
    Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)))
}

/** First child of a scroll container: room for the status bar and the top bar. */
@Composable
fun BarsTopSpacer() {
    Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))
}

/** Side insets (cutout, rail) for a scroll container; top and bottom come in as [BarsTopSpacer] / [BarsBottomSpacer]. */
@Composable
fun Modifier.horizontalBarInsets(): Modifier =
    windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
