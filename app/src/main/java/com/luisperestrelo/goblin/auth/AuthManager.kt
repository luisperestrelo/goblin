package com.luisperestrelo.goblin.auth

import androidx.work.WorkManager
import com.luisperestrelo.goblin.data.api.AspspDto
import com.luisperestrelo.goblin.data.api.AuthAccessDto
import com.luisperestrelo.goblin.data.api.AuthRequestDto
import com.luisperestrelo.goblin.data.api.CreateSessionRequestDto
import com.luisperestrelo.goblin.data.api.EnableBankingApi
import com.luisperestrelo.goblin.data.credentials.CredentialsStore
import com.luisperestrelo.goblin.data.prefs.PreferencesStore
import com.luisperestrelo.goblin.sync.BackfillWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Where a device authorization currently stands; drives the setup UI. */
sealed interface AuthPhase {
    data object Idle : AuthPhase
    data object RequestingAuthUrl : AuthPhase
    data object AwaitingBankAuthorization : AuthPhase
    data object ExchangingCode : AuthPhase
    data object Authorized : AuthPhase
    data class Error(val message: String) : AuthPhase
}

/**
 * Orchestrates the on-device bank authorization: POST /auth to get the SCA URL,
 * then POST /sessions to exchange the redirect code for a session. Both the
 * button (via the ViewModel) and the App Link redirect (via MainActivity) funnel
 * through this single @Singleton, so [phase] is the one source of truth for the
 * flow regardless of which entrypoint advanced it.
 */
@Singleton
class AuthManager @Inject constructor(
    private val api: EnableBankingApi,
    private val credentialsStore: CredentialsStore,
    private val preferencesStore: PreferencesStore,
    private val workManager: WorkManager,
) {
    private val _phase = MutableStateFlow<AuthPhase>(AuthPhase.Idle)
    val phase: StateFlow<AuthPhase> = _phase.asStateFlow()

    /**
     * Begins authorization: mints a CSRF `state`, requests the SCA URL and
     * returns it for the caller to open in a Custom Tab. Returns null on failure
     * (the error surfaces via [phase]).
     */
    suspend fun beginAuthorization(): String? = try {
        _phase.value = AuthPhase.RequestingAuthUrl
        val state = UUID.randomUUID().toString()
        preferencesStore.setPendingAuthState(state)
        val validUntil = OffsetDateTime.now(ZoneOffset.UTC)
            .plusDays(CONSENT_VALIDITY_DAYS)
            .format(VALID_UNTIL_FORMAT)
        val response = api.startAuthorization(
            AuthRequestDto(
                access = AuthAccessDto(validUntil),
                aspsp = AspspDto(name = ASPSP_NAME, country = ASPSP_COUNTRY),
                state = state,
                redirectUrl = REDIRECT_URL,
                psuType = PSU_TYPE,
            )
        )
        _phase.value = AuthPhase.AwaitingBankAuthorization
        response.url
    } catch (e: Exception) {
        _phase.value = AuthPhase.Error(e.message ?: "Could not start authorization")
        null
    }

    /**
     * Completes authorization from the redirect: validates `state`, exchanges the
     * `code` for a session, persists it plus the consent window, and kicks off
     * the deep-history backfill.
     */
    suspend fun completeAuthorization(code: String, returnedState: String?) {
        try {
            _phase.value = AuthPhase.ExchangingCode
            val expectedState = preferencesStore.pendingAuthState()
            if (expectedState != null && returnedState != null && expectedState != returnedState) {
                _phase.value = AuthPhase.Error("Authorization state mismatch - ignored")
                return
            }
            val session = api.createSession(CreateSessionRequestDto(code))
            credentialsStore.saveSessionId(session.sessionId)
            session.access?.validUntil?.let { preferencesStore.setConsentValidUntil(it) }
            preferencesStore.clearPendingAuthState()
            BackfillWorker.enqueue(workManager)
            _phase.value = AuthPhase.Authorized
        } catch (e: Exception) {
            _phase.value = AuthPhase.Error(e.message ?: "Could not complete authorization")
        }
    }

    private companion object {
        const val ASPSP_NAME = "Abanca"
        const val ASPSP_COUNTRY = "PT"
        const val PSU_TYPE = "personal"
        const val REDIRECT_URL = "https://luisperestrelo.github.io/goblin/auth-callback"

        // ABANCA's verified maximum consent validity (docs/PLAN.md section 5).
        const val CONSENT_VALIDITY_DAYS = 180L

        // e.g. 2027-01-05T18:00:00+00:00 - matches the explorer-verified format
        // (literal +00:00 offset via 'xxx', never the 'Z' shorthand).
        val VALID_UNTIL_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")
    }
}
