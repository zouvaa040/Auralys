package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Random

/** The merge rule: per (day, song) the account says R plays, the phone has L, max(0, R - L) are added. */
class RemoteHistoryMergeTest {
    private val today = LocalDate.of(2026, 11, 7) // Saturday
    private val now = LocalDateTime.of(2026, 11, 7, 15, 30)

    private fun song(id: String, seconds: Int? = 200) = RemoteSong(id, seconds)

    private fun day(date: LocalDate, vararg ids: String) = RemoteDay(date, ids.map { song(it) })

    private fun play(id: String, at: LocalDateTime) = LocalPlay(id, at)

    private fun plan(
        remote: List<RemoteDay>,
        local: List<LocalPlay> = emptyList(),
        suppressed: Map<DaySong, Int> = emptyMap(),
        floor: LocalDate? = null,
        today: LocalDate = this.today,
        now: LocalDateTime = this.now,
    ) = RemoteHistoryMerge.plan(remote, local, today, now, suppressed, floor)

    /** What the phone has after the plan was written. */
    private fun applied(local: List<LocalPlay>, plan: MergePlan) = local + plan.plays.map { LocalPlay(it.songId, it.at) }

    private fun counts(plays: List<PlannedPlay>) = plays.groupingBy { it.songId to it.at.toLocalDate() }.eachCount()

    // ------------------------------------------------------------------ the rule

    @Test
    fun `an empty phone gets every entry of the account`() {
        val remote = listOf(day(today, "a", "b", "a"), day(today.minusDays(1), "c"))
        val plan = plan(remote)
        assertEquals(4, plan.plays.size)
        assertEquals(2, counts(plan.plays)[("a" to today)])
        assertEquals(1, counts(plan.plays)[("b" to today)])
        assertEquals(1, counts(plan.plays)[("c" to today.minusDays(1))])
        assertEquals(4, plan.remoteEntries)
    }

    @Test
    fun `an empty account adds nothing`() {
        assertTrue(plan(emptyList()).plays.isEmpty())
        assertTrue(plan(listOf(RemoteDay(today, emptyList()))).plays.isEmpty())
        assertTrue(plan(emptyList(), listOf(play("a", now))).plays.isEmpty())
    }

    @Test
    fun `a play the phone already has is not added again`() {
        val local = listOf(play("a", today.atTime(10, 0)))
        assertTrue(plan(listOf(day(today, "a")), local).plays.isEmpty())
    }

    @Test
    fun `only the difference is added`() {
        val local = listOf(play("a", today.atTime(10, 0)))
        val plan = plan(listOf(day(today, "a", "a", "a")), local)
        assertEquals(2, plan.plays.size)
        assertTrue(plan.plays.all { it.songId == "a" })
    }

    @Test
    fun `more plays on the phone than in the account add nothing`() {
        val local = (0 until 5).map { play("a", today.atTime(9 + it, 0)) }
        assertTrue(plan(listOf(day(today, "a", "a")), local).plays.isEmpty())
    }

    @Test
    fun `the same song on another day is another play`() {
        val local = listOf(play("a", today.minusDays(3).atTime(10, 0)))
        val plan = plan(listOf(day(today, "a")), local)
        assertEquals(1, plan.plays.size)
        assertEquals(today, plan.plays.single().at.toLocalDate())
    }

    @Test
    fun `another song on the same day is another play`() {
        val local = listOf(play("b", today.atTime(10, 0)))
        assertEquals(1, plan(listOf(day(today, "a")), local).plays.size)
    }

    @Test
    fun `plays of this phone that were also reported to the account are not doubled`() {
        // the phone recorded three plays and YouTube listed the same three
        val local = listOf(play("a", today.atTime(9, 0)), play("b", today.atTime(9, 5)), play("a", today.atTime(11, 0)))
        assertTrue(plan(listOf(day(today, "a", "b", "a")), local).plays.isEmpty())
    }

    @Test
    fun `an entry registered some seconds after the phone's own event is the same play`() {
        // 23:59:58 on the phone, "tomorrow" 00:00:03 in the account
        val local = listOf(play("a", today.minusDays(1).atTime(23, 59, 58)))
        val plan = plan(listOf(day(today, "a")), local)
        assertTrue(plan.plays.isEmpty())
        assertEquals(1, plan.matchedAcrossMidnight)
    }

    @Test
    fun `and the other way round`() {
        val local = listOf(play("a", today.atTime(0, 0, 4)))
        val plan = plan(listOf(day(today.minusDays(1), "a")), local)
        assertTrue(plan.plays.isEmpty())
        assertEquals(1, plan.matchedAcrossMidnight)
    }

