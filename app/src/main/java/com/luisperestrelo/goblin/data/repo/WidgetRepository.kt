package com.luisperestrelo.goblin.data.repo

import com.luisperestrelo.goblin.data.credentials.CredentialsStore
import com.luisperestrelo.goblin.data.db.BalanceSnapshotDao
import com.luisperestrelo.goblin.data.db.SyncLogDao
import com.luisperestrelo.goblin.data.db.TransactionDao
import com.luisperestrelo.goblin.data.db.TransactionEntity
import com.luisperestrelo.goblin.data.prefs.PreferencesStore
import com.luisperestrelo.goblin.domain.DateWindow
import com.luisperestrelo.goblin.domain.InternalTransferDetector
import com.luisperestrelo.goblin.domain.LegKey
import com.luisperestrelo.goblin.domain.SavingStreak
import com.luisperestrelo.goblin.domain.TransferLeg
import com.luisperestrelo.goblin.domain.WeekComparisonWindows
import com.luisperestrelo.goblin.domain.WeeklySpend
import com.luisperestrelo.goblin.domain.model.Money
import com.luisperestrelo.goblin.widget.SavingGame
import com.luisperestrelo.goblin.widget.WidgetData
import com.luisperestrelo.goblin.widget.WidgetStatus
import com.luisperestrelo.goblin.widget.WidgetTransaction
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles the single-account view the widget renders. Focuses on the primary
 * account only (Luis's active one); the other account is intentionally never
 * surfaced. Primary = the stored preference, falling back to the most active
 * account until one is explicitly chosen.
 */
@Singleton
class WidgetRepository @Inject constructor(
    private val credentialsStore: CredentialsStore,
    private val preferencesStore: PreferencesStore,
    private val transactionDao: TransactionDao,
    private val balanceSnapshotDao: BalanceSnapshotDao,
    private val syncLogDao: SyncLogDao,
) {

    suspend fun primaryIban(): String? =
        preferencesStore.primaryIbanOrNull() ?: transactionDao.mostActiveIban()

    suspend fun loadWidgetData(now: LocalDate = LocalDate.now()): WidgetData {
        if (credentialsStore.credentials()?.sessionId == null) return WidgetData.SetupRequired
        val iban = primaryIban() ?: return WidgetData.SetupRequired

        val balance = balanceSnapshotDao.latestForAccount(iban)?.let { Money(it.balanceCents, it.currency) }
        val currency = balance?.currency ?: DEFAULT_CURRENCY

        val windows = WeekComparisonWindows.forToday(now)
        val spentThisWeek = Money(sumExcludingInternal(iban, DEBIT, windows.thisWeek), currency)
        val receivedThisWeek = Money(sumExcludingInternal(iban, CREDIT, windows.thisWeek), currency)

        val lastSync = syncLogDao.lastSuccessfulSyncEpochMillis()
        val isStale = lastSync == null || (System.currentTimeMillis() - lastSync) > STALE_THRESHOLD_MILLIS

        val consentExpired = consentExpired()
        val recent = transactionDao.recentForAccount(iban, RECENT_LIMIT).map { it.toWidgetTransaction() }
        val savingGame = computeSavingGame(iban, now, currency, spentThisWeek)

        return WidgetData(
            status = if (consentExpired) WidgetStatus.NEEDS_REAUTH else WidgetStatus.READY,
            accountLast4 = iban.takeLast(4),
            balance = balance,
            spentThisWeek = spentThisWeek,
            receivedThisWeek = receivedThisWeek,
            savingGame = savingGame,
            lastSyncEpochMillis = lastSync,
            isStale = isStale,
            recent = recent,
        )
    }

    /**
     * The weekly saving-streak game over the primary account's completed weeks
     * (internal transfers excluded). Null until there's enough weekly history.
     */
    private suspend fun computeSavingGame(iban: String, now: LocalDate, currency: String, thisWeekSoFar: Money): SavingGame? {
        val from = WeeklySpend.weekStartOf(now).minusWeeks(WEEKLY_HISTORY_WEEKS.toLong())
        val to = WeeklySpend.weekStartOf(now) // exclusive: completed weeks only
        val rows = transactionDao.inWindow(from.toString(), to.toString())
        val internal = InternalTransferDetector.detect(rows.map { it.toTransferLeg() })
        val debits = rows.asSequence()
            .filter { it.accountIban == iban && it.creditDebitIndicator == DEBIT }
            .filter { LegKey(it.accountIban, it.entryReference) !in internal }
            .map { LocalDate.parse(it.bookingDate) to it.amountCents }
            .toList()
        val weekly = WeeklySpend.completedWeeklyTotals(debits, now, WEEKLY_HISTORY_WEEKS)
        val streak = SavingStreak.compute(weekly) ?: return null
        return SavingGame(
            currentStreakWeeks = streak.currentStreakWeeks,
            bestStreakWeeks = streak.bestStreakWeeks,
            usualWeek = Money(streak.usualWeekCents, currency),
            thisWeekSoFar = thisWeekSoFar,
        )
    }

    /**
     * Sum for one account+direction over [window], excluding legs that are really
     * transfers between the user's own accounts. The window is padded by a few days
     * on each side so a transfer whose two legs straddle the boundary is still
     * paired, then the sum is filtered back to the exact window.
     */
    private suspend fun sumExcludingInternal(iban: String, direction: String, window: DateWindow): Long {
        val rows = transactionDao.inWindow(
            window.fromInclusive.minusDays(TRANSFER_PAD_DAYS).toString(),
            window.toExclusive.plusDays(TRANSFER_PAD_DAYS).toString(),
        )
        val internal = InternalTransferDetector.detect(rows.map { it.toTransferLeg() })
        val from = window.fromInclusive.toString()
        val to = window.toExclusive.toString()
        return rows.asSequence()
            .filter { it.accountIban == iban && it.creditDebitIndicator == direction }
            .filter { it.bookingDate >= from && it.bookingDate < to }
            .filter { LegKey(it.accountIban, it.entryReference) !in internal }
            .sumOf { it.amountCents }
    }

    private fun TransactionEntity.toTransferLeg() = TransferLeg(
        iban = accountIban,
        entryReference = entryReference,
        isDebit = creditDebitIndicator == DEBIT,
        amountCents = amountCents,
        currency = currency,
        bookingDate = LocalDate.parse(bookingDate),
    )

    private fun TransactionEntity.toWidgetTransaction(): WidgetTransaction {
        val signedCents = if (creditDebitIndicator == DEBIT) -amountCents else amountCents
        return WidgetTransaction(
            description = remittanceLines.firstOrNull()?.takeIf { it.isNotBlank() } ?: "(no description)",
            amount = Money(signedCents, currency),
            bookingDate = bookingDate,
        )
    }

    private suspend fun consentExpired(): Boolean {
        val validUntil = preferencesStore.consentValidUntil.first() ?: return false
        return try {
            OffsetDateTime.parse(validUntil).toInstant().toEpochMilli() < System.currentTimeMillis()
        } catch (e: Exception) {
            false // unparseable -> don't nag; treat as valid
        }
    }

    private companion object {
        const val DEBIT = "DBIT"
        const val CREDIT = "CRDT"
        const val DEFAULT_CURRENCY = "EUR"
        const val STALE_THRESHOLD_MILLIS = 24L * 60 * 60 * 1000
        const val RECENT_LIMIT = 8
        const val TRANSFER_PAD_DAYS = 3L
        const val WEEKLY_HISTORY_WEEKS = 26
    }
}
