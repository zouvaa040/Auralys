/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.local

import coil3.toUri
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalCoverAndSortTest {
    @Test
    fun `cover size follows the request within limits`() {
        assertEquals(512, LocalCoverFetcher.sideFor(null, null))
        assertEquals(512, LocalCoverFetcher.sideFor(0, 0))
        assertEquals(96, LocalCoverFetcher.sideFor(40, 40))
        assertEquals(300, LocalCoverFetcher.sideFor(300, 200))
        assertEquals(1024, LocalCoverFetcher.sideFor(4000, 4000))
    }

    @Test
    fun `placeholder colour is the same for a song and differs between songs`() {
        assertEquals(LocalCoverPlaceholder.colorFor(5), LocalCoverPlaceholder.colorFor(5))
        assertNotEquals(LocalCoverPlaceholder.colorFor(5), LocalCoverPlaceholder.colorFor(6))
    }

    @Test
    fun `Coil finds the cover fetcher through its service file, so the widget and share cards get it too`() {
        @OptIn(coil3.annotation.InternalCoilApi::class)
        val targets = java.util.ServiceLoader.load(coil3.util.FetcherServiceLoaderTarget::class.java).toList()
        assertTrue(targets.any { it is LocalCoverFetcherTarget })
    }

    @Test
    fun `fetcher factory takes only localaudio addresses`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val loader = coil3.ImageLoader.Builder(context).build()
        val options = coil3.request.Options(context)
        val factory = LocalCoverFetcher.Factory()
        assertNotNull(factory.create("localaudio://5".toUri(), options, loader))
        assertNull(factory.create("https://i.ytimg.com/vi/a/hq.jpg".toUri(), options, loader))
        assertNull(factory.create("content://media/external/audio/media/5".toUri(), options, loader))
    }

    @Test
    fun `a song without a picture gets the placeholder, never an error`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val loader = coil3.ImageLoader.Builder(context).build()
        val fetcher = LocalCoverFetcher.Factory().create("localaudio://999".toUri(), coil3.request.Options(context), loader)!!
        val result = kotlinx.coroutines.runBlocking { fetcher.fetch() }
        assertTrue(result is coil3.fetch.ImageFetchResult)
    }

    @Test
    fun `placeholder is a square of the asked size`() {
        val bitmap = LocalCoverPlaceholder.render(3, 128)
        assertEquals(128, bitmap.width)
        assertEquals(128, bitmap.height)
    }

    private fun song(id: String, title: String, artist: String, added: Long) =
        Song(
            song = SongEntity(id = id, title = title, isLocal = true, inLibrary = LocalDateTime.ofEpochSecond(added, 0, java.time.ZoneOffset.UTC)),
            artists = listOf(ArtistEntity(id = "a-$artist", name = artist, isLocal = true)),
        )

    private val songs =
        listOf(
            song("1", "banana", "Zed", 30),
            song("2", "Apple", "Mia", 10),
            song("3", "cherry", "Mia", 20),
        )

    @Test
    fun `sort by name ignores case`() {
        assertEquals(listOf("2", "1", "3"), sortLocalSongs(songs, LocalSongSort.NAME, false, Locale.ENGLISH).map { it.id })
        assertEquals(listOf("3", "1", "2"), sortLocalSongs(songs, LocalSongSort.NAME, true, Locale.ENGLISH).map { it.id })
    }

    @Test
    fun `sort by artist then title`() {
        assertEquals(listOf("2", "3", "1"), sortLocalSongs(songs, LocalSongSort.ARTIST, false, Locale.ENGLISH).map { it.id })
    }

    @Test
    fun `sort by date added`() {
        assertEquals(listOf("2", "3", "1"), sortLocalSongs(songs, LocalSongSort.DATE_ADDED, false, Locale.ENGLISH).map { it.id })
        assertEquals(listOf("1", "3", "2"), sortLocalSongs(songs, LocalSongSort.DATE_ADDED, true, Locale.ENGLISH).map { it.id })
    }
}
