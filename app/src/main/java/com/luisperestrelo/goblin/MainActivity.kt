package com.luisperestrelo.goblin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.luisperestrelo.goblin.auth.AuthManager
import com.luisperestrelo.goblin.ui.debug.DebugScreen
import com.luisperestrelo.goblin.ui.theme.GoblinTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var authManager: AuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GoblinTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DebugScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
        handleAuthRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthRedirect(intent)
    }

    /**
     * Captures the bank's post-SCA App Link redirect
     * (https://luisperestrelo.github.io/goblin/auth-callback?code=..&state=..)
     * and hands the code to AuthManager to exchange for a session.
     */
    private fun handleAuthRedirect(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        val code = data.getQueryParameter("code") ?: return
        val state = data.getQueryParameter("state")
        lifecycleScope.launch { authManager.completeAuthorization(code, state) }
    }
}
