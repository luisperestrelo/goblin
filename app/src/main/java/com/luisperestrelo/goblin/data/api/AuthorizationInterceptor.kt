package com.luisperestrelo.goblin.data.api

import com.luisperestrelo.goblin.data.credentials.CredentialsStore
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds the Bearer JWT to every request. Tokens are minted per call window and
 * cached until shortly before expiry; the signer is rebuilt if credentials
 * change (e.g. first import or key rotation).
 */
class AuthorizationInterceptor(private val credentialsStore: CredentialsStore) : Interceptor {

    private var cachedToken: String? = null
    private var cachedTokenExpiresAt: Long = 0
    private var cachedCredentialsVersion: Int = -1

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer ${currentToken()}")
            .build()
        return chain.proceed(request)
    }

    @Synchronized
    private fun currentToken(): String {
        val credentials = credentialsStore.requireCredentials()
        val now = System.currentTimeMillis() / 1000
        val token = cachedToken
        if (token != null &&
            credentialsStore.version == cachedCredentialsVersion &&
            now < cachedTokenExpiresAt - EXPIRY_MARGIN_SECONDS
        ) {
            return token
        }
        val signer = JwtSigner(credentials.privateKeyPem, credentials.applicationId)
        return signer.createToken(nowEpochSeconds = now).also {
            cachedToken = it
            cachedTokenExpiresAt = now + 3600
            cachedCredentialsVersion = credentialsStore.version
        }
    }

    private companion object {
        const val EXPIRY_MARGIN_SECONDS = 120L
    }
}
