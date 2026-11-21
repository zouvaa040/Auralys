/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The icons of the "iOS" family that keep their PREVIOUS look on purpose (owner, 2026-11-07: "ubah icon ios nav bar nya
 * jadi kayak semula, dan tombol next di miniplayer juga"): the five bottom-bar tabs and the previous / next buttons.
 * They are hand-kept (not generated): `Tools/build_ios_twins.py` never touches the `cx_legacy_*` files, and the generated
 * [IosIconTable] stays complete for every other id. A legacy twin always wins over the generated one.
 */

package com.metrolist.music.ui.component

import androidx.annotation.DrawableRes
import com.metrolist.music.R

internal object IosLegacyTwins {
    val twins: Map<Int, Int> =
        mapOf(
            // bottom bar: Beranda, Pencarian, Kamu (group), Pustaka, Profil
            R.drawable.home_outlined to R.drawable.cx_legacy_home_outlined,
            R.drawable.home_filled to R.drawable.cx_legacy_home_filled,
            R.drawable.search to R.drawable.cx_legacy_search,
            R.drawable.group_outlined to R.drawable.cx_legacy_group_outlined,
            R.drawable.group_filled to R.drawable.cx_legacy_group_filled,
            R.drawable.library_music_outlined to R.drawable.cx_legacy_library_music_outlined,
            R.drawable.library_music_filled to R.drawable.cx_legacy_library_music_filled,
            R.drawable.person to R.drawable.cx_legacy_person,
            // player and mini player: the solid double triangles, as a matching pair
            R.drawable.skip_next to R.drawable.cx_legacy_skip_next,
            R.drawable.skip_previous to R.drawable.cx_legacy_skip_previous,
        )

    @DrawableRes
    fun twinFor(
        @DrawableRes id: Int,
    ): Int? = twins[id]
}

/** The iOS twin to draw for [id]: a legacy twin first, then the generated table, or null (the normal drawable stays). */
@DrawableRes
internal fun iosTwinFor(
    @DrawableRes id: Int,
): Int? = IosLegacyTwins.twinFor(id) ?: IosIconTable.twinFor(id)
