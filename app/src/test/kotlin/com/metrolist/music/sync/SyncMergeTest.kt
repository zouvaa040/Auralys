package com.metrolist.music.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private fun song(id: String) = SyncSong(id = id, title = "T-$id", artist = "A-$id")

    private fun snap(
        songs: List<String> = emptyList(),
        liked: List<SyncLiked> = emptyList(),
        playlists: List<SyncPlaylist> = emptyList(),
        saved: List<SyncSaved> = emptyList(),
        events: List<SyncEvent> = emptyList(),
        searches: List<String> = emptyList(),
        platform: String = "windows",
    ) = Snapshot(
        platform = platform,
        songs = songs.map(::song),
        liked = liked,
        playlists = playlists,
        saved = saved,
        events = events,
        searches = searches,
    )

    private fun merge(store: MemStore, foreign: Snapshot, state: MemStateStore = MemStateStore()): MergeSummary =
        runBlocking { engineFor(store, state).merge(foreign) }

    // ------------------------------------------------------------------ songs

    @Test
    fun `songs that arrive for a playlist or the history stay out of the library and the liked list`() {
        val phone = MemStore()
        val summary =
            merge(
                phone,
                snap(
                    songs = listOf("a", "b", "c"),
                    liked = listOf(SyncLiked("a", 10)),
                    playlists = listOf(SyncPlaylist("pl-1", "Santai", 1, listOf("b"))),
                    events = listOf(SyncEvent("c", 100, 5000)),
                ),
            )
        assertEquals(3, summary.songsAdded)
        assertEquals(setOf("a", "b", "c"), phone.songs.keys)
        assertEquals(setOf("a"), phone.inLibrary)
        assertEquals(setOf("a"), phone.liked.keys)
    }

    @Test
    fun `existing song metadata is never overwritten`() {
        val phone = MemStore().also { it.songs["a"] = SyncSong("a", "Mine", "Me") }
        merge(phone, Snapshot(songs = listOf(SyncSong("a", "Theirs", "Them")), liked = listOf(SyncLiked("a", 5))))
        assertEquals("Mine", phone.songs["a"]!!.title)
        assertTrue("a" in phone.liked)
    }

    @Test
    fun `references without metadata are skipped and counted`() {
        val phone = MemStore()
        val summary =
            merge(
                phone,
                snap(
                    songs = listOf("a"),
                    liked = listOf(SyncLiked("ghost")),
                    playlists = listOf(SyncPlaylist("s", "P", 1, listOf("a", "ghost"))),
                    events = listOf(SyncEvent("ghost", 1, 1)),
                ),
            )
        assertEquals(3, summary.unresolved)
        assertEquals(listOf("a"), phone.playlists.single().songs)
        assertTrue(phone.events.isEmpty())
    }

    // ------------------------------------------------------------------ liked

    @Test
    fun `liked is a union and keeps the earlier time`() {
        val phone = MemStore().apply { addSong("a"); addSong("b"); like("a", 500); like("b", 100) }
        val summary =
            merge(
                phone,
                snap(songs = listOf("a", "b", "c"), liked = listOf(SyncLiked("a", 300), SyncLiked("b", 900), SyncLiked("c", 50))),
            )
        assertEquals(1, summary.likedAdded)
        assertEquals(300L, phone.liked["a"]) // earlier foreign time wins
        assertEquals(100L, phone.liked["b"]) // earlier local time kept
        assertEquals(50L, phone.liked["c"])
    }

    // ------------------------------------------------------------------ playlists

    @Test
    fun `a foreign playlist becomes a new local playlist and the map remembers its sid`() {
        val phone = MemStore()
        val state = MemStateStore()
        merge(phone, snap(songs = listOf("a", "b"), playlists = listOf(SyncPlaylist("pl-17", "Favorit", 777, listOf("b", "a")))), state)
        val created = phone.playlists.single()
        assertEquals("LPnew0", created.id)
        assertEquals("Favorit", created.name)
        assertEquals(777L, created.createdAt)
        assertEquals(listOf("b", "a"), created.songs)
        assertNull(created.browseId)
        assertEquals(mapOf("LPnew0" to "pl-17"), state.map)
        // and it is exported under the foreign sid, not under its own id
        val exported = runBlocking { phone.exportSnapshot(state.map, 0) }
        assertEquals("pl-17", exported.playlists.single().sid)
    }

    @Test
    fun `a mapped playlist is found again and gets only the missing songs, local order first`() {
        val phone = MemStore().apply { addSong("a"); addSong("b"); playlist("LPcopy1", "Favorit (kopi)", "b", "a") }
        val state = MemStateStore(mapOf("LPcopy1" to "pl-17"))
        val summary =
            merge(
                phone,
                snap(
                    songs = listOf("a", "b", "c", "d"),
                    playlists = listOf(SyncPlaylist("pl-17", "Nama lain", 1, listOf("a", "c", "b", "d", "c"))),
                ),
                state,
            )
        val p = phone.playlists.single()
        assertEquals(listOf("b", "a", "c", "d"), p.songs)
        assertEquals("Favorit (kopi)", p.name) // the local name stays
        assertEquals(1, summary.playlistsExtended)
        assertEquals(2, summary.playlistSongsAppended)
        assertEquals(0, summary.playlistsCreated)
    }

    @Test
    fun `first contact matches an unmapped local playlist by name, ignoring case and spaces`() {
        val phone = MemStore().apply { addSong("a"); playlist("LPlocal01", "Santai Sore", "a") }
        val state = MemStateStore()
        merge(phone, snap(songs = listOf("a", "b"), playlists = listOf(SyncPlaylist("LPzzzz", "  santai sore ", 1, listOf("b")))), state)
        assertEquals(1, phone.playlists.size)
        assertEquals(listOf("a", "b"), phone.playlists.single().songs)
        assertEquals(mapOf("LPlocal01" to "LPzzzz"), state.map)
    }

    @Test
    fun `a playlist that already has a mapping is not hijacked by the name fallback`() {
        val phone = MemStore().apply { addSong("a"); playlist("LPone", "Mix", "a") }
        val state = MemStateStore(mapOf("LPone" to "pl-1"))
        merge(phone, snap(songs = listOf("b"), playlists = listOf(SyncPlaylist("pl-2", "Mix", 1, listOf("b")))), state)
        assertEquals(2, phone.playlists.size)
        assertEquals(listOf("a"), phone.playlists[0].songs)
        assertEquals(listOf("b"), phone.playlists[1].songs)
    }

    @Test
    fun `two foreign playlists with one name do not both take the same local playlist`() {
        val phone = MemStore().apply { playlist("LPone", "Mix") }
        merge(
            phone,
            snap(playlists = listOf(SyncPlaylist("pl-1", "Mix", 1), SyncPlaylist("pl-2", "mix", 1))),
        )
        assertEquals(2, phone.playlists.size)
    }

    @Test
    fun `an online playlist is matched by browse id, with or without the VL prefix`() {
        val phone = MemStore().apply { addSong("a"); playlist("LPyt", "Dari YouTube", "a", browseId = "PLabcdef") }
        merge(
            phone,
            snap(
                songs = listOf("a", "b"),
                playlists = listOf(SyncPlaylist("LPonline01", "Nama PC", 1, listOf("b"), browseId = "VLPLabcdef")),
            ),
        )
        assertEquals(1, phone.playlists.size)
        assertEquals(listOf("a", "b"), phone.playlists.single().songs)
    }

    @Test
    fun `a playlist created here and sent earlier comes back under its own id`() {
        val phone = MemStore().apply { addSong("a"); addSong("b"); playlist("LPmine", "Punyaku", "a") }
        val summary =
            merge(phone, snap(songs = listOf("a", "b"), playlists = listOf(SyncPlaylist("LPmine", "Punyaku di PC", 1, listOf("a", "b")))))
        assertEquals(1, phone.playlists.size)
        assertEquals(listOf("a", "b"), phone.playlists.single().songs)
        assertEquals(1, summary.playlistsExtended)
    }

    @Test
    fun `a stale map entry of a deleted playlist is dropped`() {
        val phone = MemStore()
        val state = MemStateStore(mapOf("LPgone" to "pl-1"))
        merge(phone, snap(playlists = listOf(SyncPlaylist("pl-1", "Balik", 1))), state)
        assertEquals(1, phone.playlists.size)
        assertEquals(mapOf("LPnew0" to "pl-1"), state.map)
    }

    // ------------------------------------------------------------------ events, play time

    @Test
    fun `events are a union by song and time and raise the play time by exactly what was added`() {
        val phone = MemStore().apply { addSong("a"); events += SyncEvent("a", 1000, 30_000); totalPlay["a"] = 30_000 }
        val summary =
            merge(
                phone,
                snap(
                    songs = listOf("a", "b"),
                    events =
                        listOf(
                            SyncEvent("a", 1000, 99_999), // same song, same moment: already here
                            SyncEvent("a", 2000, 45_000),
                            SyncEvent("a", 2000, 45_000), // duplicate inside the snapshot
                            SyncEvent("b", 1000, 10_000),
                        ),
                ),
            )
        assertEquals(2, summary.eventsAdded)
        assertEquals(55_000L, summary.playMsAdded)
        assertEquals(30_000L + 45_000L, phone.totalPlay["a"])
        assertEquals(10_000L, phone.totalPlay["b"])
        assertEquals(3, phone.events.size)
    }

    // ------------------------------------------------------------------ searches

    @Test
    fun `searches keep the local ones first and add only what is new`() {
        val phone = MemStore().apply { searches += listOf("b", "a") }
        val summary = merge(phone, snap(searches = listOf("a", " c ", "d", "c", "", "b")))
        assertEquals(listOf("b", "a", "c", "d"), phone.searches)
        assertEquals(2, summary.searchesAdded)
    }

    // ------------------------------------------------------------------ saved

    @Test
    fun `saved items are a union, unknown kinds are skipped and counted`() {
        val phone = MemStore()
        phone.saved[SyncMerger.savedKey("album", "AL1")] = SyncSaved("album", "AL1", "Old")
        phone.saved[SyncMerger.savedKey("playlist", "PLx")] = SyncSaved("playlist", "PLx", "Mix")
        val summary =
            merge(
                phone,
                snap(
                    saved =
                        listOf(
                            SyncSaved("album", "AL1", "Same"),
                            SyncSaved("album", "AL2", "New"),
                            SyncSaved("artist", "UC1", "Art"),
                            SyncSaved("playlist", "VLPLx", "Mix again"), // same as PLx
                            SyncSaved("podcast", "P1", "No"),
                        ),
                ),
            )
        assertEquals(2, summary.savedAdded)
        assertEquals(1, summary.savedSkipped)
        assertEquals(4, phone.saved.size)
    }

    // ------------------------------------------------------------------ idempotence and convergence

    @Test
    fun `merging the same snapshot twice changes nothing the second time`() {
        val phone = MemStore().apply { addSong("z"); like("z", 5); playlist("LPo", "Lokal", "z"); searches += "lama" }
        val state = MemStateStore()
        val foreign =
            Snapshot(
                platform = "windows",
                songs = listOf(song("a"), song("b"), song("z")),
                liked = listOf(SyncLiked("a", 10), SyncLiked("z", 1)),
                playlists =
                    listOf(
                        SyncPlaylist("pl-1", "Baru", 1, listOf("a", "b")),
                        SyncPlaylist("LPo", "Lokal PC", 1, listOf("z", "a")),
                    ),
                saved = listOf(SyncSaved("album", "AL", "A")),
                events = listOf(SyncEvent("a", 100, 9000), SyncEvent("z", 200, 1000)),
                searches = listOf("baru", "lama"),
            )
        val first = merge(phone, foreign, state)
        assertFalse(first.isEmpty)
        val before = runBlocking { phone.exportSnapshot(state.map, 0) }
        val applyCalls = phone.applyCalls
        val second = merge(phone, foreign, state)
        assertTrue(second.isEmpty)
        assertEquals(before, runBlocking { phone.exportSnapshot(state.map, 0) })
        assertEquals(applyCalls + 1, phone.applyCalls) // the engine still calls apply, with an empty plan
        val preview = runBlocking { engineFor(phone, state).preview(foreign) }
        assertTrue(preview.isEmpty)
    }

    @Test
    fun `after a full round both sides hold the same data`() {
        val phone = MemStore("android").apply {
            addSong("a"); addSong("b"); like("a", 100)
            playlist("LPphone1", "Dari HP", "a", "b")
            playlist("LPshared", "Bareng", "a")
            events += SyncEvent("a", 1000, 100); searches += listOf("hp1", "hp2")
        }
        val pc = MemStore("windows").apply {
            addSong("b"); addSong("c"); like("c", 50); like("b", 40)
            playlist("pl-7", "Bareng", "c")
            playlist("pl-8", "Dari PC", "b", "c")
            events += SyncEvent("c", 2000, 200); searches += listOf("pc1", "hp2")
        }
        val phoneState = MemStateStore()
        val pcState = MemStateStore()
        val phoneEngine = engineFor(phone, phoneState, "LPp")
        val pcEngine = engineFor(pc, pcState, "pl-n")

        runBlocking {
            // the order of the protocol: the PC merges the phone, then the phone merges the PC's result
            pcEngine.merge(phoneEngine.buildSnapshot())
            phoneEngine.merge(pcEngine.buildSnapshot())
        }
        val a = runBlocking { phoneEngine.buildSnapshot() }
        val b = runBlocking { pcEngine.buildSnapshot() }

        assertEquals(a.liked.map { it.id }.toSet(), b.liked.map { it.id }.toSet())
        assertEquals(a.events.toSet(), b.events.toSet())
        assertEquals(a.songs.map { it.id }.toSet(), b.songs.map { it.id }.toSet())
        assertEquals(a.searches.toSet(), b.searches.toSet())
        fun byName(s: Snapshot) = s.playlists.associate { it.name.lowercase() to it.songs.toSet() }
        assertEquals(setOf("dari hp", "bareng", "dari pc"), byName(a).keys)
        assertEquals(byName(a), byName(b))
        // each playlist carries the same sid on both sides
        assertEquals(a.playlists.map { it.sid }.toSet(), b.playlists.map { it.sid }.toSet())
        // a second round is silent on both sides
        runBlocking {
            assertTrue(pcEngine.preview(phoneEngine.buildSnapshot()).isEmpty)
            assertTrue(phoneEngine.preview(pcEngine.buildSnapshot()).isEmpty)
        }
    }

    // ------------------------------------------------------------------ size

    @Test
    fun `a history of fifty thousand events plans fast`() {
        val ids = (0 until 2000).map { "s$it" }
        val local = LocalState(
            songIds = ids.toSet(),
            liked = emptyMap(),
            playlists = emptyList(),
            saved = emptyMap(),
            events = (0 until 50_000).mapTo(HashSet()) { EventKey(ids[it % 2000], it * 1000L) },
            searches = emptySet(),
        )
        val foreign = Snapshot(
            songs = ids.map(::song),
            events = (25_000 until 75_000).map { SyncEvent(ids[it % 2000], it * 1000L, 3000) },
        )
        val started = System.nanoTime()
        val plan = SyncMerger.plan(local, foreign, emptyMap(), { "x" }, 0)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(25_000, plan.events.size)
        assertTrue("planning took $ms ms", ms < 3_000)
    }
}
