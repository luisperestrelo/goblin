package com.luisperestrelo.goblin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.luisperestrelo.goblin.ui.debug.DebugScreen
import com.luisperestrelo.goblin.ui.theme.GoblinTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
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
    }
}
