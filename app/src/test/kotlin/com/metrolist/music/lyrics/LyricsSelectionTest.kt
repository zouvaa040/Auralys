package com.metrolist.music.lyrics

import com.metrolist.music.lyrics.LyricsSelection.Candidate
import com.metrolist.music.lyrics.LyricsSelection.Quality
import com.metrolist.music.lyrics.LyricsSelection.Sync
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSelectionTest {
    // a 3:00 song
    private val fits = "[00:10.00]a\n[02:50.00]z"
    private val fitsWord = "[00:10.00]a b\n<a:10.0:10.4|b:10.4:11.0>\n[02:50.00]z\n<z:170.0:171.0>"
    private val runsPast = "[00:10.00]a\n[03:30.00]z"
    private val stopsEarly = "[00:10.00]a\n[00:40.00]z"
    private val plain = "just words\nno stamps"

    private fun c(
        name: String,
        text: String,
        seconds: Int = 180,
    ) = LyricsSelection.candidate(name, text, seconds)

    private fun request(
        title: String,
        artist: String = "Artist",
        seconds: Int = 180,
    ) = LyricsRequest.of("id", title, artist.split(", "), seconds, null)

    @Test
    fun `quality follows the timestamps against the length of the song`() {
        assertEquals(Quality.VERIFIED, LyricsSelection.quality(fits, 180))
        assertEquals(Quality.REJECTED, LyricsSelection.quality(runsPast, 180))
        assertEquals(Quality.SHORT, LyricsSelection.quality(stopsEarly, 180))
        assertEquals(Quality.PLAIN, LyricsSelection.quality(plain, 180))
        assertEquals(Quality.REJECTED, LyricsSelection.quality("  ", 180))
        // unknown length: synced lyrics cannot be checked and are taken
        assertEquals(Quality.VERIFIED, LyricsSelection.quality(runsPast, 0))
        // a few seconds of outro is fine
        assertEquals(Quality.VERIFIED, LyricsSelection.quality("[00:10.00]a\n[03:07.00]z", 180))
    }

    @Test
    fun `sync kind is read from the text`() {
        assertEquals(Sync.LINE, LyricsSelection.sync(fits))
        assertEquals(Sync.WORD, LyricsSelection.sync(fitsWord))
        assertEquals(Sync.WORD, LyricsSelection.sync("[00:01.00]<00:01.00>a <00:01.50>b"))
        assertEquals(Sync.PLAIN, LyricsSelection.sync(plain))
    }

    @Test
    fun `verified beats loose beats short beats plain and priority decides between equals`() {
        assertEquals("C", LyricsSelection.best(listOf(c("A", plain), c("B", stopsEarly), c("C", fits), c("D", fits)))?.provider)
        assertEquals("B", LyricsSelection.best(listOf(c("A", plain), c("B", stopsEarly)))?.provider)
        assertEquals("A", LyricsSelection.best(listOf(c("A", plain), c("B", plain)))?.provider)
    }

    @Test
    fun `word timing beats line timing even lower in the list`() {
        assertEquals("Low", LyricsSelection.best(listOf(c("High", fits), c("Low", fitsWord)))?.provider)
        assertEquals("High", LyricsSelection.best(listOf(c("High", fitsWord), c("Low", fitsWord)))?.provider)
    }

    @Test
    fun `lyrics that run past the song are never used even when they are all there is`() {
        assertNull(LyricsSelection.best(listOf(c("A", runsPast))))
        assertEquals("B", LyricsSelection.best(listOf(c("A", runsPast), c("B", plain)))?.provider)
    }

    // ---- rate: what the source says it matched ----

    @Test
    fun `a hit of another version is rejected`() {
        val acoustic = request("La La Lost You (Acoustic Version)", "NIKI, 88rising", 209)
        val original = LyricsHit(fits, "La La Lost You", "NIKI", 209.0)
        assertEquals(Quality.REJECTED, LyricsSelection.rate("LrcLib", acoustic, original).quality)
        val right = LyricsHit(fits, "La La Lost You - Acoustic Version", "NIKI", 208.0)
        assertEquals(Quality.VERIFIED, LyricsSelection.rate("LrcLib", acoustic, right).quality)
        // and the other way round
        val plainSong = request("La La Lost You", "NIKI", 209)
        assertEquals(Quality.REJECTED, LyricsSelection.rate("LrcLib", plainSong, right).quality)
    }

    @Test
    fun `another artist or a guest the song does not have is rejected`() {
        val song = request("Untitled", "Rex Orange County", 136)
        val short = "[00:02.00]a\n[01:22.00]z"
        assertEquals(Quality.REJECTED, LyricsSelection.rate("LrcLib", song, LyricsHit(short, "Untitled", "Suede", 136.0)).quality)
        assertEquals(Quality.VERIFIED, LyricsSelection.rate("LrcLib", song, LyricsHit(short, "Untitled", "Rex Orange County", 137.0)).quality)

        val solo = request("Dracula", "Tame Impala", 206)
        val withGuest = LyricsHit("[00:29.00]a\n[03:18.00]z", "Dracula (feat. JENNIE)", "Tame Impala", 206.0)
        assertEquals(Quality.REJECTED, LyricsSelection.rate("LrcLib", solo, withGuest).quality)
    }

    @Test
    fun `a song with a guest takes a hit without the guest only at a close length`() {
        val duet = request("Dracula (feat. JENNIE)", "Tame Impala", 210)
        val lyrics = "[00:29.00]a\n[03:18.00]z"
        assertEquals(Quality.VERIFIED, LyricsSelection.rate("LrcLib", duet, LyricsHit(lyrics, "Dracula", "Tame Impala", 209.7)).quality)
        // 3:25 is the solo cut
        assertEquals(Quality.REJECTED, LyricsSelection.rate("LrcLib", duet, LyricsHit(lyrics, "Dracula", "Tame Impala", 205.3)).quality)
        // the hit that names the guest may be further off
        assertEquals(Quality.LOOSE, LyricsSelection.rate("LrcLib", duet, LyricsHit(lyrics, "Dracula (feat. JENNIE)", "Tame Impala", 205.0)).quality)
    }

    @Test
    fun `length of the source recording decides between verified loose and rejected`() {
        val song = request("Song", seconds = 180)
        assertEquals(Quality.VERIFIED, LyricsSelection.rate("X", song, LyricsHit(fits, durationSeconds = 182.5)).quality)
        assertEquals(Quality.LOOSE, LyricsSelection.rate("X", song, LyricsHit(fits, durationSeconds = 186.0)).quality)
        assertEquals(Quality.REJECTED, LyricsSelection.rate("X", song, LyricsHit(fits, durationSeconds = 195.0)).quality)
        // far off is another recording even untimed (probe: a plain text of another band's "Untitled")
        assertEquals(Quality.REJECTED, LyricsSelection.rate("X", song, LyricsHit(plain, durationSeconds = 195.0)).quality)
        assertEquals(Quality.PLAIN, LyricsSelection.rate("X", song, LyricsHit(plain, durationSeconds = 184.0)).quality)
        // keyed on the video: wider window
        assertEquals(Quality.VERIFIED, LyricsSelection.rate("X", song, LyricsHit(fits, durationSeconds = 189.0, keyedOnVideo = true)).quality)
    }

    @Test
    fun `a version song from a source that does not say what it matched stays below checked ones`() {
        val remix = request("Despacito (Remix) (feat. Justin Bieber)", "Luis Fonsi, Daddy Yankee", 231)
        val text = "[00:06.00]a\n[03:45.00]z\n<z:225.0:226.0>"
        val unchecked = LyricsSelection.rate("BetterLyrics", remix, LyricsHit(text, durationSeconds = 229.0))
        assertEquals(Quality.LOOSE, unchecked.quality)
        val checked = LyricsSelection.rate("BiniLyrics", remix, LyricsHit("[00:09.30]a\n[03:45.00]z", "Despacito (Remix)", "Luis Fonsi", 229.0))
        assertEquals(Quality.VERIFIED, checked.quality)
        assertEquals("BiniLyrics", LyricsSelection.best(listOf(unchecked, checked))?.provider)
        // a title without version words is not affected
        assertEquals(Quality.VERIFIED, LyricsSelection.rate("BetterLyrics", request("Monolog", "Pamungkas", 208), LyricsHit("[00:11.00]a\n[03:03.00]z", durationSeconds = 208.0)).quality)
    }

    @Test
    fun `an answer whose singing starts where no other does is passed over`() {
        val studio = { name: String -> c(name, "[00:52.70]a\n[04:14.70]z", 392) }
        val live = c("Unison", "[02:19.10]a\n[05:41.50]z", 392)
        val list = listOf(live, studio("LrcLib"), studio("KuGou"), studio("LrcRed"))
        assertEquals(setOf(live), LyricsSelection.outliers(list))
        assertEquals("LrcLib", LyricsSelection.best(list)?.provider)
        // an ad-lib line only one source has moves the first line, not the last: not another version
        val apple = { name: String -> c(name, "[00:27.30]a\n[03:10.30]z", 202) }
        val withAdLib = c("LrcLib", "[00:14.60]yeah\n[03:10.20]z", 202)
        assertTrue(LyricsSelection.outliers(listOf(withAdLib, apple("A"), apple("B"), apple("C"))).isEmpty())
        // two answers are not enough to call one of them odd
        assertTrue(LyricsSelection.outliers(listOf(live, studio("LrcLib"))).isEmpty())
        assertEquals(52_700L, LyricsSelection.firstSungMs("[00:00.00]\n[00:52.70]{agent:v1}a"))
    }

    // ---- race ----

    private fun entry(
        name: String,
        waitMs: Long,
        lyrics: String?,
    ) = RaceEntry(name) {
        delay(waitMs)
        lyrics?.let { c(name, it) }
    }

    @Test
    fun `providers run at the same time`() =
        runBlocking {
            val started = System.nanoTime()
            val result =
                LyricsSelection.race(
                    listOf(entry("A", 600, null), entry("B", 600, null), entry("C", 600, fits)),
                    perProviderMs = 3000,
                    totalMs = 5000,
                )
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertEquals("C", result?.provider)
            // one after another this would be 1800 ms
            assertTrue("took $tookMs ms", tookMs < 1500)
        }

    @Test
    fun `a slower provider higher in the list still wins over a quicker one below it`() =
        runBlocking {
            val result =
                LyricsSelection.race(
                    listOf(entry("High", 400, fits), entry("Low", 0, fits)),
                    perProviderMs = 3000,
                    totalMs = 5000,
                )
            assertEquals("High", result?.provider)
        }

    @Test
    fun `a word timed answer lower in the list is waited for within the grace time`() =
        runBlocking {
            val result =
                LyricsSelection.race(
                    listOf(entry("Line", 0, fits), entry("Word", 300, fitsWord)),
                    perProviderMs = 3000,
                    totalMs = 5000,
                    graceMs = 1000,
                )
            assertEquals("Word", result?.provider)
        }

    @Test
    fun `the grace time is not waited for when the best answer is word timed`() =
        runBlocking {
            val started = System.nanoTime()
            val result =
                LyricsSelection.race(
                    listOf(entry("Word", 0, fitsWord), entry("Slow", 2500, fits)),
                    perProviderMs = 3000,
                    totalMs = 5000,
                    graceMs = 2000,
                )
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertEquals("Word", result?.provider)
            assertTrue("took $tookMs ms", tookMs < 1000)
        }

    @Test
    fun `a line timed answer does not wait longer than the grace time`() =
        runBlocking {
            val started = System.nanoTime()
            val result =
                LyricsSelection.race(
                    listOf(entry("Line", 0, fits), entry("Slow", 2800, fitsWord)),
                    perProviderMs = 3000,
                    totalMs = 5000,
                    graceMs = 300,
                )
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertEquals("Line", result?.provider)
            assertTrue("took $tookMs ms", tookMs < 1500)
        }

    @Test
    fun `every answer is reported as it comes`() =
        runBlocking {
            val seen = mutableListOf<String>()
            LyricsSelection.race(
                listOf(entry("A", 10, null), entry("B", 50, fits)),
                perProviderMs = 3000,
                totalMs = 5000,
                graceMs = 0,
            ) { name, _ -> synchronized(seen) { seen += name } }
            assertEquals(listOf("A", "B"), seen)
        }

    @Test
    fun `a failing or throwing provider does not stop the others`() =
        runBlocking {
            val boom = RaceEntry("Boom") { error("broken") }
            val result =
                LyricsSelection.race(listOf(boom, entry("B", 50, null), entry("C", 50, fits)), 3000, 5000)
            assertEquals("C", result?.provider)
        }

    @Test
    fun `a provider that is too slow is skipped after its own limit`() =
        runBlocking {
            val started = System.nanoTime()
            val result =
                LyricsSelection.race(
                    listOf(entry("Slow", 10_000, fits), entry("Quick", 50, fits)),
                    perProviderMs = 300,
                    totalMs = 5000,
                )
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertEquals("Quick", result?.provider)
            assertTrue("took $tookMs ms", tookMs < 1500)
        }

    @Test
    fun `the whole search is cut at the total limit and keeps what came in`() =
        runBlocking {
            val started = System.nanoTime()
            val result =
                LyricsSelection.race(
                    listOf(entry("Plain", 50, plain), entry("Slow", 10_000, fits)),
                    perProviderMs = 20_000,
                    totalMs = 600,
                )
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertEquals("Plain", result?.provider)
            assertTrue("took $tookMs ms", tookMs < 2000)
        }

    @Test
    fun `lyrics from another cut of the song lose to a plain text`() =
        runBlocking {
            val result =
                LyricsSelection.race(listOf(entry("Wrong", 10, runsPast), entry("Plain", 10, plain)), 3000, 5000)
            assertEquals("Plain", result?.provider)
        }

    @Test
    fun `nothing found is null`() =
        runBlocking {
            assertNull(LyricsSelection.race(listOf(entry("A", 10, null), entry("B", 10, " ")), 3000, 5000))
            assertNull(LyricsSelection.race(emptyList(), 3000, 5000))
        }

    @Test
    fun `settled needs every entry above the best one`() {
        val word = IndexedValue(1, c("W", fitsWord))
        assertFalse(LyricsSelection.settled(word, booleanArrayOf(false, true, false), graceOver = false))
        assertTrue(LyricsSelection.settled(word, booleanArrayOf(true, true, false), graceOver = false))
        val line = IndexedValue(0, c("L", fits))
        assertFalse(LyricsSelection.settled(line, booleanArrayOf(true, false), graceOver = false))
        assertTrue(LyricsSelection.settled(line, booleanArrayOf(true, false), graceOver = true))
        assertTrue(LyricsSelection.settled(null, booleanArrayOf(true, true), graceOver = false))
        assertFalse(LyricsSelection.settled(null, booleanArrayOf(true, false), graceOver = false))
    }
}
