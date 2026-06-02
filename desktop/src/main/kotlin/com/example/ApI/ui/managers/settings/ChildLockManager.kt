package com.example.ApI.ui.managers.settings

import com.example.ApI.data.ParentalControlManager
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.ChildLockSettings
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * Manages child lock (parental controls) functionality.
 */
class ChildLockManager(
    private val deps: ManagerDependencies,
    private val updateAppSettings: (AppSettings) -> Unit
) {

    fun setupChildLock(password: String, startTime: String, endTime: String, deviceId: String) {
        deps.scope.launch {
            try {
                val parentalControlManager = ParentalControlManager(deps.context)
                parentalControlManager.setParentalPassword(password, deviceId)
                val updatedSettings = deps.appSettings.value.copy(
                    childLockSettings = ChildLockSettings(
                        enabled = true,
                        encryptedPassword = parentalControlManager.getEncryptedPassword(),
                        startTime = startTime,
                        endTime = endTime
                    )
                )
                deps.repository.saveAppSettings(updatedSettings)
                updateAppSettings(updatedSettings)
            } catch (e: Exception) {
                deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "Error setting up child lock: ${e.message}"))
            }
        }
    }

    fun verifyAndDisableChildLock(password: String, deviceId: String): Boolean {
        return try {
            val parentalControlManager = ParentalControlManager(deps.context)
            val isValidPassword = parentalControlManager.verifyParentalPassword(password, deviceId)
            if (isValidPassword) {
                val updatedSettings = deps.appSettings.value.copy(
                    childLockSettings = ChildLockSettings(enabled = false, encryptedPassword = "", startTime = "23:00", endTime = "07:00")
                )
                deps.repository.saveAppSettings(updatedSettings)
                updateAppSettings(updatedSettings)
                true
            } else {
                deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "Incorrect password"))
                false
            }
        } catch (e: Exception) {
            deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "Error disabling child lock: ${e.message}"))
            false
        }
    }

    fun updateChildLockSettings(enabled: Boolean, password: String, startTime: String, endTime: String) {
        val updatedSettings = deps.appSettings.value.copy(
            childLockSettings = ChildLockSettings(enabled = enabled, encryptedPassword = password, startTime = startTime, endTime = endTime)
        )
        deps.repository.saveAppSettings(updatedSettings)
        updateAppSettings(updatedSettings)
    }

    fun isChildLockActive(): Boolean {
        val settings = deps.appSettings.value.childLockSettings
        if (!settings.enabled) return false
        return isCurrentTimeInLockRange(settings.startTime, settings.endTime)
    }

    private fun isCurrentTimeInLockRange(startTime: String, endTime: String): Boolean {
        return try {
            val now = LocalTime.now()
            val start = LocalTime.parse(startTime)
            val end = LocalTime.parse(endTime)
            if (start.isBefore(end)) now.isAfter(start) && now.isBefore(end)
            else now.isAfter(start) || now.isBefore(end)
        } catch (e: Exception) { false }
    }

    fun getLockEndTime(): String = deps.appSettings.value.childLockSettings.endTime
}
