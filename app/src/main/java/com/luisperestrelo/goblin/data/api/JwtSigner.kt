package com.luisperestrelo.goblin.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Builds the RS256 JSON Web Tokens that authorize every Enable Banking API
 * call. Deliberately dependency-free: a JWT is base64url(header).base64url(payload)
 * signed with SHA256withRSA, nothing more.
 */
class JwtSigner(privateKeyPem: String, private val applicationId: String) {

    private val privateKey: PrivateKey = parsePkcs8Pem(privateKeyPem)

    fun createToken(nowEpochSeconds: Long, validitySeconds: Long = 3600): String {
        val header = JsonObject(
            mapOf(
                "typ" to JsonPrimitive("JWT"),
                "alg" to JsonPrimitive("RS256"),
                "kid" to JsonPrimitive(applicationId),
            )
        )
        val payload = JsonObject(
            mapOf(
                "iss" to JsonPrimitive("enablebanking.com"),
                "aud" to JsonPrimitive("api.enablebanking.com"),
                "iat" to JsonPrimitive(nowEpochSeconds),
                "exp" to JsonPrimitive(nowEpochSeconds + validitySeconds),
            )
        )
        val signingInput =
            base64Url(Json.encodeToString(JsonObject.serializer(), header).toByteArray()) +
                "." +
                base64Url(Json.encodeToString(JsonObject.serializer(), payload).toByteArray())

        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(signingInput.toByteArray())
            sign()
        }
        return "$signingInput.${base64Url(signature)}"
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun parsePkcs8Pem(pem: String): PrivateKey {
        val base64Body = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .filterNot { it.isWhitespace() }
        val keyBytes = Base64.getDecoder().decode(base64Body)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
    }
}
