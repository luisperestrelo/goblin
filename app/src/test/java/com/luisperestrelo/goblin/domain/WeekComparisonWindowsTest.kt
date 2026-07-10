package com.luisperestrelo.goblin.domain

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class WeekComparisonWindowsTest {

    @Test
    fun midweek_wednesday_alignsToLastMondayThroughLastWednesday() {
        // 2026-07-08 is a Wednesday; that week's Monday is 2026-07-06.
        val w = WeekComparisonWindows.forToday(LocalDate.of(2026, 7, 8))

        assertThat(w.thisWeek).isEqualTo(
            DateWindow(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 9))
        )
        // Aligned: last Mon..last Wed inclusive -> [06-29, 07-02).
        assertThat(w.lastWeekAligned).isEqualTo(
            DateWindow(LocalDate.of(2026, 6, 29), LocalDate.of(2026, 7, 2))
        )
        assertThat(w.thisWeek.spanDays()).isEqualTo(w.lastWeekAligned.spanDays())
    }

    @Test
    fun monday_isASingleDayWindow() {
        val w = WeekComparisonWindows.forToday(LocalDate.of(2026, 7, 6)) // Monday
        assertThat(w.thisWeek).isEqualTo(
            DateWindow(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 7))
        )
        assertThat(w.lastWeekAligned).isEqualTo(
            DateWindow(LocalDate.of(2026, 6, 29), LocalDate.of(2026, 6, 30))
        )
    }

    @Test
    fun sunday_spansTheFullWeek() {
        val w = WeekComparisonWindows.forToday(LocalDate.of(2026, 7, 12)) // Sunday
        assertThat(w.thisWeek).isEqualTo(
            DateWindow(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 13))
        )
        assertThat(w.lastWeekAligned).isEqualTo(
            DateWindow(LocalDate.of(2026, 6, 29), LocalDate.of(2026, 7, 6))
        )
        assertThat(w.thisWeek.spanDays()).isEqualTo(7)
    }

    @Test
    fun handlesYearBoundary() {
        // 2027-01-01 is a Friday; that week's Monday is 2026-12-28.
        val w = WeekComparisonWindows.forToday(LocalDate.of(2027, 1, 1))
        assertThat(w.thisWeek).isEqualTo(
            DateWindow(LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 2))
        )
        assertThat(w.lastWeekAligned).isEqualTo(
            DateWindow(LocalDate.of(2026, 12, 21), LocalDate.of(2026, 12, 26))
        )
    }

    private fun DateWindow.spanDays(): Long =
        java.time.temporal.ChronoUnit.DAYS.between(fromInclusive, toExclusive)
}
