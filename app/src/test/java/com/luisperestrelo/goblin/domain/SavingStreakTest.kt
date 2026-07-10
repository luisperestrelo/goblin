package com.luisperestrelo.goblin.domain

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class SavingStreakTest {

    private fun weeks(vararg cents: Long): List<WeekSpend> =
        cents.mapIndexed { i, c -> WeekSpend(LocalDate.of(2026, 1, 5).plusWeeks(i.toLong()), c) }

    @Test
    fun tooLittleHistory_returnsNull() {
        assertThat(SavingStreak.compute(weeks(100, 100, 100, 100))).isNull()
    }

    @Test
    fun sustainedLowSpend_buildsAndKeepsStreak() {
        // First 4 weeks seed the baseline; then consistently under the median -> streak grows.
        val result = SavingStreak.compute(weeks(300, 300, 300, 300, 100, 100, 100))
        assertThat(result).isNotNull()
        assertThat(result!!.currentStreakWeeks).isEqualTo(3)
        assertThat(result.bestStreakWeeks).isEqualTo(3)
    }

    @Test
    fun aBigWeekBreaksTheStreak_butBestIsRemembered() {
        // ...100,100,100 (streak 3), then a spike above the median resets to 0.
        val result = SavingStreak.compute(weeks(300, 300, 300, 300, 100, 100, 100, 5000))
        assertThat(result!!.currentStreakWeeks).isEqualTo(0)
        assertThat(result.bestStreakWeeks).isEqualTo(3)
    }

    @Test
    fun usualWeek_isMedianOfRecentWeeks() {
        val result = SavingStreak.compute(weeks(100, 200, 300, 400, 500))
        // median of all 5 (within the 12-week window) = 300.
        assertThat(result!!.usualWeekCents).isEqualTo(300L)
    }
}
