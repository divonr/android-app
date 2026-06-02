package com.example.ApI.ui.components

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.ApI.ui.theme.*
import java.awt.Desktop
import java.net.URI

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

object ProviderApiKeyConfigs {
    val configs = mapOf(
        "google" to ProviderApiKeyConfig("google", "Google", "https://aistudio.google.com/app/apikey", Regex("^.{10,}$"), "Google API key"),
        "openai" to ProviderApiKeyConfig("openai", "OpenAI", "https://platform.openai.com/api-keys", Regex("^.{10,}$"), "OpenAI API key"),
        "anthropic" to ProviderApiKeyConfig("anthropic", "Anthropic", "https://platform.claude.com/settings/keys", Regex("^.{10,}$"), "Anthropic API key"),
        "cohere" to ProviderApiKeyConfig("cohere", "Cohere", "https://dashboard.cohere.com/api-keys", Regex("^.{10,}$"), "Cohere API key"),
        "poe" to ProviderApiKeyConfig("poe", "Poe", "https://poe.com/api_key", Regex("^.{10,}$"), "Poe API key"),
        "openrouter" to ProviderApiKeyConfig("openrouter", "OpenRouter", "https://openrouter.ai/settings/keys", Regex("^.{10,}$"), "OpenRouter API key"),
        "llmstats" to ProviderApiKeyConfig("llmstats", "LLM Stats", "https://llm-stats.com/settings?section=api-keys", Regex("^.{10,}$"), "LLM Stats API key")
    )
    fun getConfig(providerId: String): ProviderApiKeyConfig? = configs[providerId]
}

sealed class ApiKeyResult {
    data class Success(val apiKey: String) : ApiKeyResult()
    object Cancelled : ApiKeyResult()
    object FallbackToCustomTabs : ApiKeyResult()
}

/**
 * Desktop: opens system browser and shows a paste-key dialog.
 * Faithful equivalent: user copies key from browser, pastes here.
 */
@Composable
fun ApiKeyWebViewDialog(
    config: ProviderApiKeyConfig,
    onResult: (ApiKeyResult) -> Unit,
    onDismiss: () -> Unit
) {
    var pastedKey by remember { mutableStateOf("") }
    var browserOpened by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!browserOpened) {
            browserOpened = true
            try {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(config.apiKeyUrl))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open browser: ${e.message}")
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Surface)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Get ${config.displayName} API Key", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Your browser has been opened to ${config.displayName}'s API key page. Copy your API key from the browser, then paste it below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        try {
                            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(config.apiKeyUrl))
                        } catch (e: Exception) {}
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open ${config.displayName} in Browser")
                }
                OutlinedTextField(
                    value = pastedKey,
                    onValueChange = { pastedKey = it },
                    label = { Text("Paste API Key here") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onResult(ApiKeyResult.Cancelled); onDismiss() }) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (pastedKey.length >= 10) {
                                onResult(ApiKeyResult.Success(pastedKey.trim()))
                            }
                        },
                        enabled = pastedKey.length >= 10
                    ) { Text("Use this Key") }
                }
            }
        }
    }
}
