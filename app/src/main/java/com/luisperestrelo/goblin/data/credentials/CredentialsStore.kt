package com.luisperestrelo.goblin.data.credentials

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class Credentials(
    val applicationId: String,
    val privateKeyPem: String,
    val sessionId: String? = null,
)

/**
 * Holds the Enable Banking application credentials (application id + private
 * key) and the current session id, encrypted at rest with an AES-256-GCM key
 * that lives in the Android Keystore and never leaves secure hardware.
 * File layout: 12-byte GCM IV followed by the ciphertext.
 */
class CredentialsStore(context: Context) {

    private val file = File(context.filesDir, "credentials.enc")
    private var cached: Credentials? = null

    /** Bumped on every save so token caches can invalidate. */
    @Volatile
    var version: Int = 0
        private set

    fun hasCredentials(): Boolean = cached != null || file.exists()

    fun credentials(): Credentials? {
        cached?.let { return it }
        if (!file.exists()) return null
        val content = file.readBytes()
        val iv = content.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = content.copyOfRange(GCM_IV_BYTES, content.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return Json.decodeFromString<Credentials>(String(cipher.doFinal(ciphertext)))
            .also { cached = it }
    }

    fun requireCredentials(): Credentials =
        credentials() ?: error("Enable Banking credentials are not configured yet")

    @Synchronized
    fun save(credentials: Credentials) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val ciphertext = cipher.doFinal(Json.encodeToString(Credentials.serializer(), credentials).toByteArray())
        file.writeBytes(cipher.iv + ciphertext)
        cached = credentials
        version += 1
    }

    @Synchronized
    fun saveSessionId(sessionId: String) {
        save(requireCredentials().copy(sessionId = sessionId))
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "goblin-credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
