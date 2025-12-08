package com.example.ApI.ui.screen

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.example.ApI.data.model.ApiKey
import com.example.ApI.data.model.Provider
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.ui.components.AddApiKeyDialog
import com.example.ApI.ui.components.ProviderApiKeyConfigs
import com.example.ApI.ui.components.openInCustomTabs
import com.example.ApI.ui.theme.*

// Provider display info with colors and logo
data class ProviderDisplayInfo(
    val id: String,
    val displayName: String,
    val logoFileName: String,
    val backgroundColor: Color
)

// Provider colors and display names
private val providerDisplayInfoMap = mapOf(
    "google" to ProviderDisplayInfo(
        id = "google",
        displayName = "Google",
        logoFileName = "google.png",
        backgroundColor = Color(0xFFFFFFFF)
    ),
    "poe" to ProviderDisplayInfo(
        id = "poe",
        displayName = "Poe",
        logoFileName = "poe.png",
        backgroundColor = Color(0xFF694BC2)
    ),
    "cohere" to ProviderDisplayInfo(
        id = "cohere",
        displayName = "Cohere",
        logoFileName = "cohere.png",
        backgroundColor = Color(0xFFC8C8C8)
    ),
    "openai" to ProviderDisplayInfo(
        id = "openai",
        displayName = "OpenAI",
        logoFileName = "openai.png",
        backgroundColor = Color(0xFFFFFFFF)
    ),
    "anthropic" to ProviderDisplayInfo(
        id = "anthropic",
        displayName = "Anthropic",
        logoFileName = "anthropic.png",
        backgroundColor = Color(0xFFD4A574)
    ),
    "openrouter" to ProviderDisplayInfo(
        id = "openrouter",
        displayName = "OpenRouter",
        logoFileName = "openrouter.png",
        backgroundColor = Color(0xFFFFFFFF)
    )
)

// First group - free trial providers (in order: Google, Poe, Cohere)
private val freeTrialProviders = listOf("google", "poe", "cohere")

