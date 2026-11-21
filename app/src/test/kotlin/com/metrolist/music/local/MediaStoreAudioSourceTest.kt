/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.local

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A stand-in for MediaStore: answers every audio query with the same rows and remembers what was asked. */
class FakeMediaProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        lastProjection = projection?.toList()
        lastSelection = selection
        val cursor = MatrixCursor(projection ?: emptyArray())
        rows.forEach { row -> cursor.addRow(projection!!.map { column -> row[column] }) }
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        var rows: List<Map<String, Any?>> = emptyList()
        var lastSelection: String? = null
        var lastProjection: List<String>? = null
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaStoreAudioSourceTest {
    @Before
    fun setUp() {
        FakeMediaProvider.rows = emptyList()
        FakeMediaProvider.lastSelection = null
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, "media")
    }

    private fun read(sdk: Int = 33, batch: Int = 2): List<List<LocalTrack>> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val out = ArrayList<List<LocalTrack>>()
        runBlocking { MediaStoreAudioSource(context.contentResolver, sdk).read(batch) { out += it } }
        return out
    }

    private fun row(id: Long, title: String? = "T$id") =
        mapOf<String, Any?>(
            MediaStore.Audio.Media._ID to id,
            MediaStore.Audio.Media.TITLE to title,
            MediaStore.Audio.Media.ARTIST to "Artis",
            MediaStore.Audio.Media.ALBUM to "Album",
            MediaStore.Audio.Media.ALBUM_ID to 9L,
            MediaStore.Audio.Media.DURATION to 215_000L,
            MediaStore.Audio.Media.DATE_ADDED to 1_700_000_000L,
            MediaStore.Audio.Media.DATE_MODIFIED to 1_700_000_100L,
            MediaStore.Audio.Media.YEAR to 2019L,
            MediaStore.Audio.Media.DISPLAY_NAME to "t$id.mp3",
            MediaStore.Audio.Media.RELATIVE_PATH to "Music/Pop/",
        )

    @Test
    fun `rows become tracks`() {
        FakeMediaProvider.rows = listOf(row(7))
        val track = read().single().single()
        assertEquals(7L, track.mediaStoreId)
        assertEquals("T7", track.title)
        assertEquals("Artis", track.artist)
        assertEquals("Album", track.album)
        assertEquals(9L, track.albumId)
        assertEquals(215_000L, track.durationMs)
        assertEquals(1_700_000_000L, track.dateAddedSec)
        assertEquals(1_700_000_100L, track.dateModifiedSec)
        assertEquals(2019, track.year)
        assertEquals("t7.mp3", track.displayName)
        assertEquals("Music/Pop/", track.folder)
    }

    @Test
    fun `tracks come in batches`() {
        FakeMediaProvider.rows = (1L..5L).map { row(it) }
        assertEquals(listOf(2, 2, 1), read(batch = 2).map { it.size })
    }

    @Test
    fun `no rows gives no batches`() {
        assertTrue(read().isEmpty())
    }

    @Test
    fun `null tags stay null and a zero album id means no album`() {
        FakeMediaProvider.rows = listOf(row(1, title = null) + (MediaStore.Audio.Media.ALBUM_ID to 0L))
        val track = read().single().single()
        assertNull(track.title)
        assertNull(track.albumId)
    }

    @Test
    fun `the query asks MediaStore for music of 30 seconds or more`() {
        FakeMediaProvider.rows = listOf(row(1))
        read(sdk = 33)
        val selection = FakeMediaProvider.lastSelection!!
        assertTrue(selection.contains("is_music != 0"))
        assertTrue(selection.contains("duration >= 30000"))
        assertTrue(selection.contains("is_ringtone = 0"))
        assertTrue(selection.contains("is_alarm = 0"))
        assertTrue(selection.contains("is_notification = 0"))
        assertTrue(selection.contains("is_podcast = 0"))
        assertTrue(FakeMediaProvider.lastProjection!!.contains(MediaStore.Audio.Media.RELATIVE_PATH))
    }

    @Test
    fun `old Android reads the file path and has no podcast column`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val old = MediaStoreAudioSource(context.contentResolver, 28)
        assertFalse(old.selection().contains("is_podcast"))
        @Suppress("DEPRECATION")
        assertTrue(old.projection().contains(MediaStore.Audio.Media.DATA))
        assertFalse(old.projection().contains(MediaStore.Audio.Media.RELATIVE_PATH))
    }
}
