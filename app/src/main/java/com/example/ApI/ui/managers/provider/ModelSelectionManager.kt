package com.example.ApI.ui.managers.provider

import android.content.Context
import android.widget.Toast
import com.example.ApI.data.model.*
import com.example.ApI.data.repository.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages AI provider and model selection.
 * Handles provider switching, model selection, and model list refreshing.
 * Extracted from ChatViewModel to reduce complexity.
 */
class ModelSelectionManager(
    private val repository: DataRepository,
    private val context: Context,
    private val scope: CoroutineScope,
    private val appSettings: StateFlow<AppSettings>,
    private val uiState: StateFlow<ChatUiState>,
    private val updateAppSettings: (AppSettings) -> Unit,
    private val updateUiState: (ChatUiState) -> Unit
) {

    /**
     * Select a new AI provider.
     * Updates settings, switches to first model, and handles web search support.
     */
    fun selectProvider(provider: Provider) {
        val firstModel = provider.models.firstOrNull()?.name ?: "Unknown Model"

        val updatedSettings = appSettings.value.copy(
            selected_provider = provider.provider,
            selected_model = firstModel
        )

        repository.saveAppSettings(updatedSettings)
        updateAppSettings(updatedSettings)

        val webSearchSupport = getWebSearchSupport(provider.provider, firstModel)
        val webSearchEnabled = when (webSearchSupport) {
            WebSearchSupport.REQUIRED -> true
            WebSearchSupport.OPTIONAL -> uiState.value.webSearchEnabled
            WebSearchSupport.UNSUPPORTED -> false
        }

        updateUiState(
            uiState.value.copy(
                currentProvider = provider,
                currentModel = firstModel,
                showProviderSelector = false,
                webSearchSupport = webSearchSupport,
                webSearchEnabled = webSearchEnabled
            )
        )
    }

    /**
     * Select a different model within the current provider.
     * Updates settings and handles web search support.
     */
    fun selectModel(modelName: String) {
        val newSettings = appSettings.value.copy(selected_model = modelName)
        repository.saveAppSettings(newSettings)
        updateAppSettings(newSettings)

        val webSearchSupport = getWebSearchSupport(uiState.value.currentProvider?.provider ?: "", modelName)
        val webSearchEnabled = when (webSearchSupport) {
            WebSearchSupport.REQUIRED -> true
            WebSearchSupport.OPTIONAL -> uiState.value.webSearchEnabled
            WebSearchSupport.UNSUPPORTED -> false
        }

        updateUiState(
            uiState.value.copy(
                currentModel = modelName,
                showModelSelector = false,
                webSearchSupport = webSearchSupport,
                webSearchEnabled = webSearchEnabled
            )
        )
    }

    /**
     * Show the provider selection dialog.
     */
    fun showProviderSelector() {
        updateUiState(uiState.value.copy(showProviderSelector = true))
    }

    /**
     * Hide the provider selection dialog.
     */
    fun hideProviderSelector() {
        updateUiState(uiState.value.copy(showProviderSelector = false))
    }

    /**
     * Show the model selection dialog.
     */
    fun showModelSelector() {
        updateUiState(uiState.value.copy(showModelSelector = true))
    }

    /**
     * Hide the model selection dialog.
     */
    fun hideModelSelector() {
        updateUiState(uiState.value.copy(showModelSelector = false))
    }

    /**
     * Force refresh the models list from providers.
     * Fetches latest models from configured providers and updates UI.
     */
    fun refreshModels() {
        scope.launch {
            try {
                // Show loading message
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "מעדכן את רשימת המודלים הזמינים...",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                android.util.Log.d("ProviderManager", "Starting forceRefreshModels()")
                val (success, errorMessage) = repository.forceRefreshModels()
                android.util.Log.d("ProviderManager", "forceRefreshModels() returned: success=$success, error=$errorMessage")

                if (success) {
                    // Reload providers with updated models
                    val settings = repository.loadAppSettings()
                    val allProviders = repository.loadProviders()
                    val activeApiKeyProviders = repository.loadApiKeys(settings.current_user)
                        .filter { it.isActive }
                        .map { it.provider }
                    val providers = allProviders.filter { provider ->
                        activeApiKeyProviders.contains(provider.provider)
                    }

                    // Update UI state with refreshed providers
                    updateUiState(uiState.value.copy(availableProviders = providers))

                    // Update current provider with refreshed models
                    val currentProvider = providers.find { it.provider == uiState.value.currentProvider?.provider }
                    if (currentProvider != null) {
                        updateUiState(uiState.value.copy(currentProvider = currentProvider))
                    }

                    // Show success message
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "הרשימה עודכנה בהצלחה!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    // Show failure message with details
                    withContext(Dispatchers.Main) {
                        val message = if (errorMessage != null) {
                            "שגיאה בעדכון הרשימה: $errorMessage"
                        } else {
                            "שגיאה בעדכון הרשימה. אנא נסה שוב."
                        }
                        Toast.makeText(
                            context,
                            message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ProviderManager", "Failed to refresh models", e)
                // Show error message
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "שגיאה בעדכון הרשימה: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Refresh available providers based on active API keys.
     * Called when returning from API keys screen.
     */
    fun refreshAvailableProviders() {
        scope.launch {
            val currentUser = appSettings.value.current_user
            val allProviders = repository.loadProviders()
            val activeApiKeyProviders = repository.loadApiKeys(currentUser)
                .filter { it.isActive }
                .map { it.provider }
            val filteredProviders = allProviders.filter { provider ->
                activeApiKeyProviders.contains(provider.provider)
            }

            // Check if current provider is still available
            val currentProvider = uiState.value.currentProvider
            val newCurrentProvider = if (currentProvider != null &&
                activeApiKeyProviders.contains(currentProvider.provider)) {
                currentProvider
            } else {
                filteredProviders.firstOrNull()
            }

            // Update model if provider changed
            val newCurrentModel = if (newCurrentProvider?.provider != currentProvider?.provider) {
                newCurrentProvider?.models?.firstOrNull()?.name ?: ""
            } else {
                uiState.value.currentModel
            }

            updateUiState(
                uiState.value.copy(
                    availableProviders = filteredProviders,
                    currentProvider = newCurrentProvider,
                    currentModel = newCurrentModel
                )
            )

            // Update app settings if provider/model changed
            if (newCurrentProvider?.provider != appSettings.value.selected_provider ||
                newCurrentModel != appSettings.value.selected_model) {
                val updatedSettings = appSettings.value.copy(
                    selected_provider = newCurrentProvider?.provider ?: "",
                    selected_model = newCurrentModel
                )
                repository.saveAppSettings(updatedSettings)
                updateAppSettings(updatedSettings)
            }
        }
    }

    /**
     * Determine web search support for a given provider and model.
     * Based on providers.json specifications.
     */
    fun getWebSearchSupport(providerName: String, modelName: String): WebSearchSupport {
        return when (providerName.lowercase()) {
            "openai" -> {
                when (modelName) {
                    "gpt-5", "gpt-4.1", "gpt-4o", "o3", "o4-mini" -> WebSearchSupport.OPTIONAL
                    "o4-mini-deep-research" -> WebSearchSupport.REQUIRED
                    "o1", "o1-pro" -> WebSearchSupport.UNSUPPORTED
                    else -> WebSearchSupport.OPTIONAL
                }
            }
            "poe" -> {
                when (modelName) {
                    "Claude-Sonnet-4-Search" -> WebSearchSupport.REQUIRED
                    "Gemini-2.5-Pro" -> WebSearchSupport.REQUIRED
                    else -> WebSearchSupport.UNSUPPORTED
                }
            }
            "google" -> {
                when (modelName) {
                    "gemini-2.5-pro", "gemini-2.5-flash", "gemini-1.5-pro-latest", "gemini-1.5-flash-latest" -> WebSearchSupport.OPTIONAL
                    else -> WebSearchSupport.OPTIONAL
                }
            }
            "anthropic" -> {
                // All Claude models support web search as optional
                WebSearchSupport.OPTIONAL
            }
            "openrouter" -> {
                // OpenRouter web search depends on the underlying model
                WebSearchSupport.UNSUPPORTED
            }
            else -> WebSearchSupport.UNSUPPORTED
        }
    }
}
