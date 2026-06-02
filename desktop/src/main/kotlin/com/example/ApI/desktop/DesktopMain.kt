package com.example.ApI.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.example.ApI.data.model.*
import com.example.ApI.ui.ChatViewModel
import com.example.ApI.ui.screen.*
import com.example.ApI.ui.theme.ApITheme
import java.io.File

fun main() = application {
    val appDir = File(System.getProperty("user.home"), ".llm-api-desktop")
    appDir.mkdirs()

    val repository = DesktopRepository(appDir)
    val context = DesktopContext(File(appDir, "files").apply { mkdirs() })
    val viewModel = remember { ChatViewModel(repository, context) }

    val windowState = rememberWindowState(size = DpSize(1280.dp, 800.dp))

    Window(
        onCloseRequest = {
            viewModel.onCleared()
            exitApplication()
        },
        title = "LLM API Desktop",
        state = windowState
    ) {
        ApITheme {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AppContent(viewModel = viewModel, repository = repository)
            }
        }
    }
}

@Composable
fun AppContent(
    viewModel: ChatViewModel,
    repository: DesktopRepository
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentScreen by viewModel.currentScreen.collectAsState()
    val appSettings by viewModel.appSettings.collectAsState()

    when (val screen = currentScreen) {
        is Screen.Welcome -> {
            WelcomeScreen(
                onNavigateToApiKeys = { viewModel.navigateToScreen(Screen.ApiKeys) },
                onNavigateToMain = {
                    viewModel.updateSkipWelcomeScreen(true)
                    viewModel.navigateToScreen(Screen.ChatHistory)
                },
                onSkipWelcomeChanged = { skip -> viewModel.updateSkipWelcomeScreen(skip) },
                repository = repository,
                currentUser = appSettings.current_user,
                providers = uiState.availableProviders
            )
        }

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
                onBackClick = { viewModel.navigateToScreen(Screen.ChatHistory) },
                onSkipWelcomeChanged = { skip -> viewModel.updateSkipWelcomeScreen(skip) },
                onProvidersChanged = { viewModel.refreshAvailableProviders() }
            )
        }

        is Screen.UserSettings -> {
            UserSettingsScreen(
                viewModel = viewModel,
                appSettings = appSettings,
                onBackClick = { viewModel.navigateToScreen(Screen.ChatHistory) }
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
                onBackClick = { viewModel.navigateToScreen(Screen.ChatHistory) }
            )
        }

        is Screen.Logs -> {
            LogsScreen(
                onBackClick = { viewModel.navigateToScreen(Screen.ChatHistory) }
            )
        }

        is Screen.Skills -> {
            SkillsScreen(
                viewModel = viewModel,
                onBackClick = { viewModel.navigateToScreen(Screen.ChatHistory) },
                onEditSkill = { skillDir -> viewModel.navigateToScreen(Screen.SkillEditor(skillDir)) }
            )
        }

        is Screen.SkillEditor -> {
            SkillEditorScreen(
                viewModel = viewModel,
                skillDirectoryName = screen.skillDirectoryName,
                onBackClick = { viewModel.navigateToScreen(Screen.Skills) }
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
