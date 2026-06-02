package com.example.ApI.ui.managers.provider

import android.util.Log
import com.example.ApI.data.model.*
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages AI provider and model selection.
 * Desktop adaptation: replaces Toast with snackbar messages.
 */
class ModelSelectionManager(
    private val deps: ManagerDependencies,
    private val updateAppSettings: (AppSettings) -> Unit
) {

    fun selectProvider(provider: Provider) {
        val firstModel = provider.models.firstOrNull()?.name ?: "Unknown Model"
        val updatedSettings = deps.appSettings.value.copy(selected_provider = provider.provider, selected_model = firstModel)
        deps.repository.saveAppSettings(updatedSettings)
        updateAppSettings(updatedSettings)
        val webSearchSupport = getWebSearchSupport(provider.provider, firstModel)
        val webSearchEnabled = when (webSearchSupport) {
            WebSearchSupport.REQUIRED -> true
            WebSearchSupport.OPTIONAL -> deps.uiState.value.webSearchEnabled
            WebSearchSupport.UNSUPPORTED -> false
        }
        deps.updateUiState(deps.uiState.value.copy(
            currentProvider = provider, currentModel = firstModel,
            webSearchSupport = webSearchSupport, webSearchEnabled = webSearchEnabled
        ))
    }

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
        deps.updateUiState(deps.uiState.value.copy(
            currentModel = modelName, showModelSelector = false,
            webSearchSupport = webSearchSupport, webSearchEnabled = webSearchEnabled
        ))
    }

    fun selectModelWithProvider(provider: Provider, modelName: String) {
        val updatedSettings = deps.appSettings.value.copy(selected_provider = provider.provider, selected_model = modelName)
        deps.repository.saveAppSettings(updatedSettings)
        updateAppSettings(updatedSettings)
        val webSearchSupport = getWebSearchSupport(provider.provider, modelName)
        val webSearchEnabled = when (webSearchSupport) {
            WebSearchSupport.REQUIRED -> true
            WebSearchSupport.OPTIONAL -> deps.uiState.value.webSearchEnabled
            WebSearchSupport.UNSUPPORTED -> false
        }
        deps.updateUiState(deps.uiState.value.copy(
            currentProvider = provider, currentModel = modelName, showModelSelector = false,
            webSearchSupport = webSearchSupport, webSearchEnabled = webSearchEnabled
        ))
    }

    fun showModelSelector() {
        deps.updateUiState(deps.uiState.value.copy(showModelSelector = true))
    }

    fun hideModelSelector() {
        deps.updateUiState(deps.uiState.value.copy(showModelSelector = false))
    }

    fun toggleStarredModel(providerKey: String, modelName: String) {
        val currentSettings = deps.appSettings.value
        val starred = StarredModel(provider = providerKey, modelName = modelName)
        val newStarredModels = if (currentSettings.starredModels.any { it.provider == providerKey && it.modelName == modelName }) {
            currentSettings.starredModels.filter { !(it.provider == providerKey && it.modelName == modelName) }
        } else {
            currentSettings.starredModels + starred
        }
        val updatedSettings = currentSettings.copy(starredModels = newStarredModels)
        deps.repository.saveAppSettings(updatedSettings)
        updateAppSettings(updatedSettings)
    }

    fun refreshModels() {
        deps.scope.launch {
            try {
                deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "Refreshing model list..."))
                Log.d("ProviderManager", "Starting forceRefreshModels()")
                val (success, errorMessage) = deps.repository.forceRefreshModels()
                Log.d("ProviderManager", "forceRefreshModels() returned: success=$success, error=$errorMessage")

                if (success) {
                    val settings = deps.repository.loadAppSettings()
                    val allProviders = deps.repository.loadProviders()
                    val activeApiKeyProviders = deps.repository.loadApiKeys(settings.current_user).filter { it.isActive }.map { it.provider }
                    val providers = allProviders.filter { activeApiKeyProviders.contains(it.provider) }
                    deps.updateUiState(deps.uiState.value.copy(availableProviders = providers, snackbarMessage = "Model list updated!"))
                    val currentProvider = providers.find { it.provider == deps.uiState.value.currentProvider?.provider }
                    if (currentProvider != null) {
                        deps.updateUiState(deps.uiState.value.copy(currentProvider = currentProvider))
                    }
                } else {
                    val message = if (errorMessage != null) "Error updating list: $errorMessage" else "Error updating list. Please try again."
                    deps.updateUiState(deps.uiState.value.copy(snackbarMessage = message))
                }
            } catch (e: Exception) {
                Log.e("ProviderManager", "Failed to refresh models", e)
                deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "Error updating list: ${e.message}"))
            }
        }
    }

    fun refreshAvailableProviders() {
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user
            deps.repository.initializeCustomProviders(currentUser)
            deps.repository.initializeFullCustomProviders(currentUser)
            val allProviders = deps.repository.loadProviders()
            val activeApiKeyProviders = deps.repository.loadApiKeys(currentUser).filter { it.isActive }.map { it.provider }
            val filteredProviders = allProviders.filter { activeApiKeyProviders.contains(it.provider) }
            val currentProvider = deps.uiState.value.currentProvider
            val newCurrentProvider = if (currentProvider != null && activeApiKeyProviders.contains(currentProvider.provider)) currentProvider else filteredProviders.firstOrNull()
            val newCurrentModel = if (newCurrentProvider?.provider != currentProvider?.provider) newCurrentProvider?.models?.firstOrNull()?.name ?: "" else deps.uiState.value.currentModel
            deps.updateUiState(deps.uiState.value.copy(
                availableProviders = filteredProviders,
                currentProvider = newCurrentProvider,
                currentModel = newCurrentModel
            ))
            if (newCurrentProvider?.provider != deps.appSettings.value.selected_provider || newCurrentModel != deps.appSettings.value.selected_model) {
                val updatedSettings = deps.appSettings.value.copy(selected_provider = newCurrentProvider?.provider ?: "", selected_model = newCurrentModel)
                deps.repository.saveAppSettings(updatedSettings)
                updateAppSettings(updatedSettings)
            }
        }
    }

    fun getWebSearchSupport(providerName: String, modelName: String): WebSearchSupport {
        val providers = deps.uiState.value.availableProviders
        val provider = providers.find { it.provider.equals(providerName, ignoreCase = true) }
        val model = provider?.models?.find { it.name == modelName }
        if (model?.webSearch != null) {
            return when (model.webSearch!!.lowercase()) {
                "required" -> WebSearchSupport.REQUIRED
                "optional" -> WebSearchSupport.OPTIONAL
                "unsupported" -> WebSearchSupport.UNSUPPORTED
                else -> WebSearchSupport.UNSUPPORTED
            }
        }
        return when (providerName.lowercase()) {
            "openai" -> when (modelName) {
                "gpt-5", "gpt-4.1", "gpt-4o", "o3", "o4-mini" -> WebSearchSupport.OPTIONAL
                "o4-mini-deep-research" -> WebSearchSupport.REQUIRED
                "o1", "o1-pro" -> WebSearchSupport.UNSUPPORTED
                else -> WebSearchSupport.OPTIONAL
            }
            "poe" -> WebSearchSupport.OPTIONAL
            "google" -> WebSearchSupport.OPTIONAL
            "anthropic" -> WebSearchSupport.OPTIONAL
            "openrouter" -> WebSearchSupport.UNSUPPORTED
            else -> WebSearchSupport.UNSUPPORTED
        }
    }
}
