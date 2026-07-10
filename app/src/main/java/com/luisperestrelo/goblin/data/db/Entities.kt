package com.luisperestrelo.goblin.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An account keyed by its IBAN. Enable Banking issues a fresh, throwaway account
 * `uid` on every authorization, so the uid can never be a storage key - the IBAN
 * is the stable identity that survives re-auth.
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val iban: String,
    val nickname: String? = null,
    val displayOrder: Int = 0,
)

/**
 * One row per bank transaction. `entryReference` is the bank's stable identifier
 * (ABANCA's `transaction_id` is always null; `entry_reference` is a deterministic
 * timestamp+amount string that is identical across authorizations), and
 * `accountIban` is the stable account identity (never the per-session uid). The
 * composite primary key makes sync upserts idempotent, including across re-auths.
 */
@Entity(tableName = "transactions", primaryKeys = ["accountIban", "entryReference"])
data class TransactionEntity(
    val accountIban: String,
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
    val accountIban: String,
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
