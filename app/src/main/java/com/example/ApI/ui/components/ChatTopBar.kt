package com.example.ApI.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ApI.R
import com.example.ApI.data.model.*
import com.example.ApI.ui.ChatViewModel
import com.example.ApI.ui.theme.*

/**
 * Container for the top bar area including main bar, quick settings, and toggle button
 */
@Composable
fun ChatTopBarContainer(
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    searchFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth()) {
        // Top bars column (without padding between them)
        Column(modifier = Modifier.fillMaxWidth()) {
            // Main Top Bar
            ChatTopBar(
                uiState = uiState,
                viewModel = viewModel,
                searchFocusRequester = searchFocusRequester
            )

            // Expandable Quick Settings Bar
            QuickSettingsBar(
                uiState = uiState,
                viewModel = viewModel
            )
        }

        // Floating arrow toggle button
        QuickSettingsToggleButton(
            expanded = uiState.quickSettingsExpanded,
            visible = !uiState.searchMode,
            onClick = { viewModel.toggleQuickSettings() }
        )
    }
}

/**
 * Main top bar with search mode and normal mode
 */
@Composable
fun ChatTopBar(
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    searchFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Surface,
        shadowElevation = 1.dp
    ) {
        if (uiState.searchMode) {
            SearchModeTopBar(
                searchQuery = uiState.searchQuery,
                onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                onBackClick = { viewModel.navigateToScreen(Screen.ChatHistory) },
                onSearchAction = { viewModel.performConversationSearch() },
                onExitSearch = { viewModel.exitSearchMode() },
                searchFocusRequester = searchFocusRequester
            )
        } else {
            NormalModeTopBar(
                uiState = uiState,
                viewModel = viewModel
            )
        }
    }
}

/**
 * Top bar in search mode - full-width search field
 */
@Composable
fun SearchModeTopBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onBackClick: () -> Unit,
    onSearchAction: () -> Unit,
    onExitSearch: () -> Unit,
    searchFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back arrow
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = SurfaceVariant,
            modifier = Modifier
                .size(36.dp)
                .clickable { onBackClick() }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to chat history",
                    tint = OnSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Search text field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(searchFocusRequester),
            placeholder = {
                Text(
                    text = "חפש בשיחה...",
                    color = OnSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = OnSurface,
                unfocusedTextColor = OnSurface,
                unfocusedContainerColor = Surface,
                focusedContainerColor = Surface
            ),
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true,
            keyboardOptions = KeyboardOptions.Default.copy(
                keyboardType = KeyboardType.Text
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSearch = { onSearchAction() }
            ),
            shape = MaterialTheme.shapes.medium,
            trailingIcon = {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = Color.Transparent,
                    modifier = Modifier
                        .size(32.dp)
                        .clickable {
                            if (searchQuery.isNotEmpty()) {
                                onExitSearch()
                            } else {
                                onSearchAction()
                            }
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (searchQuery.isNotEmpty()) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searchQuery.isNotEmpty()) "Exit search" else "Search",
                            tint = OnSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        )
    }
}

/**
 * Top bar in normal mode - navigation, provider/model info, actions
 */
@Composable
fun NormalModeTopBar(
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side - Back arrow and Search icon
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = SurfaceVariant,
                modifier = Modifier
                    .size(36.dp)
                    .clickable { viewModel.navigateToScreen(Screen.ChatHistory) }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to chat history",
                        tint = OnSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Search icon
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = SurfaceVariant,
                modifier = Modifier
                    .size(36.dp)
                    .clickable { viewModel.enterConversationSearchMode() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search in conversation",
                        tint = OnSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Center - Model and Provider info
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = uiState.currentProvider?.provider?.let { provider ->
                    stringResource(id = when(provider) {
                        "openai" -> R.string.provider_openai
                        "anthropic" -> R.string.provider_anthropic
                        "google" -> R.string.provider_google
                        "poe" -> R.string.provider_poe
                        "cohere" -> R.string.provider_cohere
                        "openrouter" -> R.string.provider_openrouter
                        else -> R.string.provider_openai
                    })
                } ?: "",
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceVariant,
                modifier = Modifier.clickable { viewModel.showProviderSelector() }
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = uiState.currentModel,
                style = MaterialTheme.typography.titleSmall,
                color = OnSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { viewModel.showModelSelector() }
            )
        }

        // Right side - System Prompt and Delete icons
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // System Prompt icon
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = SurfaceVariant,
                modifier = Modifier
                    .size(36.dp)
                    .clickable { viewModel.showSystemPromptDialog() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = "System Prompt",
                        tint = OnSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Delete Chat
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = SurfaceVariant,
                modifier = Modifier
                    .size(36.dp)
                    .clickable { viewModel.showDeleteChatConfirmation() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Chat",
                        tint = OnSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Expandable quick settings bar with thinking budget, temperature, text direction, and export
 */
@Composable
fun QuickSettingsBar(
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = uiState.quickSettingsExpanded && !uiState.searchMode,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = Surface,
            shadowElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))

                // Thinking Budget Control Button
                ThinkingBudgetButton(
                    uiState = uiState,
                    viewModel = viewModel
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Temperature Control Button
                TemperatureButton(
                    uiState = uiState,
                    viewModel = viewModel
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Text Direction Toggle Button
                TextDirectionButton(
                    textDirectionMode = uiState.textDirectionMode,
                    onModeChange = { viewModel.setTextDirectionMode(it) }
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Download/Export Button
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = SurfaceVariant,
                    modifier = Modifier
                        .size(36.dp)
                        .clickable { viewModel.openChatExportDialog() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download chat",
                            tint = OnSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(45.dp))
            }
        }
    }
}

