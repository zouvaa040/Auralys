package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** When a run starts, the plays the user removed, and how a merged history meets the streak and its recoveries. */
class RemoteHistoryMergeSupportTest {
    private val hour = 60L * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun decide(
        manual: Boolean = false,
        signedIn: Boolean = true,
        switchOn: Boolean = true,
        paused: Boolean = false,
        saver: Boolean = false,
        online: Boolean = true,
        neverRan: Boolean = false,
        lastSuccess: Long? = now - 7 * hour,
        lastAttempt: Long? = now - 7 * hour,
    ) = RemoteHistoryMergeGate.decide(manual, signedIn, switchOn, paused, saver, online, neverRan, now, lastSuccess, lastAttempt)

    // ------------------------------------------------------------------ the gate

    @Test
    fun `a run is due after six hours`() {
        assertEquals(RemoteHistoryMergeGate.Decision.RUN, decide())
        assertEquals(RemoteHistoryMergeGate.Decision.SKIP_TOO_SOON, decide(lastSuccess = now - 5 * hour, lastAttempt = now - 5 * hour))
        assertEquals(RemoteHistoryMergeGate.Decision.RUN, decide(lastSuccess = now - 6 * hour, lastAttempt = now - 6 * hour))
        assertEquals(RemoteHistoryMergeGate.Decision.SKIP_TOO_SOON, decide(lastSuccess = now - 6 * hour + 1, lastAttempt = now - 6 * hour + 1))
    }

    @Test
    fun `the first run is not held back by the six hours, but a failing start waits a quarter of an hour`() {
        assertEquals(RemoteHistoryMergeGate.Decision.RUN, decide(neverRan = true, lastSuccess = null, lastAttempt = null))
        assertEquals(RemoteHistoryMergeGate.Decision.SKIP_TOO_SOON, decide(neverRan = true, lastSuccess = null, lastAttempt = now - 5 * 60_000))
        assertEquals(RemoteHistoryMergeGate.Decision.RUN, decide(neverRan = true, lastSuccess = null, lastAttempt = now - 16 * 60_000))
        // also after the six hours passed: a run that failed a minute ago is not repeated at once
        assertEquals(RemoteHistoryMergeGate.Decision.SKIP_TOO_SOON, decide(lastSuccess = now - 9 * hour, lastAttempt = now - 60_000))
    }

    @Test
    fun `nothing runs signed out, with the listen history paused, or with the switch off`() {
        assertEquals(RemoteHistoryMergeGate.Decision.SKIP_SIGNED_OUT, decide(signedIn = false))
        assertEquals(RemoteHistoryMergeGate.Decision.SKIP_SIGNED_OUT, decide(signedIn = false, manual = true))
        assertEquals(RemoteHistoryMergeGate.Decision.SKIP_PAUSED, decide(paused = true))
        assertEquals(RemoteHistoryMergeGate.Decision.SKIP_PAUSED, decide(paused = true, manual = true))
        assertEquals(RemoteHistoryMergeGate.Decision.SKIP_SWITCH_OFF, decide(switchOn = false))
        assertEquals(RemoteHistoryMergeGate.Decision.RUN, decide(switchOn = false, manual = true))
    }

    @Test
    fun `the data saver and the missing network stop the automatic runs`() {
        assertEquals(RemoteHistoryMergeGate.Decision.SKIP_DATA_SAVER, decide(saver = true))
        assertEquals(RemoteHistoryMergeGate.Decision.SKIP_DATA_SAVER, decide(saver = true, neverRan = true, lastSuccess = null, lastAttempt = null))
        assertEquals(RemoteHistoryMergeGate.Decision.RUN, decide(saver = true, manual = true))
        assertEquals(RemoteHistoryMergeGate.Decision.SKIP_OFFLINE, decide(online = false))
        assertEquals(RemoteHistoryMergeGate.Decision.SKIP_OFFLINE, decide(online = false, manual = true))
    }

    @Test
    fun `a manual run is never too soon`() {
        assertEquals(RemoteHistoryMergeGate.Decision.RUN, decide(manual = true, lastSuccess = now - 1000, lastAttempt = now - 1000))
    }

    @Test
    fun `a periodic run reads back to three days before the last complete run, a full one as far as it can`() {
        val covered = LocalDate.of(2026, 11, 7).toEpochDay()
        val floor = LocalDate.of(2026, 11, 1).toEpochDay()
        assertEquals(LocalDate.of(2026, 11, 4), RemoteHistoryMergeGate.stopBelowDay(false, covered, null))
        assertNull(RemoteHistoryMergeGate.stopBelowDay(true, covered, null))
        assertNull(RemoteHistoryMergeGate.stopBelowDay(false, null, null))
        // after a clear of the history nothing before that day is wanted, so the reading stops there
        assertEquals(LocalDate.of(2026, 11, 1), RemoteHistoryMergeGate.stopBelowDay(true, covered, floor))
        assertEquals(LocalDate.of(2026, 11, 4), RemoteHistoryMergeGate.stopBelowDay(false, covered, floor))
        assertEquals(LocalDate.of(2026, 11, 1), RemoteHistoryMergeGate.stopBelowDay(false, floor + 1, floor))
    }

    // ------------------------------------------------------------------ the plays the user removed

