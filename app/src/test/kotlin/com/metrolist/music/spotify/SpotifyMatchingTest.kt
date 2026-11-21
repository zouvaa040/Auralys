package com.metrolist.music.spotify

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.utils.ExternalTrack
import com.metrolist.music.viewmodels.PlaylistLinkImportViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SpotifyMatchingTest {
    private fun track(n: Int) = SpotifyTrack("id$n", "Title $n", listOf("Artist $n"), "Album", 200_000L, null)

    private fun song(id: String) = SongItem(id = id, title = id, artists = listOf(Artist("A", null)), thumbnail = "")

    private fun found(m: SpotifyMatch?): String? = (m as? SpotifyMatch.Found)?.song?.id

    // ------------------------------------------------------------ the runner

    @Test
    fun `every track gets a result and the list order is the order of work`() = runBlocking {
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        val runner =
            SpotifyMatchRunner(
                matcher = { t ->
                    order += t.title
                    if (t.title.endsWith("3")) null else song("yt-${t.title}")
                },
                parallelism = 1,
            )
        val results = ConcurrentHashMap<Int, SpotifyMatch>()
        runner.run((1..5).map(::track), LruSpotifyMatchStore()) { i, m -> results[i] = m }
        assertEquals((1..5).map { "Title $it" }, order)
        assertEquals("yt-Title 1", found(results[0]))
        assertTrue(results[2] is SpotifyMatch.Missing)
        assertEquals(5, results.size)
    }

    @Test
    fun `no more than the allowed number of searches run at the same time`() = runBlocking {
        val running = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val runner =
            SpotifyMatchRunner(
                matcher = { t ->
                    val now = running.incrementAndGet()
                    peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                    delay(15)
                    running.decrementAndGet()
                    song("yt-${t.title}")
                },
                parallelism = 4,
            )
        val done = AtomicInteger(0)
        runner.run((1..30).map(::track), LruSpotifyMatchStore()) { _, _ -> done.incrementAndGet() }
        assertEquals(30, done.get())
        assertEquals(4, peak.get())
    }

    @Test
    fun `the matching is as gentle as the link import, three at a time with a short pause`() {
        assertEquals(PlaylistLinkImportViewModel.MAX_PARALLEL, SpotifyMatchRunner.DEFAULT_PARALLELISM)
        assertEquals(PlaylistLinkImportViewModel.REQUEST_PAUSE_MS, SpotifyMatchRunner.DEFAULT_PAUSE_MS)
        assertEquals(3, SpotifyMatchRunner.DEFAULT_PARALLELISM)
        assertEquals(150L, SpotifyMatchRunner.DEFAULT_PAUSE_MS)
    }

    @Test
    fun `a runner with the default limits never has more than three searches running`() = runBlocking {
        val running = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val runner =
            SpotifyMatchRunner(
                matcher = { t ->
                    peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
                    delay(10)
                    running.decrementAndGet()
                    song("yt-${t.title}")
                },
            )
        runner.run((1..12).map(::track), LruSpotifyMatchStore()) { _, _ -> }
        assertEquals(3, peak.get())
    }

    @Test
    fun `a pause follows every search`() = runBlocking {
        val starts = java.util.Collections.synchronizedList(ArrayList<Long>())
        val runner = SpotifyMatchRunner(matcher = { starts += System.currentTimeMillis(); song("x") }, parallelism = 1, pauseMs = 80)
        runner.run((1..4).map(::track), LruSpotifyMatchStore()) { _, _ -> }
        assertEquals(4, starts.size)
        for (i in 1 until starts.size) assertTrue("gap ${starts[i] - starts[i - 1]} ms", starts[i] - starts[i - 1] >= 70)
    }

    @Test
    fun `what the session already knows is reported at once and not searched again`() = runBlocking {
        val store = LruSpotifyMatchStore()
        store.put("id1", SpotifyMatch.Found(song("known")))
        val searched = java.util.Collections.synchronizedList(ArrayList<String>())
        val runner = SpotifyMatchRunner(matcher = { t -> searched += t.title; song("yt") }, parallelism = 2)
        val results = ConcurrentHashMap<Int, SpotifyMatch>()
        runner.run((1..3).map(::track), store) { i, m -> results[i] = m }
        assertEquals(listOf("Title 2", "Title 3"), searched.sorted())
        assertEquals("known", found(results[0]))
        // and what was found is kept for the next visit
        assertEquals("yt", found(store.get("id2")))
    }

    @Test
    fun `a matcher that throws counts as not found for this run and is not remembered`() = runBlocking {
        val store = LruSpotifyMatchStore()
        val runner = SpotifyMatchRunner(matcher = { error("boom") }, parallelism = 2)
        val results = ConcurrentHashMap<Int, SpotifyMatch>()
        runner.run((1..3).map(::track), store) { i, m -> results[i] = m }
        assertTrue(results.values.all { it is SpotifyMatch.Missing })
        assertNull(store.get("id1"))
    }

    @Test
    fun `cancelling stops the matching`() = runBlocking {
        val started = AtomicInteger(0)
        val runner =
            SpotifyMatchRunner(
                matcher = {
                    started.incrementAndGet()
                    delay(10_000)
                    song("never")
                },
                parallelism = 3,
            )
        val job = async { runner.run((1..50).map(::track), LruSpotifyMatchStore()) { _, _ -> } }
        while (started.get() < 3) delay(5)
        job.cancel()
        try {
            job.await()
            fail()
        } catch (e: CancellationException) {
            // stopped
        }
        delay(50)
        assertEquals("no new search starts after the cancel", 3, started.get())
    }

    @Test
    fun `an empty list needs no work`() = runBlocking {
        SpotifyMatchRunner(matcher = { error("not called") }).run(emptyList(), LruSpotifyMatchStore()) { _, _ -> fail() }
    }

    @Test
    fun `a Spotify song is looked up by its title, artists and length`() {
        val external: ExternalTrack = SpotifyTrack("x", "Flashing Lights", listOf("Kanye West", "Dwele"), "Graduation", 237_506L, null).toExternalTrack()
        assertEquals("Flashing Lights", external.title)
        assertEquals(listOf("Kanye West", "Dwele"), external.artists)
        assertEquals(237_506L, external.durationMs)
        assertNull(SpotifyTrack("x", "T", emptyList(), null, 0L, null).toExternalTrack().durationMs)
    }

    // ------------------------------------------------------------ the session store

    @Test
    fun `a miss is forgotten after ten minutes and a find is not`() {
        var now = 0L
        val store = LruSpotifyMatchStore(maxEntries = 10) { now }
        store.put("hit", SpotifyMatch.Found(song("yt")))
        store.put("miss", SpotifyMatch.Missing)
        now += LruSpotifyMatchStore.MISS_TTL_MS - 1
        assertTrue(store.get("miss") is SpotifyMatch.Missing)
        now += 2
        assertNull(store.get("miss"))
        assertEquals("yt", found(store.get("hit")))
    }

    @Test
    fun `the least recently used matches go first`() {
        val store = LruSpotifyMatchStore(maxEntries = 3)
        store.put("a", SpotifyMatch.Missing)
        store.put("b", SpotifyMatch.Missing)
        store.put("c", SpotifyMatch.Missing)
        store.get("a")
        store.put("d", SpotifyMatch.Missing)
        assertNull(store.get("b"))
        assertTrue(store.get("a") != null && store.get("c") != null && store.get("d") != null)
    }

    @Test
    fun `pending is never stored and misses can be forgotten on request`() {
        val store = LruSpotifyMatchStore()
        store.put("p", SpotifyMatch.Pending)
        assertNull(store.get("p"))
        store.put("m", SpotifyMatch.Missing)
        store.put("f", SpotifyMatch.Found(song("yt")))
        store.forgetMisses(listOf("m", "f"))
        assertNull(store.get("m"))
        assertEquals("yt", found(store.get("f")))
        store.clear()
        assertNull(store.get("f"))
    }

    // ------------------------------------------------------------ what a tap plays

    private fun row(n: Int, match: SpotifyMatch) = SpotifyRow(track(n), match)

    @Test
    fun `the queue is the matched songs in list order and skips the others`() {
        val rows =
            listOf(
                row(1, SpotifyMatch.Found(song("a"))),
                row(2, SpotifyMatch.Missing),
                row(3, SpotifyMatch.Pending),
                row(4, SpotifyMatch.Found(song("b"))),
                row(5, SpotifyMatch.Found(song("c"))),
            )
        val all = matchedQueue(rows)
        assertEquals(listOf("a", "b", "c"), all.songs.map { it.id })
        assertEquals(0, all.startIndex)

        // a tap on row 4 starts at its place among the matched songs
        val tapped = matchedQueue(rows, startRow = 3)
        assertEquals(listOf("a", "b", "c"), tapped.songs.map { it.id })
        assertEquals(1, tapped.startIndex)
        assertEquals(2, matchedQueue(rows, startRow = 4).startIndex)

        // a tap on a row without a match falls back to the first song
        assertEquals(0, matchedQueue(rows, startRow = 1).startIndex)
        assertTrue(matchedQueue(listOf(row(1, SpotifyMatch.Missing))).songs.isEmpty())
        assertSame(rows[0].song, all.songs[0])
    }
}
