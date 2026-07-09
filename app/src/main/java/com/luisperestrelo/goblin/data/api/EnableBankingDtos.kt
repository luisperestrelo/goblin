package com.luisperestrelo.goblin.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for the Enable Banking API, restricted to the fields ABANCA PT actually
 * serves (verified 2026-07-09, see docs/PLAN.md section 5). Unknown fields are
 * ignored by the Json configuration.
 */

@Serializable
data class AmountDto(
    val currency: String,
    val amount: String,
)

@Serializable
data class SessionDto(
    @SerialName("session_id") val sessionId: String? = null,
    val accounts: List<SessionAccountDto> = emptyList(),
    val access: AccessDto? = null,
)

@Serializable
data class AccessDto(
    @SerialName("valid_until") val validUntil: String? = null,
)

@Serializable
data class SessionAccountDto(
    val uid: String,
    @SerialName("account_id") val accountId: AccountIdDto? = null,
)

@Serializable
data class AccountIdDto(
    val iban: String? = null,
)

@Serializable
data class BalancesResponseDto(
    val balances: List<BalanceDto>,
)

@Serializable
data class BalanceDto(
    @SerialName("balance_amount") val balanceAmount: AmountDto,
    @SerialName("balance_type") val balanceType: String? = null,
)

@Serializable
data class TransactionsResponseDto(
    val transactions: List<TransactionDto>,
    @SerialName("continuation_key") val continuationKey: String? = null,
)

@Serializable
data class TransactionDto(
    @SerialName("entry_reference") val entryReference: String,
    @SerialName("transaction_amount") val transactionAmount: AmountDto,
    @SerialName("credit_debit_indicator") val creditDebitIndicator: String,
    val status: String,
    @SerialName("booking_date") val bookingDate: String,
    @SerialName("value_date") val valueDate: String? = null,
    @SerialName("balance_after_transaction") val balanceAfterTransaction: AmountDto? = null,
    @SerialName("remittance_information") val remittanceInformation: List<String> = emptyList(),
)
