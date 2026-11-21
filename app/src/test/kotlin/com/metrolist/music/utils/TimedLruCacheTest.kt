/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0
 */

package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimedLruCacheTest {
    private var clock = 0L

    private fun cache(
        max: Int = 3,
        ttl: Long = 1000,
    ) = TimedLruCache<String, Int>(max, ttl) { clock }

    @Test
    fun `value is returned until it is older than the ttl`() {
        val c = cache()
        c["a"] = 1
        clock = 1000
        assertEquals(1, c["a"])
        clock = 1001
        assertNull(c["a"])
        assertEquals(0, c.size)
    }

    @Test
    fun `least recently used entry leaves first`() {
        val c = cache(max = 2)
        c["a"] = 1
        c["b"] = 2
        c["a"] // touch a
        c["c"] = 3
        assertEquals(1, c["a"])
        assertNull(c["b"])
        assertEquals(3, c["c"])
    }

    @Test
    fun `writing again refreshes the age`() {
        val c = cache()
        c["a"] = 1
        clock = 900
        c["a"] = 2
        clock = 1500
        assertEquals(2, c["a"])
    }

    @Test
    fun `clear empties the cache`() {
        val c = cache()
        c["a"] = 1
        c.clear()
        assertNull(c["a"])
    }

    @Test
    fun `a partial or failed answer is not stored`() {
        val c = cache()
        assertEquals(false, c.putIfComplete("a", 1, complete = false))
        assertNull(c["a"])
        assertEquals(0, c.size)
        assertEquals(true, c.putIfComplete("a", 2, complete = true))
        assertEquals(2, c["a"])
        // a later partial answer does not replace the complete one
        assertEquals(false, c.putIfComplete("a", 3, complete = false))
        assertEquals(2, c["a"])
    }
}
