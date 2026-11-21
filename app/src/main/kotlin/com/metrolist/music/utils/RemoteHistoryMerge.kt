/**
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Merges the listening history of the YouTube Music account into the local play events, so the Recap and the
 * listening streak (both are computed from the local `event` table) also count what was played on the user's other
 * devices. Everything in this file is pure: no Android, no network, no database. The part that talks to YouTube and
 * Room is [RemoteHistoryImporter].
 *
 * The rule, per (calendar day, song): the account says the song was played R times that day, the phone already has L
 * events of it; max(0, R - L) events are added. Running it again adds nothing, because the added events count in L.
 */

package com.metrolist.music.utils

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

// ------------------------------------------------------------------------------------------------ section titles

/**
 * Turns the title of a history section ("Today", "Yesterday", "Monday", "Nov 3", "3 Nov 2025", "Hari ini",
 * "Kemarin", "Senin", "3 Nov") into the calendar day it stands for. English and Indonesian words are known. A title
 * that cannot be read gives null: the caller skips the section instead of guessing.
 */
object HistoryTitleParser {
    private val todayWords = setOf("today", "hari ini")
    private val yesterdayWords = setOf("yesterday", "kemarin")

    private val weekdays: Map<String, DayOfWeek> =
        buildMap {
            fun add(day: DayOfWeek, vararg names: String) = names.forEach { put(it, day) }
            add(DayOfWeek.MONDAY, "monday", "mon", "senin", "sen")
            add(DayOfWeek.TUESDAY, "tuesday", "tue", "tues", "selasa", "sel")
            add(DayOfWeek.WEDNESDAY, "wednesday", "wed", "weds", "rabu", "rab")
            add(DayOfWeek.THURSDAY, "thursday", "thu", "thur", "thurs", "kamis", "kam")
            add(DayOfWeek.FRIDAY, "friday", "fri", "jumat", "jum'at", "jumat", "jum")
            add(DayOfWeek.SATURDAY, "saturday", "sat", "sabtu", "sab")
            add(DayOfWeek.SUNDAY, "sunday", "sun", "minggu", "ahad", "min")
        }

    private val months: Map<String, Int> =
        buildMap {
            fun add(month: Int, vararg names: String) = names.forEach { put(it, month) }
            add(1, "january", "jan", "januari")
            add(2, "february", "feb", "februari", "pebruari")
            add(3, "march", "mar", "maret")
            add(4, "april", "apr")
            add(5, "may", "mei")
            add(6, "june", "jun", "juni")
            add(7, "july", "jul", "juli")
            add(8, "august", "aug", "agu", "agt", "ags", "agustus")
            add(9, "september", "sep", "sept")
            add(10, "october", "oct", "okt", "oktober")
            add(11, "november", "nov", "nop", "nopember")
            add(12, "december", "dec", "des", "desember")
        }

    private val fillerWords = setOf("of", "the", "pada", "tanggal", "tgl", "on")
    private val ordinal = Regex("""^(\d{1,2})(st|nd|rd|th)$""")
    private val isoLike = Regex("""^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})$""")
    private val dmyLike = Regex("""^(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})$""")
    private val dmLike = Regex("""^(\d{1,2})[-/](\d{1,2})$""")
    private val invisible = Regex("""[​-‏‪-‮⁠-⁤﻿]""")

    private fun clean(raw: String): String =
        Normalizer
            .normalize(raw, Normalizer.Form.NFKC)
            .replace(invisible, "")
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('ʼ', '\'')
            .lowercase(Locale.ROOT)
            .replace(Regex("""\s+"""), " ")
            .trim()

