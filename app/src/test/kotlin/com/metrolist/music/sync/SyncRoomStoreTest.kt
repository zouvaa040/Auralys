package com.metrolist.music.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.SearchHistory
import com.metrolist.music.db.entities.SongArtistMap
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
import java.time.ZoneId
import java.time.ZoneOffset

/** The real Room code on an in-memory database (Robolectric), with made-up data only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncRoomStoreTest {
    private lateinit var internal: InternalDatabase
    private lateinit var db: MusicDatabase
    private val jakarta = ZoneId.of("Asia/Jakarta")
    private val fixedNow = LocalDateTime.of(2026, 11, 5, 12, 0)

    private fun newStore(database: MusicDatabase = db) = RoomSyncStore(database, zone = { jakarta }, nowLocal = { fixedNow })

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

    private fun stamp(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(year, month, day, hour, minute)

    private fun epochJakarta(t: LocalDateTime) = t.atZone(jakarta).toInstant().toEpochMilli()

    private fun seedPhone() {
        val t1 = stamp(2026, 11, 4, 20, 15)
        db.insert(ArtistEntity(id = "UCrick", name = "Rick Astley", channelId = "UCrick"))
        db.insert(
            SongEntity(
                id = "dQw4w9WgXcQ", title = "Never Gonna Give You Up", duration = 213, thumbnailUrl = "https://t/1",
                albumId = "MPREb_1", albumName = "Whenever", liked = true, likedDate = t1, inLibrary = t1, totalPlayTime = 212_000,
            ),
        )
        db.insert(SongArtistMap("dQw4w9WgXcQ", "UCrick", 0))
        db.insert(SongEntity(id = "kJQP7kiw5Fk", title = "Despacito", duration = 229))
        db.insert(SongEntity(id = "localfile", title = "Dari file", isLocal = true))
        db.insert(PlaylistEntity(id = "LPabcdefgh", name = "Santai Sore", createdAt = stamp(2026, 10, 1, 9), bookmarkedAt = t1))
        db.insert(PlaylistSongMap(playlistId = "LPabcdefgh", songId = "dQw4w9WgXcQ", position = 0))
        db.insert(PlaylistSongMap(playlistId = "LPabcdefgh", songId = "kJQP7kiw5Fk", position = 1))
        db.insert(PlaylistEntity(id = "LP_WEEKLY_MOST", name = "Most played", bookmarkedAt = t1))
        db.insert(PlaylistEntity(id = "LPcached01", name = "Not saved"))
        db.insert(
            PlaylistEntity(id = "LPonline01", name = "Mix mingguan", browseId = "PLmix", isEditable = false, bookmarkedAt = t1, thumbnailUrl = "https://t/p"),
        )
        db.insert(Event(songId = "dQw4w9WgXcQ", timestamp = t1, playTime = 212_000))
        db.insert(Event(songId = "localfile", timestamp = t1, playTime = 1_000))
        db.insert(SearchHistory(query = "rick astley"))
        db.insert(SearchHistory(query = "lagu santai"))
        db.insert(AlbumEntity(id = "MPREb_1", title = "Whenever", songCount = 10, duration = 3000, bookmarkedAt = t1))
        db.insert(ArtistEntity(id = "UCbookmarked", name = "Dewa 19", bookmarkedAt = t1))
    }

    @Test
    fun `export reads the phone into the snapshot of the spec`() =
        runBlocking {
            seedPhone()
            val snapshot = newStore().exportSnapshot(mapOf("LPabcdefgh" to "pl-mapped"), createdAt = 42)

            assertEquals("android", snapshot.platform)
            assertEquals(42L, snapshot.createdAt)
            assertEquals(setOf("dQw4w9WgXcQ", "kJQP7kiw5Fk"), snapshot.songs.map { it.id }.toSet())
            val rick = snapshot.songs.first { it.id == "dQw4w9WgXcQ" }
            assertEquals("Rick Astley", rick.artist)
            assertEquals("UCrick", rick.artistId)
            assertEquals(213, rick.duration)
            assertEquals("Whenever", rick.album)
            assertEquals("MPREb_1", rick.albumId)
            assertNull(snapshot.songs.first { it.id == "kJQP7kiw5Fk" }.artistId)

            assertEquals(listOf("dQw4w9WgXcQ"), snapshot.liked.map { it.id })
            assertEquals(epochJakarta(stamp(2026, 11, 4, 20, 15)), snapshot.liked.single().at)

            // the system list, the unsaved cache row and the saved online playlist are not "playlists"
            val playlist = snapshot.playlists.single()
            assertEquals("pl-mapped", playlist.sid) // the sid map is applied
            assertEquals("Santai Sore", playlist.name)
            assertEquals(listOf("dQw4w9WgXcQ", "kJQP7kiw5Fk"), playlist.songs)
            assertEquals(epochJakarta(stamp(2026, 10, 1, 9)), playlist.createdAt)

            // the event time is the wall clock of the phone turned into a real instant
            val event = snapshot.events.single() // the one of the device file is left out
            assertEquals(epochJakarta(stamp(2026, 11, 4, 20, 15)), event.at)
            assertEquals(Instant20261104T131500Z, event.at)
            assertEquals(212_000L, event.ms)

            assertEquals(listOf("lagu santai", "rick astley"), snapshot.searches) // newest first
            assertEquals(emptyList<Long>(), snapshot.days)

            assertEquals(
                listOf(SyncSaved("album", "MPREb_1", "Whenever", null, null)),
                snapshot.saved.filter { it.kind == "album" },
            )
            assertEquals(listOf("UCbookmarked"), snapshot.saved.filter { it.kind == "artist" }.map { it.id })
            assertEquals(
                listOf(SyncSaved("playlist", "PLmix", "Mix mingguan", null, "https://t/p")),
                snapshot.saved.filter { it.kind == "playlist" },
            )
            // and it serialises
            assertTrue(snapshotFromJson(snapshot.toJson()) == snapshot)
        }

    private fun foreign(): Snapshot =
        Snapshot(
            platform = "windows",
            songs =
                listOf(
                    SyncSong("hist1", "Cuma riwayat", "Artis Satu", "UCartis1", "Album Satu", "MPREb_x", 180, "https://t/h"),
                    SyncSong("pl1", "Di playlist", "Artis, Dua"),
                    SyncSong("like1", "Disukai", "Artis Satu", "UCartis1"),
                ),
            liked = listOf(SyncLiked("like1", epochJakarta(stamp(2026, 9, 1, 8)))),
            playlists =
                listOf(
                    SyncPlaylist("pl-1793100000000", "Dari PC", epochJakarta(stamp(2026, 8, 8, 8)), listOf("pl1", "like1")),
                    SyncPlaylist("pl-empty", "Kosong", 0, emptyList()),
                ),
            saved =
                listOf(
                    SyncSaved("album", "MPREb_new", "Album Baru", "Artis Satu"),
                    SyncSaved("artist", "UCnewartist", "Artis Baru"),
                    SyncSaved("playlist", "VLPLpc", "Playlist PC"),
                    SyncSaved("podcast", "x", "Tidak ada"),
                ),
            events =
                listOf(
                    SyncEvent("hist1", epochJakarta(stamp(2026, 7, 1, 10)), 100_000),
                    SyncEvent("hist1", epochJakarta(stamp(2026, 7, 2, 10)), 50_000),
                    SyncEvent("like1", epochJakarta(stamp(2026, 7, 3, 10)), 30_000),
                ),
            searches = listOf("dari pc", "lagu santai"),
        )

    @Test
    fun `merge writes the foreign data with the library rules`() =
        runBlocking {
            seedPhone()
            val store = newStore()
            val state = MemStateStore()
            val summary = engineWith(store, state).merge(foreign())

            assertEquals(3, summary.songsAdded)
            assertEquals(1, summary.likedAdded)
            assertEquals(2, summary.playlistsCreated)
            assertEquals(3, summary.eventsAdded)
            assertEquals(3, summary.savedAdded)
            assertEquals(1, summary.savedSkipped)
            assertEquals(1, summary.searchesAdded)

            // songs that came only for history or a playlist are not in the library and not liked
            val hist = db.songEntity("hist1")!!
            assertNull(hist.inLibrary)
            assertFalse(hist.liked)
            assertEquals(180, hist.duration)
            assertEquals("Album Satu", hist.albumName)
            assertEquals("MPREb_x", hist.albumId)
            assertNull(db.songEntity("pl1")!!.inLibrary)
            val liked = db.songEntity("like1")!!
            assertTrue(liked.liked)
            assertEquals(stamp(2026, 9, 1, 8), liked.likedDate)
            assertNotNull(liked.inLibrary)
            val library = db.librarySongEntitiesByNameAsc().map { it.id }.toSet()
            assertEquals(setOf("dQw4w9WgXcQ", "like1"), library)
            assertEquals(setOf("dQw4w9WgXcQ", "like1"), db.likedSongIds().toSet())

            // artists: the channel id is reused, a "A, B" credit is one artist without a channel
            val artistId = db.getArtistById("UCartis1")
            assertNotNull(artistId)
            assertEquals("Artis Satu", artistId!!.name)
            val multi = db.artistByName("Artis, Dua")!!
            assertNull(multi.channelId)
            assertTrue(multi.id.startsWith("LA"))

            // the playlist is a normal local one at the end of the library, in the foreign order
            val copy = db.playlistEntitiesByNameAsc().first { it.name == "Dari PC" }
            assertNull(copy.browseId)
            assertTrue(copy.isEditable)
            assertNotNull(copy.bookmarkedAt)
            assertEquals(stamp(2026, 8, 8, 8), copy.createdAt)
            assertEquals(listOf("pl1", "like1"), db.playlistSongIds(copy.id))
            assertEquals(mapOf(copy.id to "pl-1793100000000", db.playlistEntitiesByNameAsc().first { it.name == "Kosong" }.id to "pl-empty"), state.map)
            assertEquals(emptyList<String>(), db.playlistSongIds(db.playlistEntitiesByNameAsc().first { it.name == "Kosong" }.id))

            // events with the phone's wall clock, and the play-time counters grew by the same milliseconds
            assertEquals(5, db.eventCount().first()) // 2 seeded (one of them on the device file) + 3 added
            assertEquals(150_000L, db.songEntity("hist1")!!.totalPlayTime)
            assertEquals(30_000L, db.songEntity("like1")!!.totalPlayTime)
            assertEquals(212_000L, db.songEntity("dQw4w9WgXcQ")!!.totalPlayTime) // untouched
            val raw = internal.openHelper.readableDatabase
            raw.query("SELECT timestamp FROM event WHERE songId = 'hist1' ORDER BY timestamp").use { c ->
                c.moveToFirst()
                assertEquals(stamp(2026, 7, 1, 10).toInstant(ZoneOffset.UTC).toEpochMilli(), c.getLong(0))
            }

            // saved items
            assertNotNull(db.albumEntity("MPREb_new")!!.bookmarkedAt)
            assertEquals("Album Baru", db.albumEntity("MPREb_new")!!.title)
            assertNotNull(db.artistEntity("UCnewartist")!!.bookmarkedAt)
            val onlineCopy = db.playlistEntitiesByNameAsc().first { it.name == "Playlist PC" }
            assertEquals("PLpc", onlineCopy.browseId) // the VL prefix is not stored
            assertFalse(onlineCopy.isEditable)
            assertNotNull(onlineCopy.bookmarkedAt)

            // search history: the phone's own searches stay first, the new one follows
            assertEquals(listOf("lagu santai", "rick astley", "dari pc"), db.searchHistory().first().map { it.query })
            db.insert(SearchHistory(query = "baru dicari"))
            assertEquals("baru dicari", db.searchHistory().first().first().query)
        }

    private fun engineWith(store: RoomSyncStore, state: MemStateStore): SyncEngine {
        var n = 0
        return SyncEngine(store, state, newPlaylistId = { PlaylistEntity.generatePlaylistId() + (n++) }, now = { 1L })
    }

    @Test
    fun `merging twice changes nothing the second time`() =
        runBlocking {
            seedPhone()
            val store = newStore()
            val state = MemStateStore()
            val engine = engineWith(store, state)
            engine.merge(foreign())
            val before = store.exportSnapshot(state.map, 0)
            val events = db.eventCount().first()
            val again = engine.merge(foreign())
            assertTrue(again.toString(), again.isEmpty)
            assertEquals(before, store.exportSnapshot(state.map, 0))
            assertEquals(events, db.eventCount().first())
            assertEquals(1, db.playlistEntitiesByNameAsc().count { it.name == "Dari PC" })
        }

    @Test
    fun `songs of a playlist that matched are appended after the existing ones`() =
        runBlocking {
            seedPhone()
            val store = newStore()
            val state = MemStateStore()
            val snapshot =
                Snapshot(
                    songs = listOf(SyncSong("new1", "Baru", "X"), SyncSong("dQw4w9WgXcQ", "x", "x")),
                    playlists = listOf(SyncPlaylist("LPabcdefgh", "Nama PC", 1, listOf("new1", "dQw4w9WgXcQ"))),
                )
            val summary = engineWith(store, state).merge(snapshot)
            assertEquals(1, summary.playlistsExtended)
            assertEquals(0, summary.playlistsCreated)
            assertEquals(listOf("dQw4w9WgXcQ", "kJQP7kiw5Fk", "new1"), db.playlistSongIds("LPabcdefgh"))
            assertEquals("Santai Sore", db.playlistEntitiesByNameAsc().first { it.id == "LPabcdefgh" }.name)
            assertNull(db.songEntity("new1")!!.inLibrary)
        }

    @Test
    fun `a song that is already here keeps its data and is liked when the other side liked it`() =
        runBlocking {
            seedPhone()
            val store = newStore()
            val snapshot =
                Snapshot(
                    songs = listOf(SyncSong("kJQP7kiw5Fk", "Ganti judul", "Orang lain")),
                    liked = listOf(SyncLiked("kJQP7kiw5Fk", epochJakarta(stamp(2026, 1, 1, 1)))),
                )
            engineWith(store, MemStateStore()).merge(snapshot)
            val song = db.songEntity("kJQP7kiw5Fk")!!
            assertEquals("Despacito", song.title)
            assertTrue(song.liked)
            assertEquals(stamp(2026, 1, 1, 1), song.likedDate)
            assertEquals(stamp(2026, 1, 1, 1), song.inLibrary)
        }

    @Test
    fun `phone to empty phone round trip keeps the data`() =
        runBlocking {
            seedPhone()
            val a = newStore().exportSnapshot(emptyMap(), 0)
            val otherInternal = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InternalDatabase::class.java).allowMainThreadQueries().build()
            try {
                val other = MusicDatabase(otherInternal)
                val store = newStore(other)
                val state = MemStateStore()
                engineWith(store, state).merge(a)
                val b = store.exportSnapshot(state.map, 0)
                assertEquals(a.songs.map { it.id }.toSet(), b.songs.map { it.id }.toSet())
                assertEquals(a.songs.first { it.id == "dQw4w9WgXcQ" }, b.songs.first { it.id == "dQw4w9WgXcQ" })
                assertEquals(a.liked, b.liked)
                assertEquals(a.events, b.events)
                assertEquals(a.searches.toSet(), b.searches.toSet())
                assertEquals(a.playlists.single().songs, b.playlists.single().songs)
                assertEquals(a.playlists.single().sid, b.playlists.single().sid) // the copy keeps the sid of the original
                assertEquals(a.saved.map { it.kind to it.id }.toSet(), b.saved.map { it.kind to it.id }.toSet())
            } finally {
                otherInternal.close()
            }
        }

    @Test
    fun `a big history is applied in one transaction in a reasonable time`() =
        runBlocking {
            seedPhone()
            val ids = (0 until 3000).map { "big$it" }
            val base = epochJakarta(stamp(2025, 1, 1, 0))
            val snapshot =
                Snapshot(
                    songs = ids.map { SyncSong(it, "Lagu $it", "Artis ${it.length}") },
                    events = (0 until 50_000).map { SyncEvent(ids[it % ids.size], base + it * 60_000L, 1_000L + it % 5) },
                )
            val store = newStore()
            val started = System.nanoTime()
            val summary = engineWith(store, MemStateStore()).merge(snapshot)
            val applyMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(50_000, summary.eventsAdded)
            assertEquals(50_002, db.eventCount().first())
            val total = db.songEntity("big0")!!.totalPlayTime
            assertTrue(total > 0)
            val again = System.nanoTime()
            val export = store.exportSnapshot(emptyMap(), 0)
            val exportMs = (System.nanoTime() - again) / 1_000_000
            assertEquals(50_001, export.events.size)
            println("SYNC-PERF apply 50000 events: $applyMs ms, export: $exportMs ms")
            assertTrue("apply took $applyMs ms", applyMs < 60_000)
        }

    private companion object {
        // 2026-11-04 20:15 in Jakarta (UTC+7) is 13:15 UTC
        val Instant20261104T131500Z: Long = java.time.Instant.parse("2026-11-04T13:15:00Z").toEpochMilli()
    }
}
