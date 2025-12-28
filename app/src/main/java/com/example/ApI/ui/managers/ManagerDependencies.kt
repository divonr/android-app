package com.example.ApI.ui.managers

import android.content.Context
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.ChatUiState
import com.example.ApI.data.repository.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Common dependencies shared by UI managers.
 * Reduces boilerplate in manager constructors by grouping frequently-used dependencies.
 */
class ManagerDependencies(
    val repository: DataRepository,
    val context: Context,
    val scope: CoroutineScope,
    val appSettings: StateFlow<AppSettings>,
    val uiState: StateFlow<ChatUiState>,
    val updateUiState: (ChatUiState) -> Unit
)
