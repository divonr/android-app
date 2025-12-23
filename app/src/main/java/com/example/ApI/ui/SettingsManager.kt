package com.example.ApI.ui

import android.content.Context
import android.widget.Toast
import com.example.ApI.data.model.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages application settings: temperature, thinking budget, and text direction.
 * Handles UI popups and value updates for model parameters.
 * Extracted from ChatViewModel to reduce complexity.
 */
class SettingsManager(
    private val context: Context,
    private val uiState: StateFlow<ChatUiState>,
    private val updateUiState: (ChatUiState) -> Unit
) {

    // ==================== Temperature Settings ====================

    /**
     * Handle temperature button click - toggles popup or shows unsupported message.
     */
    fun onTemperatureButtonClick() {
        val tempConfig = uiState.value.getTemperatureConfig()

        if (tempConfig == null) {
            // Temperature not supported for this provider
            Toast.makeText(context, "ספק זה אינו תומך בשליטה על טמפרטורה", Toast.LENGTH_SHORT).show()
            return
        }

        // Toggle popup visibility
        updateUiState(
            uiState.value.copy(
                showTemperaturePopup = !uiState.value.showTemperaturePopup
            )
        )
    }

    /**
     * Hide the temperature popup.
     */
    fun hideTemperaturePopup() {
        updateUiState(uiState.value.copy(showTemperaturePopup = false))
    }

    /**
     * Set the temperature value.
     * @param value The temperature value, or null to use API default
     */
    fun setTemperatureValue(value: Float?) {
        updateUiState(uiState.value.copy(temperatureValue = value))
    }

    /**
     * Reset temperature to model default when provider/model changes.
     */
    fun resetTemperatureToDefault() {
        val tempConfig = uiState.value.getTemperatureConfig()
        updateUiState(
            uiState.value.copy(
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
        val budgetType = uiState.value.getThinkingBudgetType()

        when (budgetType) {
            is ThinkingBudgetType.NotSupported -> {
                // Show toast that this model doesn't support thinking
                Toast.makeText(context, "מודל זה אינו תומך במצב חשיבה", Toast.LENGTH_SHORT).show()
            }
            is ThinkingBudgetType.InDevelopment -> {
                // Show toast that support is in development
                Toast.makeText(context, "נכון לעכשיו התמיכה בפרמטר למודל זה עדיין בפיתוח", Toast.LENGTH_SHORT).show()
            }
            is ThinkingBudgetType.Discrete, is ThinkingBudgetType.Continuous -> {
                // Toggle popup visibility
                updateUiState(
                    uiState.value.copy(
                        showThinkingBudgetPopup = !uiState.value.showThinkingBudgetPopup
                    )
                )

                // Initialize with default value if currently None
                if (uiState.value.thinkingBudgetValue == ThinkingBudgetValue.None) {
                    val provider = uiState.value.currentProvider?.provider ?: return
                    val modelConfig = uiState.value.currentProvider?.models
                        ?.find { it.name == uiState.value.currentModel }
                        ?.thinkingConfig
                    val defaultValue = ThinkingBudgetConfig.getDefaultValue(provider, uiState.value.currentModel, modelConfig)
                    updateUiState(uiState.value.copy(thinkingBudgetValue = defaultValue))
                }
            }
        }
    }

    /**
     * Show the thinking budget popup.
     */
    fun showThinkingBudgetPopup() {
        updateUiState(uiState.value.copy(showThinkingBudgetPopup = true))
    }

    /**
     * Hide the thinking budget popup.
     */
    fun hideThinkingBudgetPopup() {
        updateUiState(uiState.value.copy(showThinkingBudgetPopup = false))
    }

    /**
     * Set the thinking budget value.
     */
    fun setThinkingBudgetValue(value: ThinkingBudgetValue) {
        updateUiState(uiState.value.copy(thinkingBudgetValue = value))
    }

    /**
     * Set discrete thinking effort level.
     */
    fun setThinkingEffort(level: String) {
        updateUiState(
            uiState.value.copy(
                thinkingBudgetValue = ThinkingBudgetValue.Effort(level)
            )
        )
    }

    /**
     * Set continuous thinking token budget.
     */
    fun setThinkingTokenBudget(tokens: Int) {
        updateUiState(
            uiState.value.copy(
                thinkingBudgetValue = ThinkingBudgetValue.Tokens(tokens)
            )
        )
    }

    /**
     * Reset thinking budget to model default when provider/model changes.
     */
    fun resetThinkingBudgetToDefault() {
        val provider = uiState.value.currentProvider?.provider
        val model = uiState.value.currentModel

        if (provider != null) {
            val modelConfig = uiState.value.currentProvider?.models
                ?.find { it.name == model }
                ?.thinkingConfig
            val defaultValue = ThinkingBudgetConfig.getDefaultValue(provider, model, modelConfig)
            updateUiState(
                uiState.value.copy(
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
        val currentMode = uiState.value.textDirectionMode
        val nextMode = when (currentMode) {
            TextDirectionMode.AUTO -> TextDirectionMode.RTL
            TextDirectionMode.RTL -> TextDirectionMode.LTR
            TextDirectionMode.LTR -> TextDirectionMode.AUTO
        }
        updateUiState(uiState.value.copy(textDirectionMode = nextMode))
    }

    /**
     * Set a specific text direction mode.
     */
    fun setTextDirectionMode(mode: TextDirectionMode) {
        updateUiState(uiState.value.copy(textDirectionMode = mode))
    }
}
