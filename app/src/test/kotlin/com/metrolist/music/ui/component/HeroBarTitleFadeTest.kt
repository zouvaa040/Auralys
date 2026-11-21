/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The title in the top bar of every hero page fades with the same number as the scrim behind the bar (REVIEW-2026-11-07
 * F8). Three pages used to switch it on when the whole header item had left the list, 150 to 200 dp after the scrim. This
 * reads the sources like DesignGuardTest does.
 */
class HeroBarTitleFadeTest {
    private val root: File =
        listOf("src/main/kotlin", "app/src/main/kotlin")
            .map { File(it, "com/metrolist/music") }
            .firstOrNull { it.isDirectory }
            ?: error("sources not found from ${File(".").absolutePath}")

    private val pages =
        listOf(
            "ui/screens/AlbumScreen.kt", "ui/screens/artist/ArtistScreen.kt", "ui/screens/playlist/LocalPlaylistScreen.kt",
            "ui/screens/playlist/OnlinePlaylistScreen.kt", "ui/screens/playlist/AutoPlaylistScreen.kt",
            "ui/screens/playlist/TopPlaylistScreen.kt", "ui/screens/playlist/CachePlaylistScreen.kt", "ui/screens/podcast/OnlinePodcastScreen.kt",
        )

    @Test
    fun `every hero page fades its bar title with barFade and none switches it on by the list index`() {
        for (page in pages) {
            val text = File(root, page).readText()
            assertTrue("$page: the bar title does not read barFade()", text.contains("alpha = barFade()"))
            assertTrue("$page: a bar title is switched on by firstVisibleItemIndex", !text.contains("firstVisibleItemIndex > 0"))
        }
    }
}
