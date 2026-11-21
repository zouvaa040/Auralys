package com.metrolist.music.utils

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.HistoryChunk
import com.metrolist.innertube.pages.HistoryPage
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** The importer on a real (in-memory) Room database, with a made-up account history and no network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemoteHistoryImporterTest {
    private lateinit var internal: InternalDatabase
    private lateinit var db: MusicDatabase

    // 7 Nov 2026, 15:30 in Jakarta (a Saturday)
    private val jakarta = ZoneId.of("Asia/Jakarta")
    private val clock = Clock.fixed(Instant.parse("2026-11-07T08:30:00Z"), jakarta)
    private val today = LocalDate.of(2026, 11, 7)

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

    // ------------------------------------------------------------------ helpers

    private fun song(id: String, seconds: Int? = 200) =
        SongItem(id = id, title = "Song $id", artists = listOf(Artist("Artist $id", "UC$id")), thumbnail = "https://t/$id", duration = seconds)

    private fun section(title: String, vararg ids: String) = HistoryPage.HistorySection(title, ids.map { song(it) })

    private fun chunk(vararg sections: HistoryPage.HistorySection, more: List<SongItem> = emptyList(), section: String? = null, shelf: String? = null) =
        HistoryChunk(sections.toList(), more, section, shelf)

    /** A fake account: the chunk to answer for each continuation token (null is the first page). */
    private class FakeAccount(val pages: Map<String?, Result<HistoryChunk>>) : HistoryFetcher {
        val asked = ArrayList<String?>()

        constructor(single: HistoryChunk) : this(mapOf<String?, Result<HistoryChunk>>(null to Result.success(single)))

        override suspend fun fetch(continuation: String?): Result<HistoryChunk> {
            asked += continuation
            return pages[continuation] ?: Result.failure(IllegalStateException("no page for $continuation"))
        }
    }

    private fun importer(
        fetcher: HistoryFetcher,
        limits: ImportLimits = ImportLimits(),
        pause: suspend () -> Unit = {},
    ) = RemoteHistoryImporter(db, fetcher, clock, pause, limits)

    private fun run(fetcher: HistoryFetcher, request: ImportRequest = ImportRequest(ImportMode.FULL), limits: ImportLimits = ImportLimits()) =
        runBlocking { importer(fetcher, limits).run(request) }

    private fun allEvents() =
        runBlocking { db.eventStampsBetween(LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.of(2100, 1, 1, 0, 0)) }

    private fun eventsOf(id: String, day: LocalDate) = allEvents().filter { it.songId == id && it.timestamp.toLocalDate() == day }

    private fun localEvent(songId: String, at: LocalDateTime, playTime: Long = 200_000) {
        db.insert(Event(songId = songId, timestamp = at, playTime = playTime))
    }

    private fun localSong(id: String, inLibrary: LocalDateTime? = null, liked: Boolean = false) {
        db.insert(SongEntity(id = id, title = "Mine $id", duration = 200, inLibrary = inLibrary, liked = liked))
    }

    // ------------------------------------------------------------------ writing

    @Test
    fun `a new phone gets the account history, the songs stay out of the library`() {
        val account =
            FakeAccount(
                chunk(
                    section("Today", "a", "b"),
                    section("Yesterday", "a"),
                    section("Wednesday", "c"),
                ),
            )
        val outcome = run(account)

        assertEquals(4, outcome.added)
        assertTrue(outcome.complete)
        assertTrue(outcome.reachedEnd)
        assertEquals(1, eventsOf("a", today).size)
        assertEquals(1, eventsOf("b", today).size)
        assertEquals(1, eventsOf("a", today.minusDays(1)).size)
        assertEquals(1, eventsOf("c", LocalDate.of(2026, 11, 4)).size)
        for (id in listOf("a", "b", "c")) {
            val stored = db.getSongByIdBlocking(id)
            assertNotNull(id, stored)
            assertNull("$id must not be in the library", stored!!.song.inLibrary)
            assertFalse(stored.song.liked)
            assertEquals("Song $id", stored.song.title)
            assertEquals(listOf("Artist $id"), stored.artists.map { it.name })
        }
        // the song's play time counter grew by what the events hold
        assertEquals(400_000L, db.getSongByIdBlocking("a")!!.song.totalPlayTime)
        assertEquals(200_000L, db.getSongByIdBlocking("b")!!.song.totalPlayTime)
        // nothing was added to the library
        assertTrue(runBlocking { db.songsByCreateDateAsc().first() }.isEmpty())
    }

    @Test
    fun `a second run adds nothing`() {
        val account = FakeAccount(chunk(section("Today", "a", "b", "a"), section("Yesterday", "a", "a")))
        assertEquals(5, run(account).added)
        val before = allEvents()
        val second = run(account)
        assertEquals(0, second.added)
        assertEquals(before.size, allEvents().size)
        // four plays of "a", counted once
        assertEquals(800_000L, db.getSongByIdBlocking("a")!!.song.totalPlayTime)
        assertEquals(200_000L, db.getSongByIdBlocking("b")!!.song.totalPlayTime)
    }

    @Test
    fun `plays the phone recorded itself are not doubled`() {
        localSong("a", inLibrary = LocalDateTime.of(2026, 10, 1, 9, 0))
        localEvent("a", today.atTime(10, 0))
        localEvent("a", today.minusDays(1).atTime(23, 59, 58)) // the account lists it just after midnight
        val outcome = run(FakeAccount(chunk(section("Today", "a", "a"), section("Yesterday", "b"))))
        // today: two entries for "a", the phone has one play today and one just before midnight: nothing to add for "a"
        assertEquals(listOf("b"), allEvents().filter { it.songId != "a" }.map { it.songId })
        assertEquals(1, outcome.added)
        assertEquals(2, allEvents().count { it.songId == "a" })
        // the library state of a song the phone knew is untouched
        assertNotNull(db.getSongByIdBlocking("a")!!.song.inLibrary)
    }

    @Test
    fun `plays of the other phone are added next to the phone's own`() {
        localSong("a")
        localEvent("a", today.atTime(9, 0))
        val outcome = run(FakeAccount(chunk(section("Today", "a", "a", "a"))))
        assertEquals(2, outcome.added)
        assertEquals(3, eventsOf("a", today).size)
        // and the library flag of the existing song did not change
        assertNull(db.getSongByIdBlocking("a")!!.song.inLibrary)
    }

    @Test
    fun `an existing song keeps its title, its likes and its library date`() {
        val added = LocalDateTime.of(2026, 9, 1, 12, 0)
        localSong("a", inLibrary = added, liked = true)
        run(FakeAccount(chunk(section("Today", "a"))))
        val stored = db.getSongByIdBlocking("a")!!.song
        assertEquals("Mine a", stored.title)
        assertTrue(stored.liked)
        assertEquals(added, stored.inLibrary)
    }

    @Test
    fun `plays are written oldest first so the ids follow the time`() {
        run(FakeAccount(chunk(section("Today", "a", "b"), section("Yesterday", "c"), section("Oct 20", "d"))))
        val events = runBlocking { db.firstEvent().first() }
        assertEquals("d", events!!.event.songId)
        val byId = allEvents().sortedBy { it.timestamp }.map { it.songId }
        assertEquals(listOf("d", "c", "b", "a"), byId)
    }

    @Test
    fun `merged plays older than the phone's first one are the first event`() {
        localSong("x")
        localEvent("x", LocalDateTime.of(2026, 11, 6, 12, 0)) // id 1
        run(FakeAccount(chunk(section("Today", "a"), section("Oct 20", "d"))))
        val first = runBlocking { db.firstEvent().first() }!!
        assertEquals("d", first.event.songId)
        assertEquals(LocalDate.of(2026, 10, 20), first.event.timestamp.toLocalDate())
        assertTrue("the older play got the higher id", first.event.id > 1)
    }

    @Test
    fun `the numbers Recap and the streak read are right after a merge`() {
        run(FakeAccount(chunk(section("Today", "a", "b"), section("Yesterday", "a"), section("Nov 3", "c"))))
        val days = runBlocking { db.listeningDays().first() }
        assertEquals(listOf(today, today.minusDays(1), LocalDate.of(2026, 11, 3)).map { it.toEpochDay() }, days)
        val dayMs = 86_400_000L
        assertEquals(400_000L, runBlocking { db.playTimeBetween(today.toEpochDay() * dayMs, (today.toEpochDay() + 1) * dayMs).first() })
        val from = LocalDateTime.of(2026, 1, 1, 0, 0)
        val to = LocalDateTime.of(2026, 11, 8, 0, 0)
        assertEquals(800_000L, runBlocking { db.getTotalPlayTimeInRange(from, to).first() })
        assertEquals(3, runBlocking { db.getUniqueSongCountInRange(from, to).first() })
        val top = runBlocking { db.mostPlayedSongsStats(fromTimeStamp = from, limit = 10, toTimeStamp = to).first() }
        assertEquals("a", top.first().id)
        assertEquals(2, top.first().songCountListened)
    }

    @Test
    fun `the streak manager sees the merged days without being told`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = ListeningStreakManager(context, db)
        assertEquals(0, runBlocking { withTimeout(10_000) { manager.streak.first { true } } }.current)
        // the real clock this time, because the manager reads the real day
        val real = RemoteHistoryImporter(db, FakeAccount(chunk(section("Today", "a"), section("Yesterday", "b"))), Clock.systemDefaultZone(), {})
        runBlocking { real.run(ImportRequest(ImportMode.FULL)) }
        val streak = runBlocking { withTimeout(15_000) { manager.streak.first { it.current >= 2 } } }
        assertEquals(2, streak.current)
        assertEquals(StreakState.ACTIVE, streak.state)
    }

    // ------------------------------------------------------------------ reading

    @Test
    fun `continuations are followed, the songs of a section's own continuation join that section`() {
        val account =
            FakeAccount(
                mapOf(
                    null to Result.success(chunk(section("Today", "a"), section = "s1")),
                    "s1" to Result.success(chunk(section("Yesterday", "b"), section = "s2", shelf = "h1")),
                    "h1" to Result.success(chunk(more = listOf(song("c")))),
                    "s2" to Result.success(chunk(section("Nov 3", "d"))),
                ),
            )
        val outcome = run(account)
        assertEquals(listOf(null, "s1", "h1", "s2"), account.asked)
        assertEquals(4, outcome.pages)
        assertEquals(4, outcome.added)
        assertTrue(outcome.reachedEnd)
        assertEquals(1, eventsOf("c", today.minusDays(1)).size)
        assertEquals(1, eventsOf("d", LocalDate.of(2026, 11, 3)).size)
    }

    @Test
    fun `a token that comes back is not asked twice`() {
        val account =
            FakeAccount(
                mapOf(
                    null to Result.success(chunk(section("Today", "a"), section = "s1")),
                    "s1" to Result.success(chunk(section("Yesterday", "b"), section = "s1")),
                ),
            )
        val outcome = run(account)
        assertEquals(listOf(null, "s1"), account.asked)
        assertEquals(2, outcome.added)
        assertTrue(outcome.reachedEnd)
    }

    @Test
    fun `the reading pauses between pages and not after the last`() {
        val account =
            FakeAccount(
                mapOf(
                    null to Result.success(chunk(section("Today", "a"), section = "s1")),
                    "s1" to Result.success(chunk(section("Yesterday", "b"))),
                ),
            )
        var pauses = 0
        runBlocking { importer(account, pause = { pauses++ }).run(ImportRequest(ImportMode.FULL)) }
        assertEquals(1, pauses)
    }

    @Test
    fun `reading stops at the page limit and at the entry limit`() {
        val endless =
            object : HistoryFetcher {
                var calls = 0

                override suspend fun fetch(continuation: String?): Result<HistoryChunk> {
                    calls++
                    return Result.success(chunk(section("Today", "p$calls", "q$calls", "r$calls"), section = "t$calls"))
                }
            }
        val byPages = run(endless, limits = ImportLimits(maxPages = 3, maxEntries = 1000))
        assertEquals(3, endless.calls)
        assertTrue(byPages.capped)
        assertTrue(byPages.complete)

        val endless2 = object : HistoryFetcher {
            var calls = 0
            override suspend fun fetch(continuation: String?): Result<HistoryChunk> {
                calls++
                return Result.success(chunk(section = "u$calls"))
            }
        }
        val empty = run(endless2, limits = ImportLimits(maxPages = 5, maxEntries = 1000))
        assertEquals(5, endless2.calls)
        assertTrue(empty.capped)
        assertEquals(0, empty.added)

        val bigPages =
            object : HistoryFetcher {
                var calls = 0

                override suspend fun fetch(continuation: String?): Result<HistoryChunk> {
                    calls++
                    return Result.success(chunk(section("Today", *Array(20) { "n$calls-$it" }), section = "v$calls"))
                }
            }
        val byEntries = run(bigPages, limits = ImportLimits(maxPages = 40, maxEntries = 50))
        assertEquals(3, bigPages.calls)
        assertTrue(byEntries.capped)
    }

    @Test
    fun `a periodic run stops after the page that reaches the day it needs`() {
        val account =
            FakeAccount(
                mapOf(
                    null to Result.success(chunk(section("Today", "a"), section("Yesterday", "b"), section = "s1")),
                    "s1" to Result.success(chunk(section("Wednesday", "c"), section("Nov 3", "d"), section = "s2")),
                    "s2" to Result.success(chunk(section("Oct 10", "e"))),
                ),
            )
        val outcome = run(account, ImportRequest(ImportMode.INCREMENTAL, stopBelowDay = LocalDate.of(2026, 11, 4)))
        assertEquals(listOf(null, "s1"), account.asked)
        assertTrue(outcome.reachedEnd)
        assertTrue(outcome.complete)
        // the page that reached the day is still read in full
        assertEquals(4, outcome.added)
        assertTrue(eventsOf("e", LocalDate.of(2026, 10, 10)).isEmpty())
    }

    @Test
    fun `an error on a later page keeps what was read, and says it failed`() {
        val account =
            FakeAccount(
                mapOf(
                    null to Result.success(chunk(section("Today", "a"), section = "s1")),
                    "s1" to Result.failure(java.io.IOException("offline")),
                ),
            )
        val outcome = run(account)
        assertFalse(outcome.complete)
        assertNotNull(outcome.error)
        assertEquals(1, outcome.added)
        assertEquals(1, eventsOf("a", today).size)
        // the next run finds it again and adds only the rest
        val healthy =
            FakeAccount(
                mapOf(
                    null to Result.success(chunk(section("Today", "a"), section = "s1")),
                    "s1" to Result.success(chunk(section("Yesterday", "b"))),
                ),
            )
        assertEquals(1, run(healthy).added)
        assertEquals(2, allEvents().size)
    }

    @Test
    fun `an error on the first page writes nothing`() {
        val outcome = run(FakeAccount(mapOf<String?, Result<HistoryChunk>>(null to Result.failure(java.io.IOException("offline")))))
        assertFalse(outcome.complete)
        assertEquals(0, outcome.added)
        assertTrue(allEvents().isEmpty())
    }

    @Test
    fun `a slow account times out, keeps the first page and does not hang`() {
        val slow =
            object : HistoryFetcher {
                override suspend fun fetch(continuation: String?): Result<HistoryChunk> {
                    if (continuation == null) return Result.success(chunk(section("Today", "a"), section = "s1"))
                    delay(30_000)
                    return Result.success(chunk(section("Yesterday", "b")))
                }
            }
        val started = System.currentTimeMillis()
        val outcome = run(slow, limits = ImportLimits(timeoutMs = 300))
        assertTrue(System.currentTimeMillis() - started < 10_000)
        assertTrue(outcome.timedOut)
        assertFalse(outcome.complete)
        assertEquals(1, outcome.added)
    }

    @Test
    fun `cancelling a run writes nothing`() {
        val entered = CompletableDeferred<Unit>()
        val blocked =
            object : HistoryFetcher {
                override suspend fun fetch(continuation: String?): Result<HistoryChunk> {
                    if (continuation == null) return Result.success(chunk(section("Today", "a"), section = "s1"))
                    entered.complete(Unit)
                    // like the real fetcher, a cancellation inside is caught by runCatching and handed back as a failure
                    return runCatching { delay(60_000); chunk() }
                }
            }
        runBlocking {
            val job = launch(Dispatchers.Default) { importer(blocked).run(ImportRequest(ImportMode.FULL)) }
            entered.await()
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
        }
        assertTrue(allEvents().isEmpty())
    }

    @Test
    fun `a title that cannot be read is skipped and the rest is merged`() {
        val outcome = run(FakeAccount(chunk(section("Today", "a"), section("Zeitraum xyz", "b"), section("Yesterday", "c"))))
        assertEquals(listOf("Zeitraum xyz"), outcome.skippedTitles)
        assertEquals(2, outcome.added)
        assertTrue(eventsOf("b", today).isEmpty())
    }

    @Test
    fun `an account without history changes nothing`() {
        val outcome = run(FakeAccount(chunk()))
        assertEquals(0, outcome.added)
        assertTrue(outcome.complete)
        assertTrue(allEvents().isEmpty())
        assertEquals(0, run(FakeAccount(chunk(HistoryPage.HistorySection("Today", emptyList())))).added)
    }

    // ------------------------------------------------------------------ what the user did here

    @Test
    fun `plays the user removed are not brought back, plays after that are`() {
        val key = DaySong(today.minusDays(2).toEpochDay(), "a")
        val removed = mapOf(key to 1)
        val account = FakeAccount(chunk(section("Thursday", "a")))
        assertEquals(0, run(account, ImportRequest(ImportMode.FULL, removed = removed)).added)
        assertTrue(allEvents().isEmpty())
        // the other phone plays it once more
        val more = FakeAccount(chunk(section("Thursday", "a", "a")))
        // Thursday 5 Nov is two days before Saturday
        assertEquals(1, run(more, ImportRequest(ImportMode.FULL, removed = removed)).added)
    }

    @Test
    fun `days up to the day the history was cleared stay out`() {
        val account = FakeAccount(chunk(section("Today", "a"), section("Yesterday", "b"), section("Nov 3", "c")))
        val outcome = run(account, ImportRequest(ImportMode.FULL, floorDay = today.minusDays(1)))
        assertEquals(1, outcome.added)
        assertEquals(listOf("a"), allEvents().map { it.songId })
    }

    @Test
    fun `merging again after a clear of the history only brings back what lies after it`() {
        val account = FakeAccount(chunk(section("Today", "a"), section("Yesterday", "b")))
        assertEquals(2, run(account).added)
        runBlocking { db.clearListenHistory() }
        assertTrue(allEvents().isEmpty())
        assertEquals(0, run(account, ImportRequest(ImportMode.FULL, floorDay = today)).added)
        assertTrue(allEvents().isEmpty())
    }

    // ------------------------------------------------------------------ the query the merge reads

    @Test
    fun `the stamps query includes its first moment and excludes its last`() {
        localSong("a")
        localEvent("a", LocalDateTime.of(2026, 11, 5, 0, 0))
        localEvent("a", LocalDateTime.of(2026, 11, 5, 23, 59, 59))
        localEvent("a", LocalDateTime.of(2026, 11, 6, 0, 0))
        val stamps = runBlocking { db.eventStampsBetween(LocalDateTime.of(2026, 11, 5, 0, 0), LocalDateTime.of(2026, 11, 6, 0, 0)) }
        assertEquals(2, stamps.size)
        assertEquals(3, runBlocking { db.eventCountNow() })
    }

    @Test
    fun `nothing in the importer touches the library or the song list of the phone`() {
        localSong("mine", inLibrary = LocalDateTime.of(2026, 10, 1, 9, 0))
        run(FakeAccount(chunk(section("Today", "a", "b", "c"))))
        val library = runBlocking { db.songsByCreateDateAsc().first() }
        assertEquals(listOf("mine"), library.map { it.id })
    }
}
