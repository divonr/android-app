package com.example.ApI.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ApI.ui.theme.*

private const val TAG = "GitHubOAuthWebView"
private const val REDIRECT_URI_SCHEME = "chatapi"
private const val REDIRECT_URI_HOST = "github-oauth-callback"

/**
 * Result of the OAuth flow
 */
sealed class OAuthResult {
    data class Success(val code: String, val state: String) : OAuthResult()
    data class Error(val message: String) : OAuthResult()
    object Cancelled : OAuthResult()
}

/**
 * Full-screen dialog with WebView for GitHub OAuth authentication.
 * Intercepts the redirect URL to capture the authorization code.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GitHubOAuthWebViewDialog(
    authUrl: String,
    onResult: (OAuthResult) -> Unit,
    onDismiss: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var loadingProgress by remember { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = {
            onResult(OAuthResult.Cancelled)
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
                            text = "התחברות ל-GitHub",
                            style = MaterialTheme.typography.titleMedium,
                            color = OnSurface
                        )

                        TextButton(
                            onClick = {
                                onResult(OAuthResult.Cancelled)
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
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true

                                // Set dark background to match app theme
                                setBackgroundColor(android.graphics.Color.parseColor("#1A1B26"))

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val url = request?.url ?: return false
                                        Log.d(TAG, "Loading URL: $url")

                                        // Check if this is our redirect URI
                                        if (url.scheme == REDIRECT_URI_SCHEME &&
                                            url.host == REDIRECT_URI_HOST) {
                                            Log.d(TAG, "Intercepted redirect URI")
                                            handleRedirect(url, onResult, onDismiss)
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
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        isLoading = false
                                        Log.d(TAG, "Page finished: $url")
                                    }
                                }

                                loadUrl(authUrl)
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * Handle the OAuth redirect and extract code/state parameters
 */
private fun handleRedirect(
    uri: Uri,
    onResult: (OAuthResult) -> Unit,
    onDismiss: () -> Unit
) {
    val code = uri.getQueryParameter("code")
    val state = uri.getQueryParameter("state")
    val error = uri.getQueryParameter("error")
    val errorDescription = uri.getQueryParameter("error_description")

    when {
        error != null -> {
            Log.e(TAG, "OAuth error: $error - $errorDescription")
            onResult(OAuthResult.Error(errorDescription ?: error))
        }
        code != null && state != null -> {
            Log.d(TAG, "OAuth success: code received")
            onResult(OAuthResult.Success(code, state))
        }
        else -> {
            Log.e(TAG, "OAuth error: Missing code or state")
            onResult(OAuthResult.Error("Missing authorization code or state"))
        }
    }
    onDismiss()
}
