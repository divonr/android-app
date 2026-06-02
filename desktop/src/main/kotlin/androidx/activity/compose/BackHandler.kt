package androidx.activity.compose

import androidx.compose.runtime.Composable

/**
 * Desktop shim for Android's BackHandler.
 * On desktop, "back" navigation is handled via keyboard (Escape) or custom logic.
 * This shim is a no-op - the actual back navigation logic should be handled
 * by the desktop app's key event handling.
 */
@Composable
fun BackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit
) {
    // No-op on desktop: use keyboard shortcut handling (Escape key) in the window
    // The desktop app can handle Escape key presses via the window's keyEventHandler
}
