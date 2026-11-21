/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import com.metrolist.music.R
import com.metrolist.music.extensions.toEnum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BulatIconTableTest {
    private val existingDrawableIds: Set<Int> =
        R.drawable::class.java.fields
            .filter { it.type == Int::class.javaPrimitiveType }
            .map { it.getInt(null) }
            .toSet()

    @Test
    fun `every mapped id is a real drawable`() {
        for (entry in BulatIconTable.entries) {
            assertTrue("id ${entry.resId} is not in R.drawable", entry.resId in existingDrawableIds)
        }
    }

    @Test
    fun `no drawable is mapped twice`() {
        val ids = BulatIconTable.entries.map { it.resId }
        assertEquals("a drawable appears twice in the table", ids.size, ids.toSet().size)
    }

    @Test
    fun `every vector is a 24 grid icon with drawing content`() {
        for (entry in BulatIconTable.entries) {
            val v = entry.vector()
            assertEquals(v.name, 24f, v.viewportWidth, 0f)
            assertEquals(v.name, 24f, v.viewportHeight, 0f)
            assertTrue("${v.name} is empty", v.root.size > 0)
        }
    }

    @Test
    fun `two drawables share a vector only inside the agreed groups`() {
        val groups =
            BulatIconTable.entries
                .groupBy({ it.vector().name }, { it.resId })
                .filterValues { it.size > 1 }
                .mapValues { it.value.toSet() }
        val allowed =
            mapOf(
                "Rounded.Group" to setOf(R.drawable.group, R.drawable.group_filled),
                "Rounded.Tune" to setOf(R.drawable.tune, R.drawable.sliders),
                "Rounded.Translate" to setOf(R.drawable.translate, R.drawable.language_korean_latin),
                "Rounded.Notifications" to setOf(R.drawable.notification, R.drawable.subscribe),
                "Bulat.Check" to setOf(R.drawable.check, R.drawable.done),
                "Bulat.Library" to setOf(R.drawable.library_music, R.drawable.library_music_outlined),
                // the Dynamic Island and the notification use the same shapes as the player controls and the like button
                "Bulat.HeartFilled" to setOf(R.drawable.favorite, R.drawable.ic_heart),
                "Bulat.Heart" to setOf(R.drawable.favorite_border, R.drawable.ic_heart_outline),
                "Bulat.Play" to setOf(R.drawable.play, R.drawable.ic_island_play),
                "Rounded.Pause" to setOf(R.drawable.pause, R.drawable.ic_island_pause),
                "Rounded.SkipNext" to setOf(R.drawable.skip_next, R.drawable.ic_island_next),
                "Rounded.SkipPrevious" to setOf(R.drawable.skip_previous, R.drawable.ic_island_previous),
            )
        assertEquals(allowed, groups)
    }

    @Test
    fun `state pairs keep two different looks`() {
        val pairs =
            listOf(
                R.drawable.home_outlined to R.drawable.home_filled,
                R.drawable.library_music_outlined to R.drawable.library_music_filled,
                R.drawable.favorite_border to R.drawable.favorite,
                R.drawable.shuffle to R.drawable.shuffle_on,
                R.drawable.repeat to R.drawable.repeat_on,
                R.drawable.repeat_one to R.drawable.repeat_one_on,
                R.drawable.subscribe to R.drawable.subscribed,
                R.drawable.radio_button_unchecked to R.drawable.radio_button_checked,
                R.drawable.group_outlined to R.drawable.group_filled,
                R.drawable.ic_heart_outline to R.drawable.ic_heart,
                R.drawable.ic_island_play_outline to R.drawable.ic_island_play,
                R.drawable.ic_island_pause_outline to R.drawable.ic_island_pause,
                R.drawable.ic_island_next_outline to R.drawable.ic_island_next,
                R.drawable.ic_island_previous_outline to R.drawable.ic_island_previous,
                R.drawable.cast to R.drawable.cast_connected,
                R.drawable.library_add to R.drawable.library_add_check,
            )
        for ((off, on) in pairs) {
            assertNotNull(BulatIconTable.vectorFor(off))
            assertNotNull(BulatIconTable.vectorFor(on))
            assertTrue(BulatIconTable.vectorFor(off)!!.name != BulatIconTable.vectorFor(on)!!.name)
        }
    }

    @Test
    fun `the most visible icons are all mapped`() {
        val mustMap =
            listOf(
                R.drawable.home_filled, R.drawable.home_outlined, R.drawable.explore_outlined,
                R.drawable.library_music, R.drawable.search, R.drawable.settings, R.drawable.person,
                R.drawable.play, R.drawable.pause, R.drawable.skip_next, R.drawable.skip_previous,
                R.drawable.shuffle, R.drawable.repeat, R.drawable.repeat_one, R.drawable.favorite,
                R.drawable.favorite_border, R.drawable.queue_music, R.drawable.lyrics, R.drawable.share,
                R.drawable.more_vert, R.drawable.more_horiz, R.drawable.download, R.drawable.delete,
                R.drawable.edit, R.drawable.add, R.drawable.close, R.drawable.check, R.drawable.timer,
                R.drawable.equalizer, R.drawable.history, R.drawable.stats, R.drawable.playlist_add,
                R.drawable.radio, R.drawable.arrow_back, R.drawable.navigate_next, R.drawable.volume_up,
            )
        for (id in mustMap) assertNotNull("id $id has no Bulat vector", BulatIconTable.vectorFor(id))
    }

    @Test
    fun `brand logos and unknown ids keep their drawable`() {
        assertNull(BulatIconTable.vectorFor(R.drawable.github))
        assertNull(BulatIconTable.vectorFor(R.drawable.discord))
        assertNull(BulatIconTable.vectorFor(R.drawable.telegram))
        assertNull(BulatIconTable.vectorFor(0))
    }

    @Test
    fun `a family that was never stored reads as Bulat and a stored one is kept`() {
        assertEquals(IconFamily.ROUND, IconFamily.DEFAULT)
        assertEquals(IconFamily.ROUND, (null as String?).toEnum(IconFamily.DEFAULT))
        assertEquals(IconFamily.ROUND, "unknown".toEnum(IconFamily.DEFAULT))
        assertEquals(IconFamily.LINE, "LINE".toEnum(IconFamily.DEFAULT))
        assertEquals(IconFamily.IOS, "IOS".toEnum(IconFamily.DEFAULT))
        assertEquals(IconFamily.ROUND, "ROUND".toEnum(IconFamily.DEFAULT))
    }

    @Test
    fun `the stored names do not change`() {
        assertEquals(listOf("LINE", "IOS", "ROUND"), IconFamily.values().map { it.name })
    }
}
