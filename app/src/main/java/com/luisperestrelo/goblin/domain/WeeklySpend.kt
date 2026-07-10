package com.luisperestrelo.goblin.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Total spend booked in one ISO week (Monday start). */
data class WeekSpend(val weekStart: LocalDate, val spentCents: Long)

object WeeklySpend {

    fun weekStartOf(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * Contiguous weekly spend totals for the [weeks] completed weeks ending the week
     * BEFORE [today] (the in-progress week is excluded - it isn't fully settled, and
     * the 2-3 day booking lag would understate it). Weeks with no debits are filled
     * with zero so the streak sees an unbroken sequence. [debits] is (bookingDate,
     * absoluteCents), already filtered to the primary account with internal transfers
     * removed. Oldest week first.
     */
    fun completedWeeklyTotals(
        debits: List<Pair<LocalDate, Long>>,
        today: LocalDate,
        weeks: Int,
    ): List<WeekSpend> {
        val currentWeekStart = weekStartOf(today)
        val byWeek = debits.groupBy { weekStartOf(it.first) }
            .mapValues { (_, entries) -> entries.sumOf { it.second } }
        return (weeks downTo 1).map { back ->
            val start = currentWeekStart.minusWeeks(back.toLong())
            WeekSpend(start, byWeek[start] ?: 0L)
        }
    }
}
