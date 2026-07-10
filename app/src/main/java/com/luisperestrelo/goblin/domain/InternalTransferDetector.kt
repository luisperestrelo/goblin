package com.luisperestrelo.goblin.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** One transaction leg considered for internal-transfer matching. */
data class TransferLeg(
    val iban: String,
    val entryReference: String,
    val isDebit: Boolean,
    /** Absolute amount in cents (the API sends amount and direction separately). */
    val amountCents: Long,
    val currency: String,
    val bookingDate: LocalDate,
)

/** Stable identity of a transaction leg. */
data class LegKey(val iban: String, val entryReference: String)

/**
 * Detects transfers between the user's own accounts so they aren't miscounted as
 * spending or income. A debit on one account paired with a same-amount credit on
 * a *different* own account within a few days is an internal move. Every synced
 * account belongs to the user, so any cross-account amount match is internal.
 *
 * Tuned against Luis's real history: all 20 genuine transfers land on the same
 * booking date, and the nearest same-amount coincidence is 8 days away - so a
 * 3-day tolerance catches real transfers (incl. a weekend straddle) with no false
 * positives.
 */
object InternalTransferDetector {

    private const val MAX_DAY_GAP = 3L

    fun detect(legs: List<TransferLeg>): Set<LegKey> {
        val matched = mutableSetOf<LegKey>()
        // Only equal amount+currency can be the two legs of one transfer.
        for ((_, group) in legs.groupBy { it.currency to it.amountCents }) {
            val debits = group.filter { it.isDebit }.sortedBy { it.bookingDate }
            val credits = group.filterNot { it.isDebit }
            for (debit in debits) {
                val match = credits
                    .filter { it.iban != debit.iban && LegKey(it.iban, it.entryReference) !in matched }
                    .minByOrNull { gapDays(debit.bookingDate, it.bookingDate) }
                    ?.takeIf { gapDays(debit.bookingDate, it.bookingDate) <= MAX_DAY_GAP }
                if (match != null) {
                    matched.add(LegKey(debit.iban, debit.entryReference))
                    matched.add(LegKey(match.iban, match.entryReference))
                }
            }
        }
        return matched
    }

    private fun gapDays(a: LocalDate, b: LocalDate): Long = abs(ChronoUnit.DAYS.between(a, b))
}