    /** The day [title] stands for when read on [today], or null when it cannot be read (or lies in the future). */
    fun parse(title: String, today: LocalDate): LocalDate? {
        val text = clean(title)
        if (text.isEmpty()) return null
        val plain = text.trim(' ', ',', '.', ':', ';', '-', '–', '—')
        if (plain in todayWords) return today
        if (plain in yesterdayWords) return today.minusDays(1)

        numeric(text, today)?.let { return it }
        if (Regex("""^\d{1,4}$""").matches(plain)) return null // a bare year or number is not a day

        // words: commas and dots only separate ("Mon, Nov 3", "3 Nov.", "Nov. 3")
        var tokens =
            plain
                .replace(',', ' ')
                .replace('.', ' ')
                .split(' ')
                .map { it.trim('-', '–', '—', ':', ';') }
                .filter { it.isNotEmpty() && it !in fillerWords }
                .map { ordinal.matchEntire(it)?.groupValues?.get(1) ?: it }
        if (tokens.isEmpty()) return null

        // a leading weekday only decorates a full date ("Mon, Nov 3")
        if (tokens.size > 1 && tokens.first() in weekdays) tokens = tokens.drop(1)

        if (tokens.size == 1) {
            val weekday = weekdays[tokens[0]] ?: return null
            return resolveWeekday(weekday, today)
        }

        val numbers = tokens.map { it.toIntOrNull()?.takeIf { _ -> it.all(Char::isDigit) } }
        val monthIndex = tokens.indexOfFirst { it in months }
        if (monthIndex < 0) return null
        if (tokens.count { it in months } != 1) return null
        val month = months.getValue(tokens[monthIndex])
        val others = tokens.indices.filter { it != monthIndex }
        if (others.any { numbers[it] == null }) return null

        return when (tokens.size) {
            2 -> {
                // "nov 3" or "3 nov"; a bare "november 2025" is a month, not a day
                val n = numbers[others[0]]!!
                if (tokens[others[0]].length >= 3) return null
                dayWithoutYear(month, n, today)
            }

            3 -> {
                // "nov 3 2025", "3 nov 2025" or "2025 nov 3"
                val first = others[0]
                val second = others[1]
                val a = numbers[first]!!
                val b = numbers[second]!!
                val yearIsFirst = tokens[first].length == 4 && monthIndex == 1
                val (day, year) =
                    when {
                        yearIsFirst -> b to a
                        tokens[second].length == 4 -> a to b
                        else -> return null
                    }
                if (tokens[if (yearIsFirst) second else first].length > 2) return null
                dayWithYear(year, month, day, today)
            }

            else -> null
        }
    }

    private fun numeric(text: String, today: LocalDate): LocalDate? {
        val plain = text.trim(' ', ',', '.')
        isoLike.matchEntire(plain)?.let { m ->
            val (y, mo, d) = m.destructured
            return dayWithYear(y.toInt(), mo.toInt(), d.toInt(), today)
        }
        dmyLike.matchEntire(plain)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val year = m.groupValues[3].toInt()
            return when {
                a > 12 && b <= 12 -> dayWithYear(year, b, a, today)
                b > 12 && a <= 12 -> dayWithYear(year, a, b, today)
                a == b -> dayWithYear(year, a, b, today)
                else -> null // 3/4/2026 is 3 April or 4 March: not guessed
            }
        }
        dmLike.matchEntire(plain)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            return when {
                a > 12 && b <= 12 -> dayWithoutYear(b, a, today)
                b > 12 && a <= 12 -> dayWithoutYear(a, b, today)
                a == b -> dayWithoutYear(a, b, today)
                else -> null
            }
        }
        return null
    }

    /** The most recent such weekday strictly before [today], within the last 7 days (today's own weekday: a week ago). */
    fun resolveWeekday(weekday: DayOfWeek, today: LocalDate): LocalDate {
        val back = (today.dayOfWeek.value - weekday.value + 7) % 7
        return today.minusDays(if (back == 0) 7L else back.toLong())
    }

    private fun dayWithYear(year: Int, month: Int, day: Int, today: LocalDate): LocalDate? {
        if (year !in 1970..9999) return null
        val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
        return date.takeUnless { it.isAfter(today) }
    }

    /** "Nov 3" with no year: this year, or last year when that would lie in the future. 29 Feb goes to the last leap year. */
    private fun dayWithoutYear(month: Int, day: Int, today: LocalDate): LocalDate? {
        for (back in 0..8) {
            val candidate = runCatching { LocalDate.of(today.year - back, month, day) }.getOrNull() ?: continue
            if (!candidate.isAfter(today)) return candidate
        }
        return null
    }
}

// ------------------------------------------------------------------------------------------------ data

/** One entry of the account history; [durationSec] is what YouTube lists next to it. */
data class RemoteSong(val songId: String, val durationSec: Int? = null)

/** A section as the page delivers it: the (localised) title and the entries, newest first. */
data class RemoteSection(val title: String, val songs: List<RemoteSong>)

/** All entries YouTube lists for one calendar day, newest first. */
data class RemoteDay(val day: LocalDate, val songs: List<RemoteSong>)

/** A play the phone already has. */
data class LocalPlay(val songId: String, val at: LocalDateTime)

/** (calendar day as epoch day, song). */
data class DaySong(val epochDay: Long, val songId: String)

/** An event to add. [at] is local wall-clock time, like every other event. */
data class PlannedPlay(val songId: String, val at: LocalDateTime, val playTimeMs: Long)

