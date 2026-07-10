package com.luisperestrelo.goblin.domain

/**
 * The weekly saving game: keep each week's spend at or below your usual week and the
 * streak grows. Baseline is your own recent median, so it self-calibrates - no budget
 * to set, and "usual" tracks your real habits.
 */
data class SavingStreakResult(
    val currentStreakWeeks: Int,
    val bestStreakWeeks: Int,
    /** Median of recent completed weeks - "your usual week". */
    val usualWeekCents: Long,
)

object SavingStreak {

    const val BASELINE_WINDOW = 12
    private const val MIN_HISTORY = 4

    /**
     * [completedWeeks] oldest-first. A week wins if its spend is at or below the median
     * of the prior [BASELINE_WINDOW] weeks (requires at least [MIN_HISTORY] prior weeks
     * to judge fairly). Returns null until there's enough history.
     */
    fun compute(completedWeeks: List<WeekSpend>): SavingStreakResult? {
        val totals = completedWeeks.map { it.spentCents }
        if (totals.size <= MIN_HISTORY) return null

        var streak = 0
        var best = 0
        for (i in totals.indices) {
            val prior = totals.subList(maxOf(0, i - BASELINE_WINDOW), i)
            if (prior.size < MIN_HISTORY) continue
            val won = totals[i] <= median(prior)
            streak = if (won) streak + 1 else 0
            best = maxOf(best, streak)
        }
        return SavingStreakResult(
            currentStreakWeeks = streak,
            bestStreakWeeks = best,
            usualWeekCents = median(totals.takeLast(BASELINE_WINDOW)),
        )
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }
}