    @Test
    fun `an account whose days are shifted by the time zone does not double the phone's plays`() {
        // the phone is 7 hours ahead of the account's clock: its plays from 00:00 to 07:00 are the account's previous day
        val local =
            listOf(
                play("a", today.atTime(1, 30)),
                play("b", today.atTime(4, 10)),
                play("c", today.minusDays(1).atTime(2, 0)),
            )
        val remote = listOf(day(today.minusDays(1), "b", "a"), day(today.minusDays(2), "c"))
        assertTrue(plan(remote, local).plays.isEmpty())
    }

    @Test
    fun `a play far from midnight cannot stand for an entry on another day`() {
        // yesterday 11:00 is more than 12 hours before midnight, today 13:00 more than 12 hours after it
        val earlier = listOf(play("a", today.minusDays(1).atTime(11, 0)))
        assertEquals(1, plan(listOf(day(today, "a")), earlier).plays.size)
        val later = listOf(play("a", today.atTime(13, 0)))
        assertEquals(1, plan(listOf(day(today.minusDays(1), "a")), later).plays.size)
    }

    @Test
    fun `a play across midnight counts once and an exact play of its own day is used first`() {
        // two entries today, the phone has one play today and one just before midnight yesterday
        val local = listOf(play("a", today.atTime(12, 0)), play("a", today.minusDays(1).atTime(23, 50)))
        assertTrue(plan(listOf(day(today, "a", "a")), local).plays.isEmpty())
        // a third entry has no play left to stand for it
        assertEquals(1, plan(listOf(day(today, "a", "a", "a")), local).plays.size)
    }

    @Test
    fun `a play near midnight is kept for its own day when that day needs it`() {
        // 00:10 today belongs to today's entry; the yesterday entry must not take it away
        val local = listOf(play("a", today.atTime(0, 10)))
        val remote = listOf(day(today, "a"), day(today.minusDays(1), "a"))
        val plan = plan(remote, local)
        assertEquals(1, plan.plays.size)
        assertEquals(today.minusDays(1), plan.plays.single().at.toLocalDate())
    }

    // ------------------------------------------------------------------ running it twice

    @Test
    fun `a second run adds nothing`() {
        val remote = listOf(day(today, "a", "b", "a"), day(today.minusDays(1), "c", "c"), day(today.minusDays(5), "a"))
        val local = listOf(play("a", today.atTime(8, 0)), play("c", today.minusDays(1).atTime(23, 58)))
        val first = plan(remote, local)
        assertTrue(first.plays.isNotEmpty())
        val second = plan(remote, applied(local, first))
        assertTrue("second run added ${second.plays}", second.plays.isEmpty())
    }

    @Test
    fun `a third run and a run after new account entries add only the new ones`() {
        val remote = listOf(day(today, "a", "b"), day(today.minusDays(1), "c"))
        val first = plan(remote)
        var local = applied(emptyList(), first)
        assertTrue(plan(remote, local).plays.isEmpty())
        assertTrue(plan(remote, local).plays.isEmpty())
        // the other phone plays "a" again and "d": the account lists them above the old entries
        val later = listOf(day(today, "d", "a", "a", "b"), day(today.minusDays(1), "c"))
        val next = plan(later, local)
        assertEquals(listOf("a", "d"), next.plays.map { it.songId }.sorted())
        local = applied(local, next)
        assertTrue(plan(later, local).plays.isEmpty())
    }

    @Test
    fun `a random account and phone settle in one run`() {
        val random = Random(20261107)
        repeat(1500) { round ->
            val songs = listOf("a", "b", "c", "d", "e")
            val remote =
                (0 until random.nextInt(8)).map { d ->
                    RemoteDay(today.minusDays(d.toLong() * 1 + random.nextInt(2)), (0 until random.nextInt(7)).map { song(songs[random.nextInt(songs.size)], random.nextInt(400)) })
                }.distinctBy { it.day }.sortedByDescending { it.day }
            // some plays of the phone sit near midnight, some mid-day, some on days the account does not list
            val local =
                (0 until random.nextInt(20)).map {
                    val daysBack = random.nextInt(10).toLong()
                    val second =
                        when (random.nextInt(4)) {
                            0 -> random.nextInt(60 * 90)
                            1 -> 86_399 - random.nextInt(60 * 90)
                            else -> random.nextInt(86_400)
                        }
                    play(songs[random.nextInt(songs.size)], today.minusDays(daysBack).atStartOfDay().plusSeconds(second.toLong()))
                }
            val first = plan(remote, local)
            val second = plan(remote, applied(local, first))
            assertTrue("round $round: ${second.plays.size} more plays on the second run", second.plays.isEmpty())
            // and the first run never adds more than the account lists
            assertTrue(first.plays.size <= remote.sumOf { it.songs.size })
        }
    }