data class SectionResolution(
    val days: List<RemoteDay>,
    /** Titles that could not be read or did not fit the order of the page; they are skipped. */
    val skippedTitles: List<String>,
)

data class MergePlan(
    /** Oldest first, so the ids grow with the time. */
    val plays: List<PlannedPlay>,
    /** Entries of the account inside the merged days. */
    val remoteEntries: Int,
    /** Entries that a play of the same day already covered. */
    val matchedSameDay: Int,
    /** Entries covered by a play just across midnight (clock or time zone of the account differs from the phone). */
    val matchedAcrossMidnight: Int,
    /** Entries ignored because the user removed that play from the history here. */
    val suppressed: Int,
    /** Entries ignored because they lie on or before the day the history was cleared. */
    val beforeFloor: Int,
)

// ------------------------------------------------------------------------------------------------ the planner

object RemoteHistoryMerge {
    /** A local play within this many hours of midnight can stand for an account entry on the other side of midnight. */
    const val EDGE_SLACK_HOURS = 12

    /** The play time written for an entry never exceeds this (seconds); the account does not say how long it was heard. */
    const val PLAY_CAP_SECONDS = 360

    /** Used when YouTube lists no duration (seconds). */
    const val DEFAULT_PLAY_SECONDS = 180

    private const val SECONDS_PER_DAY = 86_400L

    fun playTimeMs(durationSec: Int?): Long {
        val seconds = (durationSec ?: 0).takeIf { it > 0 } ?: DEFAULT_PLAY_SECONDS
        return minOf(seconds, PLAY_CAP_SECONDS) * 1000L
    }

    /**
     * Reads the day of each section. Sections come newest first, so each day must lie before the one above it; one
     * that does not (a title that was read wrongly) is skipped. The same title twice in a row is the same day.
     */
    fun resolve(sections: List<RemoteSection>, today: LocalDate): SectionResolution {
        val days = ArrayList<RemoteDay>()
        val skipped = ArrayList<String>()
        var previousDay: LocalDate? = null
        var previousTitle: String? = null
        for (section in sections) {
            val day = HistoryTitleParser.parse(section.title, today)
            if (day == null) {
                skipped += section.title
                continue
            }
            val normalised = section.title.trim().lowercase(Locale.ROOT)
            val before = previousDay
            if (before != null && day == before && normalised == previousTitle && days.isNotEmpty()) {
                days[days.lastIndex] = days.last().let { it.copy(songs = it.songs + section.songs) }
                continue
            }
            if (before != null && !day.isBefore(before)) {
                skipped += section.title
                continue
            }
            days += RemoteDay(day, section.songs)
            previousDay = day
            previousTitle = normalised
        }
        return SectionResolution(days, skipped)
    }

