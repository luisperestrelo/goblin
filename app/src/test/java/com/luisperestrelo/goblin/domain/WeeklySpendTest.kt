package com.luisperestrelo.goblin.domain

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class WeeklySpendTest {

    @Test
    fun bucketsByMondayStartedWeek_andExcludesCurrentWeek() {
        // today 2026-07-08 (Wed) -> current week starts Mon 2026-07-06, which is excluded.
        val debits = listOf(
            LocalDate.of(2026, 7, 6) to 500L,   // current week - excluded
            LocalDate.of(2026, 6, 29) to 1000L, // last completed week (Mon 06-29)
            LocalDate.of(2026, 7, 5) to 200L,    // Sun of week 06-29
            LocalDate.of(2026, 6, 22) to 300L,   // week 06-22
        )
        val weeks = WeeklySpend.completedWeeklyTotals(debits, LocalDate.of(2026, 7, 8), weeks = 3)

        assertThat(weeks).containsExactly(
            WeekSpend(LocalDate.of(2026, 6, 15), 0L),   // no debits -> zero-filled
            WeekSpend(LocalDate.of(2026, 6, 22), 300L),
            WeekSpend(LocalDate.of(2026, 6, 29), 1200L), // 1000 + 200
        ).inOrder()
    }

    @Test
    fun emptyInput_allZeroWeeks() {
        val weeks = WeeklySpend.completedWeeklyTotals(emptyList(), LocalDate.of(2026, 7, 8), weeks = 2)
        assertThat(weeks.map { it.spentCents }).containsExactly(0L, 0L)
    }
}
