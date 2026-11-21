/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0
 */

package com.metrolist.music.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SearchFlagsTest {
    private val base = SearchFlags(hideExplicit = false, hideVideoSongs = false, hideYoutubeShorts = false, musicOnly = true, pauseHistory = false)

    @Test
    fun `each filter setting changes the cache key`() {
        assertNotEquals(base.cacheKey, base.copy(hideExplicit = true).cacheKey)
        assertNotEquals(base.cacheKey, base.copy(hideVideoSongs = true).cacheKey)
        assertNotEquals(base.cacheKey, base.copy(hideYoutubeShorts = true).cacheKey)
        assertNotEquals(base.cacheKey, base.copy(musicOnly = false).cacheKey)
    }

    @Test
    fun `pausing the history does not split the cache`() {
        assertEquals(base.cacheKey, base.copy(pauseHistory = true).cacheKey)
    }
}
