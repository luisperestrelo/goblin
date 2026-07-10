package com.luisperestrelo.goblin.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** A half-open date range `[fromInclusive, toExclusive)`. */
data class DateWindow(val fromInclusive: LocalDate, val toExclusive: LocalDate)

/**
 * This-week-to-date and the *aligned* same-length window in the previous week.
 *
 * Weeks start Monday (ISO-8601 / Portugal). The comparison window spans the same
 * number of days as elapsed this week - e.g. on a Wednesday, this-week is Mon..Wed
 * and last-week is last-Mon..last-Wed - so "spent this week vs last week" compares
 * like with like. An unaligned full-week baseline would always flatter early-week
 * spending and mislead.
 */
data class WeekComparisonWindows(
    val thisWeek: DateWindow,
    val lastWeekAligned: DateWindow,
) {
    companion object {
        fun forToday(today: LocalDate): WeekComparisonWindows {
            val thisWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val tomorrow = today.plusDays(1)
            val elapsedDays = ChronoUnit.DAYS.between(thisWeekStart, tomorrow) // 1 (Mon) .. 7 (Sun)
            val lastWeekStart = thisWeekStart.minusWeeks(1)
            return WeekComparisonWindows(
                thisWeek = DateWindow(thisWeekStart, tomorrow),
                lastWeekAligned = DateWindow(lastWeekStart, lastWeekStart.plusDays(elapsedDays)),
            )
        }
    }
}
