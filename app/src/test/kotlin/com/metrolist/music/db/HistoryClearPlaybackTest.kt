/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * Report 5: "setelah hapus riwayat, musik tidak bisa diputar sampai hapus data aplikasi". Clearing the listen history
 * (Privacy settings) is `DELETE FROM event`; removing one play is `delete(event)`. This runs, on a real Room database, every
 * read and write the playback path makes for a song (the resolver's format read, the service's song read and metadata
 * repair, the play-count write of a finished song) before and after both, and checks that nothing but the plays went.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HistoryClearPlaybackTest {
    private val internal: InternalDatabase =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InternalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val dao = internal.dao

    @After
    fun tearDown() = internal.close()

    private fun seed() {
        for (i in 1..5) {
            dao.insert(SongEntity(id = "song$i", title = "Song $i", duration = 200, inLibrary = LocalDateTime.now()))
            dao.upsert(FormatEntity("song$i", 251, "audio/webm", "opus", 130_000, 48_000, 3_400_000L, -7.0, -9.0, null))
            repeat(3) { dao.insert(Event(songId = "song$i", timestamp = LocalDateTime.now().minusDays(it.toLong()), playTime = 60_000L)) }
        }
        dao.insert(PlaylistEntity(id = "pl", name = "hhh"))
        for (i in 1..5) dao.insert(PlaylistSongMap(playlistId = "pl", songId = "song$i", position = i - 1))
    }

    /** What playback reads and writes for one song (MusicService resolver, recoverSong, onPlaybackStatsReady). */
    private fun playbackPath(id: String) =
        runBlocking {
            assertNotNull("format", dao.format(id).first())
            assertNotNull("song entity", dao.songEntity(id))
            assertNotNull("song", dao.song(id).first())
            dao.hasRelatedSongs(id)
            dao.incrementTotalPlayTime(id, 45_000L)
            dao.insert(Event(songId = id, timestamp = LocalDateTime.now(), playTime = 45_000L))
            assertEquals(5, dao.playlistSongs("pl").first().size)
        }

    @Test
    fun `clearing the whole history removes the plays only and playback reads and writes still work`() =
        runBlocking {
            seed()
            playbackPath("song1")
            dao.clearListenHistory()
            assertEquals(0, dao.eventCountNow())
            assertNull(dao.latestEvent().first())
            for (i in 1..5) playbackPath("song$i")
            assertEquals(5, dao.eventCountNow())
        }

    @Test
    fun `removing one play from the history leaves the song playable`() =
        runBlocking {
            seed()
            val one = dao.latestEvent().first()!!.event
            dao.delete(one)
            playbackPath(one.songId)
        }
}
