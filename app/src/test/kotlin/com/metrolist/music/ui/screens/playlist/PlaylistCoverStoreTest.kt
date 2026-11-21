package com.metrolist.music.ui.screens.playlist

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

/**
 * Report "after confirming the photo it cannot be changed": every cover change must get its own file name (the image
 * cache is keyed by the address, so re-using one name keeps the old picture on screen) and the old file must go.
 */
class PlaylistCoverStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun bytes(seed: Int) = ByteArray(64) { (it * seed).toByte() }

    @Test
    fun `each change gets a new name and only the newest file stays`() {
        val dir = tmp.newFolder(PlaylistCoverStore.DIR)
        val first = PlaylistCoverStore.persist(dir, "LPabc", 1_000L) { ByteArrayInputStream(bytes(3)) }
        val second = PlaylistCoverStore.persist(dir, "LPabc", 2_000L) { ByteArrayInputStream(bytes(5)) }
        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals(first!!.name, second!!.name)
        assertFalse("old cover deleted", first.exists())
        assertTrue(second.exists())
        assertArrayEquals(bytes(5), second.readBytes())
        assertEquals(listOf(second.name), dir.list()!!.toList())
    }

    @Test
    fun `two changes in the same millisecond still get two names`() {
        val dir = tmp.newFolder(PlaylistCoverStore.DIR)
        val a = PlaylistCoverStore.persist(dir, "LPabc", 5_000L) { ByteArrayInputStream(bytes(3)) }!!
        val b = PlaylistCoverStore.persist(dir, "LPabc", 5_000L) { ByteArrayInputStream(bytes(7)) }!!
        assertNotEquals(a.name, b.name)
        assertArrayEquals(bytes(7), b.readBytes())
    }

    @Test
    fun `other playlists keep their covers`() {
        val dir = tmp.newFolder(PlaylistCoverStore.DIR)
        val other = PlaylistCoverStore.persist(dir, "LPother", 1_000L) { ByteArrayInputStream(bytes(2)) }!!
        PlaylistCoverStore.persist(dir, "LPabc", 1_000L) { ByteArrayInputStream(bytes(3)) }
        PlaylistCoverStore.persist(dir, "LPabc", 2_000L) { ByteArrayInputStream(bytes(4)) }
        assertTrue(other.exists())
        PlaylistCoverStore.clear(dir, "LPabc")
        assertTrue(other.exists())
        assertEquals(listOf(other.name), dir.list()!!.toList())
    }

    @Test
    fun `nothing readable or an empty picture changes nothing`() {
        val dir = tmp.newFolder(PlaylistCoverStore.DIR)
        val kept = PlaylistCoverStore.persist(dir, "LPabc", 1_000L) { ByteArrayInputStream(bytes(3)) }!!
        assertNull(PlaylistCoverStore.persist(dir, "LPabc", 2_000L) { null })
        assertNull(PlaylistCoverStore.persist(dir, "LPabc", 3_000L) { ByteArrayInputStream(ByteArray(0)) })
        assertTrue("the working cover survives a failed change", kept.exists())
        assertEquals(listOf(kept.name), dir.list()!!.toList())
    }

    @Test
    fun `file name carries the playlist id and the time`() {
        assertEquals("LPabc_123.jpg", PlaylistCoverStore.fileName("LPabc", 123L))
    }
}
