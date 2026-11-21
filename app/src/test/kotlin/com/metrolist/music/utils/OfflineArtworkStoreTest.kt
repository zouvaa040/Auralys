/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OfflineArtworkStoreTest {
    @Test
    fun `one cover at different sizes shares a key`() {
        val base = "https://lh3.googleusercontent.com/AbCdEf123"
        assertEquals(
            OfflineArtworkStore.keyOf("$base=w544-h544-l90-rj"),
            OfflineArtworkStore.keyOf("$base=w132-h132-p-l90-rj"),
        )
    }

    @Test
    fun `query string is ignored for video thumbnails`() {
        assertEquals(
            OfflineArtworkStore.keyOf("https://i.ytimg.com/vi/abc/hqdefault.jpg"),
            OfflineArtworkStore.keyOf("https://i.ytimg.com/vi/abc/hqdefault.jpg?sqp=xyz&rs=123"),
        )
    }

    @Test
    fun `different covers keep different keys`() {
        assertNotEquals(
            OfflineArtworkStore.keyOf("https://lh3.googleusercontent.com/AAA=w544-h544"),
            OfflineArtworkStore.keyOf("https://lh3.googleusercontent.com/BBB=w544-h544"),
        )
    }
}
