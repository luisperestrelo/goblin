package com.luisperestrelo.goblin.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Upsert
    suspend fun upsertAll(accounts: List<AccountEntity>)

    @Query("SELECT * FROM accounts ORDER BY displayOrder, iban")
    fun observeAll(): Flow<List<AccountEntity>>
}

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(transactions: List<TransactionEntity>)

    @Query("SELECT * FROM transactions ORDER BY bookingDate DESC, entryReference DESC LIMIT :limit")
    fun observeMostRecent(limit: Int): Flow<List<TransactionEntity>>

    @Query("SELECT MAX(bookingDate) FROM transactions WHERE accountIban = :accountIban")
    suspend fun newestBookingDate(accountIban: String): String?

    @Query("SELECT COUNT(*) FROM transactions")
    fun observeCount(): Flow<Int>

    /**
     * Sum of amounts (cents) for one account and direction over the half-open
     * booking-date window [fromInclusive, toExclusive). bookingDate is stored as
     * ISO `YYYY-MM-DD`, so lexicographic comparison is chronological.
     */
    @Query(
        """
        SELECT COALESCE(SUM(amountCents), 0) FROM transactions
        WHERE accountIban = :iban AND creditDebitIndicator = :direction
          AND bookingDate >= :fromInclusive AND bookingDate < :toExclusive
        """
    )
    suspend fun sumAmount(iban: String, direction: String, fromInclusive: String, toExclusive: String): Long

    /** IBAN of the account with the most transactions; the primary-account fallback. */
    @Query("SELECT accountIban FROM transactions GROUP BY accountIban ORDER BY COUNT(*) DESC LIMIT 1")
    suspend fun mostActiveIban(): String?

    @Query("SELECT * FROM transactions WHERE accountIban = :iban ORDER BY bookingDate DESC, entryReference DESC LIMIT :limit")
    suspend fun recentForAccount(iban: String, limit: Int): List<TransactionEntity>
}

@Dao
interface BalanceSnapshotDao {
    @Insert
    suspend fun insert(snapshot: BalanceSnapshotEntity)

    @Query(
        """
        SELECT * FROM balance_snapshots
        WHERE id IN (
            SELECT MAX(id) FROM balance_snapshots GROUP BY accountIban
        )
        """
    )
    fun observeLatestPerAccount(): Flow<List<BalanceSnapshotEntity>>

    @Query("SELECT * FROM balance_snapshots WHERE accountIban = :iban ORDER BY id DESC LIMIT 1")
    suspend fun latestForAccount(iban: String): BalanceSnapshotEntity?
}

@Dao
interface SyncLogDao {
    @Insert
    suspend fun insert(entry: SyncLogEntity): Long

    @Query("UPDATE sync_log SET finishedAtEpochMillis = :finishedAt, outcome = :outcome, detail = :detail WHERE id = :id")
    suspend fun complete(id: Long, finishedAt: Long, outcome: String, detail: String?)

    @Query("SELECT * FROM sync_log ORDER BY id DESC LIMIT 1")
    fun observeLast(): Flow<SyncLogEntity?>

    @Query("SELECT MAX(finishedAtEpochMillis) FROM sync_log WHERE outcome = 'success'")
    suspend fun lastSuccessfulSyncEpochMillis(): Long?
}
