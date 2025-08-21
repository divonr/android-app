package com.example.ApI.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.ApI.R
import com.example.ApI.data.model.*
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.ui.components.AddApiKeyDialog
import com.example.ApI.ui.components.DeleteApiKeyConfirmationDialog
import com.example.ApI.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysScreen(
    repository: DataRepository,
    currentUser: String,
    providers: List<Provider>,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var apiKeys by remember { mutableStateOf(repository.loadApiKeys(currentUser)) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var keyToDelete by remember { mutableStateOf<ApiKey?>(null) }
    
    // Helper functions to handle operations and update state
    val onAddApiKey = { provider: String, key: String, customName: String? ->
        repository.addApiKey(
            currentUser,
            ApiKey(
                provider = provider, 
                key = key,
                customName = customName
            )
        )
        apiKeys = repository.loadApiKeys(currentUser) // Refresh the state
    }
    
    val onToggleApiKey = { keyId: String ->
        repository.toggleApiKeyStatus(currentUser, keyId)
        apiKeys = repository.loadApiKeys(currentUser) // Refresh the state
    }
    
    val onDeleteApiKey = { keyId: String ->
        repository.deleteApiKey(currentUser, keyId)
        apiKeys = repository.loadApiKeys(currentUser) // Refresh the state
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Background)
                .systemBarsPadding()
        ) {
            // Modern Top Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Surface,
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SurfaceVariant,
                        modifier = Modifier
                            .size(40.dp)
                            .clickable { onBackClick() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = OnSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    
                    Text(
                        text = stringResource(R.string.api_keys),
                        style = MaterialTheme.typography.headlineSmall,
                        color = OnSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                // Modern Add API Key Button
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAddDialog = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = OnPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.add_api_key),
                            style = MaterialTheme.typography.labelLarge,
                            color = OnPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // API Keys List with modern design
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(apiKeys) { apiKey ->
                        ApiKeyItem(
                            apiKey = apiKey,
                            onToggleActive = { onToggleApiKey(apiKey.id) },
                            onDelete = { 
                                keyToDelete = apiKey
                                showDeleteDialog = true
                            }
                        )
                    }
                }
            }
        }
    }

    // Add API Key Dialog
    if (showAddDialog) {
        AddApiKeyDialog(
            providers = providers,
            onConfirm = { provider, key, customName ->
                onAddApiKey(provider, key, customName)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
    
    // Delete API Key Confirmation Dialog
    if (showDeleteDialog && keyToDelete != null) {
        DeleteApiKeyConfirmationDialog(
            onConfirm = { 
                keyToDelete?.let { onDeleteApiKey(it.id) }
                keyToDelete = null
                showDeleteDialog = false
            },
            onDismiss = { 
                keyToDelete = null
                showDeleteDialog = false
            }
        )
    }
}

@Composable
private fun ApiKeyItem(
    apiKey: ApiKey,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Provider-specific background colors
    val providerBackgroundColors = when (apiKey.provider) {
        "poe" -> listOf(
            Color(0xFF9C27B0).copy(alpha = 0.25f), // Purple
            Color(0xFF9C27B0).copy(alpha = 0.15f)
        )
        "openai" -> listOf(
            Color.White.copy(alpha = 0.35f),
            Color.White.copy(alpha = 0.20f)
        )
        "google" -> listOf(
            Color(0xFF4285F4).copy(alpha = 0.25f), // Blue
            Color(0xFFEA4335).copy(alpha = 0.25f), // Red
            Color(0xFFFBBC05).copy(alpha = 0.25f), // Yellow
            Color(0xFF34A853).copy(alpha = 0.25f)  // Green
        )
        else -> listOf(
            SurfaceVariant,
            SurfaceVariant
        )
    }
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 1.dp
    ) {
        Box(
            modifier = Modifier
                .background(
                    brush = if (apiKey.provider == "google") {
                        Brush.horizontalGradient(providerBackgroundColors)
                    } else {
                        Brush.verticalGradient(providerBackgroundColors)
                    }
                )
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Provider icon/indicator
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Primary.copy(alpha = 0.1f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = apiKey.provider.first().uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = Primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = apiKey.provider.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = OnSurface,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = maskApiKey(apiKey.key),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceVariant,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    
                    // Custom name display (if available)
                    apiKey.customName?.let { customName ->
                        Text(
                            text = customName,
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface.copy(alpha = 0.8f),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                
                // Status toggle button
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (apiKey.isActive) {
                        AccentGreen.copy(alpha = 0.2f)
                    } else {
                        Color.Red.copy(alpha = 0.2f)
                    },
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clickable { onToggleActive() }
                ) {
                    Text(
                        text = if (apiKey.isActive) "Active" else "Disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (apiKey.isActive) AccentGreen else Color.Red,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontWeight = FontWeight.Medium
                    )
                }
                
                // Delete button
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { onDelete() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete API Key",
                            tint = OnSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun maskApiKey(key: String): String {
    return if (key.length <= 2) {
        "••••••••"
    } else {
        "${key.first()}${"•".repeat(8)}${key.last()}"
    }
}