    /**
     * What to add to the phone.
     *
     * @param remote the days of the account history with their entries (newest first inside a day)
     * @param local the plays the phone has, at least the ones within a day of the remote days
     * @param today the phone's calendar day; @param now the phone's wall-clock time (events never lie after it)
     * @param suppressed plays the user removed here: that many account entries are ignored per (day, song)
     * @param floorDay days up to and including this one are ignored (the user cleared the history that day)
     */
    fun plan(
        remote: List<RemoteDay>,
        local: List<LocalPlay>,
        today: LocalDate,
        now: LocalDateTime,
        suppressed: Map<DaySong, Int> = emptyMap(),
        floorDay: LocalDate? = null,
    ): MergePlan {
        // 1. the entries per day, inside the allowed window, merged when a day appears twice
        val perDay = java.util.TreeMap<LocalDate, MutableList<RemoteSong>>(Comparator.reverseOrder())
        var beforeFloor = 0
        for (day in remote) {
            if (day.day.isAfter(today)) continue
            val clean = day.songs.filter { it.songId.isNotBlank() }
            if (floorDay != null && !day.day.isAfter(floorDay)) {
                beforeFloor += clean.size
                continue
            }
            perDay.getOrPut(day.day) { ArrayList() } += clean
        }
        if (perDay.isEmpty()) return MergePlan(emptyList(), 0, 0, 0, 0, beforeFloor)

        // 2. how many entries each (day, song) still needs a play for
        val need = HashMap<DaySong, Int>()
        val order = ArrayList<DaySong>() // newest day first, first appearance inside the day
        var entries = 0
        var suppressedCount = 0
        for ((date, songs) in perDay) {
            for (song in songs) {
                entries++
                val key = DaySong(date.toEpochDay(), song.songId)
                val before = need[key]
                if (before == null) order += key
                need[key] = (before ?: 0) + 1
            }
        }
        for ((key, count) in suppressed) {
            val current = need[key] ?: continue
            val cut = minOf(current, count.coerceAtLeast(0))
            need[key] = current - cut
            suppressedCount += cut
        }

        // 3. the phone's plays by (day, song), the ones far from midnight first: they are used up first
        val firstDay = perDay.lastKey().toEpochDay() - 1
        val lastDay = perDay.firstKey().toEpochDay() + 1
        val have = HashMap<DaySong, ArrayList<LocalPlay>>()
        for (play in local) {
            val epochDay = play.at.toLocalDate().toEpochDay()
            if (epochDay < firstDay || epochDay > lastDay) continue
            have.getOrPut(DaySong(epochDay, play.songId)) { ArrayList() } += play
        }
        for (list in have.values) list.sortByDescending { edgeDistance(it.at) }

        // 4. same day first
        var sameDay = 0
        for (key in order) {
            val wanted = need.getValue(key)
            if (wanted == 0) continue
            val list = have[key] ?: continue
            val used = minOf(wanted, list.size)
            repeat(used) { list.removeAt(0) }
            need[key] = wanted - used
            sameDay += used
        }

        // 5. what is left may be covered by a play just across midnight
        var acrossMidnight = 0
        val slack = EDGE_SLACK_HOURS * 3600L
        for (key in order) {
            var wanted = need.getValue(key)
            if (wanted == 0) continue
            val before = have[DaySong(key.epochDay - 1, key.songId)]
            val after = have[DaySong(key.epochDay + 1, key.songId)]
            while (wanted > 0) {
                // the previous evening counts by the time left until midnight, the next morning by the time since it
                val lateIndex =
                    before
                        ?.withIndex()
                        ?.filter { secondOfDay(it.value.at) >= SECONDS_PER_DAY - slack }
                        ?.minByOrNull { SECONDS_PER_DAY - secondOfDay(it.value.at) }
                val earlyIndex =
                    after
                        ?.withIndex()
                        ?.filter { secondOfDay(it.value.at) < slack }
                        ?.minByOrNull { secondOfDay(it.value.at) }
                if (lateIndex == null && earlyIndex == null) break
                val lateGap = if (lateIndex != null) SECONDS_PER_DAY - secondOfDay(lateIndex.value.at) else Long.MAX_VALUE
                val earlyGap = if (earlyIndex != null) secondOfDay(earlyIndex.value.at) else Long.MAX_VALUE
                if (lateIndex != null && lateGap <= earlyGap) {
                    before!!.removeAt(lateIndex.index)
                } else {
                    after!!.removeAt(earlyIndex!!.index)
                }
                wanted--
                acrossMidnight++
            }
            need[key] = wanted
        }

        // 6. the rest is new: spread it through its day, in the order of the list
        val plays = ArrayList<PlannedPlay>()
        for ((date, songs) in perDay) {
            val remaining = HashMap<String, Int>()
            for (song in songs) {
                val left = need[DaySong(date.toEpochDay(), song.songId)] ?: 0
                if (left > 0) remaining[song.songId] = left
            }
            if (remaining.isEmpty()) continue
            val start = date.atStartOfDay()
            val windowSeconds =
                if (date == today) {
                    java.time.Duration.between(start, now).seconds.coerceIn(60L, SECONDS_PER_DAY - 1)
                } else {
                    SECONDS_PER_DAY - 1
                }
            val total = songs.size
            songs.forEachIndexed { position, song ->
                val left = remaining[song.songId] ?: return@forEachIndexed
                if (left <= 0) return@forEachIndexed
                remaining[song.songId] = left - 1
                // position 0 is the newest entry and gets the latest slot
                val slot = total - 1 - position
                val offset = windowSeconds * (2L * slot + 1L) / (2L * total)
                var at = start.plusSeconds(offset)
                if (date == today && at.isAfter(now)) at = now
                plays += PlannedPlay(song.songId, at, playTimeMs(song.durationSec))
            }
        }
        plays.sortWith(compareBy<PlannedPlay> { it.at }.thenBy { it.songId })
        return MergePlan(
            plays = plays,
            remoteEntries = entries,
            matchedSameDay = sameDay,
            matchedAcrossMidnight = acrossMidnight,
            suppressed = suppressedCount,
            beforeFloor = beforeFloor,
        )
    }

    private fun secondOfDay(at: LocalDateTime): Long = at.toLocalTime().toSecondOfDay().toLong()

    private fun edgeDistance(at: LocalDateTime): Long {
        val second = secondOfDay(at)
        return minOf(second, SECONDS_PER_DAY - second)
    }
}

