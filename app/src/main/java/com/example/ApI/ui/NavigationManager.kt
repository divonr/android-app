package com.example.ApI.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.ChatUiState
import com.example.ApI.data.model.Screen
import com.example.ApI.data.repository.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.InputStream

/**
 * Manages screen navigation and chat history import/export.
 * Handles navigation between screens and bulk chat history operations.
 * Extracted from ChatViewModel to reduce complexity.
 */
class NavigationManager(
    private val repository: DataRepository,
    private val context: Context,
    private val scope: CoroutineScope,
    private val appSettings: StateFlow<AppSettings>,
    private val uiState: StateFlow<ChatUiState>,
    private val currentScreen: MutableStateFlow<Screen>,
    private val updateAppSettings: (AppSettings) -> Unit,
    private val updateUiState: (ChatUiState) -> Unit,
    private val refreshAvailableProviders: () -> Unit
) {

    /**
     * Navigate to a different screen.
     * Refreshes available providers when leaving API keys screen.
     */
    fun navigateToScreen(screen: Screen) {
        // Refresh available providers when navigating away from API keys screen
        // since user may have added/removed/toggled keys
        if (currentScreen.value == Screen.ApiKeys && screen != Screen.ApiKeys) {
            refreshAvailableProviders()
        }
        currentScreen.value = screen
    }

    /**
     * Update the skip welcome screen setting.
     */
    fun updateSkipWelcomeScreen(skip: Boolean) {
        val updatedSettings = appSettings.value.copy(skipWelcomeScreen = skip)
        repository.saveAppSettings(updatedSettings)
        updateAppSettings(updatedSettings)
    }

    /**
     * Export entire chat history to a file.
     * Shows a toast with the export path on success.
     */
    fun exportChatHistory() {
        scope.launch {
            val currentUser = appSettings.value.current_user
            val exportPath = repository.exportChatHistory(currentUser)

            if (exportPath != null) {
                Toast.makeText(
                    context,
                    "היסטוריית הצ'אט יוצאה בהצלחה ל: $exportPath",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Import chat history from a URI.
     * Replaces current chat history with imported data.
     */
    fun importChatHistoryFromUri(uri: Uri) {
        scope.launch {
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                inputStream?.use { stream ->
                    val data = stream.readBytes()
                    val currentUser = appSettings.value.current_user
                    repository.importChatHistoryJson(data, currentUser)
                    // Refresh UI state after import
                    val chatHistory = repository.loadChatHistory(currentUser)
                    val currentChat = chatHistory.chat_history.lastOrNull()
                    updateUiState(
                        uiState.value.copy(
                            chatHistory = chatHistory.chat_history,
                            groups = chatHistory.groups,
                            currentChat = currentChat
                        )
                    )
                    Toast.makeText(context, "היסטוריית הצ'אט יובאה בהצלחה", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "שגיאה בייבוא: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
