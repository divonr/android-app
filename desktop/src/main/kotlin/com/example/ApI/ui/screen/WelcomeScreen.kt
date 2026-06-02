package com.example.ApI.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ApI.data.model.ApiKey
import com.example.ApI.data.model.Provider
import com.example.ApI.desktop.DesktopRepository
import com.example.ApI.ui.components.ApiKeyWebViewDialog
import com.example.ApI.ui.components.ApiKeyResult
import com.example.ApI.ui.components.ProviderApiKeyConfigs
import com.example.ApI.ui.components.dialogs.AddApiKeyDialog
import com.example.ApI.ui.theme.*
import java.awt.Desktop
import java.net.URI

// Provider display info
data class ProviderDisplayInfo(
    val id: String,
    val displayName: String,
    val logoFileName: String,
    val backgroundColor: Color
)

private val providerDisplayInfoMap = mapOf(
    "google" to ProviderDisplayInfo("google", "Google", "google.png", Color(0xFFFFFFFF)),
    "poe" to ProviderDisplayInfo("poe", "Poe", "poe.png", Color(0xFF694BC2)),
    "cohere" to ProviderDisplayInfo("cohere", "Cohere", "cohere.png", Color(0xFFC8C8C8)),
    "openai" to ProviderDisplayInfo("openai", "OpenAI", "openai.png", Color(0xFFFFFFFF)),
    "anthropic" to ProviderDisplayInfo("anthropic", "Anthropic", "anthropic.png", Color(0xFFD4A574)),
    "openrouter" to ProviderDisplayInfo("openrouter", "OpenRouter", "openrouter.png", Color(0xFFFFFFFF)),
    "llmstats" to ProviderDisplayInfo("llmstats", "LLM Stats", "llmstats.png", Color(0xFF13131C))
)

private val freeTrialProviders = listOf("google", "poe", "cohere", "llmstats")
private val paidProviders = listOf("openai", "anthropic", "openrouter")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WelcomeScreen(
    onNavigateToApiKeys: () -> Unit,
    onNavigateToMain: () -> Unit,
    onSkipWelcomeChanged: (Boolean) -> Unit,
    repository: DesktopRepository,
    currentUser: String,
    providers: List<Provider>,
    initialSkipWelcome: Boolean = false,
    modifier: Modifier = Modifier
) {
    var skipWelcome by remember { mutableStateOf(initialSkipWelcome) }
    var showAddApiKeyDialog by remember { mutableStateOf(false) }
    var showWebViewDialog by remember { mutableStateOf(false) }
    var pendingProviderId by remember { mutableStateOf<String?>(null) }
    var detectedApiKey by remember { mutableStateOf<String?>(null) }
    var detectedProvider by remember { mutableStateOf<String?>(null) }

    val onProviderClick: (String) -> Unit = { providerId ->
        val config = ProviderApiKeyConfigs.getConfig(providerId)
        if (config != null) {
            pendingProviderId = providerId
            showWebViewDialog = true
        }
    }

    if (showWebViewDialog && pendingProviderId != null) {
        val config = ProviderApiKeyConfigs.getConfig(pendingProviderId!!)
        if (config != null) {
            ApiKeyWebViewDialog(
                config = config,
                onResult = { result ->
                    when (result) {
                        is ApiKeyResult.Success -> {
                            detectedApiKey = result.apiKey
                            detectedProvider = pendingProviderId
                            showAddApiKeyDialog = true
                        }
                        else -> {}
                    }
                    showWebViewDialog = false
                    pendingProviderId = null
                },
                onDismiss = {
                    showWebViewDialog = false
                    pendingProviderId = null
                }
            )
        }
    }

    if (showAddApiKeyDialog) {
        AddApiKeyDialog(
            providers = providers,
            onConfirm = { provider, key, customName ->
                repository.addApiKey(currentUser, ApiKey(provider = provider, key = key, customName = customName))
                showAddApiKeyDialog = false
                detectedApiKey = null
                detectedProvider = null
            },
            onDismiss = { showAddApiKeyDialog = false; detectedApiKey = null; detectedProvider = null },
            initialProvider = detectedProvider,
            initialApiKey = detectedApiKey
        )
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(modifier = modifier.fillMaxSize().background(Background).windowInsetsPadding(WindowInsets.systemBars)) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar with X button
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    IconButton(
                        onClick = onNavigateToMain,
                        modifier = Modifier.align(Alignment.TopStart)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = OnSurfaceVariant)
                    }
                }

                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(16.dp))
                    Text("ברוכים הבאים", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = OnBackground)
                    Spacer(Modifier.height(8.dp))
                    Text("הוסיפו מפתח API להתחלה", fontSize = 16.sp, color = OnSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))

                    // Free trial providers
                    Text("ניסיון חינמי", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AccentGreen, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    freeTrialProviders.forEach { providerId ->
                        val displayInfo = providerDisplayInfoMap[providerId]
                        if (displayInfo != null) {
                            ProviderCard(displayInfo = displayInfo, onClick = { onProviderClick(providerId) })
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("ספקים בתשלום", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AccentYellow, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    paidProviders.forEach { providerId ->
                        val displayInfo = providerDisplayInfoMap[providerId]
                        if (displayInfo != null) {
                            ProviderCard(displayInfo = displayInfo, onClick = { onProviderClick(providerId) })
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    OutlinedButton(onClick = onNavigateToApiKeys, modifier = Modifier.fillMaxWidth()) {
                        Text("הוסף מפתח ידנית")
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = skipWelcome,
                            onCheckedChange = { newValue ->
                                skipWelcome = newValue
                                onSkipWelcomeChanged(newValue)
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("אל תציג מסך זה שוב", color = OnSurfaceVariant)
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(displayInfo: ProviderDisplayInfo, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceVariant,
        tonalElevation = 2.dp
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(displayInfo.backgroundColor),
                contentAlignment = Alignment.Center
            ) {
                Text(displayInfo.displayName.take(1), color = Color.Black, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Text(displayInfo.displayName, color = OnSurface, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        }
    }
}
