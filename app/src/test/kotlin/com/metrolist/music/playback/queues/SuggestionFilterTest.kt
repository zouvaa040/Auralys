/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What autoplay / radio pages may add: "algoritma keulang-ulang" (the same songs again and again). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SuggestionFilterTest {
    private fun meta(
        id: String,
        title: String = "Title $id",
        artist: String = "Artist $id",
    ) = MediaMetadata(id = id, title = title, artists = listOf(MediaMetadata.Artist(null, artist)), duration = 200)

    private fun item(
        id: String,
        title: String = "Title $id",
        artist: String = "Artist $id",
    ) = meta(id, title, artist).toMediaItem()

    private fun ids(list: List<androidx.media3.common.MediaItem>) = list.map { it.mediaId }

    @Test
    fun `a radio page never adds a song that is already in the queue or twice in one batch`() {
        val queue = listOf(item("a"), item("b"), item("c"))
        val page = listOf(item("b"), item("d"), item("d"), item("a"), item("e"))
        assertEquals(listOf("d", "e"), ids(SuggestionFilter.filter(page, queue, RecentPlays(), noRepeat = false)))
        assertEquals(listOf("d", "e"), ids(SuggestionFilter.filter(page, queue, RecentPlays(), noRepeat = true)))
    }

    @Test
    fun `with no-repeat on a song heard in this session does not come back, with it off it may`() {
        val recent = RecentPlays()
        recent.add(meta("x"))
        recent.add(meta("y"))
        val page = listOf(item("x"), item("z"), item("y"))
        assertEquals(listOf("z"), ids(SuggestionFilter.filter(page, emptyList(), recent, noRepeat = true)))
        assertEquals(listOf("x", "z", "y"), ids(SuggestionFilter.filter(page, emptyList(), recent, noRepeat = false)))
    }

    @Test
    fun `the music video of a song in the queue is the same song`() {
        val queue = listOf(item("audio1", "Kesariya", "Arijit Singh"))
        val page =
            listOf(
                item("video1", "Kesariya (Official Video)", "Arijit Singh"),
                item("video2", "KESARIYA [4K]", "Arijit Singh - Topic"),
                item("remix", "Kesariya (Remix)", "Arijit Singh"),
                item("other", "Kesariya", "Someone Else"),
            )
        assertEquals(listOf("remix", "other"), ids(SuggestionFilter.filter(page, queue, RecentPlays(), noRepeat = true)))
        // without no-repeat only the ids count
        assertEquals(4, SuggestionFilter.filter(page, queue, RecentPlays(), noRepeat = false).size)
        // and a cut heard earlier in the session counts as heard
        val recent = RecentPlays().apply { add(meta("audio9", "Dracula", "Tame Impala")) }
        assertEquals(
            emptyList<String>(),
            ids(SuggestionFilter.filter(listOf(item("v9", "Dracula (Official Music Video)", "Tame Impala")), emptyList(), recent, noRepeat = true)),
        )
    }

    @Test
    fun `titles are normalised but remix, live and cover stay apart`() {
        assertEquals("kesariya", SuggestionFilter.normalisedTitle("Kesariya (Official Video) | Brahmastra"))
        assertEquals("song", SuggestionFilter.normalisedTitle("Song [Lyrics]"))
        assertEquals("song live", SuggestionFilter.normalisedTitle("Song (Live)"))
        assertEquals("rex orange county", SuggestionFilter.normalisedArtist("Rex Orange County - Topic"))
        assertEquals("niki", SuggestionFilter.normalisedArtist("NIKI, Rich Brian"))
        assertEquals(null, SuggestionFilter.recordingKey(meta("q", title = " (Official Video) ")))
    }

    @Test
    fun `recent plays forget the oldest songs`() {
        val recent = RecentPlays(capacity = 3)
        listOf("a", "b", "c", "d").forEach { recent.add(meta(it)) }
        assertEquals(3, recent.size())
        assertFalse(recent.contains("a", SuggestionFilter.recordingKey(meta("a"))))
        assertTrue(recent.contains("d", null))
        // playing a song again moves it to the newest end
        recent.add(meta("b"))
        recent.add(meta("e"))
        assertTrue(recent.contains("b", null))
        assertFalse(recent.contains("c", null))
    }

    @Test
    fun `finding 26 - keys are cached per title and artist and stay correct`() {
        val a = meta("k1", "Kesariya (Official Video)", "Arijit Singh")
        val first = SuggestionFilter.recordingKey(a)
        val before = SuggestionFilter.computedKeys()
        // the same title + artist under another id (the music video) is answered from the cache
        assertEquals(first, SuggestionFilter.recordingKey(meta("k2", "Kesariya (Official Video)", "Arijit Singh")))
        assertEquals(before, SuggestionFilter.computedKeys())
        // a title without a key is cached as "no key", not recomputed into something else
        assertEquals(null, SuggestionFilter.recordingKey(meta("q1", " (Official Video) ")))
        assertEquals(null, SuggestionFilter.recordingKey(meta("q2", " (Official Video) ")))
        // another artist is another key
        assertTrue(SuggestionFilter.recordingKey(meta("k3", "Kesariya (Official Video)", "Someone")) != first)
    }

    @Test
    fun `finding 26 - a 5000-song queue is filtered quickly and the second batch is cheaper`() {
        val queue = (1..5_000).map { item("q$it", "Song number $it (Official Video)", "Artist ${it % 300}") }
        val page = (1..50).map { item("p$it", "Fresh $it", "New ${it % 7}") }
        val t0 = System.nanoTime()
        val first = SuggestionFilter.filter(page, queue, RecentPlays(), noRepeat = true)
        val firstMs = (System.nanoTime() - t0) / 1_000_000
        val computed = SuggestionFilter.computedKeys()
        val second = SuggestionFilter.filter(page, queue, RecentPlays(), noRepeat = true)
        assertEquals(50, first.size)
        assertEquals(ids(first), ids(second))
        // the second batch over the same queue computes no key again (all 5 050 come from the cache)
        assertEquals(computed, SuggestionFilter.computedKeys())
        // a generous bound (a loaded test machine)
        assertTrue("first $firstMs ms", firstMs < 3_000)
    }

    @Test
    fun `finding 26 - recent plays answer by key after the song that brought the key is forgotten`() {
        val recent = RecentPlays(capacity = 2)
        recent.add(meta("a1", "Dracula", "Tame Impala"))
        recent.add(meta("a2", "Dracula (Official Video)", "Tame Impala")) // same recording, other id
        recent.add(meta("b", "Other", "X")) // a1 is forgotten, a2 still holds the key
        val key = SuggestionFilter.recordingKey(meta("v", "Dracula", "Tame Impala"))
        assertTrue(recent.contains("v", key))
        recent.add(meta("c", "Third", "Y")) // a2 forgotten too: the key is gone
        assertFalse(recent.contains("v", key))
        // the same id played again with a new title does not keep its old key
        recent.add(meta("c", "Renamed", "Y"))
        assertFalse(recent.contains("zz", SuggestionFilter.recordingKey(meta("x", "Third", "Y"))))
    }

    @Test
    fun `only radios are filtered against what was heard`() {
        assertTrue(SuggestionFilter.isRadioPlaylistId(null))
        assertTrue(SuggestionFilter.isRadioPlaylistId("RDAMVMabc"))
        assertTrue(SuggestionFilter.isRadioPlaylistId("RDEMxyz"))
        assertFalse(SuggestionFilter.isRadioPlaylistId("RDCLAK5uy_curated"))
        assertFalse(SuggestionFilter.isRadioPlaylistId("PLabc"))
        assertFalse(SuggestionFilter.isRadioPlaylistId("OLAK5uy_album"))
        assertTrue(YouTubeQueue(WatchEndpoint(videoId = "v")).pagesAreSuggestions)
        assertTrue(YouTubeQueue.radio(meta("v")).pagesAreSuggestions)
        assertFalse(YouTubeQueue(WatchEndpoint(playlistId = "PLx")).pagesAreSuggestions)
        assertFalse(YouTubePlaylistQueue("PLx").pagesAreSuggestions)
        assertFalse(ListQueue(items = emptyList()).pagesAreSuggestions)
    }
}
