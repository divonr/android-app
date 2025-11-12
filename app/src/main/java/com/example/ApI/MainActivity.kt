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
import com.example.ApI.ui.screen.ChildLockScreen
import com.example.ApI.ui.screen.IntegrationsScreen
import com.example.ApI.ui.theme.ApITheme
import com.example.ApI.ui.theme.Background

class MainActivity : ComponentActivity() {
    // State to trigger recomposition when a new intent arrives
    private val intentState = mutableStateOf(0)

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
                        sharedIntent = intent,
                        activity = this,
                        intentTrigger = intentState.value
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        // Increment the state to trigger recomposition and LaunchedEffect
        intentState.value++
    }
}

@Composable
fun LLMChatApp(sharedIntent: Intent? = null, activity: ComponentActivity? = null, intentTrigger: Int = 0) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { DataRepository(context) }
    val viewModel: ChatViewModel = viewModel { ChatViewModel(repository, context, sharedIntent) }

    val currentScreen by viewModel.currentScreen.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val appSettings by viewModel.appSettings.collectAsState()

    // Initialize GitHub tools if already connected on app start
    LaunchedEffect(Unit) {
        viewModel.initializeGitHubToolsIfConnected()
    }

    // Handle GitHub OAuth callback
    // Key on intentTrigger so this runs whenever onNewIntent is called
    LaunchedEffect(intentTrigger) {
        activity?.intent?.let { intent ->
            val authCode = intent.getStringExtra(GitHubOAuthCallbackActivity.EXTRA_AUTH_CODE)
            val authState = intent.getStringExtra(GitHubOAuthCallbackActivity.EXTRA_AUTH_STATE)
            val error = intent.getStringExtra(GitHubOAuthCallbackActivity.EXTRA_ERROR)

            if (error != null) {
                viewModel.showSnackbar("GitHub connection error: $error")
                // Clear the intent extras
                intent.removeExtra(GitHubOAuthCallbackActivity.EXTRA_ERROR)
            } else if (authCode != null && authState != null) {
                viewModel.handleGitHubCallback(authCode, authState)
                // Clear the intent extras to prevent re-processing
                intent.removeExtra(GitHubOAuthCallbackActivity.EXTRA_AUTH_CODE)
                intent.removeExtra(GitHubOAuthCallbackActivity.EXTRA_AUTH_STATE)
            }
        }
    }

    // Check for child lock on app start
    val isChildLockActive = viewModel.isChildLockActive()
    val effectiveScreen = if (isChildLockActive && currentScreen != Screen.ChildLock) {
        Screen.ChildLock
    } else if (!isChildLockActive && currentScreen == Screen.ChildLock) {
        Screen.ChatHistory
    } else {
        currentScreen
    }

    // Handle system back button: navigate back to previous screen, exit on main screen or child lock
    BackHandler(enabled = true) {
        when {
            effectiveScreen is Screen.ChildLock -> {
                // Exit app when on child lock screen
                (context as? Activity)?.finish()
            }
            effectiveScreen is Screen.Integrations -> {
                // Go back to UserSettings from Integrations
                viewModel.navigateToScreen(Screen.UserSettings)
            }
            effectiveScreen !is Screen.ChatHistory -> {
                viewModel.navigateToScreen(Screen.ChatHistory)
            }
            else -> {
                (context as? Activity)?.finish()
            }
        }
    }

    when (effectiveScreen) {
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
        is Screen.ChildLock -> {
            ChildLockScreen(
                viewModel = viewModel
            )
        }
        is Screen.Integrations -> {
            IntegrationsScreen(
                viewModel = viewModel,
                appSettings = appSettings,
                onBackClick = { viewModel.navigateToScreen(Screen.UserSettings) }
            )
        }
    }
}