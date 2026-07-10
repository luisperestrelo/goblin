package com.luisperestrelo.goblin.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.luisperestrelo.goblin.auth.AuthManager
import com.luisperestrelo.goblin.auth.AuthPhase
import com.luisperestrelo.goblin.data.credentials.Credentials
import com.luisperestrelo.goblin.data.credentials.CredentialsStore
import com.luisperestrelo.goblin.data.prefs.PreferencesStore
import com.luisperestrelo.goblin.data.db.AccountDao
import com.luisperestrelo.goblin.data.db.AccountEntity
import com.luisperestrelo.goblin.data.db.BalanceSnapshotDao
import com.luisperestrelo.goblin.data.db.BalanceSnapshotEntity
import com.luisperestrelo.goblin.data.db.SyncLogDao
import com.luisperestrelo.goblin.data.db.SyncLogEntity
import com.luisperestrelo.goblin.data.db.TransactionDao
import com.luisperestrelo.goblin.data.db.TransactionEntity
import com.luisperestrelo.goblin.data.repo.SyncRepository
import com.luisperestrelo.goblin.widget.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DebugSetupState(
    val credentialsConfigured: Boolean = false,
    val hasSessionId: Boolean = false,
    val pendingPemLoaded: Boolean = false,
    val statusMessage: String? = null,
    val syncing: Boolean = false,
)

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val credentialsStore: CredentialsStore,
    private val syncRepository: SyncRepository,
    private val authManager: AuthManager,
    private val preferencesStore: PreferencesStore,
    private val widgetUpdater: WidgetUpdater,
    accountDao: AccountDao,
    transactionDao: TransactionDao,
    balanceSnapshotDao: BalanceSnapshotDao,
    syncLogDao: SyncLogDao,
) : ViewModel() {

    private val _setupState = MutableStateFlow(initialSetupState())
    val setupState: StateFlow<DebugSetupState> = _setupState.asStateFlow()

    /** Live authorization phase (shared with the App Link redirect handler). */
    val authPhase: StateFlow<AuthPhase> = authManager.phase

    val consentValidUntil: StateFlow<String?> =
        preferencesStore.consentValidUntil.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val primaryIban: StateFlow<String?> =
        preferencesStore.primaryIban.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** One-shot SCA URL to open in a Custom Tab; cleared once launched. */
    private val _authUrlToOpen = MutableStateFlow<String?>(null)
    val authUrlToOpen: StateFlow<String?> = _authUrlToOpen.asStateFlow()

    val accounts: StateFlow<List<AccountEntity>> =
        accountDao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val latestBalances: StateFlow<List<BalanceSnapshotEntity>> =
        balanceSnapshotDao.observeLatestPerAccount()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val recentTransactions: StateFlow<List<TransactionEntity>> =
        transactionDao.observeMostRecent(15)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val transactionCount: StateFlow<Int> =
        transactionDao.observeCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val lastSync: StateFlow<SyncLogEntity?> =
        syncLogDao.observeLast().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var pendingPem: String? = null

    private fun initialSetupState(): DebugSetupState {
        val credentials = credentialsStore.credentials()
        return DebugSetupState(
            credentialsConfigured = credentials != null,
            hasSessionId = credentials?.sessionId != null,
        )
    }

    fun onPemPicked(pemText: String) {
        if (!pemText.contains("BEGIN PRIVATE KEY")) {
            _setupState.value = _setupState.value.copy(statusMessage = "That file does not look like a PKCS#8 private key")
            return
        }
        pendingPem = pemText
        _setupState.value = _setupState.value.copy(
            pendingPemLoaded = true,
            statusMessage = "Key loaded (${pemText.length} chars), not saved yet",
        )
    }

    fun saveCredentials(applicationId: String, sessionId: String) {
        val pem = pendingPem ?: credentialsStore.credentials()?.privateKeyPem
        if (pem == null) {
            _setupState.value = _setupState.value.copy(statusMessage = "Pick the .pem file first")
            return
        }
        if (applicationId.isBlank()) {
            _setupState.value = _setupState.value.copy(statusMessage = "Application id is required")
            return
        }
        credentialsStore.save(
            Credentials(
                applicationId = applicationId.trim(),
                privateKeyPem = pem,
                sessionId = sessionId.trim().ifBlank { null },
            )
        )
        _setupState.value = _setupState.value.copy(
            credentialsConfigured = true,
            hasSessionId = sessionId.isNotBlank(),
            statusMessage = "Credentials saved",
        )
    }

    /** Requests the SCA URL and exposes it for the screen to open in a Custom Tab. */
    fun authorize() {
        viewModelScope.launch {
            _authUrlToOpen.value = authManager.beginAuthorization()
        }
    }

    fun onAuthUrlConsumed() {
        _authUrlToOpen.value = null
    }

    /**
     * Fallback for when the App Link redirect doesn't hand control back to the
     * app automatically: the callback page shows the code, paste it here. State
     * can't be checked on this path, so it's passed as null.
     */
    fun completeWithPastedCode(code: String) {
        if (code.isBlank()) return
        viewModelScope.launch { authManager.completeAuthorization(code.trim(), returnedState = null) }
    }

    /** Pin which account the widget/app focuses on, then refresh the widget. */
    fun setPrimaryAccount(iban: String) {
        viewModelScope.launch {
            preferencesStore.setPrimaryIban(iban)
            widgetUpdater.update()
        }
    }

    fun syncNow() {
        _setupState.value = _setupState.value.copy(syncing = true, statusMessage = "Syncing...")
        viewModelScope.launch {
            try {
                val summary = syncRepository.syncNow()
                widgetUpdater.update()
                _setupState.value = _setupState.value.copy(
                    syncing = false,
                    statusMessage = "Synced ${summary.accountCount} accounts, ${summary.fetchedTransactionCount} transactions fetched",
                )
            } catch (e: Exception) {
                _setupState.value = _setupState.value.copy(
                    syncing = false,
                    statusMessage = "Sync failed: ${e.message}",
                )
            }
        }
    }
}
