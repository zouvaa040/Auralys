/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.local

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.YouTubeQueue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalPlaybackTest {
    private class FakeSource(val name: String, val log: MutableList<String>) : DataSource {
        val listeners = ArrayList<TransferListener>()
        var opened: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            opened = dataSpec.uri
            log += "$name open ${dataSpec.uri}"
            return 3
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            log += "$name read"
            return -1
        }

        override fun getUri(): Uri? = opened

        override fun close() {
            log += "$name close"
            opened = null
        }
    }

    private class Env {
        val log = ArrayList<String>()
        val created = ArrayList<FakeSource>()
        val remote = DataSource.Factory { FakeSource("remote", log).also { created += it } }
        val local = DataSource.Factory { FakeSource("local", log).also { created += it } }
        val factory = DeviceAwareDataSourceFactory(remote, local)
    }

    @Test
    fun `a content address goes to the local source and never to the stream resolver`() {
        val env = Env()
        val source = env.factory.createDataSource()
        val listener = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {}
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        }
        source.addTransferListener(listener)

        val uri = Uri.parse("content://media/external/audio/media/42")
        assertEquals(3L, source.open(DataSpec(uri)))
        assertEquals(uri, source.uri)
        source.read(ByteArray(8), 0, 8)
        source.close()

        assertEquals(listOf("local open $uri", "local read", "local close"), env.log)
        assertEquals(1, env.created.size)
        assertEquals(listOf(listener), env.created.single().listeners)
        assertNull(source.uri)
    }

    @Test
    fun `file addresses are local too`() {
        val env = Env()
        env.factory.createDataSource().open(DataSpec(Uri.parse("file:///storage/emulated/0/Music/a.mp3")))
        assertTrue(env.log.single().startsWith("local open"))
    }

    @Test
    fun `a bare YouTube id and a stream address go to the remote source`() {
        val env = Env()
        val source = env.factory.createDataSource()
        source.open(DataSpec(Uri.parse("dQw4w9WgXcQ")))
        source.close()
        source.open(DataSpec(Uri.parse("https://rr1.googlevideo.com/videoplayback?id=1")))
        source.close()
        assertTrue(env.log.filter { it.contains("open") }.all { it.startsWith("remote") })
        assertFalse(env.log.any { it.startsWith("local") })
    }

    @Test
    fun `extractors for files are the full list, streams keep the short list`() {
        val streamList = arrayOf<Extractor>(Mp4Extractor())
        val fileList = arrayOf<Extractor>(Mp4Extractor(), Mp4Extractor())
        val factory =
            DeviceAwareExtractorsFactory(
                streams = ExtractorsFactory { streamList },
                files = ExtractorsFactory { fileList },
            )
        assertSame(streamList, factory.createExtractors())
        assertSame(fileList, factory.createExtractors(Uri.parse("content://media/external/audio/media/1"), emptyMap()))
        assertSame(streamList, factory.createExtractors(Uri.parse("https://x/y"), emptyMap()))
        assertSame(streamList, factory.createExtractors(Uri.parse("dQw4w9WgXcQ"), emptyMap()))
    }

    private fun metadata(id: String) =
        MediaMetadata(
            id = id,
            title = "T",
            artists = listOf(MediaMetadata.Artist(id = null, name = "A")),
            duration = 100,
            thumbnailUrl = LocalMusic.coverUrlOf(7),
        )

    @Test
    fun `media item of a device song plays from the content address and keeps the id`() {
        val item = metadata("LOCAL_42").toMediaItem()
        assertEquals("LOCAL_42", item.mediaId)
        assertEquals("content://media/external/audio/media/42", item.localConfiguration!!.uri.toString())
        assertEquals("LOCAL_42", item.localConfiguration!!.customCacheKey)
    }

    @Test
    fun `media item of a YouTube song still carries the bare id`() {
        val item = metadata("dQw4w9WgXcQ").toMediaItem()
        assertEquals("dQw4w9WgXcQ", item.localConfiguration!!.uri.toString())
    }

    @Test
    fun `radio for a device song is only that song and has no next page`() {
        val queue = YouTubeQueue.radio(metadata("LOCAL_5"))
        val status = runBlocking { queue.getInitialStatus() }
        assertEquals(listOf("LOCAL_5"), status.items.map { it.mediaId })
        assertEquals(0, status.mediaItemIndex)
        assertFalse(queue.hasNextPage())
    }

    @Test
    fun `automatic scan is due after ten minutes, or when the clock moved back`() {
        val ten = LocalMusicScanner.AUTO_SCAN_INTERVAL_MS
        assertTrue(LocalMusicScanner.isDue(0, 1_000, ten))
        assertFalse(LocalMusicScanner.isDue(1_000, 1_000 + ten - 1, ten))
        assertTrue(LocalMusicScanner.isDue(1_000, 1_000 + ten, ten))
        assertTrue(LocalMusicScanner.isDue(5_000, 4_000, ten))
    }

    @Test
    fun `permission depends on the Android version`() {
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", LocalMusicPermission.permissionFor(26))
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", LocalMusicPermission.permissionFor(32))
        assertEquals("android.permission.READ_MEDIA_AUDIO", LocalMusicPermission.permissionFor(33))
        assertEquals("android.permission.READ_MEDIA_AUDIO", LocalMusicPermission.permissionFor(35))
    }
}
