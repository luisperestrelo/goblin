package com.luisperestrelo.goblin.data.api

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface EnableBankingApi {

    @GET("sessions/{sessionId}")
    suspend fun getSession(@Path("sessionId") sessionId: String): SessionDto

    @GET("accounts/{accountUid}/balances")
    suspend fun getBalances(@Path("accountUid") accountUid: String): BalancesResponseDto

    /**
     * ABANCA's connector requires [dateFrom] to be repeated on every page
     * together with [continuationKey]; it rejects continuation-only requests.
     */
    @GET("accounts/{accountUid}/transactions")
    suspend fun getTransactions(
        @Path("accountUid") accountUid: String,
        @Query("date_from") dateFrom: String,
        @Query("continuation_key") continuationKey: String? = null,
    ): TransactionsResponseDto
}
