/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Keeps the listening streak up to date for the whole app and spends a recovery as soon as today's listening earns it,
 * even when no screen is open (the player service starts it).
 */

package com.metrolist.music.utils

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.StreakRecoveriesKey
import com.metrolist.music.db.MusicDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** The recoveries already spent, kept in the settings as text like "20400:20401,20450:20451". */
object StreakRecoveries {
    data class Entry(val missedDay: Long, val usedOnDay: Long)

    fun parse(stored: String?): List<Entry> =
        stored.orEmpty().split(',').mapNotNull { token ->
            val parts = token.split(':')
            val missed = parts.getOrNull(0)?.toLongOrNull()
            val used = parts.getOrNull(1)?.toLongOrNull()
            if (parts.size == 2 && missed != null && used != null) Entry(missed, used) else null
        }

    fun serialize(entries: List<Entry>): String = entries.joinToString(",") { "${it.missedDay}:${it.usedOnDay}" }

    /** How many recoveries were spent in the month that [day] belongs to. */
    fun usedInMonthOf(entries: List<Entry>, day: Long): Int {
        val month = YearMonth.from(LocalDate.ofEpochDay(day))
        return entries.count { YearMonth.from(LocalDate.ofEpochDay(it.usedOnDay)) == month }
    }

    fun left(entries: List<Entry>, today: Long): Int =
        (ListeningStreak.MAX_RECOVERIES - usedInMonthOf(entries, today)).coerceAtLeast(0)
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ListeningStreakManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _streak = MutableStateFlow(ListeningStreak.EMPTY)
    private var started = false

    /** The current streak; starts the watching on first use. */
    val streak: StateFlow<ListeningStreak>
        get() {
            start()
            return _streak.asStateFlow()
        }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            // the day number is re-read every minute so the streak turns over at midnight
            flow {
                while (true) {
                    emit(LocalDate.now().toEpochDay())
                    delay(60_000)
                }
            }.distinctUntilChanged()
                .flatMapLatest { today ->
                    combine(
                        database.listeningDays(),
                        database.playTimeBetween(today * MILLIS_PER_DAY, (today + 1) * MILLIS_PER_DAY),
                        context.dataStore.data.map { it[StreakRecoveriesKey] },
                    ) { days, playMillis, stored ->
                        val entries = StreakRecoveries.parse(stored)
                        ListeningStreak.from(
                            days = days,
                            restored = entries.map { it.missedDay },
                            today = today,
                            todayPlayMillis = playMillis,
                            recoveriesLeft = StreakRecoveries.left(entries, today),
                            recoveredToday = entries.any { it.usedOnDay == today },
                        )
                    }
                }.distinctUntilChanged()
                .collect { streak ->
                    _streak.value = streak
                    val missed = streak.missedDay
                    if (streak.canRecoverNow && missed != null) recover(missed)
                }
        }
    }

    /** Fills [missedDay] with a recovery, unless it was filled already or this month's recoveries are used up. */
    private suspend fun recover(missedDay: Long) {
        val today = LocalDate.now().toEpochDay()
        try {
            context.dataStore.edit { prefs ->
                val entries = StreakRecoveries.parse(prefs[StreakRecoveriesKey])
                if (entries.any { it.missedDay == missedDay }) return@edit
                if (StreakRecoveries.left(entries, today) <= 0) return@edit
                // old entries only matter for this month's count, so a year is plenty
                val kept = entries.filter { it.usedOnDay >= today - 400 }
                prefs[StreakRecoveriesKey] = StreakRecoveries.serialize(kept + StreakRecoveries.Entry(missedDay, today))
            }
        } catch (e: Exception) {
            Timber.tag("ListeningStreak").e(e, "Could not save the streak recovery")
        }
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