// Second group - paid providers (others)
private val paidProviders = listOf("openai", "anthropic", "openrouter")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WelcomeScreen(
    onNavigateToApiKeys: () -> Unit,
    onNavigateToMain: () -> Unit,
    onSkipWelcomeChanged: (Boolean) -> Unit,
    repository: DataRepository,
    currentUser: String,
    providers: List<Provider>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var skipWelcome by remember { mutableStateOf(false) }

    // Track which provider was selected for Custom Tabs (rememberSaveable survives process death)
    var pendingProviderId by rememberSaveable { mutableStateOf<String?>(null) }

    // Add API key dialog state
    var showAddApiKeyDialog by remember { mutableStateOf(false) }
    var detectedApiKey by remember { mutableStateOf<String?>(null) }
    var detectedProvider by remember { mutableStateOf<String?>(null) }

    // Track lifecycle state using Flow
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()

    // Check clipboard when app resumes and we have a pending provider
    LaunchedEffect(lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED && pendingProviderId != null) {
            // User returned - check clipboard
            val providerToCheck = pendingProviderId
            pendingProviderId = null // Reset immediately to prevent multiple checks

            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()?.trim()
                if (text != null && text.length >= 10) {
                    // Valid API key found in clipboard!
                    Toast.makeText(
                        context,
                        "זוהתה העתקה של מפתח API",
                        Toast.LENGTH_SHORT
                    ).show()

                    detectedApiKey = text
                    detectedProvider = providerToCheck
                    showAddApiKeyDialog = true
                }
            }
        }
    }

    // Handle provider card click - open Chrome Custom Tabs directly
    val onProviderClick: (String) -> Unit = { providerId ->
        val config = ProviderApiKeyConfigs.getConfig(providerId)
        if (config != null) {
            // Store which provider we're waiting for
            pendingProviderId = providerId

            // Show instruction toast
            Toast.makeText(
                context,
                "העתיקו את מפתח ה-API וחזרו לאפליקציה",
                Toast.LENGTH_LONG
            ).show()

            // Open Chrome Custom Tabs
            openInCustomTabs(context, config.apiKeyUrl)
        } else {
            Toast.makeText(context, "ספק זה אינו נתמך עדיין", Toast.LENGTH_SHORT).show()
        }
    }

    // Show Add API Key dialog with pre-filled values
    if (showAddApiKeyDialog) {
        AddApiKeyDialog(
            providers = providers,
            onConfirm = { provider, key, customName ->
                repository.addApiKey(
                    currentUser,
                    ApiKey(
                        provider = provider,
                        key = key,
                        customName = customName
                    )
                )
                showAddApiKeyDialog = false
                detectedApiKey = null
                detectedProvider = null
                Toast.makeText(context, "מפתח API נשמר בהצלחה!", Toast.LENGTH_SHORT).show()
            },
            onDismiss = {
                showAddApiKeyDialog = false
                detectedApiKey = null
                detectedProvider = null
            },
            initialProvider = detectedProvider,
            initialApiKey = detectedApiKey
        )
    }

    // Force RTL layout for Hebrew text
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Background)
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                // Top bar with X button (positioned in top-left which is visually top-right in RTL)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    // X button in top-left (visually top-right in RTL for Hebrew)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        IconButton(
                            onClick = onNavigateToMain,
                            modifier = Modifier.align(Alignment.TopStart)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = OnSurface
                            )
                        }
                    }
                }

                // Scrollable content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(16.dp))

                    // Welcome title
                    Text(
                        text = "\u05D1\u05E8\u05D5\u05DB\u05D9\u05DD \u05D4\u05D1\u05D0\u05D9\u05DD!",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = OnSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Subtitle
                    Text(
                        text = "\u05DB\u05D3\u05D9 \u05DC\u05D4\u05EA\u05D7\u05D9\u05DC, \u05EA\u05E6\u05D8\u05E8\u05DB\u05D5 \u05DE\u05E4\u05EA\u05D7 API. \u05DC\u05D7\u05E6\u05D5 \u05E2\u05DC \u05E9\u05DD \u05D4\u05E1\u05E4\u05E7 \u05DB\u05D3\u05D9 \u05DC\u05E7\u05D1\u05DC \u05DE\u05E4\u05EA\u05D7.",
                        fontSize = 16.sp,
                        color = OnSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // First group - Free trial providers
                    Text(
                        text = "\u05DE\u05D5\u05DE\u05DC\u05E5 \u05DC\u05D4\u05EA\u05D7\u05D9\u05DC \u05DB\u05D0\u05DF - \u05DE\u05E1\u05E4\u05E7\u05D9\u05DD \u05D0\u05DC\u05D5 \u05E0\u05D9\u05EA\u05DF \u05DC\u05E7\u05D1\u05DC \u05DE\u05E4\u05EA\u05D7 \u05E0\u05D9\u05E1\u05D9\u05D5\u05DF \u05DC\u05DC\u05D0 \u05EA\u05E9\u05DC\u05D5\u05DD",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = AccentGreen,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Provider grid for first group
                    ProviderGrid(
                        providers = freeTrialProviders,
                        onProviderClick = onProviderClick
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // Second group - Paid providers
                    Text(
                        text = "\u05E1\u05E4\u05E7\u05D9\u05DD \u05E0\u05D5\u05E1\u05E4\u05D9\u05DD - \u05EA\u05E9\u05DC\u05D5\u05DD \u05DE\u05D9\u05E0\u05D9\u05DE\u05DC\u05D9 5$ \u05DC\u05E7\u05D1\u05DC\u05EA \u05DE\u05E4\u05EA\u05D7 \u05E4\u05E2\u05D9\u05DC",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = OnSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Provider grid for second group
                    ProviderGrid(
                        providers = paidProviders,
                        onProviderClick = onProviderClick
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Bottom fixed section
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Surface,
                    tonalElevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // "I have an API key" button
                        TextButton(
                            onClick = onNavigateToApiKeys,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "\u05D9\u05E9 \u05DC\u05D9 \u05DE\u05E4\u05EA\u05D7 API >",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = Primary
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Checkbox to skip welcome screen
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable {
                                    skipWelcome = !skipWelcome
                                    onSkipWelcomeChanged(skipWelcome)
                                }
                                .padding(8.dp)
                        ) {
                            Checkbox(
                                checked = skipWelcome,
                                onCheckedChange = { checked ->
                                    skipWelcome = checked
                                    onSkipWelcomeChanged(checked)
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Primary,
                                    uncheckedColor = OnSurfaceVariant
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "\u05D0\u05DC \u05EA\u05E6\u05D9\u05D2\u05D5 \u05DE\u05E1\u05DA \u05D6\u05D4 \u05E9\u05D5\u05D1",
                                fontSize = 14.sp,
                                color = OnSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderGrid(
    providers: List<String>,
    onProviderClick: (String) -> Unit
) {
    // Create rows of 2 providers each
    val rows = providers.chunked(2)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        rows.forEach { rowProviders ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowProviders.forEach { providerId ->
                    val info = providerDisplayInfoMap[providerId]
                    if (info != null) {
                        ProviderCard(
                            info = info,
                            onClick = { onProviderClick(providerId) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // Fill empty space if odd number of providers
                if (rowProviders.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    info: ProviderDisplayInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .height(80.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(info.backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = rememberAsyncImagePainter(
                ImageRequest.Builder(context)
                    .data("file:///android_asset/${info.logoFileName}")
                    .build()
            ),
            contentDescription = info.displayName,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentScale = ContentScale.Fit
        )
    }
}
