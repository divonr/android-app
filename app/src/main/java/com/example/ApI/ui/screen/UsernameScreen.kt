package com.example.ApI.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import com.example.ApI.R
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.TitleGenerationSettings
import com.example.ApI.data.model.ChildLockSettings
import com.example.ApI.data.model.Screen
import com.example.ApI.ui.ChatViewModel
import com.example.ApI.ui.theme.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.provider.Settings
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff

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

        // Child lock state
        var showChildLockSetupDialog by remember { mutableStateOf(false) }
        var showChildLockDisableDialog by remember { mutableStateOf(false) }
        var childLockPassword by remember { mutableStateOf("") }
        var childLockStartTime by remember { mutableStateOf("23:00") }
        var childLockEndTime by remember { mutableStateOf("07:00") }
        var disablePasswordInput by remember { mutableStateOf("") }
        var passwordVisible by remember { mutableStateOf(false) }
        var disablePasswordVisible by remember { mutableStateOf(false) }
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "default_device"

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

                // Child Lock Section
                ChildLockSettingsSection(
                    settings = appSettings.childLockSettings,
                    onSettingsChange = { enabled, password, startTime, endTime ->
                        viewModel.updateChildLockSettings(enabled, password, startTime, endTime)
                    },
                    onShowSetupDialog = { showChildLockSetupDialog = true },
                    onShowDisableDialog = { showChildLockDisableDialog = true },
                    deviceId = deviceId
                )

                // Integrations Section
                IntegrationsNavigationItem(
                    onClick = {
                        viewModel.navigateToScreen(Screen.Integrations)
                    }
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

        // Child Lock Setup Dialog
        if (showChildLockSetupDialog) {
            ChildLockSetupDialog(
                password = childLockPassword,
                onPasswordChange = { childLockPassword = it },
                passwordVisible = passwordVisible,
                onPasswordVisibilityChange = { passwordVisible = it },
                startTime = childLockStartTime,
                onStartTimeChange = { childLockStartTime = it },
                endTime = childLockEndTime,
                onEndTimeChange = { childLockEndTime = it },
                onConfirm = {
                    viewModel.setupChildLock(childLockPassword, childLockStartTime, childLockEndTime, deviceId)
                    showChildLockSetupDialog = false
                    childLockPassword = ""
                    passwordVisible = false
                },
                onDismiss = {
                    showChildLockSetupDialog = false
                    childLockPassword = ""
                    passwordVisible = false
                }
            )
        }

        // Child Lock Disable Dialog
        if (showChildLockDisableDialog) {
            ChildLockDisableDialog(
                password = disablePasswordInput,
                onPasswordChange = { disablePasswordInput = it },
                passwordVisible = disablePasswordVisible,
                onPasswordVisibilityChange = { disablePasswordVisible = it },
                onConfirm = {
                    if (viewModel.verifyAndDisableChildLock(disablePasswordInput, deviceId)) {
                        showChildLockDisableDialog = false
                        disablePasswordInput = ""
                        disablePasswordVisible = false
                    }
                },
                onDismiss = {
                    showChildLockDisableDialog = false
                    disablePasswordInput = ""
                    disablePasswordVisible = false
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

@Composable
private fun ChildLockSettingsSection(
    settings: ChildLockSettings,
    onSettingsChange: (Boolean, String, String, String) -> Unit,
    onShowSetupDialog: () -> Unit,
    onShowDisableDialog: () -> Unit,
    deviceId: String
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Child Lock Main Switch
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
                            text = "מצב נעילת ילדים",
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnSurface,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "הגדר שעות בהן האפליקציה אינה פעילה",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                onShowSetupDialog()
                            } else {
                                onShowDisableDialog()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Primary,
                            checkedTrackColor = Primary.copy(alpha = 0.3f),
                            uncheckedThumbColor = OnSurfaceVariant,
                            uncheckedTrackColor = OnSurfaceVariant.copy(alpha = 0.2f)
                        )
                    )
                }

                // Show time range when enabled
                if (settings.enabled) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Surface,
                        shadowElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = "Start Time",
                                    tint = OnSurface.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "משעה: ${settings.startTime}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurface
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = "End Time",
                                    tint = OnSurface.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "עד שעה: ${settings.endTime}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChildLockSetupDialog(
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onPasswordVisibilityChange: (Boolean) -> Unit,
    startTime: String,
    onStartTimeChange: (String) -> Unit,
    endTime: String,
    onEndTimeChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("הגדרת נעילת ילדים") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "להפעלת מצב נעילת ילדים, הקלידו סיסמה. זכרו את הסיסמה! לא תהיה אפשרות לשחרר את נעילת הילדים ללא הסיסמה",
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("סיסמה") },
                    placeholder = { Text("הקלד סיסמה") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { onPasswordVisibilityChange(!passwordVisible) }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password"
                            )
                        }
                    }
                )

                // Time selection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Start time
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "משעה:",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface.copy(alpha = 0.7f)
                        )
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showStartTimePicker = true },
                            shape = RoundedCornerShape(8.dp),
                            color = SurfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = startTime,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = "Select Time",
                                    tint = OnSurface
                                )
                            }
                        }
                    }

                    // End time
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "עד שעה:",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface.copy(alpha = 0.7f)
                        )
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showEndTimePicker = true },
                            shape = RoundedCornerShape(8.dp),
                            color = SurfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = endTime,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = "Select Time",
                                    tint = OnSurface
                                )
                            }
                        }
                    }
                }

                // Time picker dialogs
                if (showStartTimePicker) {
                    TimePickerDialog(
                        initialTime = startTime,
                        onTimeSelected = { time ->
                            onStartTimeChange(time)
                            showStartTimePicker = false
                        },
                        onDismiss = { showStartTimePicker = false }
                    )
                }

                if (showEndTimePicker) {
                    TimePickerDialog(
                        initialTime = endTime,
                        onTimeSelected = { time ->
                            onEndTimeChange(time)
                            showEndTimePicker = false
                        },
                        onDismiss = { showEndTimePicker = false }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = password.isNotEmpty()
            ) {
                Text("אישור")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("ביטול")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChildLockDisableDialog(
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onPasswordVisibilityChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("שחרור נעילת ילדים") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "הקלד את הסיסמה כדי לשחרר את נעילת הילדים:",
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("סיסמה") },
                    placeholder = { Text("הקלד סיסמה") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { onPasswordVisibilityChange(!passwordVisible) }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password"
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = password.isNotEmpty()
            ) {
                Text("אישור")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("ביטול")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialTime: String,
    onTimeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val timeParts = initialTime.split(":").map { it.toIntOrNull() ?: 0 }
    var selectedHour by remember { mutableStateOf(timeParts.getOrElse(0) { 0 }) }
    var selectedMinute by remember { mutableStateOf(timeParts.getOrElse(1) { 0 }) }
    var showQuickSelect by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("בחר שעה")
                TextButton(onClick = { showQuickSelect = !showQuickSelect }) {
                    Text(if (showQuickSelect) "בחירה מדויקת" else "בחירה מהירה")
                }
            }
        },
        text = {
            if (showQuickSelect) {
                QuickTimeSelector(
                    selectedHour = selectedHour,
                    selectedMinute = selectedMinute,
                    onHourSelected = { selectedHour = it },
                    onMinuteSelected = { selectedMinute = it }
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Digital time display
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Primary.copy(alpha = 0.1f)
                    ) {
                        Text(
                            text = String.format("%02d:%02d", selectedHour, selectedMinute),
                            style = MaterialTheme.typography.headlineMedium,
                            color = Primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Minute picker
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("דקות", style = MaterialTheme.typography.bodySmall)
                            NumberPicker(
                                value = selectedMinute,
                                onValueChange = { selectedMinute = it },
                                range = 0..59
                            )
                        }

                        Text(":", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 16.dp))

                        // Hour picker
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("שעות", style = MaterialTheme.typography.bodySmall)
                            NumberPicker(
                                value = selectedHour,
                                onValueChange = { selectedHour = it },
                                range = 0..23
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val formattedTime = String.format("%02d:%02d", selectedHour, selectedMinute)
                onTimeSelected(formattedTime)
            }) {
                Text("אישור")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("ביטול")
            }
        }
    )
}

