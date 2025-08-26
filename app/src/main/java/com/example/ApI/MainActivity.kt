package com.example.ApI

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ApI.data.model.*
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.ui.ChatViewModel
import com.example.ApI.ui.screen.*
import com.example.ApI.ui.theme.LLMApiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LLMApiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    LLMChatApp(
                        sharedIntent = intent
                    )
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@Composable
fun LLMChatApp(sharedIntent: Intent? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { DataRepository(context) }
    val viewModel: ChatViewModel = viewModel { ChatViewModel(repository, context, sharedIntent) }
    
    val currentScreen by viewModel.currentScreen.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val appSettings by viewModel.appSettings.collectAsState()

    when (currentScreen) {
        is Screen.Chat -> {
            ChatScreen(
                viewModel = viewModel,
                uiState = uiState
            )
        }
        is Screen.ApiKeys -> {
            ApiKeysScreen(
                repository = repository,
                currentUser = appSettings.current_user,
                providers = uiState.availableProviders,
                onBackClick = { viewModel.navigateToScreen(Screen.Chat) }
            )
        }
        is Screen.UserSettings -> {
            UserSettingsScreen(
                viewModel = viewModel,
                appSettings = appSettings,
                onBackClick = { viewModel.navigateToScreen(Screen.Chat) }
            )
        }
    }
}