package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** The titles of the history sections ("Today", "Monday", "Nov 3" ...) and the day each one stands for. */
class HistoryTitleParserTest {
    // Saturday
    private val today = LocalDate.of(2026, 11, 7)

    private fun parse(title: String, on: LocalDate = today) = HistoryTitleParser.parse(title, on)

    @Test
    fun `today and yesterday in English and Indonesian`() {
        assertEquals(today, parse("Today"))
        assertEquals(today, parse("today"))
        assertEquals(today, parse("  TODAY  "))
        assertEquals(today, parse("Hari ini"))
        assertEquals(today, parse("hari  ini"))
        assertEquals(today.minusDays(1), parse("Yesterday"))
        assertEquals(today.minusDays(1), parse("Kemarin"))
    }

    @Test
    fun `every weekday name resolves to the most recent such day, on every day of the week`() {
        val names =
            mapOf(
                DayOfWeek.MONDAY to listOf("Monday", "Mon", "Senin", "Sen", "monday"),
                DayOfWeek.TUESDAY to listOf("Tuesday", "Tue", "Tues", "Selasa", "Sel"),
                DayOfWeek.WEDNESDAY to listOf("Wednesday", "Wed", "Rabu", "Rab"),
                DayOfWeek.THURSDAY to listOf("Thursday", "Thu", "Thurs", "Kamis", "Kam"),
                DayOfWeek.FRIDAY to listOf("Friday", "Fri", "Jumat", "Jum'at", "Jum"),
                DayOfWeek.SATURDAY to listOf("Saturday", "Sat", "Sabtu", "Sab"),
                DayOfWeek.SUNDAY to listOf("Sunday", "Sun", "Minggu", "Ahad", "Min"),
            )
        for (offset in 0L..6L) {
            val now = today.plusDays(offset) // all seven weekdays, and a different month for some
            for ((weekday, titles) in names) {
                val back = (now.dayOfWeek.value - weekday.value + 7) % 7
                val expected = now.minusDays(if (back == 0) 7L else back.toLong())
                for (title in titles) {
                    assertEquals("$title on $now", expected, parse(title, now))
                    assertEquals(weekday, expected.dayOfWeek)
                    assertTrue(expected.isBefore(now) && expected.isAfter(now.minusDays(8)))
                }
            }
        }
    }

    @Test
    fun `the weekday of today itself means a week ago`() {
        assertEquals(LocalDate.of(2026, 10, 31), parse("Saturday"))
        assertEquals(LocalDate.of(2026, 11, 6), parse("Friday"))
        assertEquals(LocalDate.of(2026, 11, 1), parse("Sunday"))
    }

    @Test
    fun `month and day in English`() {
        assertEquals(LocalDate.of(2026, 11, 3), parse("Nov 3"))
        assertEquals(LocalDate.of(2026, 11, 3), parse("nov 3"))
        assertEquals(LocalDate.of(2026, 11, 3), parse("November 3"))
        assertEquals(LocalDate.of(2026, 11, 3), parse("Nov. 3"))
        assertEquals(LocalDate.of(2026, 11, 3), parse("Nov 3rd"))
        assertEquals(LocalDate.of(2026, 11, 2), parse("2nd of November"))
        assertEquals(LocalDate.of(2026, 10, 21), parse("Oct 21"))
        assertEquals(LocalDate.of(2026, 9, 1), parse("Sept 1"))
        assertEquals(LocalDate.of(2026, 5, 9), parse("May 9"))
        assertEquals(LocalDate.of(2026, 11, 3), parse("Tue, Nov 3"))
        assertEquals(LocalDate.of(2026, 11, 3), parse("Tuesday, November 3"))
    }

    @Test
    fun `day and month in Indonesian`() {
        assertEquals(LocalDate.of(2026, 11, 3), parse("3 Nov"))
        assertEquals(LocalDate.of(2026, 11, 3), parse("3 November"))
        assertEquals(LocalDate.of(2026, 11, 3), parse("3 nov."))
        assertEquals(LocalDate.of(2026, 5, 20), parse("20 Mei"))
        assertEquals(LocalDate.of(2026, 8, 17), parse("17 Agustus"))
        assertEquals(LocalDate.of(2026, 8, 17), parse("17 Agu"))
        assertEquals(LocalDate.of(2026, 10, 5), parse("5 Okt"))
        assertEquals(LocalDate.of(2026, 2, 12), parse("12 Feb"))
        assertEquals(LocalDate.of(2026, 3, 1), parse("1 Maret"))
        assertEquals(LocalDate.of(2026, 1, 9), parse("9 Januari"))
        assertEquals(LocalDate.of(2026, 12, 1).minusYears(1), parse("1 Des"))
        assertEquals(LocalDate.of(2026, 11, 3), parse("Selasa, 3 November"))
        assertEquals(LocalDate.of(2026, 11, 3), parse("Sel, 3 Nov"))
    }