/**
 * Thinking budget control button with popup
 */
@Composable
fun ThinkingBudgetButton(
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        val budgetType = uiState.getThinkingBudgetType()
        val isThinkingSupported = budgetType is ThinkingBudgetType.Discrete ||
                budgetType is ThinkingBudgetType.Continuous
        val isThinkingActive = isThinkingSupported &&
                ThinkingBudgetConfig.isThinkingEnabled(uiState.thinkingBudgetValue)

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (isThinkingActive) Primary.copy(alpha = 0.15f) else SurfaceVariant,
            modifier = Modifier
                .size(36.dp)
                .clickable { viewModel.onThinkingBudgetButtonClick() }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = "Thinking budget",
                    tint = if (isThinkingActive) Primary else OnSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        ThinkingBudgetPopup(
            visible = uiState.showThinkingBudgetPopup,
            budgetType = budgetType,
            currentValue = uiState.thinkingBudgetValue,
            onValueChange = { value ->
                when (value) {
                    is ThinkingBudgetValue.Effort -> viewModel.setThinkingEffort(value.level)
                    is ThinkingBudgetValue.Tokens -> viewModel.setThinkingTokenBudget(value.count)
                    ThinkingBudgetValue.None -> {}
                }
            },
            onDismiss = { viewModel.hideThinkingBudgetPopup() }
        )
    }
}

/**
 * Temperature control button with popup
 */
@Composable
fun TemperatureButton(
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        val tempConfig = uiState.getTemperatureConfig()
        val isTemperatureSupported = tempConfig != null
        val isTemperatureActive = isTemperatureSupported && uiState.temperatureValue != null

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (isTemperatureActive) Primary.copy(alpha = 0.15f) else SurfaceVariant,
            modifier = Modifier
                .size(36.dp)
                .clickable { viewModel.onTemperatureButtonClick() }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Thermostat,
                    contentDescription = "Temperature",
                    tint = if (isTemperatureActive) Primary else OnSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        TemperaturePopup(
            visible = uiState.showTemperaturePopup,
            config = tempConfig,
            currentValue = uiState.temperatureValue,
            onValueChange = { viewModel.setTemperatureValue(it) },
            onDismiss = { viewModel.hideTemperaturePopup() }
        )
    }
}

/**
 * Text direction toggle button with popup menu
 */
@Composable
fun TextDirectionButton(
    textDirectionMode: TextDirectionMode,
    onModeChange: (TextDirectionMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var showTextDirectionMenu by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = SurfaceVariant,
            modifier = Modifier
                .size(36.dp)
                .clickable { showTextDirectionMenu = !showTextDirectionMenu }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.FormatAlignRight,
                    contentDescription = "Text Direction",
                    tint = OnSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        DropdownMenu(
            expanded = showTextDirectionMenu,
            onDismissRequest = { showTextDirectionMenu = false }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // RTL button
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (textDirectionMode == TextDirectionMode.RTL)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        SurfaceVariant,
                    modifier = Modifier
                        .size(36.dp)
                        .clickable {
                            onModeChange(TextDirectionMode.RTL)
                            showTextDirectionMenu = false
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.FormatAlignRight,
                            contentDescription = "RTL",
                            tint = if (textDirectionMode == TextDirectionMode.RTL)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                OnSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // AUTO button
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (textDirectionMode == TextDirectionMode.AUTO)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        SurfaceVariant,
                    modifier = Modifier
                        .size(36.dp)
                        .clickable {
                            onModeChange(TextDirectionMode.AUTO)
                            showTextDirectionMenu = false
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "A",
                            color = if (textDirectionMode == TextDirectionMode.AUTO)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                OnSurfaceVariant,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // LTR button
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (textDirectionMode == TextDirectionMode.LTR)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        SurfaceVariant,
                    modifier = Modifier
                        .size(36.dp)
                        .clickable {
                            onModeChange(TextDirectionMode.LTR)
                            showTextDirectionMenu = false
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.FormatAlignLeft,
                            contentDescription = "LTR",
                            tint = if (textDirectionMode == TextDirectionMode.LTR)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                OnSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Floating toggle button for quick settings
 */
@Composable
fun QuickSettingsToggleButton(
    expanded: Boolean,
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (visible) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 68.dp, end = 20.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            Surface(
                shape = CircleShape,
                color = SurfaceVariant,
                modifier = Modifier
                    .size(28.dp)
                    .clickable { onClick() },
                shadowElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        contentDescription = if (expanded) "Collapse quick settings" else "Expand quick settings",
                        tint = OnSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
