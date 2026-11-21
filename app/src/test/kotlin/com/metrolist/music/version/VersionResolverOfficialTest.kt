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

/** YouTube's own link first, the title search second; and the longer window for a video that opens with an intro. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VersionResolverOfficialTest {
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

    // real numbers from the live run: Denny Caknan, Kartonyono Medot Janji, 4:31 against a video of 4:50
    private val audio = item("NVc2La5AkGA", "Kartonyono Medot Janji", "Denny Caknan", 271, MUSIC_VIDEO_TYPE_ATV)
    private val video = item("WlmWXoP0C0s", "Kartonyono Medot Janji", "Denny Caknan", 290, MUSIC_VIDEO_TYPE_OMV, wide = true)

    private class Calls {
        val searches = ArrayList<Pair<String, YouTube.SearchFilter>>()
        val official = ArrayList<String>()
    }

    private fun resolver(
        calls: Calls,
        rows: List<SongItem>? = emptyList(),
        official: (suspend (String) -> Result<OfficialCounterpart?>)? = null,
        officialTimeoutMs: Long = 500,
    ) = VersionResolver(
        search = { q, f ->
            calls.searches += q to f
            rows
        },
        searchTimeoutMs = 500,
        official =
            official?.let { fn ->
                { id ->
                    calls.official += id
                    fn(id)
                }
            },
        officialTimeoutMs = officialTimeoutMs,
    )

    @Test
    fun `the official link answers before any search is made`() =
        runBlocking {
            val calls = Calls()
            val r = resolver(calls, rows = listOf(video), official = { Result.success(OfficialCounterpart(video, offsetMs = 12_000L, segments = 1)) })
            val found = r.counterpartOf(audio.toTrackInfo())
            assertEquals("WlmWXoP0C0s", found?.item?.id)
            assertTrue(found!!.isVideo)
            assertTrue(found.official)
            assertEquals(12_000L, found.offsetMs)
            assertTrue("no title search at all", calls.searches.isEmpty())
            assertEquals(listOf("NVc2La5AkGA"), calls.official)
            // and it is kept
            assertEquals("WlmWXoP0C0s", r.peek("NVc2La5AkGA")?.counterpart?.item?.id)
        }

    @Test
    fun `no official link, a failed one or a slow one falls back to the title search`() =
        runBlocking {
            for (answer in listOf<suspend (String) -> Result<OfficialCounterpart?>>(
                { Result.success(null) },
                { Result.failure(java.io.IOException("offline")) },
                { delay(5_000); Result.success(null) },
            )) {
                val calls = Calls()
                val r = resolver(calls, rows = listOf(video), official = answer, officialTimeoutMs = 80)
                val found = r.counterpartOf(audio.toTrackInfo())
                assertEquals("WlmWXoP0C0s", found?.item?.id)
                assertFalse(found!!.official)
                assertNull(found.offsetMs)
                assertEquals(1, calls.official.size)
                assertTrue(calls.searches.isNotEmpty())
            }
        }

    @Test
    fun `an official link that points at the asked song itself is ignored`() =
        runBlocking {
            val calls = Calls()
            val r = resolver(calls, rows = emptyList(), official = { Result.success(OfficialCounterpart(audio, offsetMs = 0L, segments = 1)) })
            assertNull(r.counterpartOf(audio.toTrackInfo()))
        }

    @Test
    fun `a video source asks for its audio and never for the official link`() =
        runBlocking {
            val calls = Calls()
            val r = resolver(calls, rows = listOf(audio), official = { Result.success(OfficialCounterpart(video, 0L, 1)) })
            // the audio is 19 s SHORTER than this video: the audio direction keeps the old 15 s window, so use a 9 s difference
            val shortIntro = video.copy(duration = 280)
            val found = r.counterpartOf(shortIntro.toTrackInfo())
            assertEquals("NVc2La5AkGA", found?.item?.id)
            assertFalse(found!!.isVideo)
            assertTrue(calls.official.isEmpty())
        }

    @Test
    fun `without an official function the resolver behaves as before`() =
        runBlocking {
            val calls = Calls()
            val found = resolver(calls, rows = listOf(video)).counterpartOf(audio.toTrackInfo())
            assertEquals("WlmWXoP0C0s", found?.item?.id)
            assertTrue(calls.official.isEmpty())
        }

    // ------------------------------------------------------------------------------------------------ the window for a video with an intro

    @Test
    fun `a music video longer by up to 45 seconds is found, 19 s and 37 s are the real cases of the live run`() =
        runBlocking {
            val cases = listOf(271 to 290, 289 to 326, 200 to 245)
            for ((songSec, videoSec) in cases) {
                val a = audio.copy(duration = songSec)
                val v = video.copy(duration = videoSec)
                val found = resolver(Calls(), rows = listOf(v)).counterpartOf(a.toTrackInfo())
                assertEquals("$songSec s against $videoSec s", v.id, found?.item?.id)
            }
        }

    @Test
    fun `a video longer by more than 45 seconds, or shorter by more than 15, is still no match`() =
        runBlocking {
            val longer = video.copy(duration = 271 + 46)
            assertNull(resolver(Calls(), rows = listOf(longer)).counterpartOf(audio.toTrackInfo()))
            val shorter = video.copy(duration = 271 - 16)
            assertNull(resolver(Calls(), rows = listOf(shorter)).counterpartOf(audio.toTrackInfo()))
            val edge = video.copy(duration = 271 - 15)
            assertNotNull(resolver(Calls(), rows = listOf(edge)).counterpartOf(audio.toTrackInfo()))
        }

    @Test
    fun `the longer window is only for the video direction, the audio of a video keeps the old window`() =
        runBlocking {
            // the catalogue audio is 19 s LONGER than the video: that is not an intro, it is another recording
            val v = video.copy(duration = 252)
            val a = audio.copy(duration = 271)
            assertNull(resolver(Calls(), rows = listOf(a)).counterpartOf(v.toTrackInfo()))
        }

    @Test
    fun `an answer of none is forgotten after half an hour, a found counterpart stays`() =
        runBlocking {
            var now = 0L
            var rows: List<SongItem>? = emptyList()
            val r = VersionResolver(search = { _, _ -> rows }, searchTimeoutMs = 500, clock = { now })
            assertNull(r.counterpartOf(audio.toTrackInfo()))
            assertNotNull("known: none", r.peek(audio.id))
            assertNull(r.peek(audio.id)!!.counterpart)
            now += 29 * 60_000L
            assertNotNull(r.peek(audio.id))
            now += 2 * 60_000L
            assertNull("forgotten", r.peek(audio.id))
            rows = listOf(video)
            assertEquals("asked again and found", video.id, r.counterpartOf(audio.toTrackInfo())?.item?.id)
            now += 5 * 60 * 60_000L
            assertEquals("a found counterpart stays", video.id, r.peek(audio.id)?.counterpart?.item?.id)
        }

    @Test
    fun `of two videos the one with the closer length wins`() =
        runBlocking {
            val far = video.copy(id = "far", duration = 271 + 40)
            val near = video.copy(id = "near", duration = 271 + 19)
            val found = resolver(Calls(), rows = listOf(far, near)).counterpartOf(audio.toTrackInfo())
            assertEquals("near", found?.item?.id)
        }
}
