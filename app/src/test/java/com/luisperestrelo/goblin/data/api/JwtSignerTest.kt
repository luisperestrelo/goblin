package com.luisperestrelo.goblin.data.api

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class JwtSignerTest {

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun privateKeyAsPem(): String {
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(keyPair.private.encoded)
        return "-----BEGIN PRIVATE KEY-----\n$base64\n-----END PRIVATE KEY-----\n"
    }

    @Test
    fun `token carries expected header and claims`() {
        val signer = JwtSigner(privateKeyAsPem(), applicationId = "app-id-123")
        val token = signer.createToken(nowEpochSeconds = 1_700_000_000, validitySeconds = 3600)
        val parts = token.split(".")
        assertThat(parts).hasSize(3)

        val header = Json.parseToJsonElement(decodeBase64Url(parts[0])).jsonObject
        assertThat(header["alg"]!!.jsonPrimitive.content).isEqualTo("RS256")
        assertThat(header["typ"]!!.jsonPrimitive.content).isEqualTo("JWT")
        assertThat(header["kid"]!!.jsonPrimitive.content).isEqualTo("app-id-123")

        val payload = Json.parseToJsonElement(decodeBase64Url(parts[1])).jsonObject
        assertThat(payload["iss"]!!.jsonPrimitive.content).isEqualTo("enablebanking.com")
        assertThat(payload["aud"]!!.jsonPrimitive.content).isEqualTo("api.enablebanking.com")
        assertThat(payload["iat"]!!.jsonPrimitive.content.toLong()).isEqualTo(1_700_000_000)
        assertThat(payload["exp"]!!.jsonPrimitive.content.toLong()).isEqualTo(1_700_003_600)
    }

    @Test
    fun `signature verifies against the public key`() {
        val signer = JwtSigner(privateKeyAsPem(), applicationId = "app-id-123")
        val token = signer.createToken(nowEpochSeconds = 1_700_000_000)
        val parts = token.split(".")
        val signingInput = "${parts[0]}.${parts[1]}".toByteArray()
        val signatureBytes = Base64.getUrlDecoder().decode(parts[2])

        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(keyPair.public)
            update(signingInput)
        }
        assertThat(verifier.verify(signatureBytes)).isTrue()
    }

    @Test
    fun `tampered payload fails verification`() {
        val signer = JwtSigner(privateKeyAsPem(), applicationId = "app-id-123")
        val token = signer.createToken(nowEpochSeconds = 1_700_000_000)
        val parts = token.split(".")
        val tamperedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(decodeBase64Url(parts[1]).replace("enablebanking", "evil").toByteArray())

        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(keyPair.public)
            update("${parts[0]}.$tamperedPayload".toByteArray())
        }
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isFalse()
    }

    private fun decodeBase64Url(value: String): String =
        String(Base64.getUrlDecoder().decode(value))
}
