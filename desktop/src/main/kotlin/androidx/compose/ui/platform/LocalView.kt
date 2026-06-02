package androidx.compose.ui.platform

import androidx.compose.runtime.compositionLocalOf

/**
 * Desktop shim for Android's LocalView.
 * On desktop, there is no Android View hierarchy.
 * This provides a null value as a stub.
 */
val LocalView = compositionLocalOf<Any?> { null }
