package com.luisperestrelo.goblin.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "goblin_prefs")

/**
 * Non-secret app state held in DataStore: the consent window and the transient
 * CSRF `state` for an in-flight authorization. The session id and private key
 * are secret and live encrypted in CredentialsStore instead.
 */
@Singleton
class PreferencesStore @Inject constructor(@ApplicationContext private val context: Context) {

    /** ISO-8601 instant the current consent expires, or null before first auth. */
    val consentValidUntil: Flow<String?> =
        context.dataStore.data.map { it[KEY_CONSENT_VALID_UNTIL] }

    suspend fun setConsentValidUntil(validUntil: String) {
        context.dataStore.edit { it[KEY_CONSENT_VALID_UNTIL] = validUntil }
    }

    /** The `state` sent with the pending /auth request, checked on redirect. */
    suspend fun setPendingAuthState(state: String) {
        context.dataStore.edit { it[KEY_PENDING_AUTH_STATE] = state }
    }

    suspend fun pendingAuthState(): String? =
        context.dataStore.data.first()[KEY_PENDING_AUTH_STATE]

    suspend fun clearPendingAuthState() {
        context.dataStore.edit { it.remove(KEY_PENDING_AUTH_STATE) }
    }

    private companion object {
        val KEY_CONSENT_VALID_UNTIL = stringPreferencesKey("consent_valid_until")
        val KEY_PENDING_AUTH_STATE = stringPreferencesKey("pending_auth_state")
    }
}
