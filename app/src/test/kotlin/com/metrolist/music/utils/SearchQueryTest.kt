package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchQueryTest {
    @Test
    fun `blank query gives no pattern`() {
        assertNull(likePatternOrNull(""))
        assertNull(likePatternOrNull("   "))
    }

    @Test
    fun `text is wrapped and trimmed`() {
        assertEquals("%negoro angin%", likePatternOrNull("  negoro angin "))
    }

    @Test
    fun `like wildcards typed by the user are matched literally`() {
        assertEquals("%100\\% cinta%", likePatternOrNull("100% cinta"))
        assertEquals("%a\\_b%", likePatternOrNull("a_b"))
        assertEquals("%a\\\\b%", likePatternOrNull("a\\b"))
    }
}
