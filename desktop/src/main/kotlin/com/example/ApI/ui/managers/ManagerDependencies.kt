package com.example.ApI.ui.managers

import android.content.Context
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.ChatUiState
import com.example.ApI.desktop.DesktopRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Common dependencies shared by UI managers.
 * On desktop, uses DesktopRepository directly.
 */
class ManagerDependencies(
    val repository: DesktopRepository,
    val context: Context,
    val scope: CoroutineScope,
    val appSettings: StateFlow<AppSettings>,
    val uiState: MutableStateFlow<ChatUiState>,
    val updateUiState: (ChatUiState) -> Unit
)
