package com.example.ApI.ui.managers.provider

import android.widget.Toast
import com.example.ApI.data.model.*
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages AI provider and model selection.
 * Handles provider switching, model selection, and model list refreshing.
 * Extracted from ChatViewModel to reduce complexity.
 */
class ModelSelectionManager(
    private val deps: ManagerDependencies,
    private val updateAppSettings: (AppSettings) -> Unit
) {

    /**
     * Select a new AI provider.
     * Updates settings, switches to first model, and handles web search support.
     */
    fun selectProvider(provider: Provider) {
        val firstModel = provider.models.firstOrNull()?.name ?: "Unknown Model"

        val updatedSettings = deps.appSettings.value.copy(
            selected_provider = provider.provider,
            selected_model = firstModel
        )

        deps.repository.saveAppSettings(updatedSettings)
        updateAppSettings(updatedSettings)

        val webSearchSupport = getWebSearchSupport(provider.provider, firstModel)
        val webSearchEnabled = when (webSearchSupport) {
            WebSearchSupport.REQUIRED -> true
            WebSearchSupport.OPTIONAL -> deps.uiState.value.webSearchEnabled
            WebSearchSupport.UNSUPPORTED -> false
        }

        deps.updateUiState(
            deps.uiState.value.copy(
                currentProvider = provider,
                currentModel = firstModel,
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
        val newSettings = deps.appSettings.value.copy(selected_model = modelName)
        deps.repository.saveAppSettings(newSettings)
        updateAppSettings(newSettings)

        val webSearchSupport = getWebSearchSupport(deps.uiState.value.currentProvider?.provider ?: "", modelName)
        val webSearchEnabled = when (webSearchSupport) {
            WebSearchSupport.REQUIRED -> true
            WebSearchSupport.OPTIONAL -> deps.uiState.value.webSearchEnabled
            WebSearchSupport.UNSUPPORTED -> false
        }

        deps.updateUiState(
            deps.uiState.value.copy(
                currentModel = modelName,
                showModelSelector = false,
                webSearchSupport = webSearchSupport,
                webSearchEnabled = webSearchEnabled
            )
        )
    }

    /**
     * Select a model with explicit provider.
     * Used when selecting from the model dialog with provider tabs.
     * This ensures we use the exact provider shown in the tab, not a guess based on model name.
     */
    fun selectModelWithProvider(provider: Provider, modelName: String) {
        val updatedSettings = deps.appSettings.value.copy(
            selected_provider = provider.provider,
            selected_model = modelName
        )

        deps.repository.saveAppSettings(updatedSettings)
        updateAppSettings(updatedSettings)

        val webSearchSupport = getWebSearchSupport(provider.provider, modelName)
        val webSearchEnabled = when (webSearchSupport) {
            WebSearchSupport.REQUIRED -> true
            WebSearchSupport.OPTIONAL -> deps.uiState.value.webSearchEnabled
            WebSearchSupport.UNSUPPORTED -> false
        }

        deps.updateUiState(
            deps.uiState.value.copy(
                currentProvider = provider,
                currentModel = modelName,
                showModelSelector = false,
                webSearchSupport = webSearchSupport,
                webSearchEnabled = webSearchEnabled
            )
        )
    }

    /**
     * Show the model selection dialog.
     */
    fun showModelSelector() {
        deps.updateUiState(deps.uiState.value.copy(showModelSelector = true))
    }

    /**
     * Hide the model selection dialog.
     */
    fun hideModelSelector() {
        deps.updateUiState(deps.uiState.value.copy(showModelSelector = false))
    }

    /**
     * Toggle the starred status of a model.
     * If the model is starred, remove it from favorites.
     * If not starred, add it to favorites.
     */
    fun toggleStarredModel(providerKey: String, modelName: String) {
        val currentSettings = deps.appSettings.value
        val starred = StarredModel(provider = providerKey, modelName = modelName)

        val newStarredModels = if (currentSettings.starredModels.any {
                it.provider == providerKey && it.modelName == modelName
            }) {
            // Remove from starred
            currentSettings.starredModels.filter {
                !(it.provider == providerKey && it.modelName == modelName)
            }
        } else {
            // Add to starred
            currentSettings.starredModels + starred
        }

        val updatedSettings = currentSettings.copy(starredModels = newStarredModels)
        deps.repository.saveAppSettings(updatedSettings)
        updateAppSettings(updatedSettings)
    }

    /**
     * Force refresh the models list from providers.
     * Fetches latest models from configured providers and updates UI.
     */
    fun refreshModels() {
        deps.scope.launch {
            try {
                // Show loading message
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        deps.context,
                        "מעדכן את רשימת המודלים הזמינים...",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                android.util.Log.d("ProviderManager", "Starting forceRefreshModels()")
                val (success, errorMessage) = deps.repository.forceRefreshModels()
                android.util.Log.d("ProviderManager", "forceRefreshModels() returned: success=$success, error=$errorMessage")

                if (success) {
                    // Reload providers with updated models
                    val settings = deps.repository.loadAppSettings()
                    val allProviders = deps.repository.loadProviders()
                    val activeApiKeyProviders = deps.repository.loadApiKeys(settings.current_user)
                        .filter { it.isActive }
                        .map { it.provider }
                    val providers = allProviders.filter { provider ->
                        activeApiKeyProviders.contains(provider.provider)
                    }

                    // Update UI state with refreshed providers
                    deps.updateUiState(deps.uiState.value.copy(availableProviders = providers))

                    // Update current provider with refreshed models
                    val currentProvider = providers.find { it.provider == deps.uiState.value.currentProvider?.provider }
                    if (currentProvider != null) {
                        deps.updateUiState(deps.uiState.value.copy(currentProvider = currentProvider))
                    }

                    // Show success message
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            deps.context,
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
                            deps.context,
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
                        deps.context,
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
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user

            // Reinitialize custom providers in LLMApiService cache
            deps.repository.initializeCustomProviders(currentUser)
            deps.repository.initializeFullCustomProviders(currentUser)

            val allProviders = deps.repository.loadProviders()
            val activeApiKeyProviders = deps.repository.loadApiKeys(currentUser)
                .filter { it.isActive }
                .map { it.provider }
            val filteredProviders = allProviders.filter { provider ->
                activeApiKeyProviders.contains(provider.provider)
            }

            // Check if current provider is still available
            val currentProvider = deps.uiState.value.currentProvider
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
                deps.uiState.value.currentModel
            }

            deps.updateUiState(
                deps.uiState.value.copy(
                    availableProviders = filteredProviders,
                    currentProvider = newCurrentProvider,
                    currentModel = newCurrentModel
                )
            )

            // Update app settings if provider/model changed
            if (newCurrentProvider?.provider != deps.appSettings.value.selected_provider ||
                newCurrentModel != deps.appSettings.value.selected_model) {
                val updatedSettings = deps.appSettings.value.copy(
                    selected_provider = newCurrentProvider?.provider ?: "",
                    selected_model = newCurrentModel
                )
                deps.repository.saveAppSettings(updatedSettings)
                updateAppSettings(updatedSettings)
            }
        }
    }

    /**
     * Determine web search support for a given provider and model.
     * Based on providers.json specifications.
     */
    fun getWebSearchSupport(providerName: String, modelName: String): WebSearchSupport {
        // Try to find the model object to check its configuration
        val providers = deps.uiState.value.availableProviders
        val provider = providers.find { it.provider.equals(providerName, ignoreCase = true) }
        val model = provider?.models?.find { it.name == modelName }

        // If model has explicit web search configuration, use it
        if (model?.webSearch != null) {
            return when (model.webSearch!!.lowercase()) {
                "required" -> WebSearchSupport.REQUIRED
                "optional" -> WebSearchSupport.OPTIONAL
                "unsupported" -> WebSearchSupport.UNSUPPORTED
                else -> WebSearchSupport.UNSUPPORTED
            }
        }

        // Fallback to hardcoded logic if not specified in model
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
                // All Poe models now support optional web search
                WebSearchSupport.OPTIONAL
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
