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

/**
 * Body of POST /auth. Starts bank authorization; the response carries the URL
 * to open in a Custom Tab for SCA. Mirrors the verified explorer request.
 */
@Serializable
data class AuthRequestDto(
    val access: AuthAccessDto,
    val aspsp: AspspDto,
    val state: String,
    @SerialName("redirect_url") val redirectUrl: String,
    @SerialName("psu_type") val psuType: String,
)

@Serializable
data class AuthAccessDto(
    @SerialName("valid_until") val validUntil: String,
)

@Serializable
data class AspspDto(
    val name: String,
    val country: String,
)

@Serializable
data class AuthResponseDto(
    val url: String,
)

/** Body of POST /sessions: exchanges the redirect `code` for a session. */
@Serializable
data class CreateSessionRequestDto(
    val code: String,
)

/**
 * Response of POST /sessions. Unlike GET /sessions/{id}, `accounts` here is a
 * list of full objects (uid + account_id), and `access.valid_until` reflects
 * the consent window the bank actually granted.
 */
@Serializable
data class CreateSessionResponseDto(
    @SerialName("session_id") val sessionId: String,
    val accounts: List<SessionAccountDto> = emptyList(),
    val access: AccessDto? = null,
)

@Serializable
data class SessionAccountDto(
    val uid: String,
    @SerialName("account_id") val accountId: AccountIdDto? = null,
)

/**
 * Response of GET /sessions/{id}. Unlike the session-creation response,
 * `accounts` here is a plain list of account uids (verified against
 * production: {"status":"AUTHORIZED","accounts":["<uid>", ...]}).
 */
@Serializable
data class GetSessionDto(
    val status: String? = null,
    val accounts: List<String> = emptyList(),
    val access: AccessDto? = null,
)

@Serializable
data class AccessDto(
    @SerialName("valid_until") val validUntil: String? = null,
)

@Serializable
data class AccountDetailsDto(
    @SerialName("account_id") val accountId: AccountIdDto? = null,
    val name: String? = null,
    val currency: String? = null,
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
