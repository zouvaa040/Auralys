package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningStreakTest {
    private val today = 20_000L
    private val tenMinutes = 10 * 60_000L

    private fun streak(
        days: List<Long>,
        restored: List<Long> = emptyList(),
        playMillis: Long = 0,
        left: Int = ListeningStreak.MAX_RECOVERIES,
    ) = ListeningStreak.from(days, restored, today, playMillis, left)

    @Test
    fun `no plays means no streak`() {
        val s = streak(emptyList())
        assertEquals(0, s.current)
        assertEquals(StreakState.NONE, s.state)
        assertFalse(s.lit)
    }

    @Test
    fun `only today is a streak of one and the flame burns`() {
        val s = streak(listOf(today))
        assertEquals(1, s.current)
        assertEquals(1, s.longest)
        assertEquals(StreakState.ACTIVE, s.state)
        assertTrue(s.lit)
    }

    @Test
    fun `days in a row count up to today`() {
        val s = streak(listOf(today, today - 1, today - 2, today - 3))
        assertEquals(4, s.current)
        assertEquals(4, s.longest)
        assertEquals(4, s.totalDays)
    }

    @Test
    fun `before playing today the streak is alive but the flame is grey`() {
        val s = streak(listOf(today - 1, today - 2, today - 3))
        assertEquals(3, s.current)
        assertEquals(StreakState.WAITING, s.state)
        assertFalse(s.lit)
    }

    @Test
    fun `one missed day can be recovered and keeps the old streak visible`() {
        val s = streak(listOf(today - 2, today - 3, today - 4))
        assertEquals(StreakState.RECOVERABLE, s.state)
        assertEquals(3, s.current)
        assertEquals(today - 1, s.missedDay)
        assertFalse(s.canRecoverNow)
        assertFalse(s.lit)
    }

    @Test
    fun `listening for ten minutes today makes the recovery due`() {
        val s = streak(listOf(today, today - 2, today - 3), playMillis = tenMinutes)
        assertEquals(StreakState.RECOVERABLE, s.state)
        assertTrue(s.canRecoverNow)
        assertEquals(10, s.todayMinutes)
        assertEquals(1f, s.recoveryProgress, 0f)
    }

    @Test
    fun `nine minutes are not enough`() {
        val s = streak(listOf(today, today - 2), playMillis = tenMinutes - 1)
        assertFalse(s.canRecoverNow)
        assertEquals(9, s.todayMinutes)
    }

    @Test
    fun `a recovered day joins the streak and today lights the flame`() {
        val s = streak(listOf(today, today - 2, today - 3), restored = listOf(today - 1), playMillis = tenMinutes)
        assertEquals(StreakState.ACTIVE, s.state)
        assertEquals(4, s.current)
        assertEquals(4, s.longest)
        assertFalse(s.canRecoverNow)
    }

    @Test
    fun `without recoveries left the streak ends`() {
        val s = streak(listOf(today - 2, today - 3), left = 0)
        assertEquals(StreakState.NONE, s.state)
        assertEquals(0, s.current)
        assertTrue(s.recoveryBlocked)
        assertEquals(2, s.longest)
    }

    @Test
    fun `without recoveries left playing today starts again from one`() {
        val s = streak(listOf(today, today - 2, today - 3), left = 0)
        assertEquals(StreakState.ACTIVE, s.state)
        assertEquals(1, s.current)
        assertEquals(2, s.longest)
    }

    @Test
    fun `two missed days cannot be recovered`() {
        val s = streak(listOf(today - 3, today - 4))
        assertEquals(StreakState.NONE, s.state)
        assertEquals(0, s.current)
        assertNull(s.missedDay)
        assertFalse(s.recoveryBlocked)
    }

    @Test
    fun `an unrecovered gap the day before ends the old streak for good`() {
        // missed yesterday-before, listened a little yesterday only: too late for the old one
        val s = streak(listOf(today - 1, today - 3, today - 4))
        assertEquals(StreakState.WAITING, s.state)
        assertEquals(1, s.current)
        assertEquals(2, s.longest)
    }

    @Test
    fun `longest streak is found anywhere in the history`() {
        val s = streak(listOf(today, today - 1, today - 10, today - 11, today - 12, today - 13, today - 14))
        assertEquals(2, s.current)
        assertEquals(5, s.longest)
        assertEquals(7, s.totalDays)
    }

    @Test
    fun `repeated and future days are ignored`() {
        val s = streak(listOf(today, today, today - 1, today - 1, today + 5))
        assertEquals(2, s.current)
        assertEquals(2, s.totalDays)
    }

    @Test
    fun `milestones are the lengths that unlock a flame`() {
        assertEquals(listOf(1, 3, 7, 14, 30, 50, 100, 365, 1000), StreakTiers.all.map { it.minDays }.filter { it > 0 })
        assertEquals(StreakTiers.all.map { it.minDays }.filter { it > 0 }, ListeningStreak.MILESTONES)
        val s = streak(listOf(today, today - 1, today - 2, today - 3))
        assertEquals(7, s.nextMilestone)
        assertEquals(3, s.previousMilestone)
        assertEquals(1f / 4f, s.milestoneProgress, 0.001f)
    }

    @Test
    fun `every flame length picks its own look`() {
        assertEquals(0, StreakTiers.forDays(0).index)
        assertEquals(1, StreakTiers.forDays(1).index)
        assertEquals(1, StreakTiers.forDays(2).index)
        assertEquals(2, StreakTiers.forDays(3).index)
        assertEquals(6, StreakTiers.forDays(99).index)
        assertEquals(7, StreakTiers.forDays(100).index)
        assertEquals(8, StreakTiers.forDays(400).index)
        assertEquals(9, StreakTiers.forDays(5000).index)
    }

    @Test
    fun `day numbers follow the stored wall-clock time`() {
        assertEquals(0L, ListeningStreak.dayOf(86_399_999L))
        assertEquals(1L, ListeningStreak.dayOf(86_400_000L))
        assertEquals(-1L, ListeningStreak.dayOf(-1L))
    }

    @Test
    fun `recoveries are stored as pairs and counted per month`() {
        val entries = StreakRecoveries.parse("20000:20001,20010:20011,garbage,5:")
        assertEquals(2, entries.size)
        assertEquals("20000:20001,20010:20011", StreakRecoveries.serialize(entries))
        // 20000 is 2024-10-04, so these two are both October 2024
        assertEquals(2, StreakRecoveries.usedInMonthOf(entries, 20_015L))
        assertEquals(3, StreakRecoveries.left(entries, 20_015L))
        // a different month starts with all of them free
        assertEquals(5, StreakRecoveries.left(entries, 20_040L))
    }
}
