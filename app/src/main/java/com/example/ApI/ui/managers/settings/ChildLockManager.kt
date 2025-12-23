package com.example.ApI.ui.managers.settings

import android.content.Context
import com.example.ApI.data.ParentalControlManager
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.ChatUiState
import com.example.ApI.data.model.ChildLockSettings
import com.example.ApI.data.repository.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * Manages child lock (parental controls) functionality.
 * Handles password verification, time-based locking, and settings management.
 * Extracted from ChatViewModel to reduce complexity.
 */
class ChildLockManager(
    private val repository: DataRepository,
    private val context: Context,
    private val scope: CoroutineScope,
    private val appSettings: StateFlow<AppSettings>,
    private val uiState: StateFlow<ChatUiState>,
    private val updateAppSettings: (AppSettings) -> Unit,
    private val updateUiState: (ChatUiState) -> Unit
) {

    /**
     * Setup child lock with password and time range
     * @param password Parent password for verification
     * @param startTime Lock start time (HH:mm format)
     * @param endTime Lock end time (HH:mm format)
     * @param deviceId Device ID for password encryption
     */
    fun setupChildLock(password: String, startTime: String, endTime: String, deviceId: String) {
        scope.launch {
            try {
                val parentalControlManager = ParentalControlManager(context)
                parentalControlManager.setParentalPassword(password, deviceId)

                // Update settings with child lock enabled
                val updatedSettings = appSettings.value.copy(
                    childLockSettings = ChildLockSettings(
                        enabled = true,
                        encryptedPassword = parentalControlManager.getEncryptedPassword(),
                        startTime = startTime,
                        endTime = endTime
                    )
                )

                repository.saveAppSettings(updatedSettings)
                updateAppSettings(updatedSettings)
            } catch (e: Exception) {
                updateUiState(
                    uiState.value.copy(
                        snackbarMessage = "שגיאה בהגדרת נעילת ילדים: ${e.message}"
                    )
                )
            }
        }
    }

    /**
     * Verify password and disable child lock if correct
     * @param password Password to verify
     * @param deviceId Device ID for password verification
     * @return True if password is correct and lock was disabled, false otherwise
     */
    fun verifyAndDisableChildLock(password: String, deviceId: String): Boolean {
        return try {
            val parentalControlManager = ParentalControlManager(context)
            val isValidPassword = parentalControlManager.verifyParentalPassword(password, deviceId)

            if (isValidPassword) {
                // Disable child lock
                val updatedSettings = appSettings.value.copy(
                    childLockSettings = ChildLockSettings(
                        enabled = false,
                        encryptedPassword = "",
                        startTime = "23:00",
                        endTime = "07:00"
                    )
                )

                repository.saveAppSettings(updatedSettings)
                updateAppSettings(updatedSettings)
                true
            } else {
                // Show error message
                updateUiState(
                    uiState.value.copy(
                        snackbarMessage = "סיסמה שגויה"
                    )
                )
                false
            }
        } catch (e: Exception) {
            updateUiState(
                uiState.value.copy(
                    snackbarMessage = "שגיאה בביטול נעילת ילדים: ${e.message}"
                )
            )
            false
        }
    }

    /**
     * Update child lock settings
     * @param enabled Whether child lock is enabled
     * @param password Encrypted password
     * @param startTime Lock start time
     * @param endTime Lock end time
     */
    fun updateChildLockSettings(enabled: Boolean, password: String, startTime: String, endTime: String) {
        val updatedSettings = appSettings.value.copy(
            childLockSettings = ChildLockSettings(
                enabled = enabled,
                encryptedPassword = password,
                startTime = startTime,
                endTime = endTime
            )
        )

        repository.saveAppSettings(updatedSettings)
        updateAppSettings(updatedSettings)
    }

    /**
     * Check if child lock is currently active based on current time
     * @return True if child lock is enabled and current time is within lock range
     */
    fun isChildLockActive(): Boolean {
        val settings = appSettings.value.childLockSettings
        if (!settings.enabled) return false

        return isCurrentTimeInLockRange(settings.startTime, settings.endTime)
    }

    /**
     * Check if current time falls within the lock time range
     * Handles overnight ranges (e.g., 23:00 to 07:00)
     * @param startTime Lock start time (HH:mm format)
     * @param endTime Lock end time (HH:mm format)
     * @return True if current time is within the lock range
     */
    private fun isCurrentTimeInLockRange(startTime: String, endTime: String): Boolean {
        return try {
            val now = LocalTime.now()
            val start = LocalTime.parse(startTime)
            val end = LocalTime.parse(endTime)

            // Handle case where end time is next day (e.g., 23:00 to 07:00)
            if (start.isBefore(end)) {
                // Same day range (e.g., 09:00 to 17:00)
                now.isAfter(start) && now.isBefore(end)
            } else {
                // Overnight range (e.g., 23:00 to 07:00)
                now.isAfter(start) || now.isBefore(end)
            }
        } catch (e: Exception) {
            // If parsing fails, default to not locked
            false
        }
    }

    /**
     * Get the lock end time
     * @return Lock end time string
     */
    fun getLockEndTime(): String {
        val settings = appSettings.value.childLockSettings
        return settings.endTime
    }
}
