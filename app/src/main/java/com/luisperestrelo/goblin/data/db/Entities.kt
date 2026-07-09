package com.luisperestrelo.goblin.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val uid: String,
    val iban: String,
    val nickname: String? = null,
    val displayOrder: Int = 0,
)

/**
 * One row per bank transaction. `entryReference` is the bank's identifier
 * (ABANCA's `transaction_id` is always null); it is unique per account, hence
 * the composite primary key, which also makes sync upserts idempotent.
 */
@Entity(tableName = "transactions", primaryKeys = ["accountUid", "entryReference"])
data class TransactionEntity(
    val accountUid: String,
    val entryReference: String,
    val amountCents: Long,
    val currency: String,
    val creditDebitIndicator: String,
    val status: String,
    val bookingDate: String,
    val valueDate: String?,
    val balanceAfterCents: Long?,
    val balanceAfterCurrency: String?,
    val remittanceLines: List<String>,
)

@Entity(tableName = "balance_snapshots")
data class BalanceSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountUid: String,
    val capturedAtEpochMillis: Long,
    val balanceCents: Long,
    val currency: String,
    val balanceType: String?,
)

@Entity(tableName = "sync_log")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long?,
    val outcome: String,
    val detail: String?,
)
