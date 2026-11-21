package com.metrolist.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsProviderRegistryTest {
    @Test
    fun `new providers are registered and in the default order`() {
        val defaults = LyricsProviderRegistry.getDefaultProviderOrder()
        listOf("SimpMusic", "BiniLyrics", "LrcRed", "Unison").forEach { name ->
            assertNotNull(name, LyricsProviderRegistry.getProviderByName(name))
            assertTrue(name, name in defaults)
        }
        assertEquals(defaults.toSet(), LyricsProviderRegistry.providerNames.toSet())
        assertEquals(defaults.size, defaults.distinct().size)
        // the YouTube fallbacks stay last
        assertEquals(listOf("YouTubeSubtitle", "YouTube"), defaults.takeLast(2))
    }

    @Test
    fun `an order saved before the update gets the new providers above the youtube fallbacks`() {
        val saved = "Zemer,BetterLyrics,LrcLib,KuGou,Paxsenix,LyricsPlus,YouTubeSubtitle,YouTube"
        val order = LyricsProviderRegistry.deserializeProviderOrder(saved)
        // the user's own order is kept (new providers may be slotted in between)
        assertEquals(saved.split(","), order.filter { it in saved.split(",") })
        assertEquals("Zemer", order.first())
        val youtubeSubtitle = order.indexOf("YouTubeSubtitle")
        listOf("SimpMusic", "BiniLyrics", "LrcRed", "Unison").forEach { name ->
            assertTrue("$name should come before the YouTube fallbacks", order.indexOf(name) in 0 until youtubeSubtitle)
        }
        assertEquals(LyricsProviderRegistry.getDefaultProviderOrder().toSet(), order.toSet())
        assertEquals(order.size, order.distinct().size)
    }

    @Test
    fun `an empty or unknown saved order falls back to the defaults`() {
        assertEquals(LyricsProviderRegistry.getDefaultProviderOrder(), LyricsProviderRegistry.deserializeProviderOrder(""))
        val order = LyricsProviderRegistry.deserializeProviderOrder("Nope,LrcLib")
        assertEquals("LrcLib", order.first())
        assertEquals(LyricsProviderRegistry.getDefaultProviderOrder().toSet(), order.toSet())
    }
}
