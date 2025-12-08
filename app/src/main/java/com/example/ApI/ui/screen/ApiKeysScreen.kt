package com.example.ApI.ui.screen

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.ApI.R
import com.example.ApI.data.model.*
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.ui.components.AddApiKeyDialog
import com.example.ApI.ui.components.DeleteApiKeyConfirmationDialog
import com.example.ApI.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ApiKeysScreen(
    repository: DataRepository,
    currentUser: String,
    providers: List<Provider>,
    onBackClick: () -> Unit,
    onSkipWelcomeChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var apiKeys by remember { mutableStateOf(repository.loadApiKeys(currentUser)) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var keyToDelete by remember { mutableStateOf<ApiKey?>(null) }
    var showWelcomeDialog by remember { mutableStateOf(false) }
    
    // Load initial skip welcome state from settings
    val appSettings = remember { repository.loadAppSettings() }
    var currentSkipWelcome by remember { mutableStateOf(appSettings.skipWelcomeScreen) }
    
    // Drag and drop state
    var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
    var targetIndex by remember { mutableStateOf<Int?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    
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
    
    val onReorderApiKeys = { fromIndex: Int, toIndex: Int ->
        if (fromIndex != toIndex) {
            // Perform the reordering in the list
            val mutableList = apiKeys.toMutableList()
            val item = mutableList.removeAt(fromIndex)
            mutableList.add(toIndex, item)
            apiKeys = mutableList
            
            // Save the new order
            coroutineScope.launch {
                repository.reorderApiKeys(currentUser, fromIndex, toIndex)
            }
        }
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
                // Two buttons side by side
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Add API Key Button (left, half width)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Primary,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showAddDialog = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = OnPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.add_api_key),
                                style = MaterialTheme.typography.labelLarge,
                                color = OnPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    
                    // Get API Key Button (right, half width, subtle green)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF4A7C59),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showWelcomeDialog = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color(0xFFB8E6C7),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "קבל מפתח API",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFFB8E6C7),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // API Keys List with drag and drop
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(
                        items = apiKeys,
                        key = { _, item -> item.id }
                    ) { index, apiKey ->
                        val actualIndex = if (isDragging && draggedItemIndex != null && targetIndex != null) {
                            when {
                                index == draggedItemIndex -> targetIndex!!
                                draggedItemIndex!! < targetIndex!! && index > draggedItemIndex!! && index <= targetIndex!! -> index - 1
                                draggedItemIndex!! > targetIndex!! && index >= targetIndex!! && index < draggedItemIndex!! -> index + 1
                                else -> index
                            }
                        } else {
                            index
                        }
                        
                        DraggableApiKeyItem(
                            apiKey = apiKey,
                            index = index,
                            actualIndex = actualIndex,
                            totalItems = apiKeys.size,
                            isDragged = draggedItemIndex == index,
                            dragOffset = if (draggedItemIndex == index) dragOffset else 0f,
                            onToggleActive = { onToggleApiKey(apiKey.id) },
                            onDelete = { 
                                keyToDelete = apiKey
                                showDeleteDialog = true
                            },
                            onDragStart = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                draggedItemIndex = index
                                targetIndex = index
                                isDragging = true
                                dragOffset = 0f
                            },
                            onDrag = { offset ->
                                dragOffset = offset
                                
                                // Calculate which item we're hovering over
                                val itemHeight = 112 // Approximate height in dp
                                val itemsOffset = (offset / itemHeight).toInt()
                                val newTargetIndex = (index + itemsOffset)
                                    .coerceIn(0, apiKeys.size - 1)
                                
                                if (newTargetIndex != targetIndex) {
                                    targetIndex = newTargetIndex
                                }
                            },
                            onDragEnd = {
                                val fromIndex = draggedItemIndex
                                val toIndex = targetIndex
                                
                                if (fromIndex != null && toIndex != null) {
                                    onReorderApiKeys(fromIndex, toIndex)
                                }
                                
                                draggedItemIndex = null
                                targetIndex = null
                                isDragging = false
                                dragOffset = 0f
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
    
    // Welcome Screen Dialog
    if (showWelcomeDialog) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showWelcomeDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            WelcomeScreen(
                onNavigateToApiKeys = { 
                    // Already on API keys screen, just close the dialog
                    showWelcomeDialog = false 
                },
                onNavigateToMain = { 
                    showWelcomeDialog = false 
                },
                onSkipWelcomeChanged = { skip -> 
                    currentSkipWelcome = skip
                    onSkipWelcomeChanged(skip)
                },
                repository = repository,
                currentUser = currentUser,
                providers = providers,
                initialSkipWelcome = currentSkipWelcome
            )
        }
    }
}

@Composable
private fun DraggableApiKeyItem(
    apiKey: ApiKey,
    index: Int,
    actualIndex: Int,
    totalItems: Int,
    isDragged: Boolean,
    dragOffset: Float,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var localDragOffset by remember { mutableStateOf(0f) }
    
    // Animation for smooth position transitions
    val animatedOffset by animateDpAsState(
        targetValue = with(density) {
            val itemHeight = 112.dp.toPx()
            ((actualIndex - index) * itemHeight).toDp()
        },
        label = "position",
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = 400f
        )
    )
    
    // Animation states
    val elevation by animateDpAsState(
        targetValue = if (isDragged) 12.dp else 1.dp,
        label = "elevation"
    )
    
    val scale by animateFloatAsState(
        targetValue = if (isDragged) 1.03f else 1f,
        label = "scale",
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = 400f
        )
    )
    
    val opacity = if (isDragged) 0.9f else 1f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (isDragged) 1f else 0f)
            .graphicsLayer {
                // Apply animation offset for non-dragged items
                translationY = if (isDragged) {
                    localDragOffset
                } else {
                    animatedOffset.toPx()
                }
                scaleX = scale
                scaleY = scale
                alpha = opacity
                shadowElevation = elevation.toPx()
            }
            .pointerInput(apiKey.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { _ ->
                        onDragStart()
                        localDragOffset = 0f
                    },
                    onDragEnd = {
                        onDragEnd()
                        localDragOffset = 0f
                    },
                    onDrag = { _, dragAmount ->
                        localDragOffset += dragAmount.y
                        onDrag(localDragOffset)
                    }
                )
            }
    ) {
        ApiKeyItem(
            apiKey = apiKey,
            onToggleActive = onToggleActive,
            onDelete = onDelete,
            elevation = elevation,
            modifier = Modifier
        )
    }
}

@Composable
private fun ApiKeyItem(
    apiKey: ApiKey,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
    elevation: androidx.compose.ui.unit.Dp = 1.dp,
    modifier: Modifier = Modifier
) {
    // Provider-specific background colors
    val providerBackgroundColors = when (apiKey.provider) {
        "poe" -> listOf(
            Color(0xFF694BC2).copy(alpha = 0.25f), // Poe Purple
            Color(0xFF694BC2).copy(alpha = 0.15f)
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
        "anthropic" -> listOf(
            Color(0xFFC6613F).copy(alpha = 0.25f), // Anthropic Orange
            Color(0xFFC6613F).copy(alpha = 0.15f)
        )
        "cohere" -> listOf(
            Color(0xFF39594D).copy(alpha = 0.25f), // Cohere Green
            Color(0xFF39594D).copy(alpha = 0.15f)
        )
        "openrouter" -> listOf(
            Color.White.copy(alpha = 0.35f),
            Color.White.copy(alpha = 0.20f)
        )
        else -> listOf(
            SurfaceVariant,
            SurfaceVariant
        )
    }
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = elevation
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
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.zIndex(1f),
                        maxLines = 1,
                        softWrap = false
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
