/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0
 */

package com.metrolist.music.version

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VersionResolverTest {
    private fun item(
        id: String,
        title: String,
        artist: String,
        sec: Int,
        type: String?,
        wide: Boolean = false,
    ) = SongItem(
        id = id,
        title = title,
        artists = listOf(Artist(artist, "UC$artist")),
        duration = sec,
        musicVideoType = type,
        thumbnail = "https://example.com/$id.jpg",
        isWideThumb = wide,
    )

    private val audioRow = item("aud", "Negoro Angin", "Denny Caknan", 224, MUSIC_VIDEO_TYPE_ATV)
    private val videoRow = item("omv", "NEGORO ANGIN (Official Music Video)", "Denny Caknan", 231, MUSIC_VIDEO_TYPE_OMV, wide = true)

    /** Fake search: records every call, answers per filter. */
    private class FakeSearch(
        val songs: List<SongItem>?,
        val videos: List<SongItem>?,
    ) {
        val calls = mutableListOf<Pair<String, YouTube.SearchFilter>>()

        suspend fun search(
            query: String,
            filter: YouTube.SearchFilter,
        ): List<SongItem>? {
            calls += query to filter
            return if (filter == YouTube.SearchFilter.FILTER_VIDEO) videos else songs
        }
    }

    private fun resolver(fake: FakeSearch) = VersionResolver(search = fake::search, searchTimeoutMs = 500)

    @Test
    fun `video gets its catalogue audio through a Songs search`() =
        runBlocking {
            val fake = FakeSearch(songs = listOf(item("cover", "Negoro Angin (Cover)", "Nina", 230, MUSIC_VIDEO_TYPE_ATV), audioRow), videos = emptyList())
            val result = resolver(fake).counterpartOf(videoRow.toTrackInfo())
            assertEquals("aud", result?.item?.id)
            assertFalse(result!!.isVideo)
            assertEquals(YouTube.SearchFilter.FILTER_SONG, fake.calls.first().second)
            // the raw video title is never searched
            assertTrue(fake.calls.none { it.first.contains("official", ignoreCase = true) })
        }

    @Test
    fun `audio song gets its music video through a Videos search`() =
        runBlocking {
            val fake = FakeSearch(songs = emptyList(), videos = listOf(videoRow))
            val result = resolver(fake).counterpartOf(audioRow.toTrackInfo())
            assertEquals("omv", result?.item?.id)
            assertTrue(result!!.isVideo)
            assertEquals(YouTube.SearchFilter.FILTER_VIDEO, fake.calls.first().second)
        }

    @Test
    fun `second question is answered from the session cache`() =
        runBlocking {
            val fake = FakeSearch(songs = listOf(audioRow), videos = emptyList())
            val r = resolver(fake)
            r.counterpartOf(videoRow.toTrackInfo())
            val calls = fake.calls.size
            assertEquals("aud", r.counterpartOf(videoRow.toTrackInfo())?.item?.id)
            assertEquals(calls, fake.calls.size)
            assertEquals("aud", r.peek("omv")?.counterpart?.item?.id)
        }

    @Test
    fun `no match is cached too, so the menu does not search again`() =
        runBlocking {
            val fake = FakeSearch(songs = listOf(item("x", "Something Else", "Z", 200, MUSIC_VIDEO_TYPE_ATV)), videos = emptyList())
            val r = resolver(fake)
            assertNull(r.counterpartOf(videoRow.toTrackInfo()))
            val calls = fake.calls.size
            assertNull(r.counterpartOf(videoRow.toTrackInfo()))
            assertEquals(calls, fake.calls.size)
            assertNotNull(r.peek("omv"))
            assertNull(r.peek("omv")?.counterpart)
        }

    @Test
    fun `a failed search is not remembered and nothing is thrown`() =
        runBlocking {
            val fake = FakeSearch(songs = null, videos = null)
            val r = resolver(fake)
            assertNull(r.counterpartOf(videoRow.toTrackInfo()))
            assertNull(r.peek("omv"))
            val boom = VersionResolver(search = { _, _ -> error("network down") })
            assertNull(boom.counterpartOf(videoRow.toTrackInfo()))
        }

    @Test
    fun `a slow search times out instead of hanging`() =
        runBlocking {
            val slow = VersionResolver(search = { _, _ -> delay(60_000); emptyList() }, searchTimeoutMs = 50)
            assertNull(slow.counterpartOf(videoRow.toTrackInfo()))
        }

    @Test
    fun `device songs and episodes are never offered and never searched`() =
        runBlocking {
            val fake = FakeSearch(songs = listOf(audioRow), videos = listOf(videoRow))
            val r = resolver(fake)
            val local = audioRow.toTrackInfo().copy(id = "LOCAL_42")
            assertFalse(r.canOffer(local))
            assertNull(r.counterpartOf(local))
            val episode = audioRow.toTrackInfo().copy(isEpisode = true)
            assertFalse(r.canOffer(episode))
            assertNull(r.counterpartOf(episode))
            assertFalse(r.canOffer(audioRow.toTrackInfo().copy(artists = emptyList())))
            assertTrue(fake.calls.isEmpty())
            assertTrue(r.canOffer(audioRow.toTrackInfo()))
        }

    @Test
    fun `song item flags decide the direction`() {
        assertTrue(videoRow.toTrackInfo().isVideo)
        assertTrue(item("w", "x", "y", 1, null, wide = true).toTrackInfo().isVideo)
        assertFalse(audioRow.toTrackInfo().isVideo)
    }

    @Test
    fun `cache keeps at most the configured number of entries`() =
        runBlocking {
            val fake = FakeSearch(songs = emptyList(), videos = emptyList())
            val r = VersionResolver(search = fake::search, maxEntries = 2)
            listOf("a", "b", "c").forEach { r.counterpartOf(audioRow.toTrackInfo().copy(id = it)) }
            assertNull(r.peek("a"))
            assertNotNull(r.peek("c"))
        }

    @Test
    fun `a lookup goes on after the caller stopped waiting and lands in the cache`() =
        runBlocking {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val calls = java.util.concurrent.atomic.AtomicInteger(0)
            val slow: suspend (String, YouTube.SearchFilter) -> List<SongItem>? = { _, _ ->
                calls.incrementAndGet()
                gate.await()
                listOf(audioRow)
            }
            val r = VersionResolver(search = slow, searchTimeoutMs = 5_000)
            val lookup = r.lookupInBackground(videoRow.toTrackInfo())
            // the caller gives up early, like the 2.5 s limit of a tap
            assertNull(kotlinx.coroutines.withTimeoutOrNull(50) { lookup.await() })
            assertNull(r.peek("omv"))
            // a second ask joins the same lookup
            assertTrue(r.lookupInBackground(videoRow.toTrackInfo()) === lookup)
            gate.complete(Unit)
            assertEquals("aud", lookup.await()?.item?.id)
            assertEquals("aud", r.peek("omv")?.counterpart?.item?.id)
            assertEquals(1, calls.get())
            // known now: answered at once, no new search
            assertEquals("aud", r.lookupInBackground(videoRow.toTrackInfo()).await()?.item?.id)
            assertEquals(1, calls.get())
        }
}
