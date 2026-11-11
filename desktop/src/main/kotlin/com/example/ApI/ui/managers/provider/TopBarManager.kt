package com.example.ApI.ui.managers.provider

import com.example.ApI.data.model.*
import com.example.ApI.ui.managers.ManagerDependencies

/**
 * Manages top bar controls: temperature, thinking budget, and text direction.
 * Desktop adaptation: replaces Toast with snackbar messages.
 */
class TopBarManager(
    private val deps: ManagerDependencies
) {

    fun onTemperatureButtonClick() {
        val tempConfig = deps.uiState.value.getTemperatureConfig()
        if (tempConfig == null) {
            deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "This provider does not support temperature control"))
            return
        }
        deps.updateUiState(deps.uiState.value.copy(showTemperaturePopup = !deps.uiState.value.showTemperaturePopup))
    }

    fun hideTemperaturePopup() {
        deps.updateUiState(deps.uiState.value.copy(showTemperaturePopup = false))
    }

    fun setTemperatureValue(value: Float?) {
        deps.updateUiState(deps.uiState.value.copy(temperatureValue = value))
    }

    fun resetTemperatureToDefault() {
        val tempConfig = deps.uiState.value.getTemperatureConfig()
        deps.updateUiState(deps.uiState.value.copy(temperatureValue = tempConfig?.default, showTemperaturePopup = false))
    }

    fun onThinkingBudgetButtonClick() {
        val budgetType = deps.uiState.value.getThinkingBudgetType()
        when (budgetType) {
            is ThinkingBudgetType.NotSupported -> {
                deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "This model does not support thinking mode"))
            }
            is ThinkingBudgetType.InDevelopment -> {
                deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "Thinking support for this model is in development"))
            }
            is ThinkingBudgetType.Discrete, is ThinkingBudgetType.Continuous -> {
                deps.updateUiState(deps.uiState.value.copy(showThinkingBudgetPopup = !deps.uiState.value.showThinkingBudgetPopup))
                if (deps.uiState.value.thinkingBudgetValue == ThinkingBudgetValue.None) {
                    val provider = deps.uiState.value.currentProvider?.provider ?: return
                    val modelConfig = deps.uiState.value.currentProvider?.models?.find { it.name == deps.uiState.value.currentModel }?.thinkingConfig
                    val defaultValue = ThinkingBudgetConfig.getDefaultValue(provider, deps.uiState.value.currentModel, modelConfig)
                    deps.updateUiState(deps.uiState.value.copy(thinkingBudgetValue = defaultValue))
                }
            }
        }
    }

    fun showThinkingBudgetPopup() {
        deps.updateUiState(deps.uiState.value.copy(showThinkingBudgetPopup = true))
    }

    fun hideThinkingBudgetPopup() {
        deps.updateUiState(deps.uiState.value.copy(showThinkingBudgetPopup = false))
    }

    fun setThinkingBudgetValue(value: ThinkingBudgetValue) {
        deps.updateUiState(deps.uiState.value.copy(thinkingBudgetValue = value))
    }

    fun setThinkingEffort(level: String) {
        deps.updateUiState(deps.uiState.value.copy(thinkingBudgetValue = ThinkingBudgetValue.Effort(level)))
    }

    fun setThinkingTokenBudget(tokens: Int) {
        deps.updateUiState(deps.uiState.value.copy(thinkingBudgetValue = ThinkingBudgetValue.Tokens(tokens)))
    }

    fun resetThinkingBudgetToDefault() {
        val provider = deps.uiState.value.currentProvider?.provider
        val model = deps.uiState.value.currentModel
        if (provider != null) {
            val modelConfig = deps.uiState.value.currentProvider?.models?.find { it.name == model }?.thinkingConfig
            val defaultValue = ThinkingBudgetConfig.getDefaultValue(provider, model, modelConfig)
            deps.updateUiState(deps.uiState.value.copy(thinkingBudgetValue = defaultValue, showThinkingBudgetPopup = false))
        }
    }

    fun toggleTextDirection() {
        val currentMode = deps.uiState.value.textDirectionMode
        val nextMode = when (currentMode) {
            TextDirectionMode.AUTO -> TextDirectionMode.RTL
            TextDirectionMode.RTL -> TextDirectionMode.LTR
            TextDirectionMode.LTR -> TextDirectionMode.AUTO
        }
        deps.updateUiState(deps.uiState.value.copy(textDirectionMode = nextMode))
    }

    fun setTextDirectionMode(mode: TextDirectionMode) {
        deps.updateUiState(deps.uiState.value.copy(textDirectionMode = mode))
    }
}
