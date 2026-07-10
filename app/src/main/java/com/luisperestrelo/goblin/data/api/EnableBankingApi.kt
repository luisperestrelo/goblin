package com.luisperestrelo.goblin.data.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface EnableBankingApi {

    /** Starts bank authorization; response `url` is opened for SCA. */
    @POST("auth")
    suspend fun startAuthorization(@Body request: AuthRequestDto): AuthResponseDto

    /** Exchanges the redirect `code` for an authorized session. */
    @POST("sessions")
    suspend fun createSession(@Body request: CreateSessionRequestDto): CreateSessionResponseDto

    @GET("sessions/{sessionId}")
    suspend fun getSession(@Path("sessionId") sessionId: String): GetSessionDto

    @GET("accounts/{accountUid}/details")
    suspend fun getAccountDetails(@Path("accountUid") accountUid: String): AccountDetailsDto

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