    @Test
    fun `a date with a year`() {
        assertEquals(LocalDate.of(2025, 11, 3), parse("Nov 3, 2025"))
        assertEquals(LocalDate.of(2025, 11, 3), parse("November 3, 2025"))
        assertEquals(LocalDate.of(2025, 11, 3), parse("3 Nov 2025"))
        assertEquals(LocalDate.of(2025, 11, 3), parse("3 November 2025"))
        assertEquals(LocalDate.of(2025, 11, 3), parse("2025 Nov 3"))
        assertEquals(LocalDate.of(2026, 11, 3), parse("Nov 3, 2026"))
        assertEquals(LocalDate.of(2024, 2, 29), parse("Feb 29, 2024"))
        assertEquals(LocalDate.of(2024, 2, 29), parse("29 Februari 2024"))
    }

    @Test
    fun `numeric dates, and the ambiguous ones are not guessed`() {
        assertEquals(LocalDate.of(2026, 11, 3), parse("2026-11-03"))
        assertEquals(LocalDate.of(2026, 11, 3), parse("2026/11/3"))
        assertEquals(LocalDate.of(2026, 10, 13), parse("13/10/2026"))
        assertEquals(LocalDate.of(2026, 10, 13), parse("10/13/2026"))
        assertEquals(LocalDate.of(2026, 10, 13), parse("13.10.2026"))
        assertEquals(LocalDate.of(2026, 10, 13), parse("13/10"))
        assertEquals(LocalDate.of(2026, 3, 3), parse("3/3/2026"))
        assertNull(parse("3/4/2026"))
        assertNull(parse("3/4"))
    }

    @Test
    fun `a date without a year is the latest such day that is not in the future`() {
        // 7 Nov 2026: 8 Nov would be next November's, so it is last year's
        assertEquals(LocalDate.of(2025, 11, 8), parse("Nov 8"))
        assertEquals(LocalDate.of(2026, 11, 7), parse("Nov 7"))
        assertEquals(LocalDate.of(2025, 12, 30), parse("Dec 30"))
        // across new year: on 2 Jan "Dec 30" is last month, "Jan 1" is this year
        val january = LocalDate.of(2027, 1, 2)
        assertEquals(LocalDate.of(2026, 12, 30), parse("Dec 30", january))
        assertEquals(LocalDate.of(2027, 1, 1), parse("Jan 1", january))
        // 29 February goes to the last leap year
        assertEquals(LocalDate.of(2024, 2, 29), parse("Feb 29"))
        assertEquals(LocalDate.of(2024, 2, 29), parse("29 Feb"))
        assertEquals(LocalDate.of(2028, 2, 29), parse("Feb 29", LocalDate.of(2028, 3, 1)))
    }

    @Test
    fun `titles that are not a day give null`() {
        for (title in listOf(
            "", "   ", "Last week", "This week", "Minggu lalu", "Earlier", "November 2025", "Oktober 2025", "2025", "2026",
            "Nov", "Hari", "Foo", "Foo 3", "Nov 40", "Feb 30", "Nov 3 Dec 4", "31 Nov", "Nov 3 25", "Next Monday",
            "Montag", "Lundi", "Today and yesterday", "3", "12", "0 Nov", "Nov 0", "Jan Feb",
        )) {
            assertNull("\"$title\" must not be read", parse(title))
        }
    }

    @Test
    fun `a date in the future is not a history day`() {
        assertNull(parse("Nov 8, 2026"))
        assertNull(parse("2027-01-01"))
        assertNull(parse("1 Jan 2030"))
        assertNull(parse("Nov 7, 2999"))
    }

    @Test
    fun `invisible direction marks, odd spaces and letter case do not matter`() {
        assertEquals(today, parse("‎Today‏"))
        assertEquals(LocalDate.of(2026, 11, 3), parse("Nov 3"))
        assertEquals(LocalDate.of(2026, 11, 3), parse("Nov 3"))
        assertEquals(LocalDate.of(2026, 11, 3), parse("3 NOV"))
        assertEquals(LocalDate.of(2026, 11, 6), parse("Jum’at"))
    }

    @Test
    fun `weekday resolution never returns today or a day in the future`() {
        for (offset in 0L..400L) {
            val now = LocalDate.of(2026, 1, 1).plusDays(offset)
            for (weekday in DayOfWeek.entries) {
                val day = HistoryTitleParser.resolveWeekday(weekday, now)
                assertEquals(weekday, day.dayOfWeek)
                assertTrue(day.isBefore(now))
                assertTrue(day.isAfter(now.minusDays(8)))
            }
        }
    }
}
