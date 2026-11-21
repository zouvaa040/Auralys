package com.metrolist.music.utils

/** What the flame is doing right now. */
enum class StreakState {
    /** No streak is alive. */
    NONE,

    /** Played today: the flame burns. */
    ACTIVE,

    /** The streak is alive through yesterday but nothing was played today yet: the flame is grey. */
    WAITING,

    /** Yesterday was missed: listening [ListeningStreak.RECOVERY_MINUTES] minutes today brings the flame back. */
    RECOVERABLE,
}

/**
 * How many days in a row something was played. A day counts when at least one play event happened on it.
 *
 * A day without plays does not end the streak on the spot: the next day [RECOVERY_MINUTES] minutes of listening fill
 * the gap again ([StreakState.RECOVERABLE]), at most [MAX_RECOVERIES] times a month. Without that the streak starts over.
 * Recovered days are handed in as [restored] and count like played days.
 */
data class ListeningStreak(
    val current: Int,
    val longest: Int,
    val totalDays: Int,
    val state: StreakState,
    /** Minutes listened today (only the ones already recorded). */
    val todayMinutes: Int = 0,
    /** Recoveries still free this month. */
    val recoveriesLeft: Int = MAX_RECOVERIES,
    /** The missed day a recovery would fill, while [state] is [StreakState.RECOVERABLE]. */
    val missedDay: Long? = null,
    /** True when today's listening is enough to recover [missedDay]; the manager then spends a recovery. */
    val canRecoverNow: Boolean = false,
    /** A recovery was spent today. */
    val recoveredToday: Boolean = false,
    /** Yesterday was missed but there is no recovery left, so the old streak is gone. */
    val recoveryBlocked: Boolean = false,
) {
    /** The flame only burns after something was played today; otherwise it is grey. */
    val lit: Boolean get() = state == StreakState.ACTIVE

    val playedToday: Boolean get() = state == StreakState.ACTIVE

    /** Look of the flame for the streak length, see [StreakTiers]. */
    val tier: StreakTier get() = StreakTiers.forDays(current)

    /** The next streak length worth reaching, or null once all of [MILESTONES] are behind. */
    val nextMilestone: Int? get() = MILESTONES.firstOrNull { it > current }

    /** The milestone reached last (0 before the first), the start of the bar towards [nextMilestone]. */
    val previousMilestone: Int get() = MILESTONES.lastOrNull { it <= current } ?: 0

    /** 0..1 progress from [previousMilestone] to [nextMilestone]; 1 when everything is reached. */
    val milestoneProgress: Float
        get() {
            val next = nextMilestone ?: return 1f
            val start = previousMilestone
            return ((current - start).toFloat() / (next - start)).coerceIn(0f, 1f)
        }

    /** 0..1 progress of today's listening towards the recovery. */
    val recoveryProgress: Float get() = (todayMinutes.toFloat() / RECOVERY_MINUTES).coerceIn(0f, 1f)

    companion object {
        const val RECOVERY_MINUTES = 10
        const val MAX_RECOVERIES = 5

        /** The streak lengths that unlock a new flame; the goals shown on the card. */
        val MILESTONES = StreakTiers.all.map { it.minDays }.filter { it > 0 }

        val EMPTY = ListeningStreak(current = 0, longest = 0, totalDays = 0, state = StreakState.NONE)

        private const val MILLIS_PER_DAY = 86_400_000L
        private const val MILLIS_PER_MINUTE = 60_000L

        /** Event timestamps are stored as local wall-clock time written as if it were UTC, so this is the local day number. */
        fun dayOf(timestampMillis: Long): Long = Math.floorDiv(timestampMillis, MILLIS_PER_DAY)

        /**
         * [days] are day numbers (epoch days) with plays, in any order and possibly repeated; [restored] the missed days
         * that were recovered; [today] is today's day number; [todayPlayMillis] what was played today so far.
         */
        fun from(
            days: Collection<Long>,
            restored: Collection<Long> = emptyList(),
            today: Long,
            todayPlayMillis: Long = 0,
            recoveriesLeft: Int = MAX_RECOVERIES,
            recoveredToday: Boolean = false,
        ): ListeningStreak {
            val minutes = (todayPlayMillis / MILLIS_PER_MINUTE).toInt().coerceAtLeast(0)
            val playedDays = days.filter { it <= today }.toSet()
            val played = (playedDays + restored.filter { it <= today }).toSortedSet()
            val base = EMPTY.copy(todayMinutes = minutes, recoveriesLeft = recoveriesLeft, recoveredToday = recoveredToday)
            if (played.isEmpty()) return base

            var longest = 0
            var run = 0
            var previous = Long.MIN_VALUE
            for (day in played) {
                run = if (previous != Long.MIN_VALUE && day == previous + 1) run + 1 else 1
                if (run > longest) longest = run
                previous = day
            }
            val total = played.size

            fun runEndingAt(day: Long): Int {
                var cursor = day
                var count = 0
                while (cursor in played) {
                    count++
                    cursor--
                }
                return count
            }

            val playedToday = today in playedDays
            val missedYesterday = (today - 1) !in played && (today - 2) in played
            if (missedYesterday && recoveriesLeft > 0) {
                return base.copy(
                    current = runEndingAt(today - 2),
                    longest = longest,
                    totalDays = total,
                    state = StreakState.RECOVERABLE,
                    missedDay = today - 1,
                    canRecoverNow = minutes >= RECOVERY_MINUTES,
                )
            }

            val current = runEndingAt(if (playedToday) today else today - 1)
            return base.copy(
                current = current,
                longest = longest,
                totalDays = total,
                state =
                    when {
                        current == 0 -> StreakState.NONE
                        playedToday -> StreakState.ACTIVE
                        else -> StreakState.WAITING
                    },
                recoveryBlocked = missedYesterday,
            )
        }
    }
}