    @Test
    fun `removed plays are counted per day and song`() {
        var stored = ""
        stored = RemovedPlays.add(stored, 20_000, "abc")
        stored = RemovedPlays.add(stored, 20_000, "abc")
        stored = RemovedPlays.add(stored, 20_001, "abc")
        stored = RemovedPlays.add(stored, 20_000, "xyz")
        val parsed = RemovedPlays.parse(stored)
        assertEquals(2, parsed[DaySong(20_000, "abc")])
        assertEquals(1, parsed[DaySong(20_001, "abc")])
        assertEquals(1, parsed[DaySong(20_000, "xyz")])
        assertEquals(parsed, RemovedPlays.parse(RemovedPlays.serialize(parsed)))
    }

    @Test
    fun `garbage in the stored text is ignored`() {
        assertTrue(RemovedPlays.parse(null).isEmpty())
        assertTrue(RemovedPlays.parse("").isEmpty())
        assertTrue(RemovedPlays.parse("nonsense,1:2,a:b:c,20000::1,20000:x:0,20000:x:-3,,:::").isEmpty())
        assertEquals(1, RemovedPlays.parse("bad,20000:x:1,also:bad").size)
        assertEquals("", RemovedPlays.add("", 20_000, ""))
        assertEquals("", RemovedPlays.add("", 20_000, "a:b"))
        assertEquals("", RemovedPlays.add("", 20_000, "a,b"))
    }

    @Test
    fun `the list is capped and the oldest removals drop out`() {
        var stored = ""
        for (i in 0 until RemovedPlays.LIMIT + 50) stored = RemovedPlays.add(stored, 20_000L + i, "s$i")
        val parsed = RemovedPlays.parse(stored)
        assertEquals(RemovedPlays.LIMIT, parsed.size)
        assertFalse(DaySong(20_000, "s0") in parsed)
        assertTrue(DaySong(20_000L + RemovedPlays.LIMIT + 49, "s${RemovedPlays.LIMIT + 49}") in parsed)
    }

    // ------------------------------------------------------------------ streak and recoveries

    private val today = 20_000L

    @Test
    fun `days filled by merged plays count like days played here`() {
        val before = ListeningStreak.from(days = listOf(today), today = today)
        assertEquals(1, before.current)
        val after = ListeningStreak.from(days = listOf(today, today - 1, today - 2, today - 3), today = today)
        assertEquals(4, after.current)
        assertEquals(4, after.longest)
        assertEquals(StreakState.ACTIVE, after.state)
    }

    @Test
    fun `a missed day that the merge fills takes the streak out of the recovery state`() {
        val missed = ListeningStreak.from(days = listOf(today - 2, today - 3), today = today)
        assertEquals(StreakState.RECOVERABLE, missed.state)
        assertEquals(today - 1, missed.missedDay)
        val filled = ListeningStreak.from(days = listOf(today - 1, today - 2, today - 3), today = today)
        assertEquals(StreakState.WAITING, filled.state)
        assertEquals(3, filled.current)
        assertNull(filled.missedDay)
    }

    @Test
    fun `a recovered day that real plays now fill is counted once`() {
        val played = listOf(today, today - 1, today - 2)
        val restored = listOf(today - 1) // a recovery was spent for a day that the merge filled afterwards
        val both = ListeningStreak.from(days = played, restored = restored, today = today)
        val plain = ListeningStreak.from(days = played, today = today)
        assertEquals(plain.current, both.current)
        assertEquals(plain.totalDays, both.totalDays)
        assertEquals(3, both.totalDays)
        assertTrue(both.current >= 0 && both.longest >= 0 && both.totalDays >= 0)
    }

    @Test
    fun `a recovery for a day that now has plays is handed back, the others stay`() {
        val entries =
            listOf(
                StreakRecoveries.Entry(missedDay = today - 5, usedOnDay = today - 4),
                StreakRecoveries.Entry(missedDay = today - 2, usedOnDay = today - 1),
            )
        val kept = refundFilledRecoveries(entries, playedDays = setOf(today - 5, today, today - 7))
        assertEquals(listOf(entries[1]), kept)
        assertEquals(entries, refundFilledRecoveries(entries, emptySet()))
        assertTrue(refundFilledRecoveries(entries, setOf(today - 5, today - 2)).isEmpty())
        // the allowance of the month grows back by the entries that were handed back
        val used = StreakRecoveries.left(entries.map { it.copy(usedOnDay = today) }, today)
        val usedAfter = StreakRecoveries.left(kept.map { it.copy(usedOnDay = today) }, today)
        assertEquals(ListeningStreak.MAX_RECOVERIES - 2, used)
        assertEquals(ListeningStreak.MAX_RECOVERIES - 1, usedAfter)
    }

    @Test
    fun `merged plays of today count towards the ten minutes that recover a missed day`() {
        // yesterday is missed, today has 12 minutes of merged plays: the same rule as for plays made here
        val s = ListeningStreak.from(days = listOf(today, today - 2, today - 3), today = today, todayPlayMillis = 12 * 60_000L)
        assertEquals(StreakState.RECOVERABLE, s.state)
        assertTrue(s.canRecoverNow)
        assertEquals(12, s.todayMinutes)
    }

    @Test
    fun `without a recovery left the old streak is gone, merged plays or not`() {
        val s = ListeningStreak.from(days = listOf(today, today - 2, today - 3), today = today, recoveriesLeft = 0)
        assertEquals(1, s.current)
        assertTrue(s.recoveryBlocked)
    }
}
