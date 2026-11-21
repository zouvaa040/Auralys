/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Icons that are not drawn by Compose: notification actions and small icons, the quick settings tile, the Android Auto
 * browse artwork, the cast button's own drawable, the home screen widget. Those APIs want a drawable RESOURCE id, so
 * the Bulat family (Compose vectors) has XML twins (`bu_*.xml`) for exactly the ids those surfaces use, kept equal to
 * the vectors by BulatXmlTwinsTest. [SystemIcons.resolve] picks the id for the stored family.
 */

package com.metrolist.music.ui.component

import android.content.Context
import androidx.annotation.DrawableRes
import com.metrolist.music.R
import com.metrolist.music.constants.IconFamilyKey
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get

/** The `bu_*` XML twins of the Bulat family, for the ids the system surfaces draw. Pure data. */
object BulatXmlTwins {
    val entries: Map<Int, Int> =
        mapOf(
            R.drawable.album to R.drawable.bu_album,
            R.drawable.artist to R.drawable.bu_artist,
            R.drawable.cast to R.drawable.bu_cast,
            R.drawable.cast_connected to R.drawable.bu_cast_connected,
            R.drawable.close to R.drawable.bu_close,
            R.drawable.download to R.drawable.bu_download,
            R.drawable.explore_outlined to R.drawable.bu_explore_outlined,
            R.drawable.favorite to R.drawable.bu_favorite,
            R.drawable.favorite_border to R.drawable.bu_favorite_border,
            R.drawable.ic_heart to R.drawable.bu_favorite,
            R.drawable.ic_heart_outline to R.drawable.bu_favorite_border,
            R.drawable.mic to R.drawable.bu_mic,
            R.drawable.music_note to R.drawable.bu_music_note,
            R.drawable.pause to R.drawable.bu_pause,
            R.drawable.play to R.drawable.bu_play,
            R.drawable.playlist_add to R.drawable.bu_playlist_add,
            R.drawable.queue_music to R.drawable.bu_queue_music,
            R.drawable.radio to R.drawable.bu_radio,
            R.drawable.repeat to R.drawable.bu_repeat,
            R.drawable.repeat_on to R.drawable.bu_repeat_on,
            R.drawable.repeat_one_on to R.drawable.bu_repeat_one_on,
            R.drawable.share to R.drawable.bu_share,
            R.drawable.shuffle to R.drawable.bu_shuffle,
            R.drawable.shuffle_on to R.drawable.bu_shuffle_on,
            R.drawable.skip_next to R.drawable.bu_skip_next,
            R.drawable.skip_previous to R.drawable.bu_skip_previous,
            R.drawable.update to R.drawable.bu_update,
        )

    @DrawableRes
    fun twinFor(
        @DrawableRes id: Int,
    ): Int? = entries[id]
}

object SystemIcons {
    /** The stored family. A blocking DataStore read (cached after the first), like the other service-side preferences. */
    fun family(context: Context): IconFamily = context.dataStore[IconFamilyKey].toEnum(IconFamily.DEFAULT)

    /** The drawable resource to hand to an API that takes an id, for [family]. Unknown ids keep their normal drawable. */
    @DrawableRes
    fun resolve(
        family: IconFamily,
        @DrawableRes id: Int,
    ): Int =
        when (family) {
            IconFamily.LINE -> id
            IconFamily.IOS -> iosTwinFor(id) ?: id
            IconFamily.ROUND -> BulatXmlTwins.twinFor(id) ?: id
        }

    @DrawableRes
    fun resolve(
        context: Context,
        @DrawableRes id: Int,
    ): Int = resolve(family(context), id)
}
