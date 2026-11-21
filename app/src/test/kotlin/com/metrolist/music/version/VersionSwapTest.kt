/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0
 */

package com.metrolist.music.version

import androidx.media3.common.MediaItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.playback.queues.YouTubeQueue
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
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VersionSwapTest {
    private fun meta(
        id: String,
        title: String = "Negoro Angin",
        type: String? = MUSIC_VIDEO_TYPE_ATV,
        suggestedBy: String? = null,
    ) = MediaMetadata(
        id = id,
        title = title,
        artists = listOf(MediaMetadata.Artist("UC1", "Denny Caknan")),
        duration = 224,
        musicVideoType = type,
        suggestedBy = suggestedBy,
    )

    @Test
    fun `swap target is the counterpart with the queue identity of the original`() {
        val row =
            SongItem(
                id = "aud",
                title = "Negoro Angin",
                artists = listOf(Artist("Denny Caknan", "UC1")),
                duration = 224,
                musicVideoType = MUSIC_VIDEO_TYPE_ATV,
                thumbnail = "https://example.com/aud.jpg",
            )
        val target = VersionSwap.targetMetadata(meta("omv", type = MUSIC_VIDEO_TYPE_OMV, suggestedBy = "radio"), Counterpart(row, isVideo = false))
        assertEquals("aud", target.id)
        assertEquals("radio", target.suggestedBy)
        assertFalse(target.isVideoSong)
        // a like belongs to the id: the new recording does not inherit it
        assertFalse(target.liked)
    }

    // ---- prefer audio: which song counts as the one that starts ----

    @Test
    fun `a music video started as a radio is a candidate, a catalogue song is not`() {
        val video = meta("omv", type = MUSIC_VIDEO_TYPE_OMV)
        val song = meta("aud")
        assertEquals("omv", PreferAudio.startCandidate(YouTubeQueue(WatchEndpoint(videoId = "omv"), video), null)?.id)
        assertNull(PreferAudio.startCandidate(YouTubeQueue(WatchEndpoint(videoId = "aud"), song), null))
        // a radio without a known first song has nothing to swap
        assertNull(PreferAudio.startCandidate(YouTubeQueue(WatchEndpoint(videoId = "omv")), null))
        // a search row that only looks like a video (wide thumbnail) is passed in explicitly
        assertEquals("aud", PreferAudio.startCandidate(ListQueue(items = listOf(song.toMediaItem())), song)?.id)
    }

    @Test
    fun `the user's own list queues never get a swap`() {
        val video = meta("omv", type = MUSIC_VIDEO_TYPE_OMV)
        // library, downloads, liked songs, playlists and history all start a ListQueue without a forced song
        assertNull(PreferAudio.startCandidate(ListQueue(items = listOf(video.toMediaItem())), null))
        val q = ListQueue(items = listOf(meta("a").toMediaItem(), video.toMediaItem()), startIndex = 1)
        assertNull(PreferAudio.startCandidate(q, null))
    }

    @Test
    fun `device songs and episodes are never candidates`() {
        assertNull(PreferAudio.startCandidate(ListQueue(items = listOf(meta("LOCAL_7").toMediaItem())), meta("LOCAL_7", type = MUSIC_VIDEO_TYPE_OMV)))
        assertNull(PreferAudio.startCandidate(YouTubeQueue(WatchEndpoint(videoId = "ep"), meta("ep", type = MUSIC_VIDEO_TYPE_OMV).copy(isEpisode = true)), null))
    }

    @Test
    fun `liked, library, downloaded and device songs count as saved`() {
        assertFalse(VersionSwap.isSavedByUser(null))
        assertFalse(VersionSwap.isSavedByUser(SongEntity(id = "x", title = "t")))
        assertTrue(VersionSwap.isSavedByUser(SongEntity(id = "x", title = "t", liked = true)))
        assertTrue(VersionSwap.isSavedByUser(SongEntity(id = "x", title = "t", inLibrary = LocalDateTime.now())))
        assertTrue(VersionSwap.isSavedByUser(SongEntity(id = "x", title = "t", isDownloaded = true)))
        assertTrue(VersionSwap.isSavedByUser(SongEntity(id = "x", title = "t", dateDownload = LocalDateTime.now())))
        assertTrue(VersionSwap.isSavedByUser(SongEntity(id = "x", title = "t", isLocal = true)))
    }

    @Test
    fun `a tapped row stays active after its audio version starts`() {
        PlayedAs.clear()
        assertFalse(PlayedAs.isActive("omv", "aud"))
        PlayedAs.record("omv", "aud")
        assertTrue(PlayedAs.isActive("omv", "aud"))
        assertTrue(PlayedAs.isActive("omv", "omv"))
        assertFalse(PlayedAs.isActive("omv", "other"))
        assertFalse(PlayedAs.isActive("omv", null))
        // an unrelated row is not affected
        assertFalse(PlayedAs.isActive("zzz", "aud"))
        PlayedAs.clear()
    }

    @Test
    fun `played-as memory is bounded and ignores identical or blank ids`() {
        PlayedAs.clear()
        PlayedAs.record("a", "a")
        PlayedAs.record("", "b")
        assertFalse(PlayedAs.isActive("", "b"))
        repeat(200) { PlayedAs.record("v$it", "a$it") }
        assertFalse(PlayedAs.isActive("v0", "a0"))
        assertTrue(PlayedAs.isActive("v199", "a199"))
        PlayedAs.clear()
    }

    @Test
    fun `only the tapped item is replaced and the rest of the queue is untouched`() =
        runBlocking {
            val video = meta("omv", type = MUSIC_VIDEO_TYPE_OMV)
            val audio = meta("aud")
            val items = listOf(Inner.meta2("a"), video, Inner.meta2("b"), audio, Inner.meta2("c")).map { it.toMediaItem() }
            val queue = AudioFirstQueue(Inner(Queue.Status("T", items, 1), preloadItem = video), video, audio)

            val status = queue.getInitialStatus()
            // the later copy of the audio recording is removed so it does not repeat right away
            assertEquals(listOf("a", "aud", "b", "c"), status.items.map { it.mediaId })
            assertEquals(1, status.mediaItemIndex)
            assertEquals("T", status.title)
            assertEquals("aud", queue.preloadItem?.id)
            assertTrue(queue.hasNextPage())
            assertEquals("next", queue.nextPage().single().mediaId)
        }

    @Test
    fun `without a preload item the wrapper stays without one`() =
        runBlocking {
            val video = meta("omv", type = MUSIC_VIDEO_TYPE_OMV)
            val queue = AudioFirstQueue(Inner(Queue.Status(null, listOf(video.toMediaItem()), 0), preloadItem = null), video, meta("aud"))
            assertNull(queue.preloadItem)
            assertEquals("aud", queue.getInitialStatus().items.single().mediaId)
        }

    @Test
    fun `a queue that does not hold the video is returned as it is`() =
        runBlocking {
            val video = meta("omv", type = MUSIC_VIDEO_TYPE_OMV)
            val items = listOf(Inner.meta2("a").toMediaItem())
            val status = AudioFirstQueue(Inner(Queue.Status(null, items, 0), null), video, meta("aud")).getInitialStatus()
            assertEquals(listOf("a"), status.items.map { it.mediaId })
            assertNotNull(status)
        }

    /** A queue with a fixed first status and one more page ("next"), the inner queue of the wrapper under test. */
    private class Inner(
        private val status: Queue.Status,
        override val preloadItem: MediaMetadata?,
    ) : Queue {
        override suspend fun getInitialStatus(): Queue.Status = status

        override fun hasNextPage(): Boolean = true

        override suspend fun nextPage(): List<MediaItem> = listOf(meta2("next").toMediaItem())

        companion object {
            fun meta2(id: String) =
                MediaMetadata(
                    id = id,
                    title = id,
                    artists = listOf(MediaMetadata.Artist("UC1", "Denny Caknan")),
                    duration = 224,
                )
        }
    }
}
