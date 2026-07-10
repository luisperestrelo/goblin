package com.luisperestrelo.goblin.data.repo

import com.luisperestrelo.goblin.data.api.EnableBankingApi
import com.luisperestrelo.goblin.data.api.TransactionDto
import com.luisperestrelo.goblin.data.credentials.CredentialsStore
import com.luisperestrelo.goblin.data.db.AccountDao
import com.luisperestrelo.goblin.data.db.AccountEntity
import com.luisperestrelo.goblin.data.db.BalanceSnapshotDao
import com.luisperestrelo.goblin.data.db.BalanceSnapshotEntity
import com.luisperestrelo.goblin.data.db.SyncLogDao
import com.luisperestrelo.goblin.data.db.SyncLogEntity
import com.luisperestrelo.goblin.data.db.TransactionDao
import com.luisperestrelo.goblin.data.db.TransactionEntity
import com.luisperestrelo.goblin.domain.model.Money
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class SyncSummary(
    val accountCount: Int,
    val fetchedTransactionCount: Int,
)

/** A session account resolved to its stable IBAN identity for this sync pass. */
private data class ResolvedAccount(
    val sessionUid: String,
    val iban: String,
    val displayOrder: Int,
)

@Singleton
class SyncRepository @Inject constructor(
    private val api: EnableBankingApi,
    private val credentialsStore: CredentialsStore,
    private val accountDao: AccountDao,
    private val transactionDao: TransactionDao,
    private val balanceSnapshotDao: BalanceSnapshotDao,
    private val syncLogDao: SyncLogDao,
) {

    /**
     * Full sync pass: refresh account list from the session, then per account
     * store a balance snapshot and upsert transactions. Incremental windows
     * overlap the newest known booking date by a few days so late-booked
     * transactions are never missed; upserts keep the overlap idempotent.
     *
     * Enable Banking issues a fresh account `uid` on every authorization, so each
     * session uid is resolved to its stable IBAN and everything is keyed by IBAN -
     * otherwise a re-auth would duplicate the entire history under new uids.
     *
     * [forceFullHistory] ignores local state and pulls the full backfill window
     * per account - used by the post-auth backfill, which must seed the deepest
     * history while the bank's ~1h post-SCA deep-history window is still open.
     */
    suspend fun syncNow(forceFullHistory: Boolean = false): SyncSummary {
        val logId = syncLogDao.insert(
            SyncLogEntity(startedAtEpochMillis = System.currentTimeMillis(), finishedAtEpochMillis = null, outcome = "running", detail = null)
        )
        try {
            val sessionId = credentialsStore.requireCredentials().sessionId
                ?: error("No session id configured yet")
            val session = api.getSession(sessionId)

            val resolvedAccounts = session.accounts.mapIndexed { index, sessionUid ->
                val details = api.getAccountDetails(sessionUid)
                val iban = details.accountId?.iban
                    ?: error("Account $sessionUid has no IBAN; cannot key it stably")
                ResolvedAccount(sessionUid = sessionUid, iban = iban, displayOrder = index)
            }
            accountDao.upsertAll(
                resolvedAccounts.map { AccountEntity(iban = it.iban, displayOrder = it.displayOrder) }
            )

            var fetchedTransactions = 0
            for (account in resolvedAccounts) {
                val balances = api.getBalances(account.sessionUid)
                balances.balances.firstOrNull()?.let { balance ->
                    val money = Money.parse(balance.balanceAmount.amount, balance.balanceAmount.currency)
                    balanceSnapshotDao.insert(
                        BalanceSnapshotEntity(
                            accountIban = account.iban,
                            capturedAtEpochMillis = System.currentTimeMillis(),
                            balanceCents = money.cents,
                            currency = money.currency,
                            balanceType = balance.balanceType,
                        )
                    )
                }
                fetchedTransactions += syncTransactionsForAccount(account.sessionUid, account.iban, forceFullHistory)
            }

            syncLogDao.complete(logId, System.currentTimeMillis(), "success", null)
            return SyncSummary(resolvedAccounts.size, fetchedTransactions)
        } catch (e: Exception) {
            syncLogDao.complete(logId, System.currentTimeMillis(), "failure", e.message)
            throw e
        }
    }

    private suspend fun syncTransactionsForAccount(
        sessionUid: String,
        accountIban: String,
        forceFullHistory: Boolean,
    ): Int {
        val newestKnown = if (forceFullHistory) null else transactionDao.newestBookingDate(accountIban)
        val dateFrom = if (newestKnown != null) {
            LocalDate.parse(newestKnown).minusDays(INCREMENTAL_OVERLAP_DAYS).toString()
        } else {
            LocalDate.now().minusDays(INITIAL_BACKFILL_DAYS).toString()
        }

        var fetched = 0
        var continuationKey: String? = null
        do {
            val page = api.getTransactions(sessionUid, dateFrom, continuationKey)
            transactionDao.upsertAll(page.transactions.map { it.toEntity(accountIban) })
            fetched += page.transactions.size
            continuationKey = page.continuationKey
        } while (continuationKey != null)
        return fetched
    }

    private fun TransactionDto.toEntity(accountIban: String): TransactionEntity {
        val amount = Money.parse(transactionAmount.amount, transactionAmount.currency)
        val balanceAfter = balanceAfterTransaction?.let { Money.parse(it.amount, it.currency) }
        return TransactionEntity(
            accountIban = accountIban,
            entryReference = entryReference,
            amountCents = amount.cents,
            currency = amount.currency,
            creditDebitIndicator = creditDebitIndicator,
            status = status,
            bookingDate = bookingDate,
            valueDate = valueDate,
            balanceAfterCents = balanceAfter?.cents,
            balanceAfterCurrency = balanceAfter?.currency,
            remittanceLines = remittanceInformation,
        )
    }

    private companion object {
        const val INCREMENTAL_OVERLAP_DAYS = 5L
        const val INITIAL_BACKFILL_DAYS = 3L * 365
    }
}