    // ------------------------------------------------------------------ the scenarios really discriminate

    /** The rule without the midnight tolerance: R - L per exact day. A planner like this double counts shifted days. */
    private fun naiveExactDay(remote: List<RemoteDay>, local: List<LocalPlay>): Int {
        var added = 0
        for (day in remote) {
            for ((id, entries) in day.songs.groupBy { it.songId }) {
                val have = local.count { it.songId == id && it.at.toLocalDate() == day.day }
                added += maxOf(0, entries.size - have)
            }
        }
        return added
    }

    @Test
    fun `the shifted-day and midnight scenarios double count with the plain per-day rule and not with the real one`() {
        val shiftedLocal = listOf(play("a", today.atTime(1, 30)), play("b", today.atTime(4, 10)), play("c", today.minusDays(1).atTime(2, 0)))
        val shiftedRemote = listOf(day(today.minusDays(1), "b", "a"), day(today.minusDays(2), "c"))
        assertEquals(3, naiveExactDay(shiftedRemote, shiftedLocal))
        assertEquals(0, plan(shiftedRemote, shiftedLocal).plays.size)

        val midnightLocal = listOf(play("a", today.minusDays(1).atTime(23, 59, 58)))
        val midnightRemote = listOf(day(today, "a"))
        assertEquals(1, naiveExactDay(midnightRemote, midnightLocal))
        assertEquals(0, plan(midnightRemote, midnightLocal).plays.size)

        // and the plain rule agrees with the real one where no day is shifted
        val plainLocal = listOf(play("a", today.atTime(10, 0)))
        val plainRemote = listOf(day(today, "a", "a", "b"))
        assertEquals(naiveExactDay(plainRemote, plainLocal), plan(plainRemote, plainLocal).plays.size)
    }

    @Test
    fun `a planner that ignored the phone's plays would fail the second run, the real one does not`() {
        val remote = listOf(day(today, "a", "b"), day(today.minusDays(1), "a"))
        val first = plan(remote)
        assertEquals(3, first.plays.size)
        // the phone ignoring what it already has would add all three again
        assertEquals(3, plan(remote, emptyList()).plays.size)
        assertEquals(0, plan(remote, applied(emptyList(), first)).plays.size)
    }

    // ------------------------------------------------------------------ placement

    @Test
    fun `new plays are spread through their day, newest entry last, inside the day`() {
        val plan = plan(listOf(day(today.minusDays(2), "new", "mid", "old")))
        val day = today.minusDays(2)
        val byId = plan.plays.associateBy { it.songId }
        assertTrue(byId.getValue("old").at.isBefore(byId.getValue("mid").at))
        assertTrue(byId.getValue("mid").at.isBefore(byId.getValue("new").at))
        for (play in plan.plays) {
            assertEquals(day, play.at.toLocalDate())
            // not at midnight, so a time-zone or clock difference does not move it to another day
            assertTrue(play.at.toLocalTime().isAfter(java.time.LocalTime.of(3, 0)))
            assertTrue(play.at.toLocalTime().isBefore(java.time.LocalTime.of(21, 0)))
        }
    }

    @Test
    fun `a single entry lands in the middle of its day`() {
        val plan = plan(listOf(day(today.minusDays(4), "a")))
        val noon = plan.plays.single().at.toLocalTime()
        assertTrue(noon.isAfter(java.time.LocalTime.of(11, 0)) && noon.isBefore(java.time.LocalTime.of(13, 0)))
    }

    @Test
    fun `plays of today never lie after now`() {
        val earlyNow = today.atTime(0, 0, 20)
        val plan = plan(listOf(day(today, "a", "b", "c", "d")), now = earlyNow)
        assertEquals(4, plan.plays.size)
        for (play in plan.plays) {
            assertFalse("${play.at} is after $earlyNow", play.at.isAfter(earlyNow))
            assertEquals(today, play.at.toLocalDate())
        }
        val noon = plan(listOf(day(today, "a", "b", "c", "d")), now = today.atTime(12, 0))
        assertTrue(noon.plays.all { !it.at.isAfter(today.atTime(12, 0)) })
        assertTrue(noon.plays.all { it.at.isAfter(today.atStartOfDay()) })
    }

    @Test
    fun `the plays come oldest first so the ids grow with the time`() {
        val plan = plan(listOf(day(today, "a", "b", "c"), day(today.minusDays(1), "d", "e"), day(today.minusDays(9), "f")))
        assertEquals(plan.plays.sortedBy { it.at }, plan.plays)
        assertEquals("f", plan.plays.first().songId)
    }

