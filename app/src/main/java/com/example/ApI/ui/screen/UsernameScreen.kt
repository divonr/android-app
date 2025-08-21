package com.example.ApI.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ApI.R
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.TitleGenerationSettings
import com.example.ApI.ui.ChatViewModel
import com.example.ApI.ui.theme.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSettingsScreen(
    viewModel: ChatViewModel,
    appSettings: AppSettings,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        val context = LocalContext.current
        var showImportWarning by remember { mutableStateOf(false) }

        // JSON file picker launcher
        val jsonPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let {
                viewModel.importChatHistoryFromUri(it)
            }
        }

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
                        text = "הגדרות מתקדמות",
                        style = MaterialTheme.typography.headlineSmall,
                        color = OnSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Settings content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Import chat history row
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showImportWarning = true },
                    shape = RoundedCornerShape(16.dp),
                    color = Surface,
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.import_chat_history),
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurface,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.beta),
                                style = MaterialTheme.typography.bodySmall,
                                color = Primary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // Multi-message mode switch
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Surface,
                    shadowElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(id = R.string.multi_message_mode),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = OnSurface,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(id = R.string.multi_message_mode_explainer),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurface.copy(alpha = 0.7f),
                                    lineHeight = 16.sp
                                )
                            }
                            Switch(
                                checked = appSettings.multiMessageMode,
                                onCheckedChange = { enabled ->
                                    viewModel.updateMultiMessageMode(enabled)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Primary,
                                    checkedTrackColor = Primary.copy(alpha = 0.3f),
                                    uncheckedThumbColor = OnSurfaceVariant,
                                    uncheckedTrackColor = OnSurfaceVariant.copy(alpha = 0.2f)
                                )
                            )
                        }
                    }
                }

                TitleGenerationSettingsSection(
                    settings = appSettings.titleGenerationSettings,
                    onSettingsChange = { newSettings ->
                        viewModel.updateTitleGenerationSettings(newSettings)
                    },
                    availableProviders = viewModel.getAvailableProvidersForTitleGeneration()
                )
            }
        }

        if (showImportWarning) {
            AlertDialog(
                onDismissRequest = { showImportWarning = false },
                title = { Text(text = stringResource(R.string.approve)) },
                text = { Text(text = stringResource(R.string.import_warning)) },
                confirmButton = {
                    TextButton(onClick = {
                        showImportWarning = false
                        jsonPickerLauncher.launch("application/json")
                    }) {
                        Text(text = stringResource(R.string.approve))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showImportWarning = false }) {
                        Text(text = stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun TitleGenerationSettingsSection(
    settings: TitleGenerationSettings,
    onSettingsChange: (TitleGenerationSettings) -> Unit,
    availableProviders: List<String>
) {
    var showProviderSelector by remember { mutableStateOf(false) }
    
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Main switch for title generation - Modern card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = SurfaceVariant,
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.auto_generate_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnSurface,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.ai_api_call_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = { enabled ->
                            onSettingsChange(settings.copy(enabled = enabled))
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Primary,
                            checkedTrackColor = Primary.copy(alpha = 0.3f),
                            uncheckedThumbColor = OnSurfaceVariant,
                            uncheckedTrackColor = OnSurfaceVariant.copy(alpha = 0.2f)
                        )
                    )
                }
                
                // Provider selector (shown when switch is enabled)
                if (settings.enabled) {
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Column {
                        // Model selector button - Modern design
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showProviderSelector = !showProviderSelector },
                            shape = RoundedCornerShape(16.dp),
                            color = Surface,
                            shadowElevation = 2.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = stringResource(R.string.select_model),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = OnSurface,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = getProviderDisplayName(settings.provider),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurface.copy(alpha = 0.7f)
                                    )
                                }
                                Icon(
                                    imageVector = if (showProviderSelector) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (showProviderSelector) "Hide" else "Show",
                                    tint = OnSurface
                                )
                            }
                        }
                        
                        // Provider options (expandable)
                        if (showProviderSelector) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = Surface.copy(alpha = 0.5f),
                                shadowElevation = 1.dp
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    // Always show "Auto" option
                                    ProviderOption(
                                        text = stringResource(R.string.auto_mode),
                                        isSelected = settings.provider == "auto",
                                        onClick = { 
                                            onSettingsChange(settings.copy(provider = "auto"))
                                            showProviderSelector = false
                                        }
                                    )
                                    
                                    // Show available provider options
                                    if ("openai" in availableProviders) {
                                        ProviderOption(
                                            text = stringResource(R.string.openai_gpt5_nano),
                                            isSelected = settings.provider == "openai",
                                            onClick = { 
                                                onSettingsChange(settings.copy(provider = "openai"))
                                                showProviderSelector = false
                                            }
                                        )
                                    }
                                    
                                    if ("poe" in availableProviders) {
                                        ProviderOption(
                                            text = stringResource(R.string.poe_gpt5_nano),
                                            isSelected = settings.provider == "poe",
                                            onClick = { 
                                                onSettingsChange(settings.copy(provider = "poe"))
                                                showProviderSelector = false
                                            }
                                        )
                                    }
                                    
                                    if ("google" in availableProviders) {
                                        ProviderOption(
                                            text = stringResource(R.string.google_gemini_flash_lite),
                                            isSelected = settings.provider == "google",
                                            onClick = { 
                                                onSettingsChange(settings.copy(provider = "google"))
                                                showProviderSelector = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    // Update on extension checkbox
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Checkbox(
                            checked = settings.updateOnExtension,
                            onCheckedChange = { updateOnExtension ->
                                onSettingsChange(settings.copy(updateOnExtension = updateOnExtension))
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Primary
                            )
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.update_title_on_extension),
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurface
                            )
                            Text(
                                text = stringResource(R.string.after_3_responses),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurface.copy(alpha = 0.6f),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderOption(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = Primary
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurface,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
        )
    }
}

@Composable
private fun getProviderDisplayName(provider: String): String {
    return when (provider) {
        "auto" -> stringResource(R.string.auto_mode)
        "openai" -> stringResource(R.string.openai_gpt5_nano)
        "poe" -> stringResource(R.string.poe_gpt5_nano)
        "google" -> stringResource(R.string.google_gemini_flash_lite)
        else -> stringResource(R.string.auto_mode)
    }
}
