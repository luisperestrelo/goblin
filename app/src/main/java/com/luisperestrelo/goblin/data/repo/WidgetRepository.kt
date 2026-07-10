package com.luisperestrelo.goblin.data.repo

import com.luisperestrelo.goblin.data.credentials.CredentialsStore
import com.luisperestrelo.goblin.data.db.BalanceSnapshotDao
import com.luisperestrelo.goblin.data.db.SyncLogDao
import com.luisperestrelo.goblin.data.db.TransactionDao
import com.luisperestrelo.goblin.data.prefs.PreferencesStore
import com.luisperestrelo.goblin.domain.WeekComparisonWindows
import com.luisperestrelo.goblin.domain.model.Money
import com.luisperestrelo.goblin.widget.WidgetData
import com.luisperestrelo.goblin.widget.WidgetStatus
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
        val spentThisWeek = transactionDao.sumAmount(
            iban, DEBIT, windows.thisWeek.fromInclusive.toString(), windows.thisWeek.toExclusive.toString()
        )
        val spentLastWeek = transactionDao.sumAmount(
            iban, DEBIT, windows.lastWeekAligned.fromInclusive.toString(), windows.lastWeekAligned.toExclusive.toString()
        )
        val receivedThisWeek = transactionDao.sumAmount(
            iban, CREDIT, windows.thisWeek.fromInclusive.toString(), windows.thisWeek.toExclusive.toString()
        )

        val lastSync = syncLogDao.lastSuccessfulSyncEpochMillis()
        val isStale = lastSync == null || (System.currentTimeMillis() - lastSync) > STALE_THRESHOLD_MILLIS

        val consentExpired = consentExpired()

        return WidgetData(
            status = if (consentExpired) WidgetStatus.NEEDS_REAUTH else WidgetStatus.READY,
            accountLast4 = iban.takeLast(4),
            balance = balance,
            spentThisWeek = Money(spentThisWeek, currency),
            spentDeltaVsLastWeek = Money(spentThisWeek - spentLastWeek, currency),
            receivedThisWeek = Money(receivedThisWeek, currency),
            lastSyncEpochMillis = lastSync,
            isStale = isStale,
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
    }
}