@Composable
private fun NumberPicker(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange
) {
    val itemHeight = 40.dp
    val visibleItems = 3

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Up arrow button
        Surface(
            modifier = Modifier
                .size(32.dp)
                .clickable {
                    val nextValue = if (value >= range.last) range.first else value + 1
                    onValueChange(nextValue)
                },
            shape = RoundedCornerShape(4.dp),
            color = SurfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("▲", style = MaterialTheme.typography.bodyLarge, color = OnSurface)
            }
        }

        // Scrolling wheel area
        Box(
            modifier = Modifier
                .height(itemHeight * visibleItems)
                .width(48.dp)
                .pointerInput(value, range) {
                    detectDragGestures { _, dragAmount ->
                        // Drag up (negative Y) increases value, drag down decreases
                        if (dragAmount.y < -10f) {
                            // Dragging up - increase value
                            val nextValue = if (value >= range.last) range.first else value + 1
                            onValueChange(nextValue)
                        } else if (dragAmount.y > 10f) {
                            // Dragging down - decrease value
                            val prevValue = if (value <= range.first) range.last else value - 1
                            onValueChange(prevValue)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Display visible items around current value
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                for (i in -1..1) {
                    val displayValue = when (i) {
                        -1 -> if (value <= range.first) range.last else value - 1
                        0 -> value
                        1 -> if (value >= range.last) range.first else value + 1
                        else -> value
                    }
                    val isSelected = i == 0

                    Box(
                        modifier = Modifier
                            .height(itemHeight)
                            .width(48.dp)
                            .background(
                                if (isSelected) Primary.copy(alpha = 0.2f) else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = displayValue.toString().padStart(2, '0'),
                            style = if (isSelected) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                                   else MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) Primary else OnSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // Invisible clickable overlay for tap scrolling
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable {
                        // Cycle through values when clicking the wheel area
                        val nextValue = if (value >= range.last) range.first else value + 1
                        onValueChange(nextValue)
                    }
            )
        }

        // Down arrow button
        Surface(
            modifier = Modifier
                .size(32.dp)
                .clickable {
                    val prevValue = if (value <= range.first) range.last else value - 1
                    onValueChange(prevValue)
                },
            shape = RoundedCornerShape(4.dp),
            color = SurfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("▼", style = MaterialTheme.typography.bodyLarge, color = OnSurface)
            }
        }
    }
}

@Composable
private fun QuickTimeSelector(
    selectedHour: Int,
    selectedMinute: Int,
    onHourSelected: (Int) -> Unit,
    onMinuteSelected: (Int) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Digital time display
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = Primary.copy(alpha = 0.1f)
        ) {
            Text(
                text = String.format("%02d:%02d", selectedHour, selectedMinute),
                style = MaterialTheme.typography.headlineLarge,
                color = Primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(20.dp),
                textAlign = TextAlign.Center
            )
        }

        // Quick hour selection
        Text(
            text = "שעות נפוצות:",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val commonHours = listOf(0, 6, 7, 8, 9, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23)
            items(commonHours) { hour ->
                QuickSelectChip(
                    text = String.format("%02d", hour),
                    isSelected = hour == selectedHour,
                    onClick = { onHourSelected(hour) }
                )
            }
        }

        // Quick minute selection
        Text(
            text = "דקות:",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val commonMinutes = listOf(0, 15, 30, 45)
            items(commonMinutes) { minute ->
                QuickSelectChip(
                    text = String.format("%02d", minute),
                    isSelected = minute == selectedMinute,
                    onClick = { onMinuteSelected(minute) }
                )
            }
        }

        // All minutes grid (for precise selection)
        Text(
            text = "בחירה מדויקת של דקות:",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface.copy(alpha = 0.7f)
        )
        
        LazyColumn(
            modifier = Modifier.height(120.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Group minutes by rows of 6
            val minuteRows = (0..59).chunked(6)
            items(minuteRows) { minuteRow ->
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(minuteRow) { minute ->
                        QuickSelectChip(
                            text = String.format("%02d", minute),
                            isSelected = minute == selectedMinute,
                            onClick = { onMinuteSelected(minute) },
                            isSmall = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickSelectChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    isSmall: Boolean = false
) {
    Surface(
        modifier = Modifier
            .clickable { onClick() }
            .then(
                if (isSmall) Modifier.size(32.dp) 
                else Modifier.padding(vertical = 4.dp)
            ),
        shape = RoundedCornerShape(if (isSmall) 6.dp else 12.dp),
        color = if (isSelected) Primary else SurfaceVariant,
        shadowElevation = if (isSelected) 4.dp else 0.dp
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(
                horizontal = if (isSmall) 0.dp else 12.dp,
                vertical = if (isSmall) 0.dp else 8.dp
            )
        ) {
            Text(
                text = text,
                style = if (isSmall) MaterialTheme.typography.bodySmall 
                       else MaterialTheme.typography.bodyMedium,
                color = if (isSelected) OnPrimary else OnSurfaceVariant,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun IntegrationsNavigationItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Surface,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "אינטגרציות (MCPs)",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface,
                fontWeight = FontWeight.Medium
            )
            
            Text(
                text = ">",
                style = MaterialTheme.typography.bodyLarge,
                color = OnSurface.copy(alpha = 0.7f),
                fontWeight = FontWeight.Bold
            )
        }
    }
}
