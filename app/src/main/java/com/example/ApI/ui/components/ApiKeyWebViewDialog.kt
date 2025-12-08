package com.example.ApI.ui.components

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.example.ApI.ui.theme.*

private const val TAG = "ApiKeyWebView"

/**
 * Configuration for provider API key acquisition
 */
data class ProviderApiKeyConfig(
    val providerId: String,
    val displayName: String,
    val apiKeyUrl: String,
    val keyPattern: Regex,
    val keyDescription: String
)

/**
 * Provider configurations for API key URLs and validation patterns
 */
object ProviderApiKeyConfigs {
    val configs = mapOf(
        "google" to ProviderApiKeyConfig(
            providerId = "google",
            displayName = "Google",
            apiKeyUrl = "https://aistudio.google.com/app/apikey",
            keyPattern = Regex("^AIza[a-zA-Z0-9_-]{35}$"),
            keyDescription = "Google API key (starts with AIza, 39 characters)"
        ),
        "openai" to ProviderApiKeyConfig(
            providerId = "openai",
            displayName = "OpenAI",
            apiKeyUrl = "https://platform.openai.com/api-keys",
            keyPattern = Regex("^sk-[a-zA-Z0-9]{48,}$"),
            keyDescription = "OpenAI API key (starts with sk-)"
        ),
        "anthropic" to ProviderApiKeyConfig(
            providerId = "anthropic",
            displayName = "Anthropic",
            apiKeyUrl = "https://console.anthropic.com/settings/keys",
            keyPattern = Regex("^sk-ant-[a-zA-Z0-9-]{90,}$"),
            keyDescription = "Anthropic API key (starts with sk-ant-)"
        ),
        "cohere" to ProviderApiKeyConfig(
            providerId = "cohere",
            displayName = "Cohere",
            apiKeyUrl = "https://dashboard.cohere.com/api-keys",
            keyPattern = Regex("^[a-zA-Z0-9]{40}$"),
            keyDescription = "Cohere API key (~40 characters)"
        ),
        "poe" to ProviderApiKeyConfig(
            providerId = "poe",
            displayName = "Poe",
            apiKeyUrl = "https://poe.com/api_key",
            keyPattern = Regex("^[a-zA-Z0-9_-]{40,50}$"),
            keyDescription = "Poe API key (40-50 characters)"
        ),
        "openrouter" to ProviderApiKeyConfig(
            providerId = "openrouter",
            displayName = "OpenRouter",
            apiKeyUrl = "https://openrouter.ai/keys",
            keyPattern = Regex("^sk-or-[a-zA-Z0-9-]{40,}$"),
            keyDescription = "OpenRouter API key (starts with sk-or-)"
        )
    )

    fun getConfig(providerId: String): ProviderApiKeyConfig? = configs[providerId]
}

/**
 * Result of the API key WebView flow
 */
sealed class ApiKeyResult {
    data class Success(val apiKey: String) : ApiKeyResult()
    object Cancelled : ApiKeyResult()
    object FallbackToCustomTabs : ApiKeyResult()
}

/**
 * Opens Chrome Custom Tabs as fallback when WebView is blocked
 */
fun openInCustomTabs(context: Context, url: String) {
    val customTabsIntent = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
    customTabsIntent.launchUrl(context, url.toUri())
}

/**
 * Full-screen dialog with WebView for acquiring API keys from provider websites.
 * Monitors clipboard for API key copies and validates against provider-specific patterns.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ApiKeyWebViewDialog(
    config: ProviderApiKeyConfig,
    onResult: (ApiKeyResult) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var hasDetectedKey by remember { mutableStateOf(false) }

    // Clipboard monitoring
    DisposableEffect(Unit) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            if (hasDetectedKey) return@OnPrimaryClipChangedListener

            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()?.trim() ?: return@OnPrimaryClipChangedListener

                Log.d(TAG, "Clipboard changed, checking pattern for ${config.providerId}")

                // Validate against provider-specific pattern
                if (config.keyPattern.matches(text)) {
                    Log.d(TAG, "Valid API key detected for ${config.providerId}")
                    hasDetectedKey = true

                    // Show toast notification
                    Toast.makeText(
                        context,
                        "זוהתה העתקה של מפתח API",
                        Toast.LENGTH_SHORT
                    ).show()

                    // Return the detected key
                    onResult(ApiKeyResult.Success(text))
                }
            }
        }

        clipboardManager.addPrimaryClipChangedListener(listener)
        Log.d(TAG, "Clipboard listener registered for ${config.providerId}")

        onDispose {
            clipboardManager.removePrimaryClipChangedListener(listener)
            Log.d(TAG, "Clipboard listener removed")
        }
    }

    Dialog(
        onDismissRequest = {
            onResult(ApiKeyResult.Cancelled)
            onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Surface
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = SurfaceVariant
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "קבלת מפתח ${config.displayName}",
                            style = MaterialTheme.typography.titleMedium,
                            color = OnSurface
                        )

                        TextButton(
                            onClick = {
                                onResult(ApiKeyResult.Cancelled)
                                onDismiss()
                            }
                        ) {
                            Text("ביטול", color = Primary)
                        }
                    }
                }

                // Loading indicator
                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Primary,
                        trackColor = SurfaceVariant
                    )
                }

                // WebView
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true

                                // Allow third-party cookies for Google sign-in
                                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                                // Set dark background to match app theme
                                setBackgroundColor(android.graphics.Color.parseColor("#1A1B26"))

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val url = request?.url?.toString() ?: return false
                                        Log.d(TAG, "Loading URL: $url")

                                        // Check for Google blocking WebView
                                        if (url.contains("disallowed_useragent") ||
                                            url.contains("accounts.google.com/v3/signin/rejected")) {
                                            Log.w(TAG, "WebView blocked by Google, falling back to Custom Tabs")
                                            onResult(ApiKeyResult.FallbackToCustomTabs)
                                            return true
                                        }

                                        return false
                                    }

                                    override fun onPageStarted(
                                        view: WebView?,
                                        url: String?,
                                        favicon: Bitmap?
                                    ) {
                                        super.onPageStarted(view, url, favicon)
                                        isLoading = true
                                        Log.d(TAG, "Page started: $url")

                                        // Check for Google blocking WebView on page start
                                        if (url?.contains("disallowed_useragent") == true ||
                                            url?.contains("accounts.google.com/v3/signin/rejected") == true) {
                                            Log.w(TAG, "WebView blocked by Google (on page start)")
                                            onResult(ApiKeyResult.FallbackToCustomTabs)
                                        }
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        isLoading = false
                                        Log.d(TAG, "Page finished: $url")
                                    }
                                }

                                loadUrl(config.apiKeyUrl)
                            }
                        }
                    )
                }
            }
        }
    }
}
