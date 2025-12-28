package com.example.ApI.ui.managers.provider

import android.widget.Toast
import com.example.ApI.data.model.*
import com.example.ApI.ui.managers.ManagerDependencies

/**
 * Manages top bar controls: temperature, thinking budget, and text direction.
 * Handles UI popups and value updates for model parameters.
 * Extracted from ChatViewModel to reduce complexity.
 */
class TopBarManager(
    private val deps: ManagerDependencies
) {

    // ==================== Temperature Settings ====================

    /**
     * Handle temperature button click - toggles popup or shows unsupported message.
     */
    fun onTemperatureButtonClick() {
        val tempConfig = deps.uiState.value.getTemperatureConfig()

        if (tempConfig == null) {
            // Temperature not supported for this provider
            Toast.makeText(deps.context, "ספק זה אינו תומך בשליטה על טמפרטורה", Toast.LENGTH_SHORT).show()
            return
        }

        // Toggle popup visibility
        deps.updateUiState(
            deps.uiState.value.copy(
                showTemperaturePopup = !deps.uiState.value.showTemperaturePopup
            )
        )
    }

    /**
     * Hide the temperature popup.
     */
    fun hideTemperaturePopup() {
        deps.updateUiState(deps.uiState.value.copy(showTemperaturePopup = false))
    }

    /**
     * Set the temperature value.
     * @param value The temperature value, or null to use API default
     */
    fun setTemperatureValue(value: Float?) {
        deps.updateUiState(deps.uiState.value.copy(temperatureValue = value))
    }

    /**
     * Reset temperature to model default when provider/model changes.
     */
    fun resetTemperatureToDefault() {
        val tempConfig = deps.uiState.value.getTemperatureConfig()
        deps.updateUiState(
            deps.uiState.value.copy(
                temperatureValue = tempConfig?.default,
                showTemperaturePopup = false
            )
        )
    }

    // ==================== Thinking Budget Settings ====================

    /**
     * Handle thinking budget button click - toggles popup or shows unsupported message.
     */
    fun onThinkingBudgetButtonClick() {
        val budgetType = deps.uiState.value.getThinkingBudgetType()

        when (budgetType) {
            is ThinkingBudgetType.NotSupported -> {
                // Show toast that this model doesn't support thinking
                Toast.makeText(deps.context, "מודל זה אינו תומך במצב חשיבה", Toast.LENGTH_SHORT).show()
            }
            is ThinkingBudgetType.InDevelopment -> {
                // Show toast that support is in development
                Toast.makeText(deps.context, "נכון לעכשיו התמיכה בפרמטר למודל זה עדיין בפיתוח", Toast.LENGTH_SHORT).show()
            }
            is ThinkingBudgetType.Discrete, is ThinkingBudgetType.Continuous -> {
                // Toggle popup visibility
                deps.updateUiState(
                    deps.uiState.value.copy(
                        showThinkingBudgetPopup = !deps.uiState.value.showThinkingBudgetPopup
                    )
                )

                // Initialize with default value if currently None
                if (deps.uiState.value.thinkingBudgetValue == ThinkingBudgetValue.None) {
                    val provider = deps.uiState.value.currentProvider?.provider ?: return
                    val modelConfig = deps.uiState.value.currentProvider?.models
                        ?.find { it.name == deps.uiState.value.currentModel }
                        ?.thinkingConfig
                    val defaultValue = ThinkingBudgetConfig.getDefaultValue(provider, deps.uiState.value.currentModel, modelConfig)
                    deps.updateUiState(deps.uiState.value.copy(thinkingBudgetValue = defaultValue))
                }
            }
        }
    }

    /**
     * Show the thinking budget popup.
     */
    fun showThinkingBudgetPopup() {
        deps.updateUiState(deps.uiState.value.copy(showThinkingBudgetPopup = true))
    }

    /**
     * Hide the thinking budget popup.
     */
    fun hideThinkingBudgetPopup() {
        deps.updateUiState(deps.uiState.value.copy(showThinkingBudgetPopup = false))
    }

    /**
     * Set the thinking budget value.
     */
    fun setThinkingBudgetValue(value: ThinkingBudgetValue) {
        deps.updateUiState(deps.uiState.value.copy(thinkingBudgetValue = value))
    }

    /**
     * Set discrete thinking effort level.
     */
    fun setThinkingEffort(level: String) {
        deps.updateUiState(
            deps.uiState.value.copy(
                thinkingBudgetValue = ThinkingBudgetValue.Effort(level)
            )
        )
    }

    /**
     * Set continuous thinking token budget.
     */
    fun setThinkingTokenBudget(tokens: Int) {
        deps.updateUiState(
            deps.uiState.value.copy(
                thinkingBudgetValue = ThinkingBudgetValue.Tokens(tokens)
            )
        )
    }

    /**
     * Reset thinking budget to model default when provider/model changes.
     */
    fun resetThinkingBudgetToDefault() {
        val provider = deps.uiState.value.currentProvider?.provider
        val model = deps.uiState.value.currentModel

        if (provider != null) {
            val modelConfig = deps.uiState.value.currentProvider?.models
                ?.find { it.name == model }
                ?.thinkingConfig
            val defaultValue = ThinkingBudgetConfig.getDefaultValue(provider, model, modelConfig)
            deps.updateUiState(
                deps.uiState.value.copy(
                    thinkingBudgetValue = defaultValue,
                    showThinkingBudgetPopup = false
                )
            )
        }
    }

    // ==================== Text Direction Settings ====================

    /**
     * Toggle between AUTO, RTL, and LTR text direction modes.
     */
    fun toggleTextDirection() {
        val currentMode = deps.uiState.value.textDirectionMode
        val nextMode = when (currentMode) {
            TextDirectionMode.AUTO -> TextDirectionMode.RTL
            TextDirectionMode.RTL -> TextDirectionMode.LTR
            TextDirectionMode.LTR -> TextDirectionMode.AUTO
        }
        deps.updateUiState(deps.uiState.value.copy(textDirectionMode = nextMode))
    }

    /**
     * Set a specific text direction mode.
     */
    fun setTextDirectionMode(mode: TextDirectionMode) {
        deps.updateUiState(deps.uiState.value.copy(textDirectionMode = mode))
    }
}
