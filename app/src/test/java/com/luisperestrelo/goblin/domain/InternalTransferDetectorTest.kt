package com.luisperestrelo.goblin.domain

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class InternalTransferDetectorTest {

    private val accountA = "PT_AAA"
    private val accountB = "PT_BBB"

    private fun leg(
        iban: String,
        ref: String,
        debit: Boolean,
        cents: Long,
        date: String,
        currency: String = "EUR",
    ) = TransferLeg(iban, ref, debit, cents, currency, LocalDate.parse(date))

    @Test
    fun sameDayCrossAccountSameAmount_bothFlagged() {
        val legs = listOf(
            leg(accountB, "d1", debit = true, cents = 5000, date = "2026-04-10"),
            leg(accountA, "c1", debit = false, cents = 5000, date = "2026-04-10"),
        )
        assertThat(InternalTransferDetector.detect(legs))
            .containsExactly(LegKey(accountB, "d1"), LegKey(accountA, "c1"))
    }

    @Test
    fun sameAccount_notATransfer() {
        // A debit and credit of the same amount on the SAME account is not a transfer.
        val legs = listOf(
            leg(accountA, "d1", debit = true, cents = 5000, date = "2026-04-10"),
            leg(accountA, "c1", debit = false, cents = 5000, date = "2026-04-10"),
        )
        assertThat(InternalTransferDetector.detect(legs)).isEmpty()
    }

    @Test
    fun differentAmounts_notMatched() {
        val legs = listOf(
            leg(accountB, "d1", debit = true, cents = 5000, date = "2026-04-10"),
            leg(accountA, "c1", debit = false, cents = 5001, date = "2026-04-10"),
        )
        assertThat(InternalTransferDetector.detect(legs)).isEmpty()
    }

    @Test
    fun gapBeyondTolerance_notMatched() {
        val legs = listOf(
            leg(accountB, "d1", debit = true, cents = 5000, date = "2026-04-10"),
            leg(accountA, "c1", debit = false, cents = 5000, date = "2026-04-18"), // 8 days
        )
        assertThat(InternalTransferDetector.detect(legs)).isEmpty()
    }

    @Test
    fun weekendStraddleWithinTolerance_matched() {
        val legs = listOf(
            leg(accountB, "d1", debit = true, cents = 5000, date = "2026-04-10"), // Fri
            leg(accountA, "c1", debit = false, cents = 5000, date = "2026-04-13"), // Mon, 3 days
        )
        assertThat(InternalTransferDetector.detect(legs))
            .containsExactly(LegKey(accountB, "d1"), LegKey(accountA, "c1"))
    }

    @Test
    fun realSpending_withNoCounterpartCredit_notFlagged() {
        val legs = listOf(
            leg(accountA, "d1", debit = true, cents = 1299, date = "2026-04-10"),
            leg(accountA, "d2", debit = true, cents = 4200, date = "2026-04-11"),
            leg(accountA, "c1", debit = false, cents = 90000, date = "2026-04-01"), // salary, unmatched
        )
        assertThat(InternalTransferDetector.detect(legs)).isEmpty()
    }

    @Test
    fun oneDebitTwoEqualCredits_onlyNearestPaired() {
        val legs = listOf(
            leg(accountB, "d1", debit = true, cents = 5000, date = "2026-04-10"),
            leg(accountA, "c_near", debit = false, cents = 5000, date = "2026-04-11"), // 1 day
            leg(accountA, "c_far", debit = false, cents = 5000, date = "2026-04-13"), // 3 days
        )
        val result = InternalTransferDetector.detect(legs)
        assertThat(result).containsExactly(LegKey(accountB, "d1"), LegKey(accountA, "c_near"))
        assertThat(result).doesNotContain(LegKey(accountA, "c_far"))
    }

    @Test
    fun twoDebitsTwoCredits_pairedWithoutDoubleCounting() {
        val legs = listOf(
            leg(accountB, "d1", debit = true, cents = 5000, date = "2026-04-10"),
            leg(accountB, "d2", debit = true, cents = 5000, date = "2026-04-12"),
            leg(accountA, "c1", debit = false, cents = 5000, date = "2026-04-10"),
            leg(accountA, "c2", debit = false, cents = 5000, date = "2026-04-12"),
        )
        assertThat(InternalTransferDetector.detect(legs)).containsExactly(
            LegKey(accountB, "d1"), LegKey(accountA, "c1"),
            LegKey(accountB, "d2"), LegKey(accountA, "c2"),
        )
    }
}
