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
     */
    suspend fun syncNow(): SyncSummary {
        val logId = syncLogDao.insert(
            SyncLogEntity(startedAtEpochMillis = System.currentTimeMillis(), finishedAtEpochMillis = null, outcome = "running", detail = null)
        )
        try {
            val sessionId = credentialsStore.requireCredentials().sessionId
                ?: error("No session id configured yet")
            val session = api.getSession(sessionId)

            accountDao.upsertAll(
                session.accounts.mapIndexed { index, accountUid ->
                    val details = api.getAccountDetails(accountUid)
                    AccountEntity(
                        uid = accountUid,
                        iban = details.accountId?.iban ?: accountUid,
                        displayOrder = index,
                    )
                }
            )

            var fetchedTransactions = 0
            for (accountUid in session.accounts) {
                val balances = api.getBalances(accountUid)
                balances.balances.firstOrNull()?.let { balance ->
                    val money = Money.parse(balance.balanceAmount.amount, balance.balanceAmount.currency)
                    balanceSnapshotDao.insert(
                        BalanceSnapshotEntity(
                            accountUid = accountUid,
                            capturedAtEpochMillis = System.currentTimeMillis(),
                            balanceCents = money.cents,
                            currency = money.currency,
                            balanceType = balance.balanceType,
                        )
                    )
                }
                fetchedTransactions += syncTransactionsForAccount(accountUid)
            }

            syncLogDao.complete(logId, System.currentTimeMillis(), "success", null)
            return SyncSummary(session.accounts.size, fetchedTransactions)
        } catch (e: Exception) {
            syncLogDao.complete(logId, System.currentTimeMillis(), "failure", e.message)
            throw e
        }
    }

    private suspend fun syncTransactionsForAccount(accountUid: String): Int {
        val newestKnown = transactionDao.newestBookingDate(accountUid)
        val dateFrom = if (newestKnown != null) {
            LocalDate.parse(newestKnown).minusDays(INCREMENTAL_OVERLAP_DAYS).toString()
        } else {
            LocalDate.now().minusDays(INITIAL_BACKFILL_DAYS).toString()
        }

        var fetched = 0
        var continuationKey: String? = null
        do {
            val page = api.getTransactions(accountUid, dateFrom, continuationKey)
            transactionDao.upsertAll(page.transactions.map { it.toEntity(accountUid) })
            fetched += page.transactions.size
            continuationKey = page.continuationKey
        } while (continuationKey != null)
        return fetched
    }

    private fun TransactionDto.toEntity(accountUid: String): TransactionEntity {
        val amount = Money.parse(transactionAmount.amount, transactionAmount.currency)
        val balanceAfter = balanceAfterTransaction?.let { Money.parse(it.amount, it.currency) }
        return TransactionEntity(
            accountUid = accountUid,
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