// ------------------------------------------------------------------------------------------------ plays the user removed

/**
 * The plays the user removed from the history on this phone, kept as text "epochDay:songId:count" separated by
 * commas, so a play that is still in the account is not brought back by the next merge. Capped, oldest dropped.
 */
object RemovedPlays {
    const val LIMIT = 400

    fun parse(stored: String?): Map<DaySong, Int> {
        val result = LinkedHashMap<DaySong, Int>()
        for (token in stored.orEmpty().split(',')) {
            val parts = token.split(':')
            if (parts.size != 3) continue
            val day = parts[0].toLongOrNull() ?: continue
            val count = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: continue
            if (parts[1].isBlank()) continue
            val key = DaySong(day, parts[1])
            result[key] = (result[key] ?: 0) + count
        }
        return result
    }

    fun serialize(entries: Map<DaySong, Int>): String =
        entries.entries.joinToString(",") { "${it.key.epochDay}:${it.key.songId}:${it.value}" }

    /** [stored] with one more removal of [songId] on [epochDay]. */
    fun add(stored: String?, epochDay: Long, songId: String): String {
        if (songId.isBlank() || ':' in songId || ',' in songId) return stored.orEmpty()
        val map = LinkedHashMap(parse(stored))
        val key = DaySong(epochDay, songId)
        val next = (map.remove(key) ?: 0) + 1 // re-inserted last, so it is the newest
        map[key] = next
        while (map.size > LIMIT) map.remove(map.keys.first())
        return serialize(map)
    }
}

// ------------------------------------------------------------------------------------------------ when to run

/** Whether a run should start now, and how far back it reads. Pure, so the rules can be tested. */
object RemoteHistoryMergeGate {
    /** The periodic runs happen at most this often (milliseconds). */
    const val INTERVAL_MS = 6L * 60 * 60 * 1000

    /** After a failed or empty start, the next automatic one waits at least this long. */
    const val RETRY_MS = 15L * 60 * 1000

    /** The periodic runs read back to this many days before the last complete run (late entries, clock differences). */
    const val MARGIN_DAYS = 3L

    enum class Decision {
        RUN,
        SKIP_SIGNED_OUT,
        SKIP_PAUSED,
        SKIP_SWITCH_OFF,
        SKIP_DATA_SAVER,
        SKIP_OFFLINE,
        SKIP_TOO_SOON,
    }

    @Suppress("LongParameterList")
    fun decide(
        manual: Boolean,
        signedIn: Boolean,
        switchOn: Boolean,
        listenHistoryPaused: Boolean,
        dataSaverActive: Boolean,
        online: Boolean,
        neverRan: Boolean,
        nowMs: Long,
        lastSuccessMs: Long?,
        lastAttemptMs: Long?,
    ): Decision =
        when {
            !signedIn -> Decision.SKIP_SIGNED_OUT
            listenHistoryPaused -> Decision.SKIP_PAUSED
            !manual && !switchOn -> Decision.SKIP_SWITCH_OFF
            !manual && dataSaverActive -> Decision.SKIP_DATA_SAVER
            !online -> Decision.SKIP_OFFLINE
            manual || neverRan && (lastAttemptMs == null || nowMs - lastAttemptMs >= RETRY_MS) -> Decision.RUN
            lastAttemptMs != null && nowMs - lastAttemptMs < RETRY_MS -> Decision.SKIP_TOO_SOON
            lastSuccessMs != null && nowMs - lastSuccessMs < INTERVAL_MS -> Decision.SKIP_TOO_SOON
            else -> Decision.RUN
        }

    /** The day a periodic run reads back to, or null for a full run. */
    fun stopBelowDay(full: Boolean, coveredEpochDay: Long?, floorEpochDay: Long?): LocalDate? {
        val covered = if (!full && coveredEpochDay != null) coveredEpochDay - MARGIN_DAYS else null
        val best =
            when {
                covered != null && floorEpochDay != null -> maxOf(covered, floorEpochDay)
                covered != null -> covered
                else -> floorEpochDay
            }
        return best?.let(LocalDate::ofEpochDay)
    }
}

/**
 * A recovery spent for a missed day that now has real plays (they came from the account history) is not needed any
 * more: it is handed back, so the month's allowance is not used up for nothing.
 */
fun refundFilledRecoveries(
    entries: List<StreakRecoveries.Entry>,
    playedDays: Set<Long>,
): List<StreakRecoveries.Entry> = entries.filter { it.missedDay !in playedDays }
