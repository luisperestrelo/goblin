package com.luisperestrelo.goblin.ui.debug

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.luisperestrelo.goblin.auth.AuthPhase
import com.luisperestrelo.goblin.data.db.TransactionEntity
import com.luisperestrelo.goblin.domain.model.Money

/**
 * Phase 1 debug surface: configure credentials, trigger a manual sync, and
 * verify real data lands in Room. Replaced by the real dashboard in phase 4.
 */
@Composable
fun DebugScreen(modifier: Modifier = Modifier, viewModel: DebugViewModel = hiltViewModel()) {
    val setupState by viewModel.setupState.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val balances by viewModel.latestBalances.collectAsState()
    val recentTransactions by viewModel.recentTransactions.collectAsState()
    val transactionCount by viewModel.transactionCount.collectAsState()
    val authPhase by viewModel.authPhase.collectAsState()
    val consentValidUntil by viewModel.consentValidUntil.collectAsState()
    val authUrlToOpen by viewModel.authUrlToOpen.collectAsState()

    val context = LocalContext.current
    var applicationIdInput by remember { mutableStateOf("") }
    var sessionIdInput by remember { mutableStateOf("") }

    LaunchedEffect(authUrlToOpen) {
        authUrlToOpen?.let { url ->
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
            viewModel.onAuthUrlConsumed()
        }
    }

    val pemPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val pemText = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().decodeToString()
            }
            if (pemText != null) viewModel.onPemPicked(pemText)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Setup", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (setupState.credentialsConfigured) "Credentials configured" else "Credentials missing",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = applicationIdInput,
                        onValueChange = { applicationIdInput = it },
                        label = { Text("Application id") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = sessionIdInput,
                        onValueChange = { sessionIdInput = it },
                        label = { Text("Session id") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pemPicker.launch(arrayOf("*/*")) }) {
                            Text(if (setupState.pendingPemLoaded) "Key loaded" else "Pick .pem key")
                        }
                        Button(onClick = { viewModel.saveCredentials(applicationIdInput, sessionIdInput) }) {
                            Text("Save")
                        }
                    }
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Authorize", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Runs the real bank auth flow on device - no PC session needed.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = viewModel::authorize,
                        enabled = setupState.credentialsConfigured && !authPhase.isInProgress(),
                    ) {
                        Text("Authorize with ABANCA")
                    }
                    Text(authPhase.describe(), style = MaterialTheme.typography.bodySmall)
                    consentValidUntil?.let {
                        Text("Consent valid until $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sync", style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = viewModel::syncNow,
                        enabled = setupState.credentialsConfigured && !setupState.syncing,
                    ) {
                        Text(if (setupState.syncing) "Syncing..." else "Sync now")
                    }
                    setupState.statusMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("$transactionCount transactions in local database", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Balances", style = MaterialTheme.typography.titleMedium)
                    if (balances.isEmpty()) {
                        Text("No data yet", style = MaterialTheme.typography.bodySmall)
                    }
                    balances.forEach { snapshot ->
                        val iban = accounts.firstOrNull { it.uid == snapshot.accountUid }?.iban ?: snapshot.accountUid
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("...${iban.takeLast(4)}")
                            Text(
                                Money(snapshot.balanceCents, snapshot.currency).formatted(),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }

        item { Text("Recent transactions", style = MaterialTheme.typography.titleMedium) }
        items(recentTransactions, key = { "${it.accountUid}/${it.entryReference}" }) { transaction ->
            TransactionRow(transaction)
            HorizontalDivider()
        }
    }
}

@Composable
private fun TransactionRow(transaction: TransactionEntity) {
    val signedCents =
        if (transaction.creditDebitIndicator == "DBIT") -transaction.amountCents else transaction.amountCents
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                transaction.remittanceLines.firstOrNull() ?: "(no description)",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(transaction.bookingDate, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            Money(signedCents, transaction.currency).formatted(),
            fontWeight = FontWeight.SemiBold,
            color = if (signedCents < 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
        )
    }
}

private fun AuthPhase.isInProgress(): Boolean = when (this) {
    AuthPhase.RequestingAuthUrl, AuthPhase.AwaitingBankAuthorization, AuthPhase.ExchangingCode -> true
    AuthPhase.Idle, AuthPhase.Authorized, is AuthPhase.Error -> false
}

private fun AuthPhase.describe(): String = when (this) {
    AuthPhase.Idle -> "Not authorized on this device yet"
    AuthPhase.RequestingAuthUrl -> "Requesting authorization URL..."
    AuthPhase.AwaitingBankAuthorization -> "Waiting for bank authorization in the browser..."
    AuthPhase.ExchangingCode -> "Exchanging code for a session..."
    AuthPhase.Authorized -> "Authorized - backfilling 3 years of history"
    is AuthPhase.Error -> "Error: $message"
}
