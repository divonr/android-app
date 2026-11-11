package com.example.ApI.ui.components

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.ApI.ui.theme.*
import java.awt.Desktop
import java.net.URI

private const val TAG = "GitHubOAuthWebView"

sealed class OAuthResult {
    data class Success(val code: String, val state: String) : OAuthResult()
    data class Error(val message: String) : OAuthResult()
    object Cancelled : OAuthResult()
}

/**
 * Desktop version: opens system browser for GitHub OAuth.
 * User completes auth in browser and pastes the callback URL (containing code and state).
 */
@Composable
fun GitHubOAuthWebViewDialog(
    authUrl: String,
    onResult: (OAuthResult) -> Unit,
    onDismiss: () -> Unit
) {
    var callbackUrl by remember { mutableStateOf("") }
    var browserOpened by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!browserOpened) {
            browserOpened = true
            try {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(authUrl))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open browser: ${e.message}")
            }
        }
    }

    Dialog(onDismissRequest = { onResult(OAuthResult.Cancelled); onDismiss() }) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Surface)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("GitHub OAuth Authentication", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Your browser has been opened. Complete authentication in the browser, then paste the callback URL below (it starts with 'chatapi://github-oauth-callback?code=...').",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = {
                    try { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(authUrl)) } catch (e: Exception) {}
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Reopen GitHub in Browser")
                }
                OutlinedTextField(
                    value = callbackUrl,
                    onValueChange = { callbackUrl = it },
                    label = { Text("Paste callback URL here") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onResult(OAuthResult.Cancelled); onDismiss() }) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val uri = try { URI(callbackUrl.trim()) } catch (e: Exception) { null }
                            val query = uri?.rawQuery ?: ""
                            val params = query.split("&").associate { p ->
                                val kv = p.split("=", limit = 2)
                                (kv.getOrElse(0) { "" }) to (kv.getOrElse(1) { "" })
                            }
                            val code = params["code"]
                            val state = params["state"]
                            if (code != null && state != null) {
                                onResult(OAuthResult.Success(code, state))
                                onDismiss()
                            } else {
                                onResult(OAuthResult.Error("Invalid callback URL"))
                            }
                        },
                        enabled = callbackUrl.isNotBlank()
                    ) { Text("Complete OAuth") }
                }
            }
        }
    }
}