    @Test
    fun `play time is the duration, capped, with a default`() {
        assertEquals(213_000L, RemoteHistoryMerge.playTimeMs(213))
        assertEquals(360_000L, RemoteHistoryMerge.playTimeMs(5_000))
        assertEquals(180_000L, RemoteHistoryMerge.playTimeMs(null))
        assertEquals(180_000L, RemoteHistoryMerge.playTimeMs(0))
        assertEquals(180_000L, RemoteHistoryMerge.playTimeMs(-1))
        val plan = plan(listOf(RemoteDay(today, listOf(RemoteSong("a", 100), RemoteSong("b", null), RemoteSong("c", 99_999)))))
        assertEquals(setOf(100_000L, 180_000L, 360_000L), plan.plays.map { it.playTimeMs }.toSet())
    }

    // ------------------------------------------------------------------ what is left out

    @Test
    fun `days in the future and blank song ids are ignored`() {
        val plan = plan(listOf(day(today.plusDays(1), "a"), RemoteDay(today, listOf(song(""), song("  "), song("b")))))
        assertEquals(listOf("b"), plan.plays.map { it.songId })
    }

    @Test
    fun `plays removed by the user are not brought back`() {
        val remote = listOf(day(today.minusDays(2), "a", "a", "b"))
        val key = DaySong(today.minusDays(2).toEpochDay(), "a")
        val plan = plan(remote, suppressed = mapOf(key to 1))
        assertEquals(listOf("a", "b"), plan.plays.map { it.songId }.sorted())
        assertEquals(1, plan.suppressed)
        // more removals than entries just remove them all
        assertEquals(listOf("b"), plan(remote, suppressed = mapOf(key to 9)).plays.map { it.songId })
    }

    @Test
    fun `removing a play here and then merging leaves the account's count and the phone's count equal`() {
        // the phone had one imported play; the user removed it (phone: 0, tombstone: 1, account: 1)
        val remote = listOf(day(today.minusDays(2), "a"))
        val key = DaySong(today.minusDays(2).toEpochDay(), "a")
        assertTrue(plan(remote, emptyList(), mapOf(key to 1)).plays.isEmpty())
        // the other phone plays it once more: that one comes
        val more = listOf(day(today.minusDays(2), "a", "a"))
        assertEquals(1, plan(more, emptyList(), mapOf(key to 1)).plays.size)
    }

    @Test
    fun `days up to the day the history was cleared are not merged`() {
        val remote = listOf(day(today, "a"), day(today.minusDays(1), "b"), day(today.minusDays(2), "c"))
        val plan = plan(remote, floor = today.minusDays(1))
        assertEquals(listOf("a"), plan.plays.map { it.songId })
        assertEquals(2, plan.beforeFloor)
        // cleared today: nothing of the account's today is merged either
        assertTrue(plan(remote, floor = today).plays.isEmpty())
    }

    @Test
    fun `plays of the phone on days far from the account's days are not looked at`() {
        val local = listOf(play("a", today.minusDays(400).atTime(10, 0)))
        assertEquals(1, plan(listOf(day(today, "a")), local).plays.size)
    }

    // ------------------------------------------------------------------ resolving the titles of a page

    private fun section(title: String, vararg ids: String) = RemoteSection(title, ids.map { song(it) })

