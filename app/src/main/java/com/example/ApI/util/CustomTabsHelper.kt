package com.example.ApI.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Helper object for launching URLs in Chrome Custom Tabs
 * with appropriate theming and fallback handling.
 */
object CustomTabsHelper {

    private const val TAG = "CustomTabsHelper"

    // App theme colors (matching Color.kt)
    private const val TOOLBAR_COLOR = 0xFF1A1B26.toInt()       // Surface color
    private const val BACKGROUND_COLOR = 0xFF0D0E14.toInt()    // Background color

    // Known Chrome Custom Tabs supporting packages
    private val CUSTOM_TABS_PACKAGES = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.microsoft.emmx",  // Edge
        "org.mozilla.firefox", // Firefox
        "com.brave.browser",   // Brave
        "com.opera.browser"    // Opera
    )

    /**
     * Launch a URL using Chrome Custom Tabs with app-matching dark theme.
     * Falls back to regular browser if Custom Tabs not available.
     *
     * @param context The context to use
     * @param url The URL to open
     * @return true if Custom Tabs was used, false if fallback was needed
     */
    fun launchUrl(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)

        // Check if any Custom Tabs compatible browser is available
        if (!isCustomTabsSupported(context)) {
            Log.d(TAG, "Custom Tabs not supported, falling back to browser")
            launchInBrowser(context, uri)
            return false
        }

        // Build Custom Tabs intent with dark theme matching the app
        val customTabsIntent = buildCustomTabsIntent()

        try {
            customTabsIntent.launchUrl(context, uri)
            Log.d(TAG, "Launched URL in Custom Tabs: $url")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Custom Tabs, falling back to browser", e)
            launchInBrowser(context, uri)
            return false
        }
    }

    /**
     * Build a CustomTabsIntent with dark theme colors matching the app.
     */
    private fun buildCustomTabsIntent(): CustomTabsIntent {
        // Dark color scheme parameters
        val darkColorScheme = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(TOOLBAR_COLOR)
            .setSecondaryToolbarColor(BACKGROUND_COLOR)
            .setNavigationBarColor(BACKGROUND_COLOR)
            .setNavigationBarDividerColor(BACKGROUND_COLOR)
            .build()

        return CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(darkColorScheme)
            .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, darkColorScheme)
            .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
            .setShowTitle(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .setUrlBarHidingEnabled(false) // Keep URL visible for security
            .build()
    }

    /**
     * Check if any browser with Custom Tabs support is installed.
     */
    private fun isCustomTabsSupported(context: Context): Boolean {
        val packageManager = context.packageManager

        // Check if any known Custom Tabs package is installed
        for (packageName in CUSTOM_TABS_PACKAGES) {
            try {
                packageManager.getPackageInfo(packageName, 0)
                Log.d(TAG, "Found Custom Tabs package: $packageName")
                return true
            } catch (e: PackageManager.NameNotFoundException) {
                // This package is not installed, try next
            }
        }

        // Alternative: Check if any browser can handle VIEW intent
        val testIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"))
        val resolveInfoList = packageManager.queryIntentActivities(testIntent, 0)

        return resolveInfoList.isNotEmpty()
    }

    /**
     * Fallback: Launch URL in default browser.
     */
    private fun launchInBrowser(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        Log.d(TAG, "Launched URL in browser: $uri")
    }
}
