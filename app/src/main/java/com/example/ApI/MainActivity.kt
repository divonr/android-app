package com.example.ApI

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import android.app.Activity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ApI.data.model.*
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.ui.ChatViewModel
import com.example.ApI.ui.screen.ApiKeysScreen
import com.example.ApI.ui.screen.ChatHistoryScreen
import com.example.ApI.ui.screen.GroupScreen
import com.example.ApI.ui.screen.ChatScreen
import com.example.ApI.ui.screen.UserSettingsScreen
import com.example.ApI.ui.theme.ApITheme
import com.example.ApI.ui.theme.Background

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(
                android.graphics.Color.parseColor("#0D0E14")
            ),
            navigationBarStyle = SystemBarStyle.dark(
                android.graphics.Color.parseColor("#0D0E14")
            )
        )
        setContent {
            ApITheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Background
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

    // Handle system back button: navigate back to previous screen, exit on main screen
    BackHandler(enabled = true) {
        if (currentScreen !is Screen.ChatHistory) {
            viewModel.navigateToScreen(Screen.ChatHistory)
        } else {
            (context as? Activity)?.finish()
        }
    }

    when (currentScreen) {
        is Screen.ChatHistory -> {
            ChatHistoryScreen(
                viewModel = viewModel,
                uiState = uiState
            )
        }
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
                onBackClick = { viewModel.navigateToScreen(Screen.ChatHistory) }
            )
        }
        is Screen.UserSettings -> {
            UserSettingsScreen(
                viewModel = viewModel,
                appSettings = appSettings,
                onBackClick = { viewModel.navigateToScreen(Screen.ChatHistory) }
            )
        }
        is Screen.Group -> {
            GroupScreen(
                viewModel = viewModel,
                uiState = uiState
            )
        }
    }
}