/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalLibrarySyncTest {
    private lateinit var internal: InternalDatabase
    private lateinit var db: MusicDatabase
    private val labels = LocalLabels(unknownArtist = "Artis tidak dikenal", untitled = "Tanpa judul")

    /** A source that hands out a fixed list, in batches like the real one. */
    private class ListSource(var tracks: List<LocalTrack>) : LocalAudioSource {
        var batches = 0

        override suspend fun read(batchSize: Int, onBatch: suspend (List<LocalTrack>) -> Unit) {
            tracks.chunked(batchSize).forEach {
                batches++
                onBatch(it)
            }
        }
    }

    private fun track(
        id: Long,
        title: String? = "Lagu $id",
        artist: String? = "Artis A",
        album: String? = "Album X",
        albumId: Long? = 10,
        durationMs: Long = 200_000,
        modified: Long = 1_000,
        added: Long = 500,
        folder: String? = "Music/",
        displayName: String? = "lagu$id.mp3",
    ) = LocalTrack(id, title, artist, album, albumId, durationMs, added, modified, 2021, displayName, folder)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build()
        db = MusicDatabase(internal)
    }

    @After
    fun tearDown() {
        internal.close()
    }

    private fun run(source: ListSource) = runBlocking { LocalLibrarySync(db).run(source, labels) }

    @Test
    fun `first scan adds songs with artist, album and library entry`() {
        val result = run(ListSource(listOf(track(1), track(2, artist = "Artis B"))))

        assertEquals(2, result.added)
        assertEquals(0, result.updated)
        assertEquals(0, result.removed)
        assertEquals(2, result.total)

        val song = runBlocking { db.song("LOCAL_1").first() }!!
        assertTrue(song.song.isLocal)
        assertFalse(song.song.liked)
        assertEquals("Lagu 1", song.song.title)
        assertEquals(200, song.song.duration)
        assertEquals("localaudio://1", song.song.thumbnailUrl)
        assertEquals("LOCAL_ALBUM_10", song.song.albumId)
        assertEquals("Album X", song.song.albumName)
        assertEquals(2021, song.song.year)
        assertNotNull("a device song must show in Pustaka > Lagu", song.song.inLibrary)
        assertEquals(LocalLibrarySync.epochToLocal(500), song.song.inLibrary)
        assertEquals(listOf("Artis A"), song.orderedArtists.map { it.name })
        assertTrue(song.orderedArtists.single().isLocal)
        assertTrue(LocalMusic.isLocalArtistId(song.orderedArtists.single().id))
        assertEquals("Album X", song.album?.title)
        assertTrue(song.album!!.isLocal)
    }

    @Test
    fun `album counts and length are recounted`() {
        run(ListSource(listOf(track(1, durationMs = 100_000), track(2, durationMs = 50_000), track(3, albumId = 11, album = "Lain"))))
        val album = runBlocking { db.album("LOCAL_ALBUM_10").first() }!!.album
        assertEquals(2, album.songCount)
        assertEquals(150, album.duration)
    }

    @Test
    fun `file without artist or album tag uses the unknown artist and no album`() {
        run(ListSource(listOf(track(1, artist = "<unknown>", album = "<unknown>", albumId = 3, title = null, displayName = "Nama File.mp3"))))
        val song = runBlocking { db.song("LOCAL_1").first() }!!
        assertEquals("Nama File", song.song.title)
        assertNull(song.song.albumId)
        assertNull(song.album)
        assertEquals(LocalMusic.UNKNOWN_ARTIST_ID, song.orderedArtists.single().id)
        assertEquals("Artis tidak dikenal", song.orderedArtists.single().name)
    }

    @Test
    fun `unchanged rescan changes nothing and keeps edits and likes`() {
        run(ListSource(listOf(track(1))))
        runBlocking {
            val song = db.songEntity("LOCAL_1")!!
            db.update(song.copy(title = "Judul Saya", liked = true, likedDate = LocalDateTime.now(), totalPlayTime = 5000))
        }
        val result = run(ListSource(listOf(track(1))))
        assertEquals(0, result.added)
        assertEquals(0, result.updated)
        val song = runBlocking { db.songEntity("LOCAL_1")!! }
        assertEquals("Judul Saya", song.title)
        assertTrue(song.liked)
        assertEquals(5000, song.totalPlayTime)
    }

    @Test
    fun `changed file takes the new tags and keeps the like`() {
        run(ListSource(listOf(track(1))))
        runBlocking {
            db.update(db.songEntity("LOCAL_1")!!.copy(liked = true, likedDate = LocalDateTime.now()))
        }
        val result = run(ListSource(listOf(track(1, title = "Baru", artist = "Artis B", modified = 2_000))))
        assertEquals(1, result.updated)
        val song = runBlocking { db.song("LOCAL_1").first() }!!
        assertEquals("Baru", song.song.title)
        assertTrue(song.song.liked)
        assertEquals(listOf("Artis B"), song.orderedArtists.map { it.name })
        // the old artist has no song left, so its row is gone
        assertNull(runBlocking { db.artistByName("Artis A") })
    }

    @Test
    fun `song that vanished and carries nothing of the user is removed`() {
        run(ListSource(listOf(track(1), track(2))))

        val result = run(ListSource(listOf(track(2))))

        assertEquals(1, result.removed)
        assertEquals(0, result.unavailable)
        assertNull(runBlocking { db.songEntity("LOCAL_1") })
        assertEquals(1, result.total)
    }

    @Test
    fun `vanished song in a playlist, liked or played is kept as unavailable and keeps its playlist entry`() {
        run(ListSource(listOf(track(1), track(2), track(3), track(4), track(5))))
        runBlocking {
            db.insert(PlaylistEntity(id = "p1", name = "Mine"))
            db.insert(PlaylistSongMap(playlistId = "p1", songId = "LOCAL_1", position = 0))
            db.update(db.songEntity("LOCAL_2")!!.copy(liked = true, likedDate = LocalDateTime.now()))
            db.update(db.songEntity("LOCAL_3")!!.copy(totalPlayTime = 4000))
        }

        // 1, 2, 3 are touched, 4 is plain; 5 stays on the phone
        val result = run(ListSource(listOf(track(5))))

        assertEquals(1, result.removed)
        assertEquals(3, result.unavailable)
        assertNull(runBlocking { db.songEntity("LOCAL_4") })
        for (id in listOf("LOCAL_1", "LOCAL_2", "LOCAL_3")) assertFalse(runBlocking { db.songEntity(id)!!.isLocal })
        assertEquals(listOf("LOCAL_1"), runBlocking { db.playlistSongIds("p1") })
        // hidden from the device list, so the next scan does not see them as missing again
        assertEquals(1, result.total)
        assertEquals(1, run(ListSource(listOf(track(5)))).total)
        assertEquals(listOf("LOCAL_1"), runBlocking { db.playlistSongIds("p1") })
    }

    @Test
    fun `unavailable song comes back with its like when the file is seen again`() {
        run(ListSource(listOf(track(1), track(2))))
        runBlocking { db.update(db.songEntity("LOCAL_1")!!.copy(title = "Judul Saya", liked = true, likedDate = LocalDateTime.now())) }
        run(ListSource(listOf(track(2))))
        assertFalse(runBlocking { db.songEntity("LOCAL_1")!!.isLocal })

        val result = run(ListSource(listOf(track(1), track(2))))

        val song = runBlocking { db.songEntity("LOCAL_1")!! }
        assertTrue(song.isLocal)
        assertTrue(song.liked)
        assertEquals("Judul Saya", song.title)
        assertEquals(2, result.total)
        assertEquals(0, result.added)
    }

    @Test
    fun `a file that MediaStore forgot but can still be opened is kept`() {
        run(ListSource(listOf(track(1), track(2))))
        val result = runBlocking { LocalLibrarySync(db, fileExists = { it == 1L }).run(ListSource(listOf(track(2))), labels) }
        assertEquals(0, result.removed)
        assertTrue(runBlocking { db.songEntity("LOCAL_1")!!.isLocal })
    }

    @Test
    fun `a memory card that is out does not wipe the library`() {
        // 100 songs; 60 of them (the card) are missing in the next scan
        run(ListSource((1L..100L).map { track(it) }))
        runBlocking {
            db.insert(PlaylistEntity(id = "p1", name = "Mine"))
            db.insert(PlaylistSongMap(playlistId = "p1", songId = "LOCAL_70", position = 0))
        }

        val result = run(ListSource((1L..40L).map { track(it) }))

        assertTrue(result.removalSkipped)
        assertEquals(0, result.removed)
        assertEquals(0, result.unavailable)
        assertEquals(100, result.total)
        assertEquals(listOf("LOCAL_70"), runBlocking { db.playlistSongIds("p1") })
    }

    @Test
    fun `a normal clean-up of a few files is still done`() {
        run(ListSource((1L..100L).map { track(it) }))
        val result = run(ListSource((1L..90L).map { track(it) }))
        assertFalse(result.removalSkipped)
        assertEquals(10, result.removed)
        assertEquals(90, result.total)
    }

    @Test
    fun `mass removal rule`() {
        assertFalse(LocalLibrarySync.massRemovalLooksWrong(missing = 20, existing = 20))
        assertTrue(LocalLibrarySync.massRemovalLooksWrong(missing = 21, existing = 30))
        assertFalse(LocalLibrarySync.massRemovalLooksWrong(missing = 30, existing = 1000))
        assertTrue(LocalLibrarySync.massRemovalLooksWrong(missing = 301, existing = 1000))
        assertFalse(LocalLibrarySync.massRemovalLooksWrong(missing = 300, existing = 1000))
    }

    @Test
    fun `rows that are not device songs are never listed or removed`() {
        run(ListSource(listOf(track(1))))
        runBlocking {
            db.insert(SongEntity(id = "abc12345678", title = "Foreign", isLocal = true, inLibrary = LocalDateTime.now()))
        }
        assertEquals(listOf("LOCAL_1"), runBlocking { db.localSongIds() })
        assertEquals(1, runBlocking { db.localSongs().first() }.size)
        assertEquals(1, runBlocking { db.localSongCount().first() })

        val result = run(ListSource(listOf(track(1))))
        assertEquals(0, result.removed)
        assertNotNull(runBlocking { db.songEntity("abc12345678") })
    }

    @Test
    fun `cover backfill list holds only web covers`() {
        run(ListSource(listOf(track(1), track(2))))
        runBlocking {
            db.insert(
                SongEntity(
                    id = "abc12345678",
                    title = "Online",
                    thumbnailUrl = "https://lh3.googleusercontent.com/x",
                    inLibrary = LocalDateTime.now(),
                ),
            )
        }
        assertEquals(listOf("https://lh3.googleusercontent.com/x"), runBlocking { db.coverUrlsToKeep() })
    }

    @Test
    fun `date added is read as local time not UTC`() {
        val zone = java.time.ZoneId.of("Asia/Jakarta")
        // 2024-01-01 03:00:00 UTC is 10:00 in Jakarta
        val seconds = LocalDateTime.of(2024, 1, 1, 3, 0).toEpochSecond(java.time.ZoneOffset.UTC)
        runBlocking { LocalLibrarySync(db, zone = zone).run(ListSource(listOf(track(1, added = seconds))), labels) }
        assertEquals(LocalDateTime.of(2024, 1, 1, 10, 0), runBlocking { db.songEntity("LOCAL_1")!!.inLibrary })
        assertNull(LocalLibrarySync.epochToLocal(0))
    }

    @Test
    fun `rows saved with the old UTC reading are moved to local time once without retaking tags`() {
        val zone = java.time.ZoneId.of("Asia/Jakarta")
        val seconds = LocalDateTime.of(2024, 1, 1, 3, 0).toEpochSecond(java.time.ZoneOffset.UTC)
        runBlocking {
            LocalLibrarySync(db, zone = java.time.ZoneOffset.UTC).run(ListSource(listOf(track(1, added = seconds, modified = seconds))), labels)
        }
        runBlocking { db.update(db.songEntity("LOCAL_1")!!.copy(title = "Judul Saya")) }
        assertEquals(LocalDateTime.of(2024, 1, 1, 3, 0), runBlocking { db.songEntity("LOCAL_1")!!.inLibrary })

        val result =
            runBlocking { LocalLibrarySync(db, zone = zone).run(ListSource(listOf(track(1, added = seconds, modified = seconds))), labels) }

        assertEquals(0, result.updated)
        val song = runBlocking { db.songEntity("LOCAL_1")!! }
        assertEquals("Judul Saya", song.title)
        assertEquals(LocalDateTime.of(2024, 1, 1, 10, 0), song.inLibrary)
        assertEquals(LocalDateTime.of(2024, 1, 1, 10, 0), song.dateModified)
    }

    @Test
    fun `empty scan removes nothing`() {
        run(ListSource(listOf(track(1), track(2))))
        val result = run(ListSource(emptyList()))
        assertEquals(0, result.removed)
        assertEquals(2, result.total)
    }

    @Test
    fun `album and artist disappear with their last song`() {
        run(ListSource(listOf(track(1), track(2, artist = "Artis B", albumId = 11, album = "Lain"))))
        run(ListSource(listOf(track(1))))
        assertNull(runBlocking { db.album("LOCAL_ALBUM_11").first() })
        assertNull(runBlocking { db.artistByName("Artis B") })
        assertNotNull(runBlocking { db.album("LOCAL_ALBUM_10").first() })
    }

    @Test
    fun `recordings, ringtones and short clips are skipped`() {
        val result =
            run(
                ListSource(
                    listOf(
                        track(1),
                        track(2, folder = "Recordings/Voice Recorder/"),
                        track(3, durationMs = 12_000),
                        track(4, displayName = "note.amr"),
                    ),
                ),
            )
        assertEquals(1, result.added)
        assertEquals(listOf("LOCAL_1"), runBlocking { db.localSongIds() })
    }

    @Test
    fun `a song on YouTube that is in the library is never touched`() {
        runBlocking { db.insert(SongEntity(id = "dQw4w9WgXcQ", title = "Remote", inLibrary = LocalDateTime.now())) }
        run(ListSource(listOf(track(1))))
        run(ListSource(listOf(track(1)))) // second scan, nothing from the phone left out
        assertNotNull(runBlocking { db.songEntity("dQw4w9WgXcQ") })
        assertFalse(runBlocking { db.songEntity("dQw4w9WgXcQ")!!.isLocal })
    }

    @Test
    fun `a big library is read in batches`() {
        val source = ListSource((1L..450L).map { track(it, albumId = it % 7 + 100, album = "A${it % 7}", artist = "Artis ${it % 13}") })
        val result = run(source)
        assertEquals(450, result.added)
        assertEquals(450, result.total)
        assertEquals(3, source.batches) // 200 + 200 + 50
        val withArtist = runBlocking { db.localSongs().first() }.count { it.orderedArtists.isNotEmpty() && it.album != null }
        assertEquals(450, withArtist)
    }

    @Test
    fun `links lost by a library wipe are repaired on the next scan`() {
        run(ListSource(listOf(track(1))))
        runBlocking {
            db.songArtistMap("LOCAL_1").forEach { db.delete(it) }
            db.deleteSongAlbumMaps("LOCAL_1")
        }
        run(ListSource(listOf(track(1))))
        val song = runBlocking { db.song("LOCAL_1").first() }!!
        assertEquals(1, song.orderedArtists.size)
        assertNotNull(song.album)
    }

    @Test
    fun `search for the device finds the scanned songs`() {
        run(ListSource(listOf(track(1, title = "Negoro Angin", artist = "Denny Caknan"))))
        val found = runBlocking { db.searchDeviceSongs("%negoro%").first() }
        assertEquals(listOf("LOCAL_1"), found.map { it.id })
        val byArtist = runBlocking { db.searchDeviceSongs("%caknan%").first() }
        assertEquals(1, byArtist.size)
    }
}
