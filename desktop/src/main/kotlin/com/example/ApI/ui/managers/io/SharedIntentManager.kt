package com.example.ApI.ui.managers.io

import com.example.ApI.data.model.Screen
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Manages handling of shared content from other apps.
 * Desktop stub: no Android share intents exist on desktop.
 * On desktop this is a no-op manager.
 */
class SharedIntentManager(
    private val deps: ManagerDependencies,
    private val currentScreen: MutableStateFlow<Screen>
) {
    /** No-op on desktop: Android share intents don't exist. */
    fun handleSharedFiles() {
        // No-op: desktop apps don't receive Android share intents
    }
}
