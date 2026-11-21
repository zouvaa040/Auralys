package com.metrolist.music.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nothing the video mode remembers about trouble lasts until the app restarts: songs without a video, songs that failed and
 * the rest of the whole mode all come back by themselves, with growing but capped waits.
 */
class VideoSessionRestTest {
    private var now = 1_000_000L

    private fun session(
        max: Int = 3,
        random: Double = 0.0,
    ) = VideoSession(maxConsecutiveFailures = max, clock = { now }, random = { random })

    @Test
    fun `a song without a video is asked again after ten minutes`() {
        val s = session()
        s.markNoVideo("a")
        assertTrue(s.isNoVideo("a"))
        now += 9 * 60_000
        assertTrue(s.isNoVideo("a"))
        now += 61_000
        assertFalse(s.isNoVideo("a"))
        assertEquals(0L, s.songRestRemainingMs("a"))
    }

    @Test
    fun `a song that failed for real rests 2 then 4 then 8 then 10 minutes`() {
        val s = session()
        assertEquals(120_000L, s.markSongFailed("a"))
        now += 121_000
        assertFalse(s.isNoVideo("a"))
        assertEquals(240_000L, s.markSongFailed("a"))
        now += 241_000
        assertEquals(480_000L, s.markSongFailed("a"))
        now += 481_000
        assertEquals(600_000L, s.markSongFailed("a"))
        now += 601_000
        assertEquals("capped", 600_000L, s.markSongFailed("a"))
        // another song starts from the beginning
        assertEquals(120_000L, s.markSongFailed("b"))
    }

    @Test
    fun `a first frame of a song clears its strikes`() {
        val s = session()
        s.markSongFailed("a")
        s.markSongFailed("a")
        s.recordSuccess("a")
        assertEquals(120_000L, s.markSongFailed("a"))
    }

    @Test
    fun `the whole mode rests after the third real failure for 60 s and comes back`() {
        val s = session()
        assertFalse(s.recordFailure())
        assertFalse(s.recordFailure())
        assertTrue(s.recordFailure())
        assertTrue(s.killed)
        assertEquals(60_000L, s.cooldownRemainingMs())
        now += 59_000
        assertTrue(s.killed)
        now += 2_000
        assertFalse(s.killed)
        assertEquals(0L, s.cooldownRemainingMs())
    }

    @Test
    fun `every rest doubles, up to ten minutes, and a first frame makes the next one short again`() {
        val s = session()
        val expected = listOf(60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L)
        for ((i, rest) in expected.withIndex()) {
            repeat(3) { s.recordFailure() }
            assertEquals("rest ${i + 1}", rest, s.cooldownRemainingMs())
            now += rest + 1
            assertFalse(s.killed)
        }
        s.recordSuccess()
        repeat(3) { s.recordFailure() }
        assertEquals(60_000L, s.cooldownRemainingMs())
    }

    @Test
    fun `after a rest the count of failures starts from zero`() {
        val s = session()
        repeat(3) { s.recordFailure() }
        now += 61_000
        assertFalse(s.killed)
        // two more failures are not enough, the old three do not count
        assertFalse(s.recordFailure())
        assertFalse(s.recordFailure())
        assertFalse(s.killed)
        assertTrue(s.recordFailure())
    }

    @Test
    fun `a failure while the mode rests does not extend the rest or count`() {
        val s = session()
        repeat(3) { s.recordFailure() }
        val before = s.cooldownRemainingMs()
        assertFalse(s.recordFailure())
        assertEquals(before, s.cooldownRemainingMs())
        assertEquals(1, s.strikes)
    }

    @Test
    fun `the jitter makes the rest 60 to 90 seconds`() {
        val low = session(random = 0.0).also { s -> repeat(3) { s.recordFailure() } }
        val high = session(random = 0.9999).also { s -> repeat(3) { s.recordFailure() } }
        assertEquals(60_000L, low.cooldownRemainingMs())
        assertTrue(high.cooldownRemainingMs() in 89_000L..90_000L)
    }

    @Test
    fun `the list of resting songs is bounded`() {
        val s = VideoSession(maxNoVideoEntries = 3, clock = { now })
        for (i in 1..10) s.markNoVideo("s$i")
        assertFalse(s.isNoVideo("s1"))
        assertTrue(s.isNoVideo("s10"))
    }
}