    @Test
    fun `a page of sections becomes days, newest first`() {
        val resolution =
            RemoteHistoryMerge.resolve(
                listOf(section("Today", "a"), section("Yesterday", "b"), section("Thursday", "c"), section("Nov 3", "d"), section("Oct 30", "e")),
                today,
            )
        assertEquals(
            listOf(today, today.minusDays(1), LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 3), LocalDate.of(2026, 10, 30)),
            resolution.days.map { it.day },
        )
        assertTrue(resolution.skippedTitles.isEmpty())
    }

    @Test
    fun `a title that cannot be read is skipped and does not stop the rest`() {
        val resolution = RemoteHistoryMerge.resolve(listOf(section("Today", "a"), section("Earlier this month", "b"), section("Nov 1", "c")), today)
        assertEquals(listOf(today, LocalDate.of(2026, 11, 1)), resolution.days.map { it.day })
        assertEquals(listOf("Earlier this month"), resolution.skippedTitles)
    }

    @Test
    fun `a section that does not lie before the one above is skipped, not merged`() {
        // the same day under two different titles: a title that was read wrongly
        val resolution = RemoteHistoryMerge.resolve(listOf(section("Yesterday", "a"), section("Friday", "b"), section("Nov 6", "c")), today)
        assertEquals(listOf(today.minusDays(1)), resolution.days.map { it.day })
        assertEquals(listOf("Friday", "Nov 6"), resolution.skippedTitles)
    }

    @Test
    fun `the same title twice in a row is one day with all entries`() {
        val resolution = RemoteHistoryMerge.resolve(listOf(section("Today", "a", "b"), section("Today", "c"), section("Yesterday", "d")), today)
        assertEquals(2, resolution.days.size)
        assertEquals(listOf("a", "b", "c"), resolution.days[0].songs.map { it.songId })
    }

    @Test
    fun `Indonesian titles resolve the same way`() {
        val resolution = RemoteHistoryMerge.resolve(listOf(section("Hari ini", "a"), section("Kemarin", "b"), section("Kamis", "c"), section("3 Nov", "d")), today)
        assertEquals(4, resolution.days.size)
        assertEquals(LocalDate.of(2026, 11, 5), resolution.days[2].day)
    }

    // ------------------------------------------------------------------ zones, day lengths, size

    @Test
    fun `today is the phone's day, whatever zone that is`() {
        val instant = Instant.parse("2026-11-07T20:30:00Z")
        for (zoneName in listOf("UTC", "Asia/Jakarta", "Pacific/Auckland", "America/Los_Angeles", "Pacific/Kiritimati", "Pacific/Pago_Pago")) {
            val zone = ZoneId.of(zoneName)
            val localToday = LocalDate.ofInstant(instant, zone)
            val localNow = LocalDateTime.ofInstant(instant, zone)
            val resolution = RemoteHistoryMerge.resolve(listOf(section("Today", "a", "b"), section("Yesterday", "c")), localToday)
            val plan = RemoteHistoryMerge.plan(resolution.days, emptyList(), localToday, localNow)
            assertEquals(3, plan.plays.size)
            val onToday = plan.plays.filter { it.at.toLocalDate() == localToday }
            assertEquals(zoneName, 2, onToday.size)
            assertTrue(zoneName, onToday.all { !it.at.isAfter(localNow) })
            assertEquals(zoneName, 1, plan.plays.count { it.at.toLocalDate() == localToday.minusDays(1) })
            // a second run on the same instant adds nothing
            val local = plan.plays.map { LocalPlay(it.songId, it.at) }
            assertTrue(zoneName, RemoteHistoryMerge.plan(resolution.days, local, localToday, localNow).plays.isEmpty())
        }
    }

    @Test
    fun `days with 23 or 25 hours take the same plays`() {
        // spring forward and fall back: the stored time is wall-clock, the plan never builds an instant
        for ((zoneName, date) in listOf("Europe/London" to LocalDate.of(2026, 3, 29), "America/New_York" to LocalDate.of(2026, 11, 1), "Australia/Lord_Howe" to LocalDate.of(2026, 4, 5))) {
            val zone = ZoneId.of(zoneName)
            val hours = Duration.between(date.atStartOfDay(zone), date.plusDays(1).atStartOfDay(zone)).toHours()
            val remote = listOf(RemoteDay(date, (1..12).map { song("s$it") }))
            val later = date.plusDays(10)
            val plan = RemoteHistoryMerge.plan(remote, emptyList(), later, later.atTime(12, 0))
            assertEquals("$zoneName ($hours h)", 12, plan.plays.size)
            assertTrue(plan.plays.all { it.at.toLocalDate() == date })
            assertEquals(12, plan.plays.map { it.at }.toSet().size)
            assertTrue(RemoteHistoryMerge.plan(remote, plan.plays.map { LocalPlay(it.songId, it.at) }, later, later.atTime(12, 0)).plays.isEmpty())
        }
    }

    @Test
    fun `a huge history is planned fast and settles in one run`() {
        val random = Random(7)
        val songs = (0 until 400).map { "song$it" }
        val remote = (0 until 400).map { d -> RemoteDay(today.minusDays(d.toLong()), (0 until 150).map { song(songs[random.nextInt(songs.size)]) }) } // 60 000 entries
        val local = (0 until 20_000).map { play(songs[random.nextInt(songs.size)], today.minusDays(random.nextInt(400).toLong()).atStartOfDay().plusSeconds(random.nextInt(86_400).toLong())) }
        val started = System.nanoTime()
        val first = plan(remote, local)
        val second = plan(remote, applied(local, first))
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("took $seconds s", seconds < 20)
        assertTrue(first.plays.isNotEmpty())
        assertTrue(second.plays.isEmpty())
    }
}
